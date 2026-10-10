//! The JMAP verbs over fake JMAP accounts: a calendar listed a page at a
//! time and never complete when the server stops short, an address book
//! paged and its delta chunked by `maxObjectsInGet`, a message sent
//! through Drafts to Sent with its refusals mapped, the session cache,
//! and calendar entries written through `CalendarEvent/set`, one
//! occurrence inside its event.
//!
//! Each fake answers the requests of one of [`JmapEventPages`],
//! [`JmapCardReads`], [`JmapSubmit`] and [`JmapEventWrites`] the way a server does where it
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

/// A weekly series on a JMAP calendar as a JSCalendar 2.0 server holds
/// it, with an attendee, and a member the conversion carries only through
/// its escape hatch.
fn standup() -> Value {
    json!({
        "id": "ev1",
        "calendarIds": { "c1": true },
        "@type": "Event",
        "uid": "standup",
        "title": "Standup",
        "start": "2026-10-01T09:00:00",
        "timeZone": "Europe/Paris",
        "duration": "PT30M",
        "recurrenceRule": { "@type": "RecurrenceRule", "frequency": "weekly" },
        "organizerCalendarAddress": "mailto:jane@example.com",
        "participants": {
            "p1": {
                "@type": "Participant",
                "name": "Ada",
                "calendarAddress": "mailto:ada@example.com",
                "roles": { "attendee": true },
            },
        },
        "useDefaultAlerts": true,
    })
}

/// A JMAP account's events, applying the writes it takes, and what was
/// asked of it.
struct FakeEvents {
    events: BTreeMap<String, Value>,
    state: u32,
    /// Whether another client writes between each read and the next write.
    races: bool,
    /// The refusal each write of these ids or creation keys answers.
    refusals: BTreeMap<String, Value>,
    /// Every `CalendarEvent/set` asked for, as sent.
    sets: Vec<Value>,
}

fn events(events: &[Value]) -> FakeEvents {
    FakeEvents {
        events: events
            .iter()
            .map(|event| (event["id"].as_str().unwrap().to_string(), event.clone()))
            .collect(),
        state: 1,
        races: false,
        refusals: BTreeMap::new(),
        sets: Vec::new(),
    }
}

impl FakeEvents {
    /// The iCalendar the listing gives the event `id`, which is what an
    /// edit starts from.
    fn listed(&self, id: &str) -> Event {
        jmap_event(from_value(self.events[id].clone()).unwrap()).unwrap()
    }

    /// The one patch the last write sent.
    fn patch(&self) -> &serde_json::Map<String, Value> {
        let update = self.sets.last().unwrap()["update"].as_object().unwrap();
        assert_eq!(update.len(), 1, "{update:?}");
        update.values().next().unwrap().as_object().unwrap()
    }
}

impl JmapEventWrites for FakeEvents {
    fn event(&mut self, id: &str) -> Result<(Option<JmapCalendarEvent>, String), BridgeError> {
        let read = self
            .events
            .get(id)
            .map(|event| from_value(event.clone()).unwrap());
        let state = format!("s{}", self.state);
        if self.races {
            self.state += 1;
        }
        Ok((read, state))
    }

