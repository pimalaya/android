//! Microsoft Graph mail: mail folders as mailboxes and messages read as
//! the MIME Graph keeps of them, the Graph half of what `syncMail` does
//! over IMAP and JMAP.
//!
//! A folder has one message delta link, made with no filter: its first
//! pass names every message of the folder by id (1,000 a page), and every
//! delta after it reports a change whatever the message's date, so it
//! serves any scope and a widening relists nothing. Mail itself is listed
//! by band: a first chunk, a scroll widening and the fill list only the
//! band they lack by the plain `/messages` list on `sentDateTime`, every
//! member named by the summary `$select`. A message is read as its RFC
//! 5322 source, so the store and the parser stay the ones the other
//! backends use.

use std::{
    collections::{HashMap, HashSet, VecDeque},
    fs,
    path::{Path, PathBuf},
    thread::sleep,
    time::Duration,
};

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::v1::{
    rest::{
        batch::{
            MSGRAPH_BATCH_MAX_REQUESTS, MsgraphBatch, MsgraphBatchRequest, MsgraphBatchResponse,
        },
        users::{
            mail_folders::{
                MsgraphMailFolder,
                child_folders::MsgraphMailChildFoldersList,
                get::MsgraphMailFolderGet,
                list::{
                    MsgraphMailFoldersList, MsgraphMailFoldersListParams,
                    MsgraphMailFoldersListResponse,
                },
            },
            messages::{
                MsgraphFlagStatus, MsgraphFollowupFlag, MsgraphMessage, MsgraphRecipient,
                copy::MsgraphMessageCopy,
                delta::{
                    MsgraphMessageDelta, MsgraphMessagesDelta, MsgraphMessagesDeltaParams,
                    MsgraphMessagesDeltaResponse,
                },
                get_raw::MsgraphMessageGetRaw,
                list::{
                    MsgraphMessagesList, MsgraphMessagesListParams, MsgraphMessagesListResponse,
                },
                r#move::MsgraphMessageMove,
                update::MsgraphMessageUpdate,
            },
            send_mail::MsgraphMailSendMime,
        },
    },
    send::{MSGRAPH_API_BASE, MsgraphNoResponse, MsgraphSend},
};

use io_pimdir::summary::{
    PimdirAddress,
    mail::{PimdirMailSummary, decode},
};
use jiff::Timestamp;
use serde::{Deserialize, Serialize};
use url::Url;

use crate::{
    client::{
        Client,
        convert::converged,
        gmail::source_path,
        graph::{graph_url, parse_graph_url, relative},
        listing::{Floor, GRAPH_PAGE, Listing, MailPage, MailRequest, Named, Scope, flags, utc},
        throttle::WAIT_BUDGET,
    },
    types::{BridgeError, Mailbox},
};

/// The well-known name Graph gives the trash (`Deleted Items`).
const TRASH: &str = "deleteditems";

/// The other well-known folders a pass is ordered by, with the role each
/// is in pimdir's vocabulary.
const WELL_KNOWN: [(&str, &str); 4] = [
    ("inbox", "inbox"),
    ("sentitems", "sent"),
    ("drafts", "drafts"),
    ("junkemail", "junk"),
];

/// The `$select` of a band's `/messages` page and of a message read by
/// id: what Annex A's summary and addresses read, and the markers.
///
/// The date is `sentDateTime`, Graph's name for the `Date` header, which
/// is what pimdir STORAGE Annex A.1 stores and sorts mail by; the
/// reception time is no part of a summary.
const MESSAGE_SELECT: &str = "id,subject,from,toRecipients,ccRecipients,sentDateTime,isRead,\
flag,hasAttachments,internetMessageId";

/// The `$expand` riding along [`MESSAGE_SELECT`]: the MAPI
/// `PidTagMessageSize` (`Integer 0x0E08`), the size Exchange states for
/// the message, as IMAP's `RFC822.SIZE` does.
const MESSAGE_EXPAND: &str = "singleValueExtendedProperties($filter=id eq 'Integer 0x0E08')";

impl<'a, 'local> Client<'a, 'local> {
    /// Reads the inbox's folder, so a session that cannot authenticate
    /// fails when it is opened rather than on the first verb that uses it.
    pub fn graph_mail_check(&mut self, token: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMailFolderGet::new(&auth, "me", "inbox").map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;
        Ok(())
    }

    /// The account's mail folders as `(id, mailbox)` pairs, the name
    /// being the folder's path, its ancestors' names and its own joined
    /// by `/`, the way IMAP hands the app a hierarchical name.
    ///
    /// Graph v1.0 tells no folder's role, so each role is asked for by its
    /// well-known name and recognised by id: the trash, which a delete
    /// needs, and the inbox, the sent items, the drafts and the junk, which
    /// order a pass. Only the trash is required; a well-known folder the
    /// mailbox does not answer for leaves its role unsaid.
    pub fn list_graph_mailboxes(
        &mut self,
        token: &str,
    ) -> Result<Vec<(String, Mailbox)>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let coroutine =
            MsgraphMailFolderGet::new(&auth, "me", TRASH).map_err(|err| err.to_string())?;
        let mut roles = vec![(self.run_msgraph(coroutine)?.id, "trash")];
        for (known, role) in WELL_KNOWN {
            let coroutine =
                MsgraphMailFolderGet::new(&auth, "me", known).map_err(|err| err.to_string())?;
            match self.run_msgraph(coroutine) {
                Ok(folder) => roles.push((folder.id, role)),
                Err(err) => log::debug!("no well-known folder {known}: {err}"),
            }
        }

        let params = MsgraphMailFoldersListParams {
            top: Some(100),
            ..Default::default()
        };

        let coroutine =
            MsgraphMailFoldersList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
        let first = self.run_msgraph(coroutine)?;
        let mut pending: Vec<(String, MsgraphMailFolder)> = self
            .graph_folder_pages(&auth, first)?
            .into_iter()
            .map(|folder| (folder.display_name.clone(), folder))
            .collect();

        let mut mailboxes = Vec::new();
        while let Some((path, folder)) = pending.pop() {
            if folder.child_folder_count.unwrap_or(0) > 0 {
                let coroutine = MsgraphMailChildFoldersList::new(&auth, "me", &folder.id, &params)
                    .map_err(|err| err.to_string())?;
                let first = self.run_msgraph(coroutine)?;
                for child in self.graph_folder_pages(&auth, first)? {
                    pending.push((format!("{path}/{}", child.display_name), child));
                }
            }

            let role = roles
                .iter()
                .find(|(id, _)| *id == folder.id)
                .map_or("", |(_, role)| *role);
            mailboxes.push((
                folder.id,
                Mailbox {
                    name: path,
                    role: role.into(),
                },
            ));
        }

