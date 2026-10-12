use std::collections::{BTreeMap, BTreeSet};

use io_gmail::v1::rest::messages::{GmailMessageHeader, GmailMessagePayload};

use super::*;

fn header(name: &str, value: &str) -> GmailMessageHeader {
    GmailMessageHeader {
        name: name.into(),
        value: value.into(),
    }
}

#[test]
fn a_relocation_into_the_trash_is_a_trash() {
    assert_eq!(relocation("INBOX", TRASH), GmailRelocation::Trash);
}

#[test]
fn a_relocation_out_of_the_trash_untrashes_first() {
    assert_eq!(
        relocation(TRASH, "Label_7"),
        GmailRelocation::Untrash {
            add: vec!["Label_7".into()]
        }
    );
}

#[test]
fn a_relocation_between_labels_swaps_them() {
    assert_eq!(
        relocation(INBOX, "Label_7"),
        GmailRelocation::Modify {
            add: vec!["Label_7".into()],
            remove: vec![INBOX.into()],
        }
    );
}

#[test]
fn a_metadata_read_becomes_a_decoded_envelope() {
    let message = GmailMessage {
        id: "m1".into(),
        label_ids: vec!["INBOX".into(), "UNREAD".into(), "STARRED".into()],
        // NOTE: the reception, a day after the `Date`: never the date.
        internal_date: Some("1700086400000".into()),
        payload: Some(GmailMessagePayload {
            headers: vec![
                header("Subject", "=?UTF-8?Q?Caf=C3=A9?="),
                header("From", "=?UTF-8?Q?Ren=C3=A9?= <rene@example.org>"),
                header("Date", "Tue, 14 Nov 2023 22:13:20 +0000"),
                header("Content-Type", "multipart/mixed; boundary=x"),
            ],
            ..Default::default()
        }),
        ..Default::default()
    };

    let envelope = envelope(message);

    assert_eq!(envelope.summary.subject, "Café");
    assert_eq!(envelope.summary.sender_name.as_deref(), Some("René"));
    assert_eq!(envelope.summary.sender.as_deref(), Some("rene@example.org"));
    assert_eq!(
        envelope.summary.date.as_deref(),
        Some("2023-11-14T22:13:20Z")
    );
    assert_eq!(envelope.summary.attachment, Some(true));
    assert!(!envelope.flags.iter().any(|flag| flag == "\\Seen"));
    assert!(envelope.flags.iter().any(|flag| flag == "\\Flagged"));
}

#[test]
fn trash_and_spam_take_a_message_out_of_its_other_labels() {
    let labels = |ids: &[&str]| ids.iter().map(|id| id.to_string()).collect::<Vec<_>>();

    let filed = labels(&["INBOX", "Label_7", "IMPORTANT"]);
    assert!(belongs(&filed, "INBOX"));
    assert!(belongs(&filed, "Label_7"));
    assert!(!belongs(&filed, "SENT"));

    let trashed = labels(&["TRASH", "Label_7"]);
    assert!(belongs(&trashed, "TRASH"));
    assert!(
        !belongs(&trashed, "Label_7"),
        "a trashed message is in the trash alone"
    );

    let junk = labels(&["SPAM", "INBOX"]);
    assert!(belongs(&junk, "SPAM"));
    assert!(!belongs(&junk, "INBOX"));
}

#[test]
fn a_history_names_each_move_once_and_reads_no_deleted_message() {
    let message = |id: &str| serde_json::json!({ "message": { "id": id } });
    let labelled = |id: &str| serde_json::json!({ "message": { "id": id }, "labelIds": ["INBOX"] });
    let records: Vec<GmailHistory> = serde_json::from_value(serde_json::json!([
        { "id": "1", "messagesAdded": [message("a"), message("b")] },
        { "id": "2", "labelsRemoved": [labelled("a")], "labelsAdded": [labelled("c")] },
        { "id": "3", "messagesDeleted": [message("b"), message("d")] },
    ]))
    .unwrap();

    let (changed, deleted) = history_changes(&records);
    assert_eq!(changed, ["a", "c"], "b was deleted after its arrival");
    assert_eq!(deleted, ["b", "d"]);
}

