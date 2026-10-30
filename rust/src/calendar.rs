//! # Calendars
//!
//! The calendar domain: calendars and events as they cross the bridge,
//! and the [`Calendars`] router dispatching each operation on its backend.
//!
//! [`caldav`], [`msgraph`], [`jmap`] and [`gcal`] add the event verbs to
//! their backend; [`phone`] maps objects to the phone's calendar rows. The
//! rest works on iCalendar text alone: [`read`], [`expand`], [`series`],
//! [`occurrence`], [`zone`], [`edit`] and the merge ([`conflict`]).
//!
//! Expansion is civil (RFC 5545): an event at 09:00 recurs at 09:00 on
//! its start's wall clock. Times cross the bridge with their zone
//! ([`EventTime`]) for Java to resolve with the platform's tzdata; this
//! side resolves only the zones an object defines itself ([`zone`]).

pub mod alarm;
mod caldav;
pub mod conflict;
pub mod edit;
pub mod expand;
pub mod gcal;
mod jmap;
mod msgraph;
mod occurrence;
pub mod phone;
pub mod read;
pub mod series;
pub mod task;
#[cfg(test)]
mod vectors;
mod zone;

use ical::{
    component::{IcalComponent, IcalComponentKind, IcalComponentName},
    prop::{IcalProp, IcalPropKind, IcalPropName},
    tree::{
        cst::{IcalCst, IcalItem},
        line::IcalLine,
    },
    value::IcalValue,
    version::IcalVersion,
};
use io_webdav::rfc6578::sync_collection::WebdavSyncDelta;
use serde::Serialize;

use crate::{
    account::{self, Account, Backend},
    calendar::{gcal::Gcal, zone::EventTime},
    client::{
        Client, Host,
        jmap::Jmap,
        msgraph::Msgraph,
        parse_url,
        webdav::{Webdav, href_resource},
    },
    error::BridgeError,
};

/// How deep components may nest in an object read here: RFC 5545 needs
/// three (`VCALENDAR`, `VEVENT`, `VALARM`; `VTIMEZONE`, `STANDARD`).
const MAX_NESTING: usize = 16;

/// What every object this side composes names as its producer.
pub const PRODID: &str = "-//Pimalaya//Pimalaya for Android//EN";

/// The components an agenda places on a day: everything RFC 5545 dates,
/// minus the two that describe rather than schedule (VFREEBUSY reports
/// busy time, VTIMEZONE defines offsets).
pub const SCHEDULED: [IcalComponentKind; 3] = [
    IcalComponentKind::VEvent,
    IcalComponentKind::VTodo,
    IcalComponentKind::VJournal,
];

/// One calendar surfaced to the Java client.
///
/// Shaped as [`crate::contact::Addressbook`] by coincidence, and kept
/// apart: a calendar grows a default time zone and a component set that
/// an addressbook never will.
#[derive(Serialize)]
pub struct Calendar {
    /// Last non-empty path segment of the collection URL.
    pub id: String,
    /// Human-readable name (display name, falling back to the id).
    pub name: String,
    /// Absolute collection URL, the target of every event operation.
    pub url: String,
    /// Free-form description, when the server exposes one.
    pub description: Option<String>,
    /// Display colour (`#RRGGBB`), when the server exposes one.
    pub color: Option<String>,
    /// What the source says the calendar is for, as
    /// [`crate::contact::Addressbook::role`].
    pub role: String,
    /// Whether the user may write into it, true where the source says
    /// nothing.
    pub writable: bool,
    /// Whether its server takes a to-do there (RFC 4791 §5.2.3): false when
    /// its `supported-calendar-component-set` names components but not
    /// `VTODO`, true when it names `VTODO`, [`None`] when it states none or
    /// the backend has no such set (Graph, Google, JMAP).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub vtodo: Option<bool>,
}

/// One calendar item surfaced to the Java client, raw.
///
/// The text stays unparsed: expanding a series depends on the window
/// rendered, so decoding happens where that window is known.
#[derive(Serialize)]
pub struct Event {
    /// Display identifier (resource name with any `.ics` stripped).
    pub id: String,
    /// Entity tag guarding concurrent updates, when the server sent one.
    pub etag: Option<String>,
    /// Raw iCalendar text, one VCALENDAR.
    pub ical: String,
}

