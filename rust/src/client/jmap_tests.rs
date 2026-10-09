//! The JMAP verbs over fake JMAP accounts: a calendar listed a page at a
//! time and never complete when the server stops short, an address book
//! paged and its delta chunked by `maxObjectsInGet`, a message sent
//! through Drafts to Sent with its refusals mapped, and the session
//! cache.
//!
//! Each fake answers the requests of one of [`JmapEventPages`],
//! [`JmapCardReads`] and [`JmapSubmit`] the way a server does where it
//! matters here, and records what it was asked.

use std::time::{Duration, Instant};

use serde_json::{Value, from_value, json, to_value};

use super::*;

/// One JSCalendar event the converter reads.
fn event(index: usize) -> Value {
    json!({
        "id": format!("e{index:04}"),
        "@type": "Event",
        "uid": format!("u{index}"),
        "title": format!("Event {index}"),
        "start": "2026-10-01T09:00:00",
        "duration": "PT1H",
    })
}

/// A JMAP calendar of `size` events, and what was asked of it.
struct FakeCalendar {
    events: Vec<Value>,
    /// Whether the query states its total.
    total: bool,
    /// The position from which the server answers empty pages.
    stops_at: Option<u64>,
    /// Whether the server answers the first page whatever the position.
    ignores_position: bool,
    /// Every page asked for: its position and limit.
    asked: Vec<(u64, u64)>,
}

fn calendar(size: usize) -> FakeCalendar {
    FakeCalendar {
        events: (0..size).map(event).collect(),
        total: true,
        stops_at: None,
        ignores_position: false,
        asked: Vec::new(),
    }
}

impl JmapEventPages for FakeCalendar {
    fn events(
        &mut self,
        calendar_id: &str,
        position: u64,
        limit: u64,
    ) -> Result<JmapCalendarEventQueryOutput, BridgeError> {
        assert_eq!(calendar_id, "c1");
        self.asked.push((position, limit));

        let start = if self.ignores_position { 0 } else { position };
        let end = match self.stops_at {
            Some(stop) if start >= stop => start,
            _ => (start + limit).min(self.events.len() as u64),
        };
        Ok(JmapCalendarEventQueryOutput {
            events: self.events[start as usize..end as usize]
                .iter()
                .map(|event| from_value(event.clone()).unwrap())
                .collect(),
            total: self.total.then_some(self.events.len() as u64),
            position: start,
            query_state: "q1".into(),
            keep_alive: true,
        })
    }
}

#[test]
fn a_calendar_larger_than_a_page_is_read_page_by_page() {
    let mut calendar = calendar(1200);

    let (events, complete) = list_events(&mut calendar, "c1", 500).unwrap();

    assert!(complete);
    assert_eq!(events.len(), 1200);
    assert_eq!(calendar.asked, [(0, 500), (500, 500), (1000, 500)]);
    assert!(events[0].ical.contains("UID:u0"), "{}", events[0].ical);
}

#[test]
fn a_listing_cut_short_of_its_total_is_never_complete() {
    let mut calendar = calendar(1200);
    calendar.stops_at = Some(500);

    let (events, complete) = list_events(&mut calendar, "c1", 500).unwrap();

    assert!(!complete, "the 700 left out would read as deleted");
    assert_eq!(events.len(), 500);
}

#[test]
fn a_server_stating_no_total_is_read_until_an_empty_page() {
    let mut calendar = calendar(700);
    calendar.total = false;

    let (events, complete) = list_events(&mut calendar, "c1", 500).unwrap();

    assert!(complete);
    assert_eq!(events.len(), 700);
    assert_eq!(calendar.asked, [(0, 500), (500, 500), (700, 500)]);
}

#[test]
fn a_server_ignoring_the_position_ends_the_listing_incomplete() {
    let mut calendar = calendar(700);
    calendar.total = false;
    calendar.ignores_position = true;

    let (events, complete) = list_events(&mut calendar, "c1", 500).unwrap();

    assert!(!complete);
    assert_eq!(events.len(), 500, "the repeated page is read once");
    assert_eq!(calendar.asked.len(), 2);
}