/// A metadata answer as Gmail writes it, for one message.
fn metadata_json(id: &str) -> String {
    serde_json::json!({
        "id": id,
        "threadId": "t1",
        "labelIds": ["INBOX", "Label_7", "UNREAD"],
        "sizeEstimate": 2048,
        "internalDate": "1700086400000",
        "payload": {
            "mimeType": "multipart/alternative",
            "headers": [
                {"name": "Date", "value": "Tue, 14 Nov 2023 22:13:20 +0100"},
                {"name": "From", "value": "Ren\u{e9} <rene@example.org>"},
                {"name": "To", "value": "jane@example.com"},
                {"name": "Subject", "value": "=?UTF-8?Q?R=C3=A9union?="},
                {"name": "Message-ID", "value": "<m1@example.org>"},
                {"name": "Content-Type", "value": "multipart/alternative; boundary=b"}
            ]
        }
    })
    .to_string()
}

/// The source the fake account serves for one message.
fn source_of(id: &str) -> String {
    format!(
        "Date: Tue, 14 Nov 2023 22:13:20 +0100\r\nFrom: rene@example.org\r\nSubject: Mail {id}\r\nMessage-ID: <{id}@example.org>\r\n\r\nBody of {id}\r\n"
    )
}

/// A raw answer as Gmail writes it, for one message: its bytes in
/// base64url with its labels, size and reception beside them.
fn raw_json(id: &str) -> String {
    serde_json::json!({
        "id": id,
        "threadId": "t1",
        "labelIds": ["INBOX", "Label_7", "STARRED"],
        "sizeEstimate": source_of(id).len(),
        "historyId": "991",
        "internalDate": "1700086400000",
        "raw": encode_raw(source_of(id).as_bytes()),
    })
    .to_string()
}

/// A minimal answer, the pre-pass: labels, size and reception alone.
fn minimal_json(id: &str, size: u64) -> String {
    serde_json::json!({
        "id": id,
        "threadId": "t1",
        "labelIds": ["INBOX"],
        "sizeEstimate": size,
        "historyId": "991",
        "internalDate": "1700086400000",
    })
    .to_string()
}

/// Runs a Gmail coroutine against one canned answer.
fn drive<C, T>(mut coroutine: C, answer: &[u8]) -> Result<GmailSendOutput<T>, GmailSendError>
where
    C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
{
    let mut answered = false;
    let mut arg: Option<&[u8]> = None;
    loop {
        match coroutine.resume(arg.take()) {
            GmailCoroutineState::Complete(result) => return result,
            GmailCoroutineState::Yielded(GmailYield::WantsWrite(_)) => {}
            GmailCoroutineState::Yielded(GmailYield::WantsRead) => {
                arg = Some(match answered {
                    false => answer,
                    true => &[],
                });
                answered = true;
            }
        }
    }
}