/// One incremental round of a calendar collection, surfaced to Java.
///
/// As [`crate::contact::CardDelta`]: the members that moved, the merge reading
/// the bodies it needs after. A round that read them anyway (Google's, JMAP's
/// complete ones) hands them in [`Self::bodies`], not to read each twice.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventDelta {
    /// Resource names created or updated since the cursor, with their
    /// ETags; no body, which is the point.
    pub changed: Vec<EventRef>,
    /// The bodies the round already held, as named in [`Self::changed`];
    /// empty where it listed names alone (CalDAV, Graph).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub bodies: Vec<Event>,
    /// Resource names removed since the cursor.
    pub vanished: Vec<String>,
    /// The next cursor, when the backend issued one.
    pub token: Option<String>,
    /// True when the round listed the complete member set (an initial
    /// round, or an expired cursor re-run as one).
    pub complete: bool,
}

/// One member of an enumerated calendar: what it is called and what it
/// stands at.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct EventRef {
    pub id: String,
    pub etag: Option<String>,
}

/// One account's calendars and events, as the bridge routes them.
pub struct Calendars<'c, H> {
    /// The client the requests go through.
    pub client: &'c mut Client<H>,
    /// The account whose backend serves every operation.
    pub account: &'c Account<'c>,
}

/// The object [`Calendars::create_event`] files.
pub struct EventCreate<'a> {
    /// The resource name asked for, which only CalDAV takes.
    pub id: &'a str,
    /// The iCalendar text.
    pub ical: &'a str,
    /// The account's address, which Google and JMAP announce a meeting
    /// the user organizes by.
    pub address: &'a str,
}

/// The object [`Calendars::update_event`] writes.
pub struct EventUpdate<'a> {
    /// The event's resource name or server id.
    pub id: &'a str,
    /// The edited iCalendar text.
    pub ical: &'a str,
    /// The ETag guarding the write, [`None`] when unknown.
    pub etag: Option<&'a str>,
    /// The account's address, as [`EventCreate::address`].
    pub address: &'a str,
}

/// The object [`Calendars::delete_event`] removes.
pub struct EventDelete<'a> {
    /// The event's resource name or server id.
    pub id: &'a str,
    /// The ETag guarding the deletion, [`None`] when unknown.
    pub etag: Option<&'a str>,
    /// The account's address, as [`EventCreate::address`].
    pub address: &'a str,
}

/// The object [`Calendars::move_event`] moves into another calendar.
pub struct EventMove<'a> {
    /// The event's resource name.
    pub id: &'a str,
    /// The ETag guarding the move, [`None`] when unknown.
    pub etag: Option<&'a str>,
    /// The URL of the calendar it moves into.
    pub target_url: &'a str,
}

