//! # Tasks
//!
//! A to-do read the GTD way (cairn/changes/gtd-by-categories, pimdir GUIDE
//! §17): its state, its categories, its parent, who it waits on and the
//! mail it came from, each a standard VTODO property. The state is three
//! reserved `CATEGORIES` values, `inbox`, `waiting` and `someday`; an open
//! to-do carrying none is a next action, which is what a task another
//! client created is.

use ical::{
    component::{IcalComponent, IcalComponentKind},
    param::IcalParam,
    prop::{IcalProp, IcalPropKind, IcalPropName},
    value::{IcalValue, binary::IcalBinary},
};
use serde::{Deserialize, Serialize};

use crate::{
    calendar::{
        collect_scheduled, cst_of, has, raw_value, text_of, time_of,
        zone::{EventTime, Zones},
    },
    error::BridgeError,
};

/// The reserved `CATEGORIES` values, in the order a to-do carrying several
/// is listed by: captured, someone else's, not a commitment.
///
/// Written as these English literals in every locale, compared folded
/// ([`state_of`]); every other value is the person's own.
pub const STATES: [&str; 3] = ["inbox", "waiting", "someday"];

/// The list of an open to-do carrying none of [`STATES`].
pub const NEXT: &str = "NEXT";

/// The reserved value a category is, folded as pimdir folds `fn` (Unicode
/// lowercase, locale-independent, trimmed), [`None`] for the person's own.
pub fn state_of(category: &str) -> Option<&'static str> {
    let folded = category.to_lowercase();
    let folded = folded.trim();
    STATES.iter().find(|state| **state == folded).copied()
}

/// One to-do as the Tasks tab lists it.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Task {
    pub uid: String,
    pub summary: String,
    pub description: String,
    /// `STATUS`, uppercased, empty when the to-do carries none.
    pub status: String,
    /// The first of [`STATES`] it carries, uppercased, else [`NEXT`].
    pub list: String,
    /// Every one of [`STATES`] it carries, in that order: a to-do another
    /// client marked twice shows in both lists.
    pub states: Vec<String>,
    /// Every other `CATEGORIES` value, in order: contexts, projects,
    /// anything.
    pub categories: Vec<String>,
    /// The `UID` a `RELATED-TO` of type `PARENT` names, which shows it
    /// under that task and decides nothing else.
    pub parent: Option<String>,
    /// `PRIORITY`, 0 being undefined (RFC 5545 3.8.1.9).
    pub priority: u8,
    pub start: Option<EventTime>,
    pub due: Option<EventTime>,
    pub completed: Option<EventTime>,
    /// Who a Waiting For item waits on, from `CONTACT`.
    pub contact: Option<TaskContact>,
    /// The `Message-ID` of the mail it came from, angle brackets aside,
    /// from an `ATTACH` or `URL` holding a `mid:` URI (RFC 2392).
    pub source: Option<String>,
    /// `CREATED`, else `DTSTAMP`, raw: when it was captured.
    pub created: String,
}

/// The person a `CONTACT` names: its text, and the address its `ALTREP`
/// gives as a `mailto:` URI.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", default)]
pub struct TaskContact {
    pub name: String,
    pub address: String,
}

/// One object handed to [`tasks`], keyed by the caller.
#[derive(Deserialize)]
pub struct TaskObject {
    pub key: String,
    pub ical: String,
}

/// One to-do [`tasks`] read, under its object's key.
#[derive(Serialize)]
pub struct TaskRow {
    pub key: String,
    pub task: Task,
}

/// Reads every to-do among the objects given, skipping the rest.
///
/// One call for a whole list, rather than one per row: an object that is
/// not a to-do, or does not parse, is not a task and is left out.
pub fn tasks(objects: &str) -> Result<Vec<TaskRow>, BridgeError> {
    let objects: Vec<TaskObject> = serde_json::from_str(objects).map_err(|err| err.to_string())?;

    Ok(objects
        .into_iter()
        .filter_map(|object| {
            let task = task(&object.ical).ok()??;
            Some(TaskRow {
                key: object.key,
                task,
            })
        })
        .collect())
}