fn http(status: &str, content_type: &str, body: &str) -> Vec<u8> {
    format!(
        "HTTP/1.1 {status}\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    )
    .into_bytes()
}

/// A batch answer: one part per `(id, status, body)`, in the order
/// given, which Google does not promise to keep.
fn batch_answer(parts: &[(&str, &str, String)]) -> Vec<u8> {
    let mut body = String::new();
    for (id, status, json) in parts {
        body.push_str("--batch_x\r\nContent-Type: application/http\r\n");
        body.push_str(&format!("Content-ID: <response-{id}>\r\n\r\n"));
        body.push_str(&format!(
            "HTTP/1.1 {status}\r\nContent-Type: application/json; charset=UTF-8\r\n\r\n{json}\r\n"
        ));
    }
    body.push_str("--batch_x--\r\n");
    http("200 OK", "multipart/mixed; boundary=batch_x", &body)
}

fn batch_of(ids: &[&str], read: Read) -> GmailBatch<GmailMessage> {
    let requests: Vec<GmailBatchRequest> = ids
        .iter()
        .map(|id| GmailBatchRequest::message_get(id, "me", id, read.format(), read.headers()))
        .collect::<Result<_, _>>()
        .unwrap();
    GmailBatch::new(&HttpAuthBearer::new("token"), &requests).unwrap()
}

#[test]
fn a_batched_read_equals_a_single_read() {
    let json = metadata_json("18b0a1");

    let auth = HttpAuthBearer::new("token");
    let get = GmailMessageGet::new(
        &auth,
        "me",
        "18b0a1",
        GmailMessageFormat::Metadata,
        &ENVELOPE_HEADERS,
    )
    .unwrap();
    let single = drive(get, &http("200 OK", "application/json", &json)).unwrap();
    let single = envelope(single.response);

    let answer = batch_answer(&[("18b0a1", "200 OK", json.clone())]);
    let batched = drive(batch_of(&["18b0a1"], Read::Metadata), &answer).unwrap();
    let message = batched.response.into_iter().next().unwrap().result.unwrap();

    assert_eq!(envelope(message), single);
    assert_eq!(single.summary.subject, "Réunion");
    assert_eq!(single.summary.date.as_deref(), Some("2023-11-14T21:13:20Z"));
    assert!(single.belongs("Label_7"));
}

/// The minute quota refusal Gmail answered on 2026-10-07.
const MINUTE_QUOTA: &str = r#"{"error":{"code":403,"message":"Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user'","errors":[{"message":"Quota exceeded","domain":"usageLimits","reason":"rateLimitExceeded"}],"status":"PERMISSION_DENIED"}}"#;

/// Gmail's per-user concurrency limit, as a batch's inner call meets it.
const CONCURRENCY: &str = r#"{"error":{"code":429,"message":"Too many concurrent requests for user","errors":[{"message":"Too many concurrent requests for user","domain":"global","reason":"rateLimitExceeded"}],"status":"RESOURCE_EXHAUSTED"}}"#;

/// Gmail's daily quota refusal.
const DAILY_QUOTA: &str = r#"{"error":{"code":403,"message":"Quota exceeded for quota metric 'Queries' and limit 'Queries per day'","errors":[{"message":"Daily Limit Exceeded","domain":"usageLimits","reason":"dailyLimitExceeded"}],"status":"PERMISSION_DENIED"}}"#;

#[test]
fn an_inner_answer_is_read_gone_busy_minute_daily_or_failed() {
    let answer = batch_answer(&[
        ("a1", "200 OK", metadata_json("a1")),
        ("b2", "404 Not Found", r#"{"error":{"code":404}}"#.into()),
        ("c3", "429 Too Many Requests", CONCURRENCY.into()),
        ("d4", "403 Forbidden", MINUTE_QUOTA.into()),
        ("f6", "403 Forbidden", DAILY_QUOTA.into()),
        ("g7", "401 Unauthorized", "{}".into()),
        ("h8", "503 Service Unavailable", "{}".into()),
    ]);
    // NOTE: e5 has no part in the answer at all.
    let ids = ["a1", "b2", "c3", "d4", "e5", "f6", "g7", "h8"];
    let batched = drive(batch_of(&ids, Read::Metadata), &answer).unwrap();

    let taken: BTreeMap<String, String> = batched
        .response
        .into_iter()
        .map(|answer| {
            let kind = match inner(answer.result) {
                Inner::Read(_) => "read".to_string(),
                Inner::Gone => "gone".into(),
                Inner::Busy => "busy".into(),
                Inner::Minute => "minute".into(),
                Inner::Daily => "daily".into(),
                Inner::Failed(err) => format!("failed {:?}", err.status),
            };
            (answer.id, kind)
        })
        .collect();
    let expected: BTreeMap<String, String> = [
        ("a1", "read"),
        ("b2", "gone"),
        ("c3", "busy"),
        ("d4", "minute"),
        ("e5", "busy"),
        ("f6", "daily"),
        ("g7", "failed Some(401)"),
        ("h8", "busy"),
    ]
    .into_iter()
    .map(|(id, kind)| (id.to_string(), kind.to_string()))
    .collect();
    assert_eq!(taken, expected);
}

#[test]
fn a_minute_refusal_waits_two_to_five_seconds_more_each_time() {
    let pauses: Vec<u64> = (1..=6).map(|count| minute_pause(count).as_secs()).collect();
    assert_eq!(pauses, [2, 3, 4, 5, 5, 5]);
}

/// How the fake account answers one message's next read.
#[derive(Clone, Copy)]
enum Answer {
    Concurrency,
    Minute,
    Daily,
}

/// A Gmail answering batches from a script: refusals queued per message,
/// sizes for the pre-pass, the rest served.
#[derive(Default)]
struct FakeBatcher {
    /// The refusals each message meets before it is served, in order.
    refusals: BTreeMap<String, Vec<Answer>>,
    /// Messages gone.
    gone: BTreeSet<String>,
    /// Sizes the pre-pass reports, 1 KB unless named.
    sizes: BTreeMap<String, u64>,
    /// Every batch sent: what it read and its ids.
    batches: Vec<(Read, Vec<String>)>,
    /// Every read sent alone.
    singles: Vec<String>,
    paused: Vec<Duration>,
}

impl FakeBatcher {
    fn answer(&self, id: &str, read: Read) -> String {
        match read {
            Read::Metadata => metadata_json(id),
            Read::Minimal => minimal_json(id, self.sizes.get(id).copied().unwrap_or(1024)),
            Read::Raw => raw_json(id),
        }
    }
}

impl GmailBatcher for FakeBatcher {
    fn batch(
        &mut self,
        ids: &[String],
        read: Read,
    ) -> Result<Vec<GmailBatchResponse<GmailMessage>>, BridgeError> {
        assert!(ids.len() <= BATCH, "{} reads in one batch", ids.len());
        self.batches.push((read, ids.to_vec()));
        let mut parts: Vec<(&str, &str, String)> = Vec::new();
        for id in ids {
            let refusal = self
                .refusals
                .get_mut(id)
                .filter(|queued| !queued.is_empty())
                .map(|queued| queued.remove(0));
            parts.push(match refusal {
                Some(Answer::Concurrency) => (id, "429 Too Many Requests", CONCURRENCY.into()),
                Some(Answer::Minute) => (id, "403 Forbidden", MINUTE_QUOTA.into()),
                Some(Answer::Daily) => (id, "403 Forbidden", DAILY_QUOTA.into()),
                None if self.gone.contains(id) => (id, "404 Not Found", "{}".into()),
                None => (id, "200 OK", self.answer(id, read)),
            });
        }
        let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
        Ok(drive(batch_of(&ids, read), &batch_answer(&parts))
            .unwrap()
            .response)
    }

    fn single(&mut self, id: &str, read: Read) -> Result<Option<GmailMessage>, BridgeError> {
        self.singles.push(id.into());
        Ok(Some(serde_json::from_str(&self.answer(id, read)).unwrap()))
    }

    fn pause(&mut self, delay: Duration) {
        self.paused.push(delay);
    }
}

fn ids(count: usize) -> Vec<String> {
    (0..count).map(|n| format!("m{n:03}")).collect()
}

#[test]
fn reads_ride_batches_of_ten_the_refused_put_back_into_the_next() {
    let ids = ids(45);
    let mut batcher = FakeBatcher {
        refusals: [("m007".to_string(), vec![Answer::Concurrency])].into(),
        gone: ["m030".to_string()].into(),
        ..Default::default()
    };

    let read = read_envelopes(&mut batcher, &ids).unwrap();

    let sizes: Vec<usize> = batcher.batches.iter().map(|(_, ids)| ids.len()).collect();
    assert_eq!(sizes, [10, 10, 10, 10, 6], "the refused one rides again");
    assert_eq!(
        batcher.batches[1].1[0], "m007",
        "at the head of the next batch"
    );
    assert!(batcher.singles.is_empty(), "never read again alone");
    assert_eq!(batcher.paused.len(), 1);
    let paused = batcher.paused[0];
    assert!(
        (Duration::from_secs(1)..=Duration::from_secs(2)).contains(&paused),
        "{paused:?}"
    );
    assert_eq!(read.len(), 45, "every id answered once");
    let gone: Vec<&str> = read
        .iter()
        .filter(|(_, envelope)| envelope.is_none())
        .map(|(id, _)| id.as_str())
        .collect();
    assert_eq!(gone, ["m030"]);
}

#[test]
fn minute_refusals_back_off_more_each_time_and_a_daily_one_stops() {
    let mut batcher = FakeBatcher {
        refusals: [("m001".to_string(), vec![Answer::Minute, Answer::Minute])].into(),
        ..Default::default()
    };
    read_envelopes(&mut batcher, &ids(3)).unwrap();
    assert_eq!(
        batcher.paused,
        [Duration::from_secs(2), Duration::from_secs(3)],
        "never the next wall-clock minute"
    );

    let mut batcher = FakeBatcher {
        refusals: [("m001".to_string(), vec![Answer::Daily])].into(),
        ..Default::default()
    };
    let err = read_envelopes(&mut batcher, &ids(3)).unwrap_err();
    assert_eq!(err.status, Some(403));
    assert_eq!(batcher.batches.len(), 1, "nothing sent after it");
}

#[test]
fn a_message_refused_without_end_fails_the_read() {
    let mut batcher = FakeBatcher {
        refusals: [("m000".to_string(), vec![Answer::Concurrency; 10])].into(),
        ..Default::default()
    };
    assert!(read_envelopes(&mut batcher, &ids(1)).is_err());
    assert_eq!(batcher.batches.len(), MAX_TRIES as usize);
}

#[test]
fn raw_batches_hold_about_eight_megabytes_and_a_large_message_goes_alone() {
    const MB: u64 = 1024 * 1024;
    let ids = ids(6);
    let mut batcher = FakeBatcher {
        sizes: [
            ("m000", MB),
            ("m001", 3 * MB),
            ("m002", 6 * MB),
            ("m003", 4 * MB),
            ("m004", 2 * MB),
            ("m005", MB),
        ]
        .into_iter()
        .map(|(id, size)| (id.to_string(), size))
        .collect(),
        ..Default::default()
    };
    let dir = tempfile::tempdir().unwrap();

    let fetched = read_sources(&mut batcher, &ids, dir.path())
        .unwrap()
        .fetched;

    let raw: Vec<Vec<String>> = batcher
        .batches
        .iter()
        .filter(|(read, _)| *read == Read::Raw)
        .map(|(_, ids)| ids.clone())
        .collect();
    let strings = |ids: &[&str]| ids.iter().map(|id| id.to_string()).collect::<Vec<_>>();
    assert_eq!(
        raw,
        [
            strings(&["m000", "m001"]),
            strings(&["m003", "m004", "m005"])
        ],
        "4 MB, then the 6 MB one alone, then 7 MB"
    );
    assert_eq!(batcher.singles, ["m002"]);
    assert_eq!(fetched.len(), 6);
}

#[test]
fn a_raw_read_writes_the_bytes_with_their_labels_and_reception() {
    let mut batcher = FakeBatcher::default();
    let dir = tempfile::tempdir().unwrap();

    let fetched = read_sources(&mut batcher, &["18b0/a1".to_string()], dir.path())
        .unwrap()
        .fetched;

    let reads: Vec<Read> = batcher.batches.iter().map(|(read, _)| *read).collect();
    assert_eq!(
        reads,
        [Read::Minimal, Read::Raw],
        "the pre-pass, then the bytes"
    );
    let message = &fetched[0];
    assert_eq!(message.path, dir.path().join("18b0_a1.eml"));
    let written = fs::read(&message.path).unwrap();
    assert_eq!(written, source_of("18b0/a1").as_bytes());
    assert_eq!(message.size, written.len());
    assert_eq!(message.labels, ["INBOX", "Label_7", "STARRED"]);
    assert_eq!(message.received, Some(1_700_086_400_000));
    assert!(message.flags.iter().any(|flag| flag == "\\Seen"));
    assert!(message.flags.iter().any(|flag| flag == "\\Flagged"));
    assert_eq!(message.summary.subject, "Mail 18b0/a1");
    assert_eq!(
        message.summary.date.as_deref(),
        Some("2023-11-14T21:13:20Z"),
        "the Date header, never the reception"
    );
}

#[test]
fn a_raw_read_names_a_message_gone_apart_from_the_read() {
    let mut batcher = FakeBatcher {
        gone: ["m001".to_string()].into(),
        ..Default::default()
    };
    let dir = tempfile::tempdir().unwrap();

    let read = read_sources(&mut batcher, &ids(3), dir.path()).unwrap();

    let fetched: Vec<&str> = read.fetched.iter().map(|item| item.id.as_str()).collect();
    assert_eq!(fetched, ["m000", "m002"]);
    assert_eq!(read.gone, ["m001"], "gone, not failed");
}

/// One message write answered `answer`, as the bridge reports it
/// ([`Client::run_gmail`]) and a removal, a move or a marker takes it.
fn write_answered<C, T>(coroutine: C, answer: &[u8]) -> Result<Option<T>, BridgeError>
where
    C: GmailCoroutine<Yield = GmailYield, Return = Result<GmailSendOutput<T>, GmailSendError>>,
{
    let sent = drive(coroutine, answer)
        .map(|output| output.response)
        .map_err(|err| coroutine_error(&err));
    converged(sent)
}

#[test]
fn a_write_on_a_message_gone_converges() {
    let auth = HttpAuthBearer::new("token");
    let gone = http(
        "404 Not Found",
        "application/json",
        r#"{"error":{"code":404,"message":"Requested entity was not found.","status":"NOT_FOUND"}}"#,
    );
    let label = ["STARRED".to_string()];

    // A message deleted for good, a trash or spam purge, a draft saved
    // under a new id since the listing: nothing is left to write.
    let delete = GmailMessageDelete::new(&auth, "me", "m1").unwrap();
    assert!(write_answered(delete, &gone).unwrap().is_none());
    let trash = GmailMessageTrash::new(&auth, "me", "m1").unwrap();
    assert!(write_answered(trash, &gone).unwrap().is_none());
    let untrash = GmailMessageUntrash::new(&auth, "me", "m1").unwrap();
    assert!(write_answered(untrash, &gone).unwrap().is_none());
    let modify = GmailMessageModify::new(&auth, "me", "m1", &label, &[]).unwrap();
    assert!(write_answered(modify, &gone).unwrap().is_none());

    // The 404 crosses as one, for a copy of nothing to be refused.
    let copy = GmailMessageModify::new(&auth, "me", "m1", &label, &[]).unwrap();
    let refused = drive(copy, &gone).unwrap_err();
    assert_eq!(coroutine_error(&refused).status, Some(404));
}

#[test]
fn a_write_failing_otherwise_still_fails() {
    let auth = HttpAuthBearer::new("token");
    let label = ["STARRED".to_string()];

    let modify = GmailMessageModify::new(&auth, "me", "m1", &label, &[]).unwrap();
    let busy = http("503 Service Unavailable", "application/json", "{}");
    let failed = write_answered(modify, &busy).unwrap_err();
    assert_eq!(failed.status, Some(503));

    let modify = GmailMessageModify::new(&auth, "me", "m1", &label, &[]).unwrap();
    let done = http(
        "200 OK",
        "application/json",
        r#"{"id":"m1","labelIds":["INBOX","STARRED"]}"#,
    );
    let written = write_answered(modify, &done).unwrap().unwrap();
    assert_eq!(written.label_ids, vec!["INBOX", "STARRED"]);
}