/// The protocol-agnostic calendar API, each method routed to the CalDAV,
/// Graph, JMAP or Google backend behind the account's base URL.
impl<H: Host> Calendars<'_, H> {
    /// Lists the account's calendars, each carrying the URL its events
    /// are listed from: the CalDAV collection URL, or the calendar id
    /// behind the account's marker.
    pub fn list_calendars(&mut self) -> Result<Vec<Calendar>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                let mut calendars = Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .list_calendars()?;
                for calendar in &mut calendars {
                    // NOTE: the listing already put the account-scoped path
                    // there; only the base is missing.
                    calendar.url = format!("{}/{}", account.base_url, calendar.url);
                }
                Ok(calendars)
            }
            Backend::Graph => {
                let mut calendars = Msgraph {
                    client: self.client,
                    token: account.auth.secret(),
                }
                .list_calendars()?;
                for calendar in &mut calendars {
                    calendar.url = format!("{}/{}", account.base_url, calendar.id);
                }
                Ok(calendars)
            }
            Backend::Google => {
                let mut calendars = Gcal {
                    client: self.client,
                    token: account.auth.secret(),
                }
                .list_calendars()?;
                for calendar in &mut calendars {
                    calendar.url = format!("{}/{}", account.base_url, calendar.id);
                }
                Ok(calendars)
            }
            // NOTE: a calendar account with no sentinel is a CalDAV context
            // root, the only other endpoint the connection flow builds.
            _ => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .list_calendars(&parse_url(account.base_url)?),
        }
    }

    /// One calendar's incremental round, rerun in full on a rejected cursor.
    ///
    /// As [`crate::contact::Contacts::sync_cards`]: a still-rejected
    /// initial round errors, since an empty collection would read as every
    /// event deleted.
    pub fn sync_events(
        &mut self,
        calendar_url: &str,
        cursor: Option<&str>,
    ) -> Result<EventDelta, BridgeError> {
        if let Some(mut delta) = self.sync_events_round(calendar_url, cursor)? {
            // NOTE: a JMAP round has no cursor and lists in full, and only
            // it knows whether the listing reached its end.
            if self.account.backend != Backend::Jmap {
                delta.complete = cursor.is_none();
            }
            return Ok(delta);
        }

        match self.sync_events_round(calendar_url, None)? {
            Some(mut delta) => {
                delta.complete = true;
                Ok(delta)
            }
            None => Err(format!("Initial sync round rejected for {calendar_url}").into()),
        }
    }

    /// One round on the base URL's backend; [`None`] if the cursor is rejected.
    ///
    /// JMAP has no incremental read wired (the draft's
    /// `CalendarEvent/changes` would go here): it lists in full with no
    /// cursor, complete unless the server capped the listing.
    fn sync_events_round(
        &mut self,
        calendar_url: &str,
        cursor: Option<&str>,
    ) -> Result<Option<EventDelta>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                let calendar_id = account::jmap_collection_id(calendar_url);
                let (events, complete) = Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .list_events(calendar_id)?;

                Ok(Some(listed(events, complete)))
            }
            // NOTE: Graph's event delta runs over a time window only, and
            // an event leaving the window would read as deleted, so every
            // round is a complete listing with no cursor.
            Backend::Graph => Ok(Some(EventDelta {
                changed: Msgraph {
                    client: self.client,
                    token: account.auth.secret(),
                }
                .list_events(account::book_segment(account.base_url, calendar_url))?,
                bodies: Vec::new(),
                vanished: Vec::new(),
                token: None,
                complete: true,
            })),
            // NOTE: Google's sync token reports instances, and an instance
            // moving is its series moving, so a changed instance reads its
            // series again; every body comes with the round.
            Backend::Google => Gcal {
                client: self.client,
                token: account.auth.secret(),
            }
            .sync_events(
                account::book_segment(account.base_url, calendar_url),
                cursor,
            ),
            _ => {
                let url = parse_url(calendar_url)?;
                let delta = Webdav {
                    client: self.client,
                    auth: account.auth,
                }
                .sync_events(&url, cursor)?;
                Ok(delta.map(into_event_delta))
            }
        }
    }

    /// The bodies of the named events, on any backend.
    ///
    /// JMAP has no multiget either, so it re-lists and picks what was
    /// asked for: one request rather than one per event.
    pub fn multiget_events(
        &mut self,
        calendar_url: &str,
        ids: &[&str],
    ) -> Result<Vec<Event>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => {
                let session_url = account::jmap_session_url(account.base_url)?;
                let calendar_id = account::jmap_collection_id(calendar_url);
                let (events, _) = Jmap {
                    client: self.client,
                    session_url,
                    auth: account.auth,
                }
                .list_events(calendar_id)?;

                Ok(events
                    .into_iter()
                    .filter(|event| ids.contains(&event.id.as_str()))
                    .collect())
            }
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .read_events(ids),
            Backend::Google => Gcal {
                client: self.client,
                token: account.auth.secret(),
            }
            .read_events(account::book_segment(account.base_url, calendar_url), ids),
            _ => {
                let url = parse_url(calendar_url)?;
                Webdav {
                    client: self.client,
                    auth: account.auth,
                }
                .multiget_events(&url, ids)
            }
        }
    }

    /// Files one new object, answering the resource it landed under: on
    /// CalDAV the name asked for or the one the server filed it by, the id
    /// Graph, Google or the JMAP server minted.
    pub fn create_event(
        &mut self,
        calendar_url: &str,
        event: &EventCreate,
    ) -> Result<EventRef, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => Jmap {
                client: self.client,
                session_url: account::jmap_session_url(account.base_url)?,
                auth: account.auth,
            }
            .create_event(
                account::jmap_collection_id(calendar_url),
                event.ical,
                event.address,
            ),
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .create_event(
                account::book_segment(account.base_url, calendar_url),
                event.ical,
            ),
            Backend::Google => Gcal {
                client: self.client,
                token: account.auth.secret(),
            }
            .create_event(
                account::book_segment(account.base_url, calendar_url),
                event.ical,
                event.address,
            ),
            _ => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .create_event(&parse_url(calendar_url)?, event.id, event.ical),
        }
    }

    /// Pushes one edited object back to its calendar, answering its new
    /// ETag.
    pub fn update_event(
        &mut self,
        calendar_url: &str,
        event: &EventUpdate,
    ) -> Result<Option<String>, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => Jmap {
                client: self.client,
                session_url: account::jmap_session_url(account.base_url)?,
                auth: account.auth,
            }
            .update_event(event.id, event.ical, event.etag, event.address),
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .update_event(event.id, event.ical, event.etag),
            Backend::Google => Gcal {
                client: self.client,
                token: account.auth.secret(),
            }
            .update_event(account::book_segment(account.base_url, calendar_url), event),
            _ => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .update_event(&parse_url(calendar_url)?, event.id, event.ical, event.etag),
        }
    }

    /// Moves one object into another calendar of the account, answering the
    /// resource it landed under.
    ///
    /// CalDAV alone moves a member (WebDAV `MOVE`); Graph, Google and JMAP
    /// answer a 501, a move their backend cannot do, which a caller meets
    /// by uploading the object instead (pimdir SYNC §4).
    pub fn move_event(
        &mut self,
        calendar_url: &str,
        event: &EventMove,
    ) -> Result<EventRef, BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap | Backend::Graph | Backend::Google => Err(BridgeError {
                message: format!("Cannot move {} on this backend", event.id),
                status: Some(501),
            }),
            _ => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .move_event(
                &parse_url(calendar_url)?,
                event.id,
                &parse_url(event.target_url)?,
                event.etag,
            ),
        }
    }

    /// Removes one object from its calendar.
    pub fn delete_event(
        &mut self,
        calendar_url: &str,
        event: &EventDelete,
    ) -> Result<(), BridgeError> {
        let account = self.account;

        match account.backend {
            Backend::Jmap => Jmap {
                client: self.client,
                session_url: account::jmap_session_url(account.base_url)?,
                auth: account.auth,
            }
            .delete_event(event.id, event.etag, event.address),
            Backend::Graph => Msgraph {
                client: self.client,
                token: account.auth.secret(),
            }
            .delete_event(event.id, event.etag),
            Backend::Google => Gcal {
                client: self.client,
                token: account.auth.secret(),
            }
            .delete_event(
                account::book_segment(account.base_url, calendar_url),
                event.id,
                event.etag,
                event.address,
            ),
            _ => Webdav {
                client: self.client,
                auth: account.auth,
            }
            .delete_event(&parse_url(calendar_url)?, event.id, event.etag),
        }
    }
}