/// One JSContact card the converter reads, in the book `b1`.
fn card(index: usize) -> Value {
    json!({
        "id": format!("k{index:04}"),
        "addressBookIds": { "b1": true },
        "@type": "Card",
        "version": "1.0",
        "uid": format!("urn:uuid:{index}"),
        "name": { "full": format!("Jane {index}") },
    })
}

/// A JMAP account's cards, and what was asked of it in order.
struct FakeBook {
    cards: Vec<Value>,
    /// The `/changes` pages a delta answers, in order.
    changes: Vec<JmapChangesOutput>,
    /// Whether the server can no longer compute changes.
    expired: bool,
    asked: Vec<String>,
}

fn book(size: usize) -> FakeBook {
    FakeBook {
        cards: (0..size).map(card).collect(),
        changes: Vec::new(),
        expired: false,
        asked: Vec::new(),
    }
}

impl JmapCardReads for FakeBook {
    fn state(&mut self) -> Result<String, BridgeError> {
        self.asked.push("state".into());
        Ok("s1".into())
    }

    fn page(
        &mut self,
        position: u64,
        limit: u64,
    ) -> Result<JmapContactCardQueryOutput, BridgeError> {
        self.asked.push(format!("page {position} {limit}"));
        let end = (position + limit).min(self.cards.len() as u64);
        Ok(JmapContactCardQueryOutput {
            cards: self.cards[position as usize..end as usize]
                .iter()
                .map(|card| from_value(card.clone()).unwrap())
                .collect(),
            total: Some(self.cards.len() as u64),
            position,
            query_state: "q1".into(),
            keep_alive: true,
        })
    }

    fn cards(&mut self, ids: Vec<String>) -> Result<Vec<JmapContactCard>, BridgeError> {
        self.asked.push(format!("cards {}", ids.len()));
        Ok(self
            .cards
            .iter()
            .filter(|card| ids.contains(&card["id"].as_str().unwrap().to_string()))
            .map(|card| from_value(card.clone()).unwrap())
            .collect())
    }

    fn changes(&mut self, state: &str) -> Result<Option<JmapChangesOutput>, BridgeError> {
        self.asked.push(format!("changes {state}"));
        if self.expired {
            return Ok(None);
        }
        Ok(Some(self.changes.remove(0)))
    }
}

fn changes(since: usize, created: &[&str], destroyed: &[&str], more: bool) -> JmapChangesOutput {
    JmapChangesOutput {
        new_state: format!("s{}", since + 1),
        has_more_changes: more,
        created: created.iter().map(|id| id.to_string()).collect(),
        updated: Vec::new(),
        destroyed: destroyed.iter().map(|id| id.to_string()).collect(),
        keep_alive: true,
    }
}

#[test]
fn a_first_round_reads_the_state_then_every_card_a_page_at_a_time() {
    let mut book = book(2500);

    let delta = card_delta(&mut book, None, 1000).unwrap().unwrap();

    assert_eq!(
        book.asked,
        ["state", "page 0 1000", "page 1000 1000", "page 2000 1000"],
        "the state first, so what changes while it lists is the next delta's"
    );
    assert_eq!(delta.changed.len(), 2500);
    assert_eq!(delta.token.as_deref(), Some("s1"));
    assert!(delta.complete);
}

#[test]
fn a_delta_reads_its_changed_cards_a_chunk_at_a_time() {
    let mut book = book(5);
    book.changes = vec![
        changes(1, &["k0000", "k0001", "k0002"], &[], true),
        changes(2, &["k0003", "k0004"], &["k0002"], false),
    ];

    let delta = card_delta(&mut book, Some("s1"), 2).unwrap().unwrap();

    assert_eq!(
        book.asked,
        ["changes s1", "changes s2", "cards 2", "cards 2"],
        "four changed cards, the destroyed one left out, two a get"
    );
    assert_eq!(delta.changed.len(), 4);
    assert_eq!(delta.vanished, ["k0002"]);
    assert_eq!(delta.token.as_deref(), Some("s3"));
    assert!(!delta.complete);
}