    fn set(
        &mut self,
        args: JmapCalendarEventSetArgs,
    ) -> Result<JmapCalendarEventSetOutput, BridgeError> {
        let sent = to_value(&args).unwrap();
        self.sets.push(sent.clone());
        if args
            .if_in_state
            .is_some_and(|state| state != format!("s{}", self.state))
        {
            let mismatch = JmapCalendarEventSetError::Set(JmapSetError::Method(
                JmapMethodError::StateMismatch { description: None },
            ));
            return Err(set_failure(&mismatch));
        }

        let refusal = |key: &str| {
            self.refusals
                .get(key)
                .map(|err| from_value::<JmapCalendarEventSetItemError>(err.clone()).unwrap())
        };
        let mut out = JmapCalendarEventSetOutput {
            new_state: String::new(),
            created: BTreeMap::new(),
            updated: BTreeMap::new(),
            destroyed: Vec::new(),
            not_created: BTreeMap::new(),
            not_updated: BTreeMap::new(),
            not_destroyed: BTreeMap::new(),
            keep_alive: true,
        };

        for (key, event) in sent["create"].as_object().into_iter().flatten() {
            if let Some(err) = refusal(key) {
                out.not_created.insert(key.clone(), err);
                continue;
            }
            let id = format!("ev{}", self.events.len() + 1);
            let mut event = event.clone();
            event["id"] = Value::from(id.clone());
            self.events.insert(id.clone(), event);
            out.created
                .insert(key.clone(), from_value(json!({ "id": id })).unwrap());
        }
        for (id, patch) in sent["update"].as_object().into_iter().flatten() {
            match (refusal(id), self.events.get_mut(id)) {
                (Some(err), _) => {
                    out.not_updated.insert(id.clone(), err);
                }
                (None, None) => {
                    out.not_updated.insert(
                        id.clone(),
                        JmapCalendarEventSetItemError::NotFound { description: None },
                    );
                }
                (None, Some(event)) => {
                    for (pointer, value) in patch.as_object().unwrap() {
                        let path: Vec<String> = pointer
                            .split('/')
                            .map(|part| part.replace("~1", "/").replace("~0", "~"))
                            .collect();
                        let (last, parents) = path.split_last().unwrap();
                        let mut target = &mut *event;
                        for parent in parents {
                            target = &mut target[parent.as_str()];
                        }
                        let target = target.as_object_mut().unwrap();
                        match value {
                            Value::Null => target.remove(last),
                            value => target.insert(last.clone(), value.clone()),
                        };
                    }
                    out.updated.insert(id.clone(), None);
                }
            }
        }
        for id in sent["destroy"].as_array().into_iter().flatten() {
            let id = id.as_str().unwrap().to_string();
            match (refusal(&id), self.events.remove(&id)) {
                (Some(err), _) => {
                    out.not_destroyed.insert(id, err);
                }
                (None, None) => {
                    out.not_destroyed.insert(
                        id,
                        JmapCalendarEventSetItemError::NotFound { description: None },
                    );
                }
                (None, Some(_)) => out.destroyed.push(id),
            }
        }

        self.state += 1;
        out.new_state = format!("s{}", self.state);
        Ok(out)
    }
}

/// A new entry as the entry page starts it.
const NEW_ENTRY: &str = "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nPRODID:-//Pimalaya//Android//EN\r\n\
    BEGIN:VEVENT\r\nUID:lunch\r\nDTSTAMP:20261009T080000Z\r\n\
    DTSTART;TZID=Europe/Paris:20261012T120000\r\nDURATION:PT1H\r\nSUMMARY:Lunch\r\n\
    END:VEVENT\r\nEND:VCALENDAR\r\n";

#[test]
fn a_new_entry_lands_in_its_calendar_under_the_id_the_server_gives() {
    let mut account = events(&[]);

    let created = create_event(&mut account, "c1", NEW_ENTRY, &[]).unwrap();

    assert_eq!(created.id, "ev1");
    let stored = &account.events["ev1"];
    assert_eq!(stored["calendarIds"], json!({ "c1": true }));
    assert_eq!(stored["uid"], "lunch");
    assert_eq!(stored["title"], "Lunch");
    assert_eq!(stored["start"], "2026-10-12T12:00:00");
    assert_eq!(stored["timeZone"], "Europe/Paris");
    assert!(stored.get("isOrigin").is_none(), "{stored}");
    assert_eq!(
        created.etag,
        account.listed("ev1").etag,
        "the revision is the one the next listing reads"
    );
}

#[test]
fn an_edit_patches_only_what_it_changed() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");

    let etag = update_event(&mut account, "ev1", &edited, listed.etag.as_deref(), &[]).unwrap();

    let patch = account.patch();
    assert_eq!(
        patch.keys().collect::<Vec<_>>(),
        ["title"],
        "the escape hatch's member is never sent: {patch:?}"
    );
    assert_eq!(account.sets[0]["ifInState"], "s1");
    let stored = &account.events["ev1"];
    assert_eq!(stored["title"], "Daily");
    assert_eq!(stored["useDefaultAlerts"], true);
    assert_eq!(etag, account.listed("ev1").etag);
    assert_ne!(etag, listed.etag);
}

#[test]
fn an_edit_changing_nothing_writes_nothing() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");

    let etag = update_event(
        &mut account,
        "ev1",
        &listed.ical,
        listed.etag.as_deref(),
        &[],
    )
    .unwrap();

    assert!(account.sets.is_empty());
    assert_eq!(etag, listed.etag);
}