/// One WebDAV sync round as the calendar delta it stands for.
fn into_event_delta(delta: WebdavSyncDelta) -> EventDelta {
    let changed = delta
        .changed
        .into_iter()
        .filter_map(|change| {
            Some(EventRef {
                id: href_resource(&change.href)?,
                etag: change.etag,
            })
        })
        .collect();
    let vanished = delta
        .vanished
        .iter()
        .filter_map(|href| href_resource(href))
        .collect();

    EventDelta {
        changed,
        bodies: Vec::new(),
        vanished,
        token: delta.sync_token,
        complete: false,
    }
}

/// A round that listed events whole: their names and revisions, the
/// bodies with them, and whether the listing reached its end.
fn listed(events: Vec<Event>, complete: bool) -> EventDelta {
    EventDelta {
        changed: events
            .iter()
            .map(|event| EventRef {
                id: event.id.clone(),
                etag: event.etag.clone(),
            })
            .collect(),
        bodies: events,
        vanished: Vec::new(),
        token: None,
        complete,
    }
}

/// The first line of one property of a component.
pub fn line<'c>(component: &'c IcalCst<'static>, name: &str) -> Option<&'c IcalLine<'static>> {
    component.items.iter().find_map(|item| match item {
        IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case(name) => Some(line),
        _ => None,
    })
}

pub fn line_mut<'c>(
    component: &'c mut IcalCst<'static>,
    name: &str,
) -> Option<&'c mut IcalLine<'static>> {
    component.items.iter_mut().find_map(|item| match item {
        IcalItem::Prop(line) if line.name.get().eq_ignore_ascii_case(name) => Some(line),
        _ => None,
    })
}

