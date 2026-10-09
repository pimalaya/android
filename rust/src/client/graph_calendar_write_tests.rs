//! The Graph entry write over a fake Graph: the series master patched
//! with what changed, then each occurrence the object holds otherwise
//! written through its instance, an instance that refuses its write
//! leaving the edit staged.
//!
//! The fake serves the requests of [`GraphWrites`] over one weekly series
//! of six Mondays at 09:00 UTC, from 5 October 2026, and records every
//! write.

use serde_json::{Value, from_value, json, to_value};

use super::*;
use crate::calendar::{remove, write};

/// A Graph series, and what was written to it.
struct FakeGraph {
    master: MsgraphEvent,
    exceptions: Vec<MsgraphEvent>,
    /// Every write, its method, the event it names and its body.
    sent: Vec<(&'static str, String, Value)>,
    /// The instance whose write Graph fails, as it may not next time.
    failing: Option<String>,
    /// The instance whose write Graph refuses for good.
    refusing: Option<String>,
    /// Instance listings asked for.
    listings: usize,
    /// Series read whole.
    reads: usize,
}

const DAYS: [&str; 6] = [
    "2026-10-05",
    "2026-10-12",
    "2026-10-19",
    "2026-10-26",
    "2026-11-02",
    "2026-11-09",
];

fn graph() -> FakeGraph {
    FakeGraph {
        master: from_value(json!({
            "id": "m",
            "iCalUId": "uid-m",
            "changeKey": "ck-0",
            "type": "seriesMaster",
            "subject": "Series",
            "start": {"dateTime": "2026-10-05T09:00:00.0000000", "timeZone": "UTC"},
            "end": {"dateTime": "2026-10-05T10:00:00.0000000", "timeZone": "UTC"},
            "recurrence": {
                "pattern": {"type": "weekly", "interval": 1, "daysOfWeek": ["monday"]},
                "range": {"type": "numbered", "startDate": "2026-10-05", "numberOfOccurrences": 6},
            },
        }))
        .unwrap(),
        exceptions: Vec::new(),
        sent: Vec::new(),
        failing: None,
        refusing: None,
        listings: 0,
        reads: 0,
    }
}

/// The instance of the occurrence on `day`, starting at `hour` under
/// `subject`: an exception unless it is the series' own.
fn instance(day: &str, hour: &str, subject: &str) -> MsgraphEvent {
    let kind = match (hour, subject) {
        ("09", "Series") => "occurrence",
        _ => "exception",
    };
    from_value(json!({
        "id": format!("m-{day}"),
        "changeKey": format!("ck-m-{day}"),
        "type": kind,
        "seriesMasterId": "m",
        "originalStart": format!("{day}T09:00:00Z"),
        "subject": subject,
        "start": {"dateTime": format!("{day}T{hour}:00:00.0000000"), "timeZone": "UTC"},
        "end": {"dateTime": format!("{day}T{hour}:30:00.0000000"), "timeZone": "UTC"},
    }))
    .unwrap()
}

impl FakeGraph {
    /// The entry as a read hands it over.
    fn ical(&self) -> String {
        let exceptions: Vec<&MsgraphEvent> = self.exceptions.iter().collect();
        self.master.to_ical_series(&exceptions)
    }

    fn written(&mut self, method: &'static str, id: &str, body: Value) -> Result<(), BridgeError> {
        self.sent.push((method, id.to_string(), body));
        if self.failing.as_deref() == Some(id) {
            return Err(BridgeError {
                message: "Internal server error".into(),
                status: Some(500),
            });
        }
        if self.refusing.as_deref() == Some(id) {
            return Err(BridgeError {
                message: "ErrorOccurrenceCrossingBoundary".into(),
                status: Some(400),
            });
        }
        self.master.change_key = Some(format!("ck-{}", self.sent.len()));
        Ok(())
    }

    fn writes(&self) -> Vec<(&'static str, &str)> {
        self.sent
            .iter()
            .map(|(method, id, _)| (*method, id.as_str()))
            .collect()
    }
}

impl GraphWrites for FakeGraph {
    fn series(&mut self, id: &str) -> Result<(MsgraphEvent, Vec<MsgraphEvent>), BridgeError> {
        assert_eq!(id, "m");
        self.reads += 1;
        Ok((self.master.clone(), self.exceptions.clone()))
    }

    fn master(&mut self, id: &str) -> Result<MsgraphEvent, BridgeError> {
        assert_eq!(id, "m");
        Ok(self.master.clone())
    }

    fn instances(
        &mut self,
        id: &str,
        start: &str,
        end: &str,
    ) -> Result<Vec<MsgraphEvent>, BridgeError> {
        assert_eq!(id, "m");
        self.listings += 1;
        Ok(DAYS
            .iter()
            .map(|day| {
                self.exceptions
                    .iter()
                    .find(|exception| exception.id == format!("m-{day}"))
                    .cloned()
                    .unwrap_or_else(|| instance(day, "09", "Series"))
            })
            .filter(|instance| {
                let original = instance.original_start.as_deref().unwrap();
                original >= start && original < end
            })
            .collect())
    }

    fn update(&mut self, id: &str, patch: &MsgraphEvent) -> Result<MsgraphEvent, BridgeError> {
        self.written("PATCH", id, to_value(patch).unwrap())?;
        Ok(self.master.clone())
    }