#[test]
fn a_state_the_server_cannot_compute_from_asks_for_a_first_round() {
    let mut book = book(1);
    book.expired = true;

    assert!(card_delta(&mut book, Some("s1"), 500).unwrap().is_none());
}

/// A message to Ada, copying Bob, blind to Carol.
const MESSAGE: &str = "From: Jane <jane@example.com>\r\n\
    To: ada@example.com\r\n\
    Cc: bob@example.com\r\n\
    Bcc: carol@example.com\r\n\
    Subject: Hi\r\n\
    Message-ID: <m1@example.com>\r\n\
    \r\n\
    Hello\r\n";

/// A JMAP mail account, and what a submission asked of it.
struct FakeMail {
    identities: Vec<JmapIdentity>,
    mailboxes: Vec<(String, String, String)>,
    uploaded: Vec<Vec<u8>>,
    imported: Vec<(String, String)>,
    submitted: Vec<Value>,
    destroyed: Vec<String>,
    /// The error the import answers the message with.
    import_refusal: Option<Value>,
    /// The error the submission answers the message with.
    refusal: Option<Value>,
    /// Whether the submission's answer never comes.
    lost: bool,
}

fn identity(id: &str, email: &str) -> JmapIdentity {
    from_value(json!({ "id": id, "name": "", "email": email })).unwrap()
}

fn mailbox(id: &str, role: &str) -> (String, String, String) {
    (id.into(), id.into(), role.into())
}

fn account() -> FakeMail {
    FakeMail {
        identities: vec![
            identity("i0", "other@example.com"),
            identity("i1", "Jane@Example.com"),
        ],
        mailboxes: vec![
            mailbox("mb-inbox", "inbox"),
            mailbox("mb-drafts", "drafts"),
            mailbox("mb-sent", "sent"),
        ],
        uploaded: Vec::new(),
        imported: Vec::new(),
        submitted: Vec::new(),
        destroyed: Vec::new(),
        import_refusal: None,
        refusal: None,
        lost: false,
    }
}

impl JmapSubmit for FakeMail {
    fn identities(&mut self) -> Result<Vec<JmapIdentity>, BridgeError> {
        Ok(self.identities.clone())
    }

    fn mailboxes(&mut self) -> Result<Vec<(String, String, String)>, BridgeError> {
        Ok(self.mailboxes.clone())
    }

    fn upload(&mut self, message: Vec<u8>) -> Result<String, BridgeError> {
        self.uploaded.push(message);
        Ok("blob1".into())
    }

    fn import(
        &mut self,
        blob_id: &str,
        drafts: &str,
    ) -> Result<JmapEmailImportOutput, BridgeError> {
        self.imported.push((blob_id.into(), drafts.into()));
        let mut out = JmapEmailImportOutput {
            new_state: "e2".into(),
            created: BTreeMap::new(),
            not_created: BTreeMap::new(),
            keep_alive: true,
        };
        match self.import_refusal.clone() {
            Some(refusal) => {
                out.not_created
                    .insert("m0".into(), from_value(refusal).unwrap());
            }
            None => {
                let email = JmapEmail {
                    id: Some("email1".into()),
                    ..Default::default()
                };
                out.created.insert("m0".into(), email);
            }
        }
        Ok(out)
    }