/// Where every scheduled component sits among the calendar's items.
pub fn scheduled(cst: &IcalCst) -> Vec<usize> {
    cst.items
        .iter()
        .enumerate()
        .filter_map(|(index, item)| match item {
            IcalItem::Component(child) => {
                let name = child.begin.as_ref()?.raw_value_str().to_ascii_uppercase();
                SCHEDULED
                    .iter()
                    .any(|kind| kind.to_string() == name)
                    .then_some(index)
            }
            _ => None,
        })
        .collect()
}

/// The component at one of the calendar's items.
pub fn child<'c>(cst: &'c IcalCst<'static>, index: usize) -> &'c IcalCst<'static> {
    match &cst.items[index] {
        IcalItem::Component(child) => child,
        _ => unreachable!("not a component"),
    }
}

pub fn child_mut<'c>(cst: &'c mut IcalCst<'static>, index: usize) -> &'c mut IcalCst<'static> {
    match &mut cst.items[index] {
        IcalItem::Component(child) => child,
        _ => unreachable!("not a component"),
    }
}

/// A calendar object's syntax tree, refused past [`MAX_NESTING`] levels.
///
/// The parser and every walk here recurse once per level, which a few
/// hundred levels overflow on a 1 MiB Java thread (a SIGSEGV no panic
/// guard catches).
pub fn cst_of(ical: &str) -> Result<IcalCst<'_>, String> {
    // NOTE: each line's start, unfolded (RFC 5545 section 3.1), is
    // enough to tell a BEGIN or an END: a name folded across lines
    // still counts.
    let mut depth = 0usize;
    let mut head = String::new();
    for line in ical.lines().chain([""]) {
        if let Some(folded) = line.strip_prefix([' ', '\t']) {
            if head.len() < 8 {
                head.extend(folded.chars().take(8));
            }
            continue;
        }
        let name = head.split([':', ';']).next().unwrap_or_default();
        if name.eq_ignore_ascii_case("BEGIN") {
            depth += 1;
            if depth > MAX_NESTING {
                return Err(format!(
                    "Calendar object nests components deeper than {MAX_NESTING}"
                ));
            }
        } else if name.eq_ignore_ascii_case("END") {
            depth = depth.saturating_sub(1);
        }
        head.clear();
        head.extend(line.chars().take(8));
    }
    IcalCst::parse(ical).map_err(|err| err.to_string())
}

/// A calendar's text.
pub fn text(cst: &IcalCst) -> Result<String, BridgeError> {
    String::from_utf8(cst.to_bytes()).map_err(|err| err.to_string().into())
}

/// A component of the syntax tree decoded on its own.
pub fn decoded<'c>(component: &'c IcalCst<'static>) -> IcalComponent<'c> {
    let mut props = Vec::new();
    let mut components = Vec::new();
    for item in &component.items {
        match item {
            IcalItem::Prop(line) => props.push(line.decode(IcalVersion::V2_0)),
            IcalItem::Component(nested) => components.push(decoded(nested)),
            IcalItem::Opaque(_) => {}
        }
    }

    IcalComponent {
        name: IcalComponentName::from(
            component
                .begin
                .as_ref()
                .map(|begin| begin.raw_value_str())
                .unwrap_or_default(),
        ),
        props,
        components,
    }
}

/// Every scheduled component of a tree, outermost first.
pub fn collect_scheduled<'a>(
    component: &'a IcalComponent<'a>,
    out: &mut Vec<(&'a IcalComponent<'a>, IcalComponentKind)>,
) {
    for kind in SCHEDULED {
        if is_kind(&component.name, kind) {
            out.push((component, kind));
        }
    }

    // NOTE: a VEVENT nests only VALARMs, but a VCALENDAR arriving inside
    // another component (some servers wrap) would otherwise be missed.
    for nested in &component.components {
        collect_scheduled(nested, out);
    }
}

/// The time a component's row is placed at.
///
/// A to-do need not carry a DTSTART (RFC 5545 3.6.2), and one with only a
/// DUE belongs on the day it is due rather than hidden entirely.
pub fn start_of(component: &IcalComponent, kind: IcalComponentKind) -> Option<EventTime> {
    match kind {
        IcalComponentKind::VTodo => time_of(component, IcalPropKind::DtStart)
            .or_else(|| time_of(component, IcalPropKind::Due)),
        _ => time_of(component, IcalPropKind::DtStart),
    }
}