        Ok(mailboxes)
    }

    /// Every folder of a listing, following its next links.
    fn graph_folder_pages(
        &mut self,
        auth: &HttpAuthBearer,
        mut page: MsgraphMailFoldersListResponse,
    ) -> Result<Vec<MsgraphMailFolder>, BridgeError> {
        let mut folders = Vec::new();

        loop {
            folders.extend(page.value);
            match page.next_link {
                Some(next) => {
                    let url = parse_graph_url(&next)?;
                    page = self.run_msgraph(MsgraphSend::<MsgraphMailFoldersListResponse>::get(
                        auth, url,
                    ))?;
                }
                None => return Ok(folders),
            }
        }
    }

    /// One page of a folder's listing (pimdir SYNC §4, §5), over the live
    /// Graph ([`list_folder`]).
    pub fn list_graph_page(
        &mut self,
        token: &str,
        folder_id: &str,
        request: &MailRequest,
    ) -> Result<MailPage, BridgeError> {
        let mut folder = LiveFolder {
            client: self,
            auth: HttpAuthBearer::new(token),
            folder: folder_id,
        };
        list_folder(&mut folder, request)
    }

    /// Names the messages a delta listed by id alone, as the summary
    /// `$select` reads them ([`name_messages`]); a message gone since the
    /// delta is left out.
    pub fn name_graph_messages(
        &mut self,
        token: &str,
        folder_id: &str,
        ids: &[String],
    ) -> Result<Vec<Named>, BridgeError> {
        let mut folder = LiveFolder {
            client: self,
            auth: HttpAuthBearer::new(token),
            folder: folder_id,
        };
        name_messages(&mut folder, ids)
    }

    /// Takes a folder's newest messages below the floor's ceiling until its
    /// chunk is full: a plain listing ordered by `sentDateTime` (the `Date`
    /// header, which Graph filters on exactly), filtered below the ceiling,
    /// `sentDateTime` alone selected, as many a page as the chunk asks for.
    pub fn graph_floor(
        &mut self,
        token: &str,
        folder_id: &str,
        floor: &mut Floor,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let filter = floor
            .scope()
            .until
            .as_deref()
            .map(|until| format!("sentDateTime lt {until}"));
        let top = floor.count().min(GRAPH_PAGE as usize) as u32;
        let params = MsgraphMessagesListParams {
            top: Some(top),
            select: Some("id,sentDateTime"),
            filter: filter.as_deref(),
            orderby: Some("sentDateTime desc"),
            ..Default::default()
        };

        let coroutine = MsgraphMessagesList::new(&auth, "me", Some(folder_id), &params)
            .map_err(|err| err.to_string())?;
        let mut page = self.run_msgraph(coroutine)?;
        loop {
            for message in &page.value {
                let date = message.sent_date_time.as_deref().and_then(utc);
                if floor.take(date.as_deref()) {
                    return Ok(());
                }
            }
            let Some(next) = page.next_link.take() else {
                return Ok(());
            };
            let url = parse_graph_url(&next)?;
            page = self.run_msgraph(MsgraphSend::<MsgraphMessagesListResponse>::get(&auth, url))?;
        }
    }

    /// Adds or removes one marker on one message; one gone stays gone
    /// ([`converged`]).
    ///
    /// `\Seen` is `isRead` and `\Flagged` the follow-up flag. Graph keeps
    /// no answered marker and no deleted one, so the other two are
    /// refused the way the JMAP side refuses what it cannot express.
    pub fn set_graph_flag(
        &mut self,
        token: &str,
        id: &str,
        flag: &str,
        add: bool,
    ) -> Result<(), BridgeError> {
        let mut patch = MsgraphMessage::default();
        match flag {
            "\\Seen" => patch.is_read = Some(add),
            "\\Flagged" => {
                let status = if add {
                    MsgraphFlagStatus::Flagged
                } else {
                    MsgraphFlagStatus::NotFlagged
                };
                patch.flag = Some(MsgraphFollowupFlag {
                    flag_status: Some(status),
                });
            }
            other => return Err(format!("No Graph property for the marker `{other}`").into()),
        }

        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMessageUpdate::new(&auth, "me", id, &patch).map_err(|err| err.to_string())?;
        converged(self.run_msgraph(coroutine))?;
        Ok(())
    }

    /// Moves one message into the folder `to` (a folder id); one gone
    /// stays gone ([`converged`]).
    pub fn relocate_graph_message(
        &mut self,
        token: &str,
        id: &str,
        to: &str,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMessageMove::new(&auth, "me", id, to).map_err(|err| err.to_string())?;
        converged(self.run_msgraph(coroutine))?;
        Ok(())
    }

    /// Copies one message into the folder `to` (a folder id), failing
    /// with the 404 of a message gone for the caller to refuse.
    pub fn copy_graph_message(
        &mut self,
        token: &str,
        id: &str,
        to: &str,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMessageCopy::new(&auth, "me", id, to).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;
        Ok(())
    }

    /// Deletes one message for good, past `Recoverable Items`
    /// (`permanentDelete`), which io-msgraph has no coroutine for; one
    /// gone already is deleted ([`converged`]).
    pub fn destroy_graph_message(&mut self, token: &str, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let send = MsgraphSend::<MsgraphNoResponse>::with_method(
            &auth,
            "POST",
            permanent_delete_url(id)?,
            None,
            Vec::new(),
        );
        converged(self.run_msgraph(send))?;
        Ok(())
    }

    /// Sends one RFC 5322 message, Graph filing the copy in `Sent Items`
    /// itself.
    pub fn send_graph_message(&mut self, token: &str, raw: &[u8]) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMailSendMime::new(&auth, "me", raw).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;
        Ok(())
    }
}

/// What a delta link is kept under as a checkpoint: a link made with no
/// filter, which reports a change whatever the message's date.
///
/// A checkpoint without it is a link made under a reception filter
/// (before the delta was unfiltered), bound to that filter: it is refused,
/// and the round it opens makes the unfiltered one.
const DELTA_CHECKPOINT: &str = "graph-delta:";

/// What a round's cursor starts with while it lists a band by
/// `/messages`, the rest the band's [`Ceiling`] as JSON.
const BAND_CURSOR: &str = "band:";

/// What a round's cursor starts with while it walks the unfiltered
/// delta's first pass, the rest Graph's next link.
const DELTA_CURSOR: &str = "delta:";

/// The `$select` of the unfiltered delta: the markers, and the `Date`
/// that tells a member in scope from one out of it, which is all a
/// change needs where the store already names the message. The delta
/// link keeps it: every later delta answers with these alone.
const DELTA_SELECT: &str = "id,sentDateTime,isRead,flag";