#[test]
fn one_occurrence_moved_sends_that_occurrence_alone() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    let edit = json!({
        "scope": "this",
        "recurrenceId": "20261008T090000",
        "start": {"time": "20261008T100000"},
        "summary": "Standup, later",
    });
    let moved = crate::calendar::write(&listed.ical, &edit.to_string()).unwrap();

    update_event(&mut account, "ev1", &moved, listed.etag.as_deref(), &[]).unwrap();

    let patch = account.patch();
    assert_eq!(
        patch.keys().collect::<Vec<_>>(),
        ["recurrenceOverrides"],
        "no override existed, so the member is set whole: {patch:?}"
    );
    let over = &account.events["ev1"]["recurrenceOverrides"]["2026-10-08T09:00:00"];
    assert_eq!(over["start"], "2026-10-08T10:00:00", "{over}");
    assert_eq!(over["title"], "Standup, later", "{over}");
    assert_eq!(account.events["ev1"]["title"], "Standup");

    // Moved again, the override already there is replaced alone.
    let listed = account.listed("ev1");
    let edit = json!({
        "scope": "this",
        "recurrenceId": "20261008T090000",
        "start": {"time": "20261008T110000"},
    });
    let again = crate::calendar::write(&listed.ical, &edit.to_string()).unwrap();

    update_event(&mut account, "ev1", &again, listed.etag.as_deref(), &[]).unwrap();

    let patch = account.patch();
    assert_eq!(
        patch.keys().collect::<Vec<_>>(),
        ["recurrenceOverrides/2026-10-08T09:00:00"],
        "{patch:?}"
    );
    let over = &account.events["ev1"]["recurrenceOverrides"]["2026-10-08T09:00:00"];
    assert_eq!(over["start"], "2026-10-08T11:00:00", "{over}");
}

#[test]
fn one_occurrence_deleted_is_excluded_in_the_event() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    let edit = json!({"scope": "this", "recurrenceId": "20261015T090000"});
    let left = crate::calendar::remove(&listed.ical, &edit.to_string())
        .unwrap()
        .unwrap();

    update_event(&mut account, "ev1", &left, listed.etag.as_deref(), &[]).unwrap();

    assert_eq!(
        account.events["ev1"]["recurrenceOverrides"],
        json!({ "2026-10-15T09:00:00": { "excluded": true } })
    );
    assert_eq!(account.events["ev1"]["title"], "Standup");
}

#[test]
fn an_override_removed_is_nulled_alone() {
    let mut series = standup();
    series["recurrenceOverrides"] = json!({
        "2026-10-08T09:00:00": { "title": "Moved" },
        "2026-10-15T09:00:00": { "excluded": true },
    });
    let mut account = events(&[series]);
    let listed = account.listed("ev1");
    // NOTE: the occurrence edited back to the series' own values is what
    // a removed override is; here it is taken out of the object by hand.
    let start = listed
        .ical
        .match_indices("BEGIN:VEVENT\r\n")
        .map(|(at, _)| at)
        .find(|at| {
            let component = listed.ical[*at..].split("END:VEVENT").next().unwrap();
            component.contains("SUMMARY:Moved")
        })
        .expect("the override of the 8th");
    let end = start + listed.ical[start..].find("END:VEVENT\r\n").unwrap() + "END:VEVENT\r\n".len();
    let reverted = format!("{}{}", &listed.ical[..start], &listed.ical[end..]);

    update_event(&mut account, "ev1", &reverted, listed.etag.as_deref(), &[]).unwrap();

    assert_eq!(
        account.patch().clone(),
        json!({ "recurrenceOverrides/2026-10-08T09:00:00": null })
            .as_object()
            .unwrap()
            .clone()
    );
    assert_eq!(
        account.events["ev1"]["recurrenceOverrides"],
        json!({ "2026-10-15T09:00:00": { "excluded": true } })
    );
}

#[test]
fn an_edit_of_an_event_that_moved_is_never_written() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    account.events.get_mut("ev1").unwrap()["title"] = Value::from("Renamed elsewhere");
    let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");

    let err = update_event(&mut account, "ev1", &edited, listed.etag.as_deref(), &[]).unwrap_err();

    assert_eq!(err.status, Some(412));
    assert!(account.sets.is_empty());
    assert_eq!(account.events["ev1"]["title"], "Renamed elsewhere");
}

#[test]
fn a_write_racing_another_client_lands_nothing_and_waits() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    account.races = true;
    let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");

    let err = update_event(&mut account, "ev1", &edited, listed.etag.as_deref(), &[]).unwrap_err();

    assert_eq!(err.status, Some(412), "{}", err.message);
    assert_eq!(account.events["ev1"]["title"], "Standup");
}