    fn submit(
        &mut self,
        args: JmapEmailSubmissionSetArgs,
    ) -> Result<JmapEmailSubmissionSetOutput, BridgeError> {
        self.submitted.push(to_value(&args).unwrap());
        if self.lost {
            return Err("Connection reset".into());
        }
        let mut out = JmapEmailSubmissionSetOutput {
            new_state: "u2".into(),
            created: BTreeMap::new(),
            not_created: BTreeMap::new(),
            keep_alive: true,
        };
        match self.refusal.clone() {
            Some(refusal) => {
                out.not_created
                    .insert("s0".into(), from_value(refusal).unwrap());
            }
            None => {
                out.created.insert("s0".into(), Default::default());
            }
        }
        Ok(out)
    }

    fn destroy(&mut self, id: &str) -> Result<(), BridgeError> {
        self.destroyed.push(id.into());
        Ok(())
    }
}

#[test]
fn a_message_goes_through_drafts_to_sent_with_its_blind_copy_in_the_envelope() {
    let mut mail = account();

    send(&mut mail, MESSAGE.as_bytes(), None).unwrap();

    let uploaded = String::from_utf8(mail.uploaded[0].clone()).unwrap();
    assert!(!uploaded.contains("Bcc"), "{uploaded}");
    assert!(uploaded.contains("Message-ID: <m1@example.com>"));
    assert_eq!(mail.imported, [("blob1".into(), "mb-drafts".into())]);
    assert_eq!(
        mail.submitted,
        [json!({
            "create": { "s0": {
                "identityId": "i1",
                "emailId": "email1",
                "envelope": {
                    "mailFrom": { "email": "jane@example.com", "parameters": null },
                    "rcptTo": [
                        { "email": "ada@example.com", "parameters": null },
                        { "email": "bob@example.com", "parameters": null },
                        { "email": "carol@example.com", "parameters": null },
                    ],
                },
            } },
            "onSuccessUpdateEmail": { "#s0": {
                "mailboxIds/mb-drafts": null,
                "mailboxIds/mb-sent": true,
                "keywords/$draft": null,
            } },
        })]
    );
    assert!(mail.destroyed.is_empty());
}

#[test]
fn an_account_with_no_sent_mailbox_keeps_no_copy() {
    let mut mail = account();
    mail.mailboxes.retain(|(_, _, role)| role != "sent");

    send(&mut mail, MESSAGE.as_bytes(), None).unwrap();

    assert_eq!(mail.submitted[0]["onSuccessDestroyEmail"], json!(["#s0"]));
    assert!(mail.submitted[0].get("onSuccessUpdateEmail").is_none());
}

#[test]
fn a_wildcard_identity_sends_as_any_address_of_its_domain() {
    let mut mail = account();
    mail.identities = vec![identity("i9", "*@example.com")];

    send(&mut mail, MESSAGE.as_bytes(), None).unwrap();

    assert_eq!(mail.submitted[0]["create"]["s0"]["identityId"], "i9");
}

#[test]
fn a_sender_the_server_refuses_is_refused_for_good_and_its_draft_destroyed() {
    let mut mail = account();
    mail.refusal = Some(json!({ "type": "forbiddenFrom", "description": "Not yours" }));

    let refused = send(&mut mail, MESSAGE.as_bytes(), None).unwrap_err();

    assert_eq!(refused.status, Some(REFUSED));
    assert_eq!(
        refused.message,
        "The server refused to send the message: JMAP forbiddenFrom: Not yours"
    );
    assert_eq!(mail.destroyed, ["email1"]);
}

#[test]
fn a_rate_limit_waits_for_the_next_drain() {
    let mut mail = account();
    mail.refusal = Some(json!({ "type": "rateLimit" }));

    let failed = send(&mut mail, MESSAGE.as_bytes(), None).unwrap_err();

    assert_eq!(failed.status, None);
    assert_eq!(
        mail.destroyed,
        ["email1"],
        "the next drain imports it again"
    );
}

#[test]
fn a_submission_whose_answer_never_came_keeps_its_draft() {
    let mut mail = account();
    mail.lost = true;

    let failed = send(&mut mail, MESSAGE.as_bytes(), None).unwrap_err();

    assert_eq!(failed.status, None);
    assert!(
        mail.destroyed.is_empty(),
        "the server may have sent it and filed it in Sent already"
    );
}