/// The Graph requests a folder's listing is made of: one page of the
/// plain `/messages` list over a band of `sentDateTime`, one page of the
/// folder's unfiltered message delta, and the summaries of messages read
/// by id. The listing ([`list_folder`]) is one piece of logic over them,
/// the live Graph or a fake one.
pub(crate) trait GraphFolder {
    /// The newest messages of the band first, `top` at most, with the
    /// summary `$select`; `more` when Graph holds more past them.
    fn messages(&mut self, band: &Band, top: u32) -> Result<MessagesPage, BridgeError>;

    /// One page of the unfiltered delta: its first, or the one `link`
    /// names (a next link or a delta link). [`None`] when Graph expired
    /// the link (410).
    fn delta(
        &mut self,
        link: Option<&str>,
    ) -> Result<Option<MsgraphMessagesDeltaResponse>, BridgeError>;

    /// The messages of `ids` read with the summary `$select`, those gone
    /// left out.
    fn read(&mut self, ids: &[String]) -> Result<Vec<MsgraphMessage>, BridgeError>;
}

/// One page of the plain `/messages` list.
pub(crate) struct MessagesPage {
    pub value: Vec<MsgraphMessage>,
    pub more: bool,
}

/// A band of `sentDateTime`, Graph's name for the `Date` header, which it
/// filters on exactly: from `since` on, below `until` (or up to it,
/// `inclusive`, for a page resuming among messages of the same second).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub(crate) struct Band {
    pub since: Option<String>,
    pub until: Option<String>,
    pub inclusive: bool,
}

impl Band {
    /// The `$filter` of the band, [`None`] for an open one.
    pub fn filter(&self) -> Option<String> {
        let since = self
            .since
            .as_deref()
            .map(|since| format!("sentDateTime ge {since}"));
        let until = self.until.as_deref().map(|until| match self.inclusive {
            true => format!("sentDateTime le {until}"),
            false => format!("sentDateTime lt {until}"),
        });
        match (since, until) {
            (Some(since), Some(until)) => Some(format!("{since} and {until}")),
            (since, until) => since.or(until),
        }
    }
}

/// Where a band's listing resumes: below the oldest `Date` the pages
/// before reached, that second included so messages of it a page cut off
/// are not lost, the ones already listed at it named in `seen`.
///
/// Not Graph's next link: that one pages by `$skip`, and a message
/// removed from the pages before shifts the rest up by one, so the first
/// of the next page would never be listed.
#[derive(Debug, Default, Deserialize, Serialize, Eq, PartialEq)]
struct Ceiling {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    until: Option<String>,
    #[serde(default, skip_serializing_if = "core::ops::Not::not")]
    inclusive: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    seen: Vec<String>,
}

/// One page of a folder's listing (pimdir SYNC §4, §5).
///
/// The folder has one delta link, made with no filter, so it reports a
/// change to any message whatever its date and serves every scope: the
/// connector is bound to none (`scope_bound` false), and a widening lists
/// the band it lacks alone.
///
/// - A delta follows the checkpoint's link to its new delta link, as one
///   page: a removal applies whatever the date, a change to a message
///   dated out of the scope is dropped, and one in scope is listed by id
///   and markers alone, for the caller to name ([`name_messages`]) when
///   the store binds it not. An expired link (410), or one made under a
///   filter before, is refused for the engine to open a round.
/// - A round over a scope the store does not cover yet (a mailbox's first
///   chunk), and every band round, lists the band by `/messages`
///   ([`band_page`]), each page named by the summary `$select`, and hands
///   no checkpoint: the first chunk lands without waiting on the delta.
/// - A round over a scope the store covers (`covered`: the round a pass
///   opens once the first chunk has landed and no delta link exists yet,
///   or after a refused one) walks the delta's first pass ([`delta_page`]):
///   every message of the folder by id, the ones in scope listed, a page
///   a round page, the last carrying the delta link as the checkpoint. It
///   is what the folder holds as it ends, so what changed or went since
///   the band was listed is read there: a member it does not list is
///   absent from the scope.
pub(crate) fn list_folder<G: GraphFolder>(
    graph: &mut G,
    request: &MailRequest,
) -> Result<MailPage, BridgeError> {
    let scope = &request.scope;

    match &request.listing {
        Listing::Delta { checkpoint } => {
            let Some(link) = checkpoint.strip_prefix(DELTA_CHECKPOINT) else {
                return Ok(MailPage::rejected());
            };
            let mut items = Vec::new();
            let mut vanished = Vec::new();
            let mut next = link.to_string();
            loop {
                let Some(page) = graph.delta(Some(&next))? else {
                    return Ok(MailPage::rejected());
                };
                changes(page.value, scope, &mut items, &mut vanished);
                // NOTE: a delta is one page however many Graph cuts it
                // into.
                match (page.next_link, page.delta_link) {
                    (Some(link), _) => next = link,
                    (None, Some(link)) => {
                        let checkpoint = format!("{DELTA_CHECKPOINT}{link}");
                        return Ok(MailPage::delta(items, vanished, checkpoint));
                    }
                    (None, None) => return Err("Graph delta page with no link".into()),
                }
            }
        }
        Listing::Round { cursor: None, band } if !band && request.covered => {
            delta_page(graph, None, scope)
        }
        Listing::Round { cursor: None, .. } => {
            let ceiling = Ceiling {
                until: scope.until.clone(),
                ..Default::default()
            };
            band_page(graph, scope, ceiling)
        }
        Listing::Round {
            cursor: Some(cursor),
            ..
        } => {
            if let Some(link) = cursor.strip_prefix(DELTA_CURSOR) {
                return delta_page(graph, Some(link), scope);
            }
            match cursor
                .strip_prefix(BAND_CURSOR)
                .and_then(|ceiling| serde_json::from_str::<Ceiling>(ceiling).ok())
            {
                Some(ceiling) => band_page(graph, scope, ceiling),
                // NOTE: a cursor from before (a filtered delta's next
                // link) restarts the round.
                None => Ok(MailPage::rejected()),
            }
        }
    }
}