#[test]
fn a_set_refused_for_its_state_waits() {
    // The answer a server gives an `ifInState` it no longer stands at,
    // through the real coroutine.
    let session = session(
        json!({ "urn:ietf:params:jmap:calendars": {} }),
        json!({ "urn:ietf:params:jmap:calendars": "a1" }),
    );
    let args = JmapCalendarEventSetArgs {
        if_in_state: Some("s1".into()),
        destroy: Some(vec!["ev1".into()]),
        ..Default::default()
    };
    let mut coroutine =
        JmapCalendarEventSet::new(&session, &SecretString::from("Bearer jane"), args).unwrap();
    let body =
        br#"{"methodResponses":[["error",{"type":"stateMismatch"},"c0"]],"sessionState":"s2"}"#;
    let mut reply = format!(
        "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\n\r\n",
        body.len()
    )
    .into_bytes();
    reply.extend_from_slice(body);

    let mut arg: Option<&[u8]> = None;
    let err = loop {
        match coroutine.resume(arg.take()) {
            JmapCoroutineState::Yielded(JmapYield::WantsWrite(_)) => {}
            JmapCoroutineState::Yielded(JmapYield::WantsRead) => arg = Some(&reply),
            JmapCoroutineState::Complete(Err(err)) => break err,
            JmapCoroutineState::Complete(Ok(_)) => panic!("a refused set completed"),
        }
    };

    assert_eq!(set_failure(&err).status, Some(412));
}

#[test]
fn a_property_the_server_refuses_is_refused_for_good() {
    let mut account = events(&[standup()]);
    account.refusals.insert(
        "ev1".into(),
        json!({ "type": "invalidProperties", "properties": ["recurrenceOverrides"] }),
    );
    let listed = account.listed("ev1");
    let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");

    let err = update_event(&mut account, "ev1", &edited, listed.etag.as_deref(), &[]).unwrap_err();

    assert_eq!(err.status, Some(REFUSED));
    assert!(
        err.message.contains("recurrenceOverrides"),
        "{}",
        err.message
    );
}

#[test]
fn a_rate_limited_create_waits() {
    let mut account = events(&[]);
    account
        .refusals
        .insert("e0".into(), json!({ "type": "rateLimit" }));

    let err = create_event(&mut account, "c1", NEW_ENTRY, &[])
        .err()
        .expect("a refused create");

    assert_eq!(err.status, Some(412));
    assert!(account.events.is_empty());
}

#[test]
fn a_delete_goes_under_the_state_its_check_read() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");

    destroy_event(&mut account, "ev1", listed.etag.as_deref(), &[]).unwrap();

    assert!(account.events.is_empty());
    assert_eq!(account.sets[0]["ifInState"], "s1");
    assert_eq!(account.sets[0]["destroy"], json!(["ev1"]));
}

#[test]
fn a_delete_of_an_event_already_gone_converges() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    account.events.clear();

    destroy_event(&mut account, "ev1", listed.etag.as_deref(), &[]).unwrap();
    destroy_event(&mut account, "ev1", None, &[]).unwrap();

    assert!(
        account.sets.is_empty(),
        "a delete of an event gone sends nothing"
    );
}

#[test]
fn a_delete_of_an_event_that_moved_is_never_sent() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    account.events.get_mut("ev1").unwrap()["title"] = Value::from("Renamed elsewhere");

    let err = destroy_event(&mut account, "ev1", listed.etag.as_deref(), &[]).unwrap_err();

    assert_eq!(err.status, Some(412));
    assert!(account.sets.is_empty());
    assert!(account.events.contains_key("ev1"));
}

#[test]
fn an_object_of_two_events_is_refused_for_good() {
    let two = NEW_ENTRY.replace(
        "END:VEVENT\r\n",
        "END:VEVENT\r\nBEGIN:VEVENT\r\nUID:other\r\nDTSTAMP:20261009T080000Z\r\n\
         DTSTART:20261013T120000\r\nEND:VEVENT\r\n",
    );
    let mut account = events(&[]);

    let err = create_event(&mut account, "c1", &two, &[])
        .err()
        .expect("a refused create");

    assert_eq!(err.status, Some(REFUSED));
    assert!(account.sets.is_empty());
}