#[test]
fn an_import_the_server_refuses_is_refused_for_good() {
    let mut mail = account();
    mail.import_refusal = Some(json!({ "type": "invalidEmail" }));

    let refused = send(&mut mail, MESSAGE.as_bytes(), None).unwrap_err();

    assert_eq!(refused.status, Some(REFUSED));
    assert!(mail.submitted.is_empty());
}

#[test]
fn what_no_pass_changes_is_refused_before_anything_is_written() {
    let mut nobody = account();
    nobody.identities = vec![identity("i0", "other@example.com")];
    let mut no_drafts = account();
    no_drafts.mailboxes.retain(|(_, _, role)| role != "drafts");
    let mut too_large = account();

    let refusals = [
        send(&mut nobody, MESSAGE.as_bytes(), None).unwrap_err(),
        send(&mut no_drafts, MESSAGE.as_bytes(), None).unwrap_err(),
        send(&mut too_large, MESSAGE.as_bytes(), Some(10)).unwrap_err(),
    ];

    for refused in &refusals {
        assert_eq!(refused.status, Some(REFUSED), "{}", refused.message);
    }
    for mail in [nobody, no_drafts, too_large] {
        assert!(mail.uploaded.is_empty());
    }
}

fn session(capabilities: Value, primary: Value) -> JmapSession {
    from_value(json!({
        "username": "jane@example.com",
        "accounts": {},
        "primaryAccounts": primary,
        "capabilities": capabilities,
        "apiUrl": "https://api.example.com/jmap/",
        "downloadUrl": "",
        "uploadUrl": "",
        "eventSourceUrl": "",
        "state": "s1",
    }))
    .unwrap()
}

#[test]
fn a_capability_is_served_only_with_an_account_to_use_it_in() {
    let session = session(
        json!({
            "urn:ietf:params:jmap:core": {},
            "urn:ietf:params:jmap:mail": {},
            "urn:ietf:params:jmap:submission": {},
            "urn:ietf:params:jmap:contacts": {},
            "urn:ietf:params:jmap:calendars": {},
        }),
        json!({
            "urn:ietf:params:jmap:mail": "a1",
            "urn:ietf:params:jmap:contacts": "a1",
        }),
    );

    assert_eq!(
        served_capabilities(&session),
        [
            JMAP_MAIL_CAPABILITY,
            JMAP_SUBMISSION_CAPABILITY,
            JMAP_CONTACTS_CAPABILITY
        ],
        "calendars are advertised with no primary account"
    );
}

#[test]
fn a_cached_session_serves_until_it_ages_or_its_api_fails() {
    let mut cache = SessionCache {
        entries: BTreeMap::new(),
    };
    let url = Url::parse("https://api.example.com/jmap/session").unwrap();
    let jane = session_key(&url, &SecretString::from("Bearer jane"));
    let other = session_key(&url, &SecretString::from("Bearer other"));
    let now = Instant::now();

    cache.put(jane.clone(), now, session(json!({}), json!({})));

    assert!(cache.get(&jane, now + Duration::from_secs(60)).is_some());
    assert!(
        cache.get(&other, now).is_none(),
        "another credential misses"
    );
    assert!(cache.get(&jane, now + SESSION_TTL).is_none());
    assert!(
        cache.get(&jane, now).is_none(),
        "an aged session is dropped"
    );

    cache.put(jane.clone(), now, session(json!({}), json!({})));
    cache.forget(&Url::parse("https://api.example.com/jmap/").unwrap());

    assert!(cache.get(&jane, now).is_none());

    // A refreshed token is a new key; the one it replaced goes once aged.
    cache.put(jane.clone(), now, session(json!({}), json!({})));
    cache.put(other, now + SESSION_TTL, session(json!({}), json!({})));

    assert_eq!(cache.entries.len(), 1);
}