    fn delete(&mut self, id: &str) -> Result<(), BridgeError> {
        self.written("DELETE", id, Value::Null)
    }
}

fn this(day: &str, edit: Value) -> String {
    let mut edit = edit;
    edit["scope"] = json!("this");
    edit["recurrenceId"] = json!(format!("{}T090000", day.replace('-', "")));
    edit.to_string()
}

fn moved(day: &str, hour: &str) -> String {
    let stamp = day.replace('-', "");
    this(
        day,
        json!({
            "start": {"time": format!("{stamp}T{hour}0000")},
            "end": {"time": format!("{stamp}T{hour}3000")},
        }),
    )
}

#[test]
fn an_occurrence_moved_alone_patches_its_instance_alone() {
    let mut graph = graph();
    let staged = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();

    let revision = update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap();

    assert_eq!(graph.writes(), [("PATCH", "m-2026-10-19")]);
    let body = &graph.sent[0].2;
    assert_eq!(body["start"]["dateTime"], "2026-10-19T10:00:00");
    assert_eq!(body["end"]["dateTime"], "2026-10-19T10:30:00");
    assert!(body.get("subject").is_none(), "{body}");
    assert!(body.get("recurrence").is_none(), "{body}");
    assert_eq!(revision.as_deref(), Some("ck-1"));
    assert_eq!(
        graph.reads, 1,
        "the master unwritten, the series read stands"
    );
}

#[test]
fn an_edit_of_the_series_and_one_occurrence_writes_both() {
    let mut graph = graph();
    let renamed = write(
        &graph.ical(),
        &json!({"scope": "all", "summary": "Renamed"}).to_string(),
    )
    .unwrap();
    let staged = write(&renamed, &moved("2026-10-26", "11")).unwrap();

    update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap();

    assert_eq!(graph.writes(), [("PATCH", "m"), ("PATCH", "m-2026-10-26")]);
    assert_eq!(graph.sent[0].2["subject"], "Renamed");
    assert!(graph.sent[0].2.get("start").is_none());
    assert_eq!(graph.reads, 2, "read again once the master is written");
}

#[test]
fn an_occurrence_deleted_alone_deletes_its_instance() {
    let mut graph = graph();
    let staged = remove(&graph.ical(), &this("2026-10-26", json!({})))
        .unwrap()
        .unwrap();

    update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap();

    assert_eq!(graph.writes(), [("DELETE", "m-2026-10-26")]);
}

#[test]
fn an_exception_edited_again_is_patched_from_what_graph_holds() {
    let mut graph = graph();
    graph.exceptions = vec![instance("2026-10-12", "10", "Moved")];
    let staged = write(
        &graph.ical(),
        &this("2026-10-12", json!({"summary": "Moved twice"})),
    )
    .unwrap();

    update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap();

    assert_eq!(graph.writes(), [("PATCH", "m-2026-10-12")]);
    assert_eq!(graph.sent[0].2["subject"], "Moved twice");
    assert!(graph.sent[0].2.get("start").is_none());
    assert_eq!(graph.listings, 0, "the exception came with the series");
}

#[test]
fn an_exception_reverted_is_patched_back_to_the_series() {
    let mut graph = graph();
    graph.exceptions = vec![instance("2026-10-12", "10", "Moved")];
    let staged = graph.master.to_ical();

    update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap();

    assert_eq!(graph.writes(), [("PATCH", "m-2026-10-12")]);
    let body = &graph.sent[0].2;
    assert_eq!(body["subject"], "Series");
    assert_eq!(body["start"]["dateTime"], "2026-10-12T09:00:00");
    assert_eq!(body["end"]["dateTime"], "2026-10-12T10:00:00");
}

#[test]
fn an_occurrence_graph_refuses_leaves_the_edit_staged() {
    let mut graph = graph();
    graph.failing = Some("m-2026-10-19".into());
    let first = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();
    let staged = write(&first, &moved("2026-11-02", "10")).unwrap();

    let refused = update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap_err();

    assert_eq!(refused.status, Some(412), "{}", refused.message);
    assert_eq!(
        graph.writes(),
        [("PATCH", "m-2026-10-19"), ("PATCH", "m-2026-11-02")],
        "the other occurrence still lands"
    );
}

#[test]
fn a_series_moved_on_graph_writes_nothing() {
    let mut graph = graph();
    let staged = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();
    graph.master.change_key = Some("ck-elsewhere".into());

    let refused = update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap_err();

    assert_eq!(refused.status, Some(412));
    assert!(graph.sent.is_empty());
}

#[test]
fn an_occurrence_graph_refuses_for_good_refuses_the_edit() {
    let mut graph = graph();
    graph.refusing = Some("m-2026-10-19".into());
    let first = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();
    let staged = write(&first, &moved("2026-11-02", "10")).unwrap();

    let refused = update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap_err();

    assert_eq!(refused.status, Some(422), "{}", refused.message);
    assert_eq!(
        graph.writes(),
        [("PATCH", "m-2026-10-19"), ("PATCH", "m-2026-11-02")],
        "the other occurrence still lands"
    );
}

#[test]
fn a_refusal_beside_a_failure_keeps_the_edit_waiting() {
    let mut graph = graph();
    graph.refusing = Some("m-2026-10-19".into());
    graph.failing = Some("m-2026-11-02".into());
    let first = write(&graph.ical(), &moved("2026-10-19", "10")).unwrap();
    let staged = write(&first, &moved("2026-11-02", "10")).unwrap();

    let refused = update_entry(&mut graph, "m", &staged, Some("ck-0")).unwrap_err();

    assert_eq!(refused.status, Some(412), "{}", refused.message);
}