/// Reads the to-do of one object, [`None`] when it holds none.
///
/// The master, as a to-do series is shown once rather than per
/// occurrence.
pub fn task(ical: &str) -> Result<Option<Task>, BridgeError> {
    let cst = cst_of(ical)?;
    let decoded = cst.decode();
    let zones = Zones::of(&decoded);

    let mut found = Vec::new();
    for component in &decoded.components {
        collect_scheduled(component, &mut found);
    }
    let todos: Vec<_> = found
        .into_iter()
        .filter(|(_, kind)| *kind == IcalComponentKind::VTodo)
        .map(|(component, _)| component)
        .collect();
    let Some(todo) = todos
        .iter()
        .find(|component| !has(component, IcalPropKind::RecurrenceId))
        .or(todos.first())
        .copied()
    else {
        return Ok(None);
    };

    let mut states = Vec::new();
    let mut categories = Vec::new();
    for value in categories_of(todo) {
        match state_of(&value) {
            Some(state) => states.push(state),
            None => categories.push(value),
        }
    }
    let states: Vec<String> = STATES
        .iter()
        .filter(|state| states.contains(*state))
        .map(|state| state.to_string())
        .collect();
    let list = states
        .first()
        .map(|state| state.to_ascii_uppercase())
        .unwrap_or_else(|| NEXT.to_string());

    Ok(Some(Task {
        uid: text_of(todo, IcalPropKind::Uid).unwrap_or_default(),
        summary: text_of(todo, IcalPropKind::Summary).unwrap_or_default(),
        description: text_of(todo, IcalPropKind::Description).unwrap_or_default(),
        status: text_of(todo, IcalPropKind::Status)
            .unwrap_or_default()
            .to_ascii_uppercase(),
        list,
        states,
        categories,
        parent: parent_of(todo),
        priority: text_of(todo, IcalPropKind::Priority)
            .and_then(|value| value.trim().parse().ok())
            .filter(|value| *value <= 9)
            .unwrap_or(0),
        start: time_of(todo, IcalPropKind::DtStart).map(|time| zones.resolve(time)),
        due: time_of(todo, IcalPropKind::Due).map(|time| zones.resolve(time)),
        completed: time_of(todo, IcalPropKind::Completed),
        contact: contact_of(todo),
        source: source_of(todo),
        created: text_of(todo, IcalPropKind::Created)
            .or_else(|| text_of(todo, IcalPropKind::DtStamp))
            .unwrap_or_default(),
    }))
}

/// Every value of every `CATEGORIES`, as a to-do may carry several,
/// deduplicated in order.
fn categories_of(component: &IcalComponent) -> Vec<String> {
    let mut contexts = Vec::new();
    for prop in kinds(component, IcalPropKind::Categories) {
        let values: Vec<String> = match &prop.value {
            IcalValue::TextList(list) => list.0.iter().map(|value| value.to_string()).collect(),
            _ => raw_value(prop).into_iter().collect(),
        };
        for value in values {
            let value = value.trim();
            if !value.is_empty() && !contexts.iter().any(|known: &String| known == value) {
                contexts.push(value.to_string());
            }
        }
    }
    contexts
}

/// The `UID` of the first `RELATED-TO` naming a parent, the default type
/// (RFC 5545 3.2.15).
fn parent_of(component: &IcalComponent) -> Option<String> {
    kinds(component, IcalPropKind::RelatedTo)
        .find(|prop| {
            prop.params.iter().all(|param| match param {
                IcalParam::RelType(kind) => kind.eq_ignore_ascii_case("PARENT"),
                _ => true,
            })
        })
        .and_then(raw_value)
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty())
}

/// The first `CONTACT`, its `ALTREP` read when it is a `mailto:`.
fn contact_of(component: &IcalComponent) -> Option<TaskContact> {
    let prop = kinds(component, IcalPropKind::Contact).next()?;
    let address = prop
        .params
        .iter()
        .find_map(|param| match param {
            IcalParam::AltRep(uri) => uri
                .split_once(':')
                .filter(|(scheme, _)| scheme.eq_ignore_ascii_case("mailto"))
                .map(|(_, address)| address.to_string()),
            _ => None,
        })
        .unwrap_or_default();
    let name = raw_value(prop).unwrap_or_default();

    (!name.is_empty() || !address.is_empty()).then_some(TaskContact { name, address })
}

/// The `Message-ID` of the first `ATTACH` or `URL` holding a `mid:` URI.
fn source_of(component: &IcalComponent) -> Option<String> {
    kinds(component, IcalPropKind::Attach)
        .chain(kinds(component, IcalPropKind::Url))
        .filter_map(|prop| match &prop.value {
            IcalValue::Binary(IcalBinary::Uri(uri)) => Some(uri.to_string()),
            IcalValue::Binary(IcalBinary::Base64(_)) => None,
            _ => raw_value(prop),
        })
        .find_map(|uri| message_id_of(&uri))
}