/// One page of a band listed by `/messages`, newest `Date` first, below
/// the ceiling; the next page resumes below the oldest `Date` it reached.
/// No checkpoint: a band round keeps the one the source has, and a first
/// chunk lands before any delta link exists.
fn band_page<G: GraphFolder>(
    graph: &mut G,
    scope: &Scope,
    ceiling: Ceiling,
) -> Result<MailPage, BridgeError> {
    let band = Band {
        since: scope.since.clone(),
        until: ceiling.until.clone(),
        inclusive: ceiling.inclusive,
    };
    let page = graph.messages(&band, GRAPH_PAGE)?;

    let mut items = Vec::with_capacity(page.value.len());
    let mut fresh = 0;
    let mut oldest: Option<String> = None;
    let mut at_oldest: Vec<String> = Vec::new();
    for message in page.value {
        if ceiling.seen.contains(&message.id) {
            continue;
        }
        fresh += 1;
        if let Some(date) = message.sent_date_time.as_deref().and_then(utc) {
            match oldest.as_deref().map(|oldest| date.as_str().cmp(oldest)) {
                None | Some(core::cmp::Ordering::Less) => {
                    oldest = Some(date);
                    at_oldest = vec![message.id.clone()];
                }
                Some(core::cmp::Ordering::Equal) => at_oldest.push(message.id.clone()),
                Some(core::cmp::Ordering::Greater) => (),
            }
        }
        named(message, scope, &mut items);
    }

    if !page.more {
        return Ok(MailPage::round(items, None, None));
    }
    let next = match oldest {
        Some(oldest) if fresh > 0 => {
            // NOTE: the ones listed at that second on the pages before
            // stay listed, when the ceiling did not move.
            let mut seen = at_oldest;
            if ceiling.inclusive && ceiling.until.as_deref() == Some(oldest.as_str()) {
                seen.extend(ceiling.seen);
            }
            Ceiling {
                until: Some(oldest),
                inclusive: true,
                seen,
            }
        }
        // NOTE: a whole page of one second's messages listed already: that
        // second holds more than a page, or Graph did not answer them in
        // the same order twice; the band goes on below it, never by
        // offset, and the delta's first pass names what this misses.
        _ if ceiling.inclusive && fresh == 0 => Ceiling {
            until: ceiling.until,
            ..Default::default()
        },
        // NOTE: a page of undated messages alone, which no ceiling
        // resumes below; the delta's first pass names the rest.
        _ => return Ok(MailPage::round(items, None, None)),
    };
    let cursor = serde_json::to_string(&next).map_err(|err| err.to_string())?;
    Ok(MailPage::round(
        items,
        Some(format!("{BAND_CURSOR}{cursor}")),
        None,
    ))
}

/// One page of the unfiltered delta's first pass, as a round page: the
/// members in scope listed by id and markers, the next link the cursor,
/// the delta link on the last page the checkpoint. A next link Graph
/// expired restarts the round.
fn delta_page<G: GraphFolder>(
    graph: &mut G,
    link: Option<&str>,
    scope: &Scope,
) -> Result<MailPage, BridgeError> {
    let Some(page) = graph.delta(link)? else {
        if link.is_some() {
            return Ok(MailPage::rejected());
        }
        return Err("Graph refused a new message delta".into());
    };

    let mut items = Vec::new();
    let mut vanished = Vec::new();
    changes(page.value, scope, &mut items, &mut vanished);
    let mut reply = match (page.next_link, page.delta_link) {
        (Some(next), _) => MailPage::round(items, Some(format!("{DELTA_CURSOR}{next}")), None),
        (None, Some(link)) => {
            MailPage::round(items, None, Some(format!("{DELTA_CHECKPOINT}{link}")))
        }
        (None, None) => return Err("Graph delta page with no link".into()),
    };
    reply.vanished = vanished;
    Ok(reply)
}

/// What one delta page says of the folder: a removal goes whatever the
/// date, a message dated out of the scope is dropped, and one in scope
/// is listed by id and markers alone.
fn changes(
    rows: Vec<MsgraphMessageDelta>,
    scope: &Scope,
    items: &mut Vec<Named>,
    vanished: &mut Vec<String>,
) {
    for row in rows {
        if row.removed.is_some() {
            vanished.push(row.message.id);
            continue;
        }
        let date = row.message.sent_date_time.as_deref().and_then(utc);
        if !scope.contains(date.as_deref()) {
            continue;
        }
        let marks = marks(&row.message);
        items.push(Named::unnamed(row.message.id, marks));
    }
}

/// The messages of `ids` named by the summary `$select` reads, those gone
/// since they were listed left out.
pub(crate) fn name_messages<G: GraphFolder>(
    graph: &mut G,
    ids: &[String],
) -> Result<Vec<Named>, BridgeError> {
    let read = graph.read(ids)?;
    let mut items = Vec::with_capacity(read.len());
    for message in read {
        named(message, &Scope::default(), &mut items);
    }
    Ok(items)
}

/// The live Graph of one folder.
struct LiveFolder<'c, 'a, 'local> {
    client: &'c mut Client<'a, 'local>,
    auth: HttpAuthBearer,
    folder: &'c str,
}

impl GraphFolder for LiveFolder<'_, '_, '_> {
    fn messages(&mut self, band: &Band, top: u32) -> Result<MessagesPage, BridgeError> {
        let filter = band.filter();
        let params = MsgraphMessagesListParams {
            top: Some(top),
            select: Some(MESSAGE_SELECT),
            expand: Some(MESSAGE_EXPAND),
            filter: filter.as_deref(),
            orderby: Some("sentDateTime desc"),
            ..Default::default()
        };
        let coroutine = MsgraphMessagesList::new(&self.auth, "me", Some(self.folder), &params)
            .map_err(|err| err.to_string())?;
        let page = self.client.run_msgraph(coroutine)?;
        Ok(MessagesPage {
            more: page.next_link.is_some(),
            value: page.value,
        })
    }

    fn delta(
        &mut self,
        link: Option<&str>,
    ) -> Result<Option<MsgraphMessagesDeltaResponse>, BridgeError> {
        let coroutine = match link {
            Some(link) => MsgraphMessagesDelta::from_link(&self.auth, link)
                .map_err(|err| err.to_string())?
                .max_page_size(GRAPH_PAGE),
            None => {
                let params = MsgraphMessagesDeltaParams {
                    select: Some(DELTA_SELECT),
                    filter: None,
                    expand: None,
                    max_page_size: Some(GRAPH_PAGE),
                };
                MsgraphMessagesDelta::with_params(&self.auth, "me", Some(self.folder), &params)
                    .map_err(|err| err.to_string())?
            }
        };
        match self.client.run_msgraph(coroutine) {
            Ok(page) => Ok(Some(page)),
            Err(err) if err.status == Some(410) && link.is_some() => Ok(None),
            Err(err) => Err(err),
        }
    }

    /// `$batch` calls of 20 GETs; a GET the batch could not serve
    /// (throttled inside it, say) is sent again on its own, where the
    /// transport rides out the throttling.
    fn read(&mut self, ids: &[String]) -> Result<Vec<MsgraphMessage>, BridgeError> {
        let mut read = Vec::with_capacity(ids.len());
        let mut again = Vec::new();
        for chunk in ids.chunks(MSGRAPH_BATCH_MAX_REQUESTS) {
            let requests: Vec<MsgraphBatchRequest> = chunk
                .iter()
                .enumerate()
                .map(|(index, id)| {
                    Ok(MsgraphBatchRequest {
                        id: index.to_string(),
                        method: String::from("GET"),
                        url: relative(&message_url(id)?),
                        ..Default::default()
                    })
                })
                .collect::<Result<_, BridgeError>>()?;
            let coroutine =
                MsgraphBatch::new(&self.auth, &requests).map_err(|err| err.to_string())?;
            let replies = self.client.run_msgraph(coroutine)?;
            for reply in replies.responses {
                let Some(id) = reply
                    .id
                    .parse::<usize>()
                    .ok()
                    .and_then(|index| chunk.get(index))
                else {
                    continue;
                };
                match reply.status {
                    404 => (),
                    200..300 => match reply.parse::<MsgraphMessage>() {
                        Ok(message) => read.push(message),
                        Err(_) => again.push(id.clone()),
                    },
                    _ => again.push(id.clone()),
                }
            }
        }

        for id in again {
            match self.client.run_msgraph(MsgraphSend::<MsgraphMessage>::get(
                &self.auth,
                message_url(&id)?,
            )) {
                Ok(message) => read.push(message),
                Err(err) if err.status == Some(404) => (),
                Err(err) => return Err(err),
            }
        }
        Ok(read)
    }
}