#[test]
fn a_series_reads_as_a_series_with_its_people() {
    let account = events(&[standup()]);

    let listed = account.listed("ev1");

    assert!(
        listed.ical.contains("RRULE:FREQ=WEEKLY\r\n"),
        "{}",
        listed.ical
    );
    assert!(
        listed.ical.contains("mailto:ada@example.com"),
        "{}",
        listed.ical
    );
    assert!(listed.ical.contains("ORGANIZER"), "{}", listed.ical);
    assert!(
        !listed.ical.contains("JSPTR=recurrenceRule"),
        "{}",
        listed.ical
    );
}

#[test]
fn a_new_series_is_written_as_jscalendar_2_0() {
    let mut account = events(&[]);
    let series = NEW_ENTRY
        .replace("VERSION:2.0\r\n", "VERSION:2.0\r\nMETHOD:REQUEST\r\n")
        .replace(
            "SUMMARY:Lunch\r\n",
            "SUMMARY:Lunch\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\n\
             ORGANIZER:mailto:jane@example.com\r\nATTENDEE;CN=Ada:mailto:ada@example.com\r\n",
        );

    create_event(&mut account, "c1", &series, &[]).unwrap();

    let stored = &account.events["ev1"];
    // NOTE: Stalwart 0.16 refuses a `version` as an invalid property;
    // the draft has the server set it.
    assert!(stored.get("version").is_none(), "{stored}");
    assert!(stored.get("method").is_none(), "{stored}");
    assert_eq!(stored["recurrenceRule"]["frequency"], "weekly", "{stored}");
    assert_eq!(stored["recurrenceRule"]["count"], 4, "{stored}");
    assert!(stored.get("recurrenceRules").is_none(), "{stored}");
    assert_eq!(
        stored["organizerCalendarAddress"],
        "mailto:jane@example.com"
    );
    assert!(stored.get("replyTo").is_none(), "{stored}");
    let ada = stored["participants"]
        .as_object()
        .unwrap()
        .values()
        .find(|participant| participant["name"] == "Ada")
        .expect("the attendee");
    assert_eq!(ada["calendarAddress"], "mailto:ada@example.com", "{stored}");
    assert!(ada.get("sendTo").is_none(), "{stored}");
}

/// JSCalendar 2.0 holds one rule, and a second would ride a hatch
/// Stalwart 0.16 drops: the series is refused rather than cut.
#[test]
fn a_series_of_two_rules_is_refused_for_good() {
    let rules = "SUMMARY:Lunch\r\nRRULE:FREQ=WEEKLY\r\nRRULE:FREQ=MONTHLY\r\n";
    let mut account = events(&[]);
    let series = NEW_ENTRY.replace("SUMMARY:Lunch\r\n", rules);

    let err = create_event(&mut account, "c1", &series, &[])
        .err()
        .expect("a refused create");

    assert_eq!(err.status, Some(REFUSED));
    assert!(
        err.message.contains("one recurrence rule"),
        "{}",
        err.message
    );
    assert!(account.sets.is_empty());

    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    let edited = listed.ical.replace(
        "RRULE:FREQ=WEEKLY\r\n",
        "RRULE:FREQ=WEEKLY\r\nRRULE:FREQ=MONTHLY\r\n",
    );
    assert_ne!(edited, listed.ical);

    let err = update_event(&mut account, "ev1", &edited, listed.etag.as_deref(), &[]).unwrap_err();

    assert_eq!(err.status, Some(REFUSED));
    assert!(account.sets.is_empty());
}

/// Jane's address, which organizes [`standup`].
fn jane() -> Vec<String> {
    vec!["jane@example.com".to_string()]
}

/// Whether the last `CalendarEvent/set` asked the server to schedule.
fn scheduled(account: &FakeEvents) -> bool {
    account.sets.last().unwrap()["sendSchedulingMessages"] == true
}