fn is_kind(name: &IcalComponentName, kind: IcalComponentKind) -> bool {
    matches!(name, IcalComponentName::Kind(found) if *found == kind)
}

/// Whether a component carries a property.
pub fn has(component: &IcalComponent, kind: IcalPropKind) -> bool {
    props(component, kind).next().is_some()
}

/// Every property of one kind a component carries.
fn props<'c>(
    component: &'c IcalComponent<'c>,
    kind: IcalPropKind,
) -> impl Iterator<Item = &'c IcalProp<'c>> {
    component
        .props
        .iter()
        .filter(move |prop| matches!(&prop.name, IcalPropName::Kind(found) if *found == kind))
}

/// The first date property of one kind, with its zone.
pub fn time_of(component: &IcalComponent, kind: IcalPropKind) -> Option<EventTime> {
    props(component, kind).next().and_then(EventTime::of_prop)
}

/// The raw text of a property's value, whatever value type it decoded to.
pub fn text_of(component: &IcalComponent, kind: IcalPropKind) -> Option<String> {
    props(component, kind).next().and_then(raw_value)
}

/// A property's value as text, [`None`] when empty or not textual.
pub fn raw_value(prop: &IcalProp) -> Option<String> {
    // NOTE: CATEGORIES is the one list the reader's page asks for, and it
    // reads as the line it was written on.
    if let IcalValue::TextList(values) = &prop.value {
        let joined = values.0.join(", ");
        return match joined.is_empty() {
            true => None,
            false => Some(joined),
        };
    }

    let raw = match &prop.value {
        IcalValue::Text(value) => value.0.as_ref(),
        IcalValue::DateTime(value) => value.0.as_ref(),
        IcalValue::Date(value) => value.0.as_ref(),
        IcalValue::Recur(value) => value.0.as_ref(),
        IcalValue::Duration(value) => value.0.as_ref(),
        // NOTE: numbers (PRIORITY, PERCENT-COMPLETE) and URIs (URL, the
        // calendar-user addresses) serve the page, never an agenda row.
        IcalValue::Integer(value) => value.0.as_ref(),
        IcalValue::Uri(value) => value.0.as_ref(),
        IcalValue::CalAddress(value) => value.0.as_ref(),
        _ => return None,
    };

    match raw.is_empty() {
        true => None,
        false => Some(raw.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use std::thread;

    use crate::calendar::expand::expand;

    pub fn object(body: &str) -> String {
        format!("BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//test//EN\r\n{body}END:VCALENDAR\r\n")
    }

    /// A zone defined by the object alone, under a name no database
    /// knows: the rules of Paris since 1996.
    pub const ROMANCE: &str = "BEGIN:VTIMEZONE\r\nTZID:/example.org/Romance\r\n\
         BEGIN:DAYLIGHT\r\nDTSTART:19810329T020000\r\nTZOFFSETFROM:+0100\r\n\
         TZOFFSETTO:+0200\r\nRRULE:FREQ=YEARLY;BYMONTH=3;BYDAY=-1SU\r\nEND:DAYLIGHT\r\n\
         BEGIN:STANDARD\r\nDTSTART:19961027T030000\r\nTZOFFSETFROM:+0200\r\n\
         TZOFFSETTO:+0100\r\nRRULE:FREQ=YEARLY;BYMONTH=10;BYDAY=-1SU\r\nEND:STANDARD\r\n\
         END:VTIMEZONE\r\n";

    #[test]
    fn a_hostile_nesting_is_refused_on_a_java_stack() {
        let nested = |begin: &str| {
            let mut ical = String::from("BEGIN:VCALENDAR\r\nVERSION:2.0\r\n");
            for _ in 0..2_000 {
                ical.push_str(begin);
            }
            for _ in 0..2_000 {
                ical.push_str("END:VEVENT\r\n");
            }
            ical + "END:VCALENDAR\r\n"
        };
        let plain = nested("BEGIN:VEVENT\r\nUID:1\r\n");
        let folded = nested("BEG\r\n IN:VEVENT\r\nUID:1\r\n");

        let refused = thread::Builder::new()
            .stack_size(1024 * 1024)
            .spawn(move || {
                let range = ("20260101T000000", "20260201T000000");
                (
                    expand(&plain, range.0, range.1).is_err(),
                    expand(&folded, range.0, range.1).is_err(),
                )
            })
            .unwrap()
            .join()
            .unwrap();

        assert_eq!(refused, (true, true));
    }
}