/// The address one message is read at by id: the summary `$select` and
/// its `$expand`, percent-encoded.
fn message_url(id: &str) -> Result<Url, BridgeError> {
    let mut url = graph_url(&format!("me/messages/{id}"))?;
    url.query_pairs_mut()
        .append_pair("$select", MESSAGE_SELECT)
        .append_pair("$expand", MESSAGE_EXPAND);
    Ok(url)
}

/// The markers of one Graph message, named the IMAP way.
fn marks(message: &MsgraphMessage) -> Vec<String> {
    let flagged = message
        .flag
        .as_ref()
        .and_then(|flag| flag.flag_status.as_ref())
        .is_some_and(|status| *status == MsgraphFlagStatus::Flagged);
    flags(message.is_read.unwrap_or(false), false, flagged)
}

/// One Graph message, named by the summary its `$select` read, when its
/// `Date` falls in the scope.
fn named(message: MsgraphMessage, scope: &Scope, items: &mut Vec<Named>) {
    let summary = graph_summary(&message);
    if !scope.contains(summary.date.as_deref()) {
        return;
    }
    let flags = marks(&message);
    items.push(Named::new(message.id, flags, summary));
}

/// The Annex A summary of one Graph message, read off its `$select`.
///
/// The date is the `Date` header (`sentDateTime`), never the reception;
/// the attachment mark is Graph's own `hasAttachments`; the size is the
/// MAPI one [`MESSAGE_EXPAND`] asks for, the only property it expands.
fn graph_summary(message: &MsgraphMessage) -> PimdirMailSummary {
    let from: Vec<PimdirAddress> = message.from.iter().filter_map(address).collect();
    let first = from.first().cloned();

    PimdirMailSummary {
        message_id: message
            .internet_message_id
            .as_deref()
            .map(|id| {
                id.trim()
                    .trim_start_matches('<')
                    .trim_end_matches('>')
                    .to_string()
            })
            .filter(|id| !id.is_empty()),
        in_reply_to: Vec::new(),
        subject: message.subject.as_deref().map(decode).unwrap_or_default(),
        sender: first.as_ref().map(|address| address.address.clone()),
        sender_name: first.and_then(|address| address.name),
        date: message.sent_date_time.as_deref().and_then(utc),
        size: message
            .single_value_extended_properties
            .first()
            .and_then(|size| size.value.trim().parse().ok()),
        attachment: message.has_attachments,
        invitation: None,
        from,
        to: message.to_recipients.iter().filter_map(address).collect(),
        cc: message.cc_recipients.iter().filter_map(address).collect(),
        bcc: Vec::new(),
    }
}

/// One Graph recipient as Annex A.6 stores an address.
fn address(recipient: &MsgraphRecipient) -> Option<PimdirAddress> {
    let address = recipient.email_address.address.as_deref()?;
    let address = PimdirAddress::canonical(address);
    if address.is_empty() {
        return None;
    }
    let name = recipient
        .email_address
        .name
        .as_deref()
        .map(str::trim)
        .filter(|name| !name.is_empty())
        .map(String::from);
    Some(PimdirAddress { address, name })
}

/// The `permanentDelete` action on one message (Graph v1.0).
fn permanent_delete_url(id: &str) -> Result<Url, BridgeError> {
    parse_graph_url(&format!(
        "{MSGRAPH_API_BASE}me/messages/{id}/permanentDelete"
    ))
}

/// The `$select` of a step's listing: what places a message (its folder),
/// what orders it and moves the frontier (its `Date`), and its reception,
/// which tells an import from a timely message. The size rides along as
/// [`MESSAGE_EXPAND`], for the bodies' batches.
const STEP_SELECT: &str = "id,sentDateTime,receivedDateTime,parentFolderId";

/// How many more messages than the step's count one listing asks for:
/// the ids held at the frontier, and those out of scope.
const STEP_SLACK: usize = 25;

/// The most declared bytes one `$batch` of bodies asks for.
pub(crate) const BATCH_BYTES: u64 = 4 * 1024 * 1024;

/// A message declared larger than this is read alone, by a plain GET.
pub(crate) const ALONE_BYTES: u64 = 1024 * 1024;

/// What a step asks: how many messages, below which frontier and from
/// which bound, in which folders, the store holding which.
pub(crate) struct StepAsk<'a> {
    /// The folders stepped, by id: the account's but the trash, the junk,
    /// the mailboxes kept whole; the one folder a filtered view steps.
    pub folders: &'a [String],
    /// Whether the step lists that one folder alone.
    pub alone: bool,
    /// The account's frontier, [`None`] for the first step.
    pub frontier: Option<&'a str>,
    /// The account's bound, [`None`] for all mail.
    pub bound: Option<&'a str>,
    /// About how many messages to bring.
    pub count: usize,
}

/// One message a step brought.
#[derive(Clone, Debug, Eq, PartialEq)]
pub(crate) struct StepMessage {
    pub id: String,
    /// Its folder, by id.
    pub folder: String,
    /// Its `Date`, as the store sorts it.
    pub date: String,
    /// When it was received, in milliseconds since the epoch.
    pub received: Option<i64>,
    /// The MAPI size Graph states for it.
    pub size: Option<u64>,
    /// Whether the store binds it already: its body is not read again.
    pub held: bool,
}

/// What a step brought, and whether nothing older is left.
#[derive(Debug, Default)]
pub(crate) struct Stepped {
    pub messages: Vec<StepMessage>,
    pub exhausted: bool,
}