/// The `Message-ID` a `mid:` URI names, percent-decoded, without the
/// part a `/` adds after it (RFC 2392).
pub fn message_id_of(uri: &str) -> Option<String> {
    let (scheme, rest) = uri.trim().split_once(':')?;
    if !scheme.eq_ignore_ascii_case("mid") {
        return None;
    }
    let id = rest.split('/').next().unwrap_or_default();
    let id = percent_decode(id);
    let id = id.trim_start_matches('<').trim_end_matches('>');
    (!id.is_empty()).then(|| id.to_string())
}

/// The `mid:` URI naming a `Message-ID` (RFC 2392), what [`message_id_of`]
/// reads back.
pub fn mid_uri(message_id: &str) -> String {
    let id = message_id
        .trim()
        .trim_start_matches('<')
        .trim_end_matches('>');
    let mut uri = String::from("mid:");
    for byte in id.bytes() {
        match byte {
            b'A'..=b'Z'
            | b'a'..=b'z'
            | b'0'..=b'9'
            | b'-'
            | b'.'
            | b'_'
            | b'~'
            | b'!'
            | b'$'
            | b'&'
            | b'\''
            | b'('
            | b')'
            | b'*'
            | b'+'
            | b','
            | b';'
            | b'='
            | b'@' => uri.push(byte as char),
            other => uri.push_str(&format!("%{other:02X}")),
        }
    }
    uri
}

fn percent_decode(value: &str) -> String {
    let bytes = value.as_bytes();
    let mut out = Vec::with_capacity(bytes.len());
    let mut at = 0;
    while at < bytes.len() {
        if bytes[at] == b'%'
            && let Some(hex) = value.get(at + 1..at + 3)
            && let Ok(byte) = u8::from_str_radix(hex, 16)
        {
            out.push(byte);
            at += 3;
            continue;
        }
        out.push(bytes[at]);
        at += 1;
    }
    String::from_utf8_lossy(&out).into_owned()
}

fn kinds<'c>(
    component: &'c IcalComponent<'c>,
    kind: IcalPropKind,
) -> impl Iterator<Item = &'c IcalProp<'c>> {
    component
        .props
        .iter()
        .filter(move |prop| matches!(&prop.name, IcalPropName::Kind(found) if *found == kind))
}

#[cfg(test)]
mod tests {
    use crate::calendar::{edit::write, task::*, tests::object};

    #[test]
    fn a_todo_without_a_state_is_a_next_action() {
        let ical = object("BEGIN:VTODO\r\nUID:1\r\nSUMMARY:Buy ink\r\nEND:VTODO\r\n");

        let task = task(&ical).unwrap().unwrap();

        assert_eq!(task.list, NEXT);
        assert_eq!(task.summary, "Buy ink");
        assert!(task.categories.is_empty());
        assert!(task.parent.is_none());
    }