#[test]
fn every_write_of_a_meeting_the_user_organizes_is_scheduled() {
    let mut account = events(&[]);
    let series = NEW_ENTRY.replace(
        "SUMMARY:Lunch\r\n",
        "SUMMARY:Lunch\r\nRRULE:FREQ=WEEKLY;COUNT=4\r\nATTENDEE;CN=Ada:mailto:ada@example.com\r\n",
    );
    create_event(&mut account, "c1", &series, &jane()).unwrap();
    assert!(
        scheduled(&account),
        "a meeting with no organizer is the user's"
    );

    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    let edited = listed.ical.replace("SUMMARY:Standup", "SUMMARY:Daily");
    update_event(
        &mut account,
        "ev1",
        &edited,
        listed.etag.as_deref(),
        &jane(),
    )
    .unwrap();
    assert!(scheduled(&account));

    let listed = account.listed("ev1");
    let edit = json!({
        "scope": "this",
        "recurrenceId": "20261008T090000",
        "start": {"time": "20261008T100000"},
    });
    let moved = crate::calendar::write(&listed.ical, &edit.to_string()).unwrap();
    update_event(&mut account, "ev1", &moved, listed.etag.as_deref(), &jane()).unwrap();
    assert_eq!(
        account.patch().keys().collect::<Vec<_>>(),
        ["recurrenceOverrides"]
    );
    assert!(scheduled(&account));

    let listed = account.listed("ev1");
    let edit = json!({"scope": "this", "recurrenceId": "20261015T090000"});
    let left = crate::calendar::remove(&listed.ical, &edit.to_string())
        .unwrap()
        .unwrap();
    update_event(&mut account, "ev1", &left, listed.etag.as_deref(), &jane()).unwrap();
    assert!(scheduled(&account));

    let listed = account.listed("ev1");
    destroy_event(&mut account, "ev1", listed.etag.as_deref(), &jane()).unwrap();
    assert_eq!(account.sets.last().unwrap()["destroy"], json!(["ev1"]));
    assert!(scheduled(&account));
}

/// The attendees taken off a meeting are cancelled: the copy the edit
/// replaces had them.
#[test]
fn a_meeting_whose_attendees_are_all_taken_off_is_scheduled() {
    let mut account = events(&[standup()]);
    let listed = account.listed("ev1");
    let alone: String = listed
        .ical
        .split_inclusive("\r\n")
        .filter(|line| !line.starts_with("ATTENDEE"))
        .collect();
    assert_ne!(alone, listed.ical);

    update_event(&mut account, "ev1", &alone, listed.etag.as_deref(), &jane()).unwrap();

    assert!(scheduled(&account));
}

#[test]
fn a_write_of_an_event_without_attendees_is_not_scheduled() {
    let mut account = events(&[]);
    let lunch = NEW_ENTRY.replace(
        "SUMMARY:Lunch\r\n",
        "SUMMARY:Lunch\r\nORGANIZER:mailto:jane@example.com\r\n",
    );
    create_event(&mut account, "c1", &lunch, &jane()).unwrap();
    assert!(!scheduled(&account));

    let listed = account.listed("ev1");
    let edited = listed.ical.replace("SUMMARY:Lunch", "SUMMARY:Brunch");
    update_event(
        &mut account,
        "ev1",
        &edited,
        listed.etag.as_deref(),
        &jane(),
    )
    .unwrap();
    assert!(!scheduled(&account));

    destroy_event(&mut account, "ev1", None, &jane()).unwrap();
    assert_eq!(account.sets.last().unwrap()["destroy"], json!(["ev1"]));
    assert!(!scheduled(&account));
}

/// Answering someone else's invitation would send the organizer an iTIP
/// `REPLY` under `sendSchedulingMessages`, a reply flow the app does not
/// drive: nothing new is sent.
#[test]
fn an_answer_to_someone_elses_invitation_is_not_scheduled() {
    let mut invitation = standup();
    invitation["organizerCalendarAddress"] = json!("mailto:boss@example.com");
    invitation["participants"]["p2"] = json!({
        "@type": "Participant",
        "calendarAddress": "mailto:jane@example.com",
        "roles": { "attendee": true },
        "participationStatus": "needs-action",
    });
    let mut account = events(&[invitation]);
    let listed = account.listed("ev1");
    let answered = listed
        .ical
        .replace("PARTSTAT=NEEDS-ACTION", "PARTSTAT=ACCEPTED");
    assert_ne!(answered, listed.ical);

    update_event(
        &mut account,
        "ev1",
        &answered,
        listed.etag.as_deref(),
        &jane(),
    )
    .unwrap();

    assert_eq!(
        account.events["ev1"]["participants"]["p2"]["participationStatus"],
        "accepted"
    );
    assert!(!scheduled(&account));

    let listed = account.listed("ev1");
    destroy_event(&mut account, "ev1", listed.etag.as_deref(), &jane()).unwrap();
    assert!(!scheduled(&account));
}