/// One body read whole into a file.
#[derive(Debug, Eq, PartialEq)]
pub(crate) struct GraphFetched {
    pub id: String,
    pub path: PathBuf,
    pub size: usize,
}

/// One item of a `$batch` of `$value` GETs, as the read sorts it.
#[derive(Debug, Eq, PartialEq)]
pub(crate) enum Inner {
    /// The message's bytes.
    Body(Vec<u8>),
    /// A 2xx whose body is not a base64 string: read alone.
    Undecoded,
    /// Gone (404).
    Gone,
    /// Throttled (429), with the seconds its `Retry-After` names.
    Throttled(Option<u64>),
    /// Any other failure.
    Failed(String),
}

/// The Graph requests a step and a read of bodies are made of, apart so
/// they can run over a fake.
pub(crate) trait GraphMail {
    /// One page of the account's messages, or of one folder's, newest
    /// `Date` first, within `band`, `top` at most, with [`STEP_SELECT`].
    fn dated(
        &mut self,
        folder: Option<&str>,
        band: &Band,
        top: u32,
    ) -> Result<Vec<MsgraphMessage>, BridgeError>;

    /// One `$batch` of `$value` GETs, each answer by its id.
    fn batch(&mut self, ids: &[String]) -> Result<Vec<(String, Inner)>, BridgeError>;

    /// One message's bytes, by a plain GET.
    fn source(&mut self, id: &str) -> Result<Vec<u8>, BridgeError>;

    /// The MAPI size Graph states for each of `ids` (20 to a `$batch`),
    /// those it states none for left out.
    fn sizes(&mut self, ids: &[String]) -> Result<HashMap<String, u64>, BridgeError>;

    /// Waits `delay`.
    fn pause(&mut self, delay: Duration);

    /// Which of `ids` the store holds, asked once per page a step lists.
    fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError>;
}

/// One step of a Graph account (docs B.13): its messages newest `Date`
/// first below the frontier, `count` of them and every one sharing the
/// last one's `Date`, the ones held at the frontier and those out of the
/// stepped folders left out.
///
/// A keyset, never `nextLink`: a request answering as many as it asked
/// for is followed by one from the oldest `Date` it reached, that second
/// included and the ids seen left out. On `InefficientFilter` or a 504
/// the account's listing gives way to each folder's, merged by `Date`.
pub(crate) fn step<G: GraphMail>(graph: &mut G, ask: &StepAsk) -> Result<Stepped, BridgeError> {
    if ask.alone {
        let folder = ask.folders.first().map(String::as_str);
        return walk(graph, folder, ask);
    }

    match walk(graph, None, ask) {
        Err(err) if inefficient(&err) => {
            log::debug!("graph step lists each folder: {err}");
        }
        walked => return walked,
    }

    let mut merged = Vec::new();
    let mut exhausted = true;
    for folder in ask.folders {
        let walked = walk(graph, Some(folder), ask)?;
        exhausted &= walked.exhausted;
        merged.extend(walked.messages);
    }
    merged.sort_by(|a, b| b.date.cmp(&a.date));
    let kept = with_ties(&merged, ask.count);
    exhausted &= kept == merged.len();
    merged.truncate(kept);
    Ok(Stepped {
        messages: merged,
        exhausted,
    })
}

/// Whether a listing failed the way the per-folder fallback answers: a
/// filter Graph finds too costly over the whole mailbox, or a timeout.
fn inefficient(err: &BridgeError) -> bool {
    err.status == Some(504) || err.message.contains("InefficientFilter")
}

/// How many of some messages, newest first, a step keeps: `count`, and
/// every one after sharing the last one's `Date`.
fn with_ties(newest_first: &[StepMessage], count: usize) -> usize {
    let Some(last) = count.checked_sub(1).and_then(|at| newest_first.get(at)) else {
        return newest_first.len();
    };
    count
        + newest_first[count..]
            .iter()
            .take_while(|message| message.date == last.date)
            .count()
}

/// The keyset walk of one listing, the account's or a folder's.
fn walk<G: GraphMail>(
    graph: &mut G,
    folder: Option<&str>,
    ask: &StepAsk,
) -> Result<Stepped, BridgeError> {
    let top = (ask.count + STEP_SLACK).min(GRAPH_PAGE as usize) as u32;
    let mut band = Band {
        since: ask.bound.map(String::from),
        until: ask.frontier.map(String::from),
        inclusive: true,
    };
    let mut seen = HashSet::new();
    let mut messages = Vec::new();
    let mut cut: Option<String> = None;

    loop {
        let page = graph.dated(folder, &band, top)?;
        let ids: Vec<String> = page.iter().map(|message| message.id.clone()).collect();
        let held = graph.held(&ids)?;
        let full = page.len() >= top as usize;
        let mut fresh = 0;
        let mut oldest: Option<String> = None;
        for message in page {
            if !seen.insert(message.id.clone()) {
                continue;
            }
            fresh += 1;
            let Some(date) = message.sent_date_time.as_deref().and_then(utc) else {
                continue;
            };
            if cut.as_deref().is_some_and(|cut| date.as_str() < cut) {
                return Ok(Stepped {
                    messages,
                    exhausted: false,
                });
            }
            oldest = Some(date.clone());

            let Some(parent) = placed(&message, folder, ask) else {
                continue;
            };
            let held = held.contains(&message.id);
            if held && ask.frontier == Some(date.as_str()) {
                continue;
            }
            messages.push(StepMessage {
                received: message
                    .received_date_time
                    .as_deref()
                    .and_then(|raw| raw.parse::<Timestamp>().ok())
                    .map(|received| received.as_millisecond()),
                size: message
                    .single_value_extended_properties
                    .first()
                    .and_then(|size| size.value.trim().parse().ok()),
                id: message.id,
                folder: parent,
                date: date.clone(),
                held,
            });
            if messages.len() == ask.count {
                cut = Some(date);
            }
        }

        if !full {
            return Ok(Stepped {
                messages,
                exhausted: true,
            });
        }
        match oldest {
            Some(oldest) if fresh > 0 => {
                band.until = Some(oldest);
                band.inclusive = true;
            }
            // NOTE: a whole page of one second seen already: on below it.
            _ if band.inclusive && band.until.is_some() => band.inclusive = false,
            // NOTE: undated messages alone, which no keyset goes below.
            _ => {
                return Ok(Stepped {
                    messages,
                    exhausted: true,
                });
            }
        }
    }
}

/// The folder a listed message belongs to when the step takes it: the
/// folder listed, or on the account's listing one of the stepped ones.
fn placed(message: &MsgraphMessage, folder: Option<&str>, ask: &StepAsk) -> Option<String> {
    if let Some(folder) = folder {
        return Some(folder.to_string());
    }
    message
        .parent_folder_id
        .as_ref()
        .filter(|parent| ask.folders.contains(parent))
        .cloned()
}