    #[test]
    fn reads_every_gtd_field() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:2\r\nDTSTAMP:20261027T090000Z\r\nSUMMARY:Feedback on the filter page\r\n\
             CATEGORIES:@computer,waiting\r\nCATEGORIES:@phone,@home\r\n\
             RELATED-TO;RELTYPE=SIBLING:other\r\nRELATED-TO:project-1\r\nPRIORITY:3\r\n\
             CONTACT;ALTREP=\"mailto:lea@example.net\":Léa Martin\r\n\
             ATTACH:mid:abc%2F1@example.net\r\nDUE;VALUE=DATE:20261030\r\nEND:VTODO\r\n",
        );

        let task = task(&ical).unwrap().unwrap();

        assert_eq!(task.list, "WAITING");
        // NOTE: every CATEGORIES line, deduplicated in order, the state
        // aside.
        assert_eq!(task.categories, ["@computer", "@phone", "@home"]);
        // NOTE: a sibling is not a parent; no RELTYPE means PARENT.
        assert_eq!(task.parent.as_deref(), Some("project-1"));
        assert_eq!(task.priority, 3);
        let contact = task.contact.unwrap();
        assert_eq!(contact.name, "Léa Martin");
        assert_eq!(contact.address, "lea@example.net");
        assert_eq!(task.source.as_deref(), Some("abc/1@example.net"));
        assert_eq!(task.due.unwrap().time, "20261030");
        assert_eq!(task.created, "20261027T090000Z");
    }

    #[test]
    fn each_state_reads_from_its_category() {
        for (category, list) in [
            ("inbox", "INBOX"),
            ("waiting", "WAITING"),
            ("someday", "SOMEDAY"),
        ] {
            let ical = object(&format!(
                "BEGIN:VTODO\r\nUID:3\r\nCATEGORIES:@home,{category}\r\nEND:VTODO\r\n"
            ));

            let task = task(&ical).unwrap().unwrap();

            assert_eq!(task.list, list);
            assert_eq!(task.states, [category]);
            assert_eq!(task.categories, ["@home"]);
        }
    }

    #[test]
    fn a_cased_state_reads_folded() {
        let ical = object("BEGIN:VTODO\r\nUID:3\r\nCATEGORIES: Inbox\r\nEND:VTODO\r\n");

        let task = task(&ical).unwrap().unwrap();

        assert_eq!(task.list, "INBOX");
        assert!(task.categories.is_empty());
    }

    #[test]
    fn an_x_pimalaya_gtd_task_is_a_next_action_written_back_untouched() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:3\r\nX-PIMALAYA-GTD:WAITING\r\nCATEGORIES:@home\r\nEND:VTODO\r\n",
        );

        let read = task(&ical).unwrap().unwrap();
        let written = write(&ical, r#"{"gtd":"someday"}"#).unwrap();

        assert_eq!(read.list, NEXT);
        assert_eq!(read.categories, ["@home"]);
        assert!(written.contains("X-PIMALAYA-GTD:WAITING\r\nCATEGORIES:@home\r\n"));
        assert_eq!(task(&written).unwrap().unwrap().list, "SOMEDAY");
    }

    #[test]
    fn each_state_writes_as_its_category_and_clears() {
        for state in STATES {
            let ical = object("BEGIN:VTODO\r\nUID:3\r\nX-VENDOR:keep\r\nEND:VTODO\r\n");

            let written =
                write(&ical, &format!(r#"{{"gtd":"{}"}}"#, state.to_uppercase())).unwrap();
            let cleared = write(&written, r#"{"gtd":""}"#).unwrap();

            assert!(written.contains(&format!("CATEGORIES:{state}\r\n")));
            assert!(!written.contains("X-PIMALAYA-GTD"));
            assert_eq!(task(&written).unwrap().unwrap().states, [state]);
            assert_eq!(cleared, ical);
        }
    }

    #[test]
    fn a_state_takes_the_place_of_the_one_before() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:3\r\nCATEGORIES:@home,inbox,Work\r\nCATEGORIES:@phone\r\n\
             END:VTODO\r\n",
        );

        let written = write(&ical, r#"{"gtd":"waiting"}"#).unwrap();
        let task = task(&written).unwrap().unwrap();

        assert!(written.contains("CATEGORIES:@home,waiting,Work\r\nCATEGORIES:@phone\r\n"));
        assert_eq!(task.list, "WAITING");
        assert_eq!(task.categories, ["@home", "Work", "@phone"]);
    }

    #[test]
    fn clearing_the_state_leaves_the_rest_byte_for_byte() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:3\r\nCATEGORIES:Inbox\r\nX-VENDOR:keep\r\n\
             CATEGORIES:@home\r\nEND:VTODO\r\n",
        );

        let written = write(&ical, r#"{"gtd":""}"#).unwrap();

        assert_eq!(written, ical.replace("CATEGORIES:Inbox\r\n", ""));
    }

    #[test]
    fn a_task_in_its_state_already_is_left_untouched() {
        let ical = object("BEGIN:VTODO\r\nUID:3\r\nCATEGORIES:Waiting,@home\r\nEND:VTODO\r\n");

        assert_eq!(write(&ical, r#"{"gtd":"waiting"}"#).unwrap(), ical);
    }

    #[test]
    fn editing_the_categories_keeps_the_state() {
        let ical = object("BEGIN:VTODO\r\nUID:3\r\nCATEGORIES:inbox,@home\r\nEND:VTODO\r\n");

        let written = write(&ical, r#"{"categories":"@work, someday"}"#).unwrap();
        let task = task(&written).unwrap().unwrap();

        assert_eq!(task.list, "INBOX");
        assert_eq!(task.states, ["inbox"]);
        assert_eq!(task.categories, ["@work"]);
    }

    #[test]
    fn an_event_is_no_task() {
        let ical = object("BEGIN:VEVENT\r\nUID:4\r\nDTSTART:20261027T090000\r\nEND:VEVENT\r\n");

        assert!(task(&ical).unwrap().is_none());
    }

    #[test]
    fn a_listing_keeps_the_todos_under_their_keys() {
        let todo = object("BEGIN:VTODO\r\nUID:5\r\nEND:VTODO\r\n");
        let event = object("BEGIN:VEVENT\r\nUID:6\r\nDTSTART:20261027T090000\r\nEND:VEVENT\r\n");
        let objects = serde_json::json!([
            {"key": "a", "ical": todo},
            {"key": "b", "ical": event},
            {"key": "c", "ical": "not a calendar"},
        ]);

        let rows = tasks(&objects.to_string()).unwrap();

        assert_eq!(rows.len(), 1);
        assert_eq!(rows[0].key, "a");
        assert_eq!(rows[0].task.uid, "5");
    }

    #[test]
    fn an_edit_writes_every_gtd_field_and_reads_it_back() {
        let ical =
            object("BEGIN:VTODO\r\nUID:7\r\nSUMMARY:Feedback\r\nX-VENDOR:keep\r\nEND:VTODO\r\n");
        let edit = serde_json::json!({
            "gtd": "waiting",
            "parent": "project-1",
            "categories": "@computer, @phone",
            "contact": {"name": "Léa Martin", "address": "lea@example.net"},
            "source": "<abc@example.net>",
        });

        let written = write(&ical, &edit.to_string()).unwrap();
        let task = task(&written).unwrap().unwrap();

        assert!(written.contains("CATEGORIES:@computer,@phone\r\n"));
        assert!(written.contains("CATEGORIES:waiting\r\n"));
        assert!(written.contains("RELATED-TO;RELTYPE=PARENT:project-1\r\n"));
        assert!(written.contains("ATTACH:mid:abc@example.net\r\n"));
        assert!(written.contains("X-VENDOR:keep\r\n"));
        assert_eq!(task.list, "WAITING");
        assert_eq!(task.parent.as_deref(), Some("project-1"));
        assert_eq!(task.categories, ["@computer", "@phone"]);
        assert_eq!(task.contact.unwrap().address, "lea@example.net");
        assert_eq!(task.source.as_deref(), Some("abc@example.net"));
    }

    #[test]
    fn clearing_keeps_what_another_client_relates_or_attaches() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:8\r\nCATEGORIES:waiting\r\n\
             RELATED-TO;RELTYPE=PARENT:project-1\r\nRELATED-TO;RELTYPE=SIBLING:other\r\n\
             CONTACT:Sam\r\nATTACH:mid:abc@example.net\r\n\
             ATTACH:https://example.org/plan.pdf\r\nEND:VTODO\r\n",
        );
        let edit = r#"{"gtd":"","parent":"","contact":{},"source":""}"#;

        let written = write(&ical, edit).unwrap();
        let task = task(&written).unwrap().unwrap();

        assert_eq!(task.list, NEXT);
        assert!(task.parent.is_none());
        assert!(task.contact.is_none());
        assert!(task.source.is_none());
        assert!(!written.contains("CATEGORIES"));
        assert!(written.contains("RELATED-TO;RELTYPE=SIBLING:other\r\n"));
        assert!(written.contains("ATTACH:https://example.org/plan.pdf\r\n"));
    }

    #[test]
    fn an_untouched_todo_round_trips_byte_for_byte() {
        let ical = object(
            "BEGIN:VTODO\r\nUID:9\r\nCATEGORIES:someday,@home\r\nRELATED-TO:p\r\nEND:VTODO\r\n",
        );

        assert_eq!(write(&ical, "{}").unwrap(), ical);
    }

    #[test]
    fn a_capture_is_a_todo_with_no_date() {
        let draft = crate::calendar::edit::EventDraft {
            component: "VTODO",
            uid: "10",
            stamp: "20261027T090000Z",
            start: "",
            tzid: "",
            vtimezone: "",
        };

        let ical = crate::calendar::edit::create(&draft).unwrap();

        assert!(!ical.contains("DTSTART"));
        assert!(task(&ical).unwrap().is_some());
        assert!(
            crate::calendar::edit::create(&crate::calendar::edit::EventDraft {
                component: "VEVENT",
                ..draft
            })
            .is_err(),
            "an event still needs its start"
        );
    }

    #[test]
    fn a_mid_uri_round_trips() {
        let uri = mid_uri("<a b/c%d@example.org>");

        assert_eq!(uri, "mid:a%20b%2Fc%25d@example.org");
        assert_eq!(message_id_of(&uri).as_deref(), Some("a b/c%d@example.org"));
        assert_eq!(message_id_of("https://example.org"), None);
    }
}