/// Reads some messages whole into files of `dir`, each with the size
/// Graph states (or none): `$batch` calls of up to 20 `$value` GETs and
/// [`BATCH_BYTES`] declared, one at a time, a message past
/// [`ALONE_BYTES`] or of no stated size read alone.
///
/// An item throttled inside a call waits its `Retry-After` once and goes
/// in the next call; one gone, or failing otherwise, or throttled twice,
/// is left out of the answer, for the caller to see which ids came back
/// with no file. An expired token fails the read.
pub(crate) fn read_sources<G: GraphMail>(
    graph: &mut G,
    wanted: &[(String, Option<u64>)],
    dir: &Path,
) -> Result<Vec<GraphFetched>, BridgeError> {
    let mut alone = Vec::new();
    let mut batches: VecDeque<Vec<String>> = VecDeque::new();
    let mut bytes = 0;
    for (id, size) in wanted {
        let Some(size) = size.filter(|size| *size <= ALONE_BYTES) else {
            alone.push(id.clone());
            continue;
        };
        match batches.back_mut() {
            Some(batch)
                if batch.len() < MSGRAPH_BATCH_MAX_REQUESTS && bytes + size <= BATCH_BYTES =>
            {
                batch.push(id.clone());
                bytes += size;
            }
            _ => {
                batches.push_back(vec![id.clone()]);
                bytes = size;
            }
        }
    }

    let mut fetched = Vec::with_capacity(wanted.len());
    let mut throttled = HashSet::new();
    while let Some(batch) = batches.pop_front() {
        let answers = match graph.batch(&batch) {
            Ok(answers) => answers,
            Err(err) if err.status == Some(401) => return Err(err),
            Err(err) => {
                log::warn!("graph batch of {} bodies failed: {err}", batch.len());
                continue;
            }
        };

        let mut again = Vec::new();
        let mut wait = 0;
        for (id, inner) in answers {
            match inner {
                Inner::Body(source) => fetched.push(written(dir, id, &source)?),
                Inner::Undecoded => alone.push(id),
                Inner::Gone => (),
                Inner::Throttled(after) if throttled.insert(id.clone()) => {
                    wait = wait.max(after.unwrap_or(1));
                    again.push(id);
                }
                Inner::Throttled(_) => log::warn!("body of {id} throttled twice"),
                Inner::Failed(reason) => log::warn!("body of {id} not read: {reason}"),
            }
        }
        if !again.is_empty() {
            let delay = Duration::from_secs(wait).min(WAIT_BUDGET);
            log::debug!("graph batch: {} throttled, waiting {delay:?}", again.len());
            graph.pause(delay);
            batches.push_front(again);
        }
    }

    for id in alone {
        match graph.source(&id) {
            Ok(source) => fetched.push(written(dir, id, &source)?),
            Err(err) if err.status == Some(401) => return Err(err),
            Err(err) if err.status == Some(404) => (),
            Err(err) => log::warn!("body of {id} not read: {err}"),
        }
    }
    Ok(fetched)
}

/// Writes one message's bytes to its file of `dir`.
fn written(dir: &Path, id: String, source: &[u8]) -> Result<GraphFetched, BridgeError> {
    let path = source_path(dir, &id);
    fs::write(&path, source).map_err(|err| format!("Could not write `{id}`: {err}"))?;
    Ok(GraphFetched {
        id,
        path,
        size: source.len(),
    })
}

/// How one inner answer of a `$batch` of `$value` GETs reads.
fn inner(reply: MsgraphBatchResponse) -> Inner {
    match reply.status {
        404 => Inner::Gone,
        429 => Inner::Throttled(reply.retry_after()),
        200..300 => match reply.bytes() {
            Ok(bytes) => Inner::Body(bytes),
            Err(_) => Inner::Undecoded,
        },
        status => Inner::Failed(format!("HTTP {status}")),
    }
}

/// The live Graph of one account's mail.
struct LiveMail<'c, 'a, 'local> {
    client: &'c mut Client<'a, 'local>,
    auth: HttpAuthBearer,
}

impl LiveMail<'_, '_, '_> {
    /// Sends one `$batch` of GETs at `urls`, each answer by its index.
    fn send_batch(&mut self, urls: &[Url]) -> Result<Vec<MsgraphBatchResponse>, BridgeError> {
        let requests: Vec<MsgraphBatchRequest> = urls
            .iter()
            .enumerate()
            .map(|(index, url)| MsgraphBatchRequest {
                id: index.to_string(),
                method: String::from("GET"),
                url: relative(url),
                ..Default::default()
            })
            .collect();
        let coroutine = MsgraphBatch::new(&self.auth, &requests).map_err(|err| err.to_string())?;
        Ok(self.client.run_msgraph(coroutine)?.responses)
    }
}

impl GraphMail for LiveMail<'_, '_, '_> {
    fn dated(
        &mut self,
        folder: Option<&str>,
        band: &Band,
        top: u32,
    ) -> Result<Vec<MsgraphMessage>, BridgeError> {
        let filter = band.filter();
        let params = MsgraphMessagesListParams {
            top: Some(top),
            select: Some(STEP_SELECT),
            expand: Some(MESSAGE_EXPAND),
            filter: filter.as_deref(),
            orderby: Some("sentDateTime desc"),
            ..Default::default()
        };
        let coroutine = MsgraphMessagesList::new(&self.auth, "me", folder, &params)
            .map_err(|err| err.to_string())?;
        Ok(self.client.run_msgraph(coroutine)?.value)
    }

    fn batch(&mut self, ids: &[String]) -> Result<Vec<(String, Inner)>, BridgeError> {
        let urls = ids
            .iter()
            .map(|id| graph_url(&format!("me/messages/{id}/$value")))
            .collect::<Result<Vec<_>, _>>()?;
        let mut answers = Vec::with_capacity(ids.len());
        for reply in self.send_batch(&urls)? {
            let Some(id) = reply.id.parse::<usize>().ok().and_then(|at| ids.get(at)) else {
                continue;
            };
            answers.push((id.clone(), inner(reply)));
        }
        Ok(answers)
    }

    fn source(&mut self, id: &str) -> Result<Vec<u8>, BridgeError> {
        let coroutine =
            MsgraphMessageGetRaw::new(&self.auth, "me", id).map_err(|err| err.to_string())?;
        self.client.run_msgraph(coroutine)
    }

    fn sizes(&mut self, ids: &[String]) -> Result<HashMap<String, u64>, BridgeError> {
        let mut sizes = HashMap::with_capacity(ids.len());
        for chunk in ids.chunks(MSGRAPH_BATCH_MAX_REQUESTS) {
            let urls = chunk
                .iter()
                .map(|id| {
                    let mut url = graph_url(&format!("me/messages/{id}"))?;
                    url.query_pairs_mut()
                        .append_pair("$select", "id")
                        .append_pair("$expand", MESSAGE_EXPAND);
                    Ok(url)
                })
                .collect::<Result<Vec<_>, BridgeError>>()?;
            for reply in self.send_batch(&urls)? {
                let Some(id) = reply.id.parse::<usize>().ok().and_then(|at| chunk.get(at)) else {
                    continue;
                };
                let size = reply.parse::<MsgraphMessage>().ok().and_then(|message| {
                    message
                        .single_value_extended_properties
                        .first()
                        .and_then(|size| size.value.trim().parse().ok())
                });
                if let Some(size) = size {
                    sizes.insert(id.clone(), size);
                }
            }
        }
        Ok(sizes)
    }

    fn pause(&mut self, delay: Duration) {
        sleep(delay);
    }

    fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError> {
        self.client.held(ids)
    }
}

impl<'a, 'local> Client<'a, 'local> {
    /// One step of a Graph account ([`step`]), its bodies not held read
    /// into files of `dir` ([`read_sources`]) with the sizes its listing
    /// stated.
    pub fn graph_step(
        &mut self,
        token: &str,
        ask: &StepAsk,
        dir: &Path,
    ) -> Result<(Stepped, Vec<GraphFetched>), BridgeError> {
        let mut graph = LiveMail {
            client: self,
            auth: HttpAuthBearer::new(token),
        };
        let stepped = step(&mut graph, ask)?;
        let wanted: Vec<(String, Option<u64>)> = stepped
            .messages
            .iter()
            .filter(|message| !message.held)
            .map(|message| (message.id.clone(), message.size))
            .collect();
        let fetched = read_sources(&mut graph, &wanted, dir)?;
        Ok((stepped, fetched))
    }

    /// Some messages read whole into files of `dir`: their sizes first,
    /// 20 to a `$batch`, then their bodies batched by them
    /// ([`read_sources`]).
    pub fn graph_sources(
        &mut self,
        token: &str,
        ids: &[String],
        dir: &Path,
    ) -> Result<Vec<GraphFetched>, BridgeError> {
        let mut graph = LiveMail {
            client: self,
            auth: HttpAuthBearer::new(token),
        };
        let sizes = match graph.sizes(ids) {
            Ok(sizes) => sizes,
            Err(err) if err.status == Some(401) => return Err(err),
            Err(err) => {
                log::warn!("graph sizes not read: {err}");
                HashMap::new()
            }
        };
        let wanted: Vec<(String, Option<u64>)> = ids
            .iter()
            .map(|id| (id.clone(), sizes.get(id).copied()))
            .collect();
        read_sources(&mut graph, &wanted, dir)
    }
}

#[cfg(test)]
#[path = "graph_mail_tests.rs"]
mod sync_tests;

#[cfg(test)]
mod tests {
    use io_msgraph::v1::rest::users::{
        contacts::MsgraphSingleValueExtendedProperty,
        messages::{MsgraphEmailAddress, MsgraphMessage, MsgraphRecipient},
    };

    use super::{graph_summary, message_url, permanent_delete_url};
    use crate::client::graph::relative;

    #[test]
    fn a_permanent_delete_posts_the_action() {
        assert_eq!(
            permanent_delete_url("AAMk-1=").unwrap().as_str(),
            "https://graph.microsoft.com/v1.0/me/messages/AAMk-1=/permanentDelete"
        );
    }

    #[test]
    fn a_read_by_id_expands_the_size_percent_encoded() {
        let url = message_url("AAMk-1=").unwrap();
        let expand = url
            .query_pairs()
            .find(|(key, _)| key == "$expand")
            .map(|(_, value)| value.into_owned());
        assert_eq!(
            expand.as_deref(),
            Some("singleValueExtendedProperties($filter=id eq 'Integer 0x0E08')")
        );

        // NOTE: a batch names it relative to the API version, no raw
        // space left in it.
        let batched = relative(&url);
        assert!(batched.starts_with("/me/messages/AAMk-1=?%24select="));
        assert!(!batched.contains(' '), "got: {batched}");
    }

    #[test]
    fn the_size_is_the_expanded_mapi_size() {
        let sized = |value: &str| MsgraphMessage {
            id: "m1".into(),
            single_value_extended_properties: vec![MsgraphSingleValueExtendedProperty {
                id: "Integer 0xe08".into(),
                value: value.into(),
            }],
            ..Default::default()
        };
        assert_eq!(graph_summary(&sized("48213")).size, Some(48213));
        assert_eq!(graph_summary(&sized("large")).size, None, "unparsable");
        assert_eq!(graph_summary(&sized("-1")).size, None, "negative");

        let bare = MsgraphMessage {
            id: "m2".into(),
            ..Default::default()
        };
        assert_eq!(graph_summary(&bare).size, None, "not expanded");
    }

    #[test]
    fn the_date_is_the_sent_date_not_the_reception() {
        let message = MsgraphMessage {
            id: "m1".into(),
            sent_date_time: Some("2026-10-07T08:00:00Z".into()),
            received_date_time: Some("2026-10-07T08:05:00Z".into()),
            ..Default::default()
        };
        assert_eq!(
            graph_summary(&message).date.as_deref(),
            Some("2026-10-07T08:00:00Z")
        );

        // A message with no `Date` has none, rather than the reception
        // time standing in for it (Annex A.1: `NULL`).
        let undated = MsgraphMessage {
            id: "m2".into(),
            received_date_time: Some("2026-10-07T08:05:00Z".into()),
            ..Default::default()
        };
        assert_eq!(graph_summary(&undated).date, None);
    }

    #[test]
    fn the_summary_reads_the_select() {
        let recipient = |name: &str, address: &str| MsgraphRecipient {
            email_address: MsgraphEmailAddress {
                name: Some(name.into()),
                address: Some(address.into()),
            },
        };
        let message = MsgraphMessage {
            id: "m1".into(),
            subject: Some("Hello".into()),
            from: Some(recipient("Ana", "Ana@Example.org")),
            to_recipients: vec![recipient("Bo", "bo@example.org")],
            internet_message_id: Some("<m1@example.org>".into()),
            has_attachments: Some(true),
            ..Default::default()
        };

        let summary = graph_summary(&message);
        assert_eq!(summary.message_id.as_deref(), Some("m1@example.org"));
        assert_eq!(summary.sender.as_deref(), Some("ana@example.org"));
        assert_eq!(summary.sender_name.as_deref(), Some("Ana"));
        assert_eq!(summary.to.len(), 1);
        assert_eq!(summary.attachment, Some(true));
    }
}
