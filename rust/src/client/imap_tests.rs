//! The IMAP session over a scripted server: a mailbox of dated messages
//! answering EXAMINE, `UID SEARCH`, `UID SORT` and the `UID FETCH`es a
//! listing, a floor and a body read send, its replies cut into small reads
//! so a streamed body crosses several of them.

use core::num::NonZeroU32;
use std::{
    collections::{HashMap, VecDeque},
    fs,
};

use io_imap::types::{
    core::NString,
    fetch::{MessageDataItem, Section},
    flag::{Flag, FlagFetch},
};
use jiff::{SignedDuration, Timestamp, civil::Date, tz::TimeZone};
use serde_json::from_str;
use tempfile::tempdir;
use url::Url;

use super::{
    BODY_BATCH, Capability, Disposal, ImapSession, ImapState, ImapWire, MAILBOX_REFUSED,
    MAX_MESSAGE_SIZE, Relocation, batches, disposal, header, literal_bound, relocation,
};
use crate::{
    client::listing::{Floor, MailRequest},
    types::{BridgeError, Credentials},
};

/// One message of the scripted mailbox.
struct Message {
    uid: u32,
    /// Its `Date` header, RFC 3339.
    date: String,
    /// Its `INTERNALDATE`, RFC 3339.
    received: String,
}

impl Message {
    fn header(&self) -> String {
        let date: Timestamp = self.date.parse().unwrap();
        format!(
            "Date: {}\r\nSubject: m{}\r\n\r\n",
            date.strftime("%a, %d %b %Y %H:%M:%S +0000"),
            self.uid
        )
    }

    fn body(&self) -> String {
        format!(
            "{}message {} {}\r\n",
            self.header(),
            self.uid,
            "x".repeat(self.uid as usize % 7)
        )
    }
}

/// A server holding one mailbox, its answers queued as its commands come.
struct Scripted {
    messages: Vec<Message>,
    uid_next: u32,
    modseq: u64,
    /// The most bytes one read hands back.
    chunk: usize,
    /// Whether a streamed body batch is refused (`NO`).
    refuse_batches: bool,
    /// What EXAMINE answers in place of opening the mailbox, `TAG` standing
    /// for the command's tag: a refusal, or the session closing.
    examine_refused: Option<&'static str>,
    /// UIDs a search still names whose header fetch Gmail refuses (`NO`),
    /// gone meanwhile.
    gone: Vec<u32>,
    pending: VecDeque<u8>,
    commands: Vec<String>,
    /// Every UID whose header fields were fetched, in order.
    headers_read: Vec<u32>,
}

impl Scripted {
    fn new(messages: Vec<Message>) -> Self {
        let uid_next = messages
            .iter()
            .map(|message| message.uid)
            .max()
            .unwrap_or(0)
            + 1;
        Self {
            messages,
            uid_next,
            modseq: 9,
            chunk: 7,
            refuse_batches: false,
            examine_refused: None,
            gone: Vec::new(),
            pending: VecDeque::new(),
            commands: Vec::new(),
            headers_read: Vec::new(),
        }
    }

    fn sent(&self, prefix: &str) -> usize {
        self.commands
            .iter()
            .filter(|command| command.starts_with(prefix))
            .count()
    }

    fn seq(&self, uid: u32) -> usize {
        let mut uids: Vec<u32> = self.messages.iter().map(|message| message.uid).collect();
        uids.sort_unstable();
        uids.iter().position(|held| *held == uid).unwrap() + 1
    }

    fn message(&self, uid: u32) -> Option<&Message> {
        self.messages.iter().find(|message| message.uid == uid)
    }

    /// The messages a `SENTSINCE` / `SENTBEFORE` criteria list keeps.
    fn matching(&self, criteria: &str) -> Vec<&Message> {
        let words: Vec<&str> = criteria.split_whitespace().collect();
        let mut since = None;
        let mut before = None;
        for pair in words.windows(2) {
            match pair[0] {
                "SENTSINCE" => since = Some(imap_day(pair[1])),
                "SENTBEFORE" => before = Some(imap_day(pair[1])),
                _ => {}
            }
        }
        self.messages
            .iter()
            .filter(|message| {
                let day = day_of(&message.date);
                since.is_none_or(|since| day >= since) && before.is_none_or(|before| day < before)
            })
            .collect()
    }

    fn answer(&mut self, line: &str) -> String {
        let (tag, command) = line.trim_end().split_once(' ').unwrap();
        self.commands.push(command.to_string());

        if command.starts_with("EXAMINE") {
            if let Some(reply) = self.examine_refused {
                return reply.replace("TAG", tag);
            }
            return format!(
                "* {} EXISTS\r\n* OK [UIDVALIDITY 7] v\r\n* OK [UIDNEXT {}] n\r\n\
                 * OK [HIGHESTMODSEQ {}] m\r\n{tag} OK [READ-ONLY] done\r\n",
                self.messages.len(),
                self.uid_next,
                self.modseq
            );
        }
        if let Some(criteria) = command.strip_prefix("UID SORT (REVERSE DATE) UTF-8 ") {
            let mut matching = self.matching(criteria);
            matching.sort_by(|a, b| b.date.cmp(&a.date));
            let uids: Vec<String> = matching.iter().map(|m| m.uid.to_string()).collect();
            return format!("* SORT {}\r\n{tag} OK sorted\r\n", uids.join(" "));
        }
        if let Some(criteria) = command.strip_prefix("UID SEARCH ") {
            let uids: Vec<String> = self
                .matching(criteria)
                .iter()
                .map(|m| m.uid.to_string())
                .collect();
            return format!("* SEARCH {}\r\n{tag} OK searched\r\n", uids.join(" "));
        }
        let Some(fetch) = command.strip_prefix("UID FETCH ") else {
            return format!("{tag} BAD unscripted\r\n");
        };
        let (set, items) = fetch.split_once(' ').unwrap();
        let uids: Vec<u32> = expand(set)
            .into_iter()
            .filter(|uid| self.message(*uid).is_some())
            .collect();

        let mut reply = String::new();
        if items == "(UID RFC822.SIZE)" {
            for uid in uids {
                let size = self.message(uid).unwrap().body().len();
                reply += &format!(
                    "* {} FETCH (UID {uid} RFC822.SIZE {size})\r\n",
                    self.seq(uid)
                );
            }
        } else if items
            .trim_matches(|c| c == '(' || c == ')')
            .ends_with("BODY.PEEK[]")
        {
            if self.refuse_batches && items == "(UID BODY.PEEK[])" {
                return format!("{tag} NO busy\r\n");
            }
            for uid in uids {
                let body = self.message(uid).unwrap().body();
                reply += &format!(
                    "* {} FETCH (UID {uid} BODY[] {{{}}}\r\n{body})\r\n",
                    self.seq(uid),
                    body.len()
                );
            }
        } else if items.contains("HEADER.FIELDS") {
            let refused = uids.iter().any(|uid| self.gone.contains(uid));
            for uid in uids {
                if self.gone.contains(&uid) {
                    continue;
                }
                self.headers_read.push(uid);
                let message = self.message(uid).unwrap();
                let header = message.header();
                let received: Timestamp = message.received.parse().unwrap();
                reply += &format!(
                    "* {} FETCH (UID {uid} FLAGS () RFC822.SIZE {} INTERNALDATE \"{}\" \
                     BODY[HEADER.FIELDS (DATE SUBJECT)] {{{}}}\r\n{header})\r\n",
                    self.seq(uid),
                    message.body().len(),
                    received.strftime("%d-%b-%Y %H:%M:%S +0000"),
                    header.len()
                );
            }
            if refused {
                return reply + &format!("{tag} NO Some messages could not be FETCHed\r\n");
            }
        } else {
            return format!("{tag} BAD unscripted\r\n");
        }
        reply + &format!("{tag} OK fetched\r\n")
    }
}

impl ImapWire for Scripted {
    fn imap_read(&mut self, _url: &str) -> Result<Vec<u8>, BridgeError> {
        let take = self.chunk.min(self.pending.len());
        Ok(self.pending.drain(..take).collect())
    }

    fn imap_write(&mut self, _url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        let reply = self.answer(&String::from_utf8_lossy(bytes));
        self.pending.extend(reply.into_bytes());
        Ok(())
    }
}

/// A sequence set as the UIDs it names.
fn expand(set: &str) -> Vec<u32> {
    let mut uids = Vec::new();
    for part in set.split(',') {
        match part.split_once(':') {
            Some((low, high)) => {
                let (low, high): (u32, u32) = (low.parse().unwrap(), high.parse().unwrap());
                uids.extend(low.min(high)..=low.max(high));
            }
            None => uids.push(part.parse().unwrap()),
        }
    }
    uids
}

/// An IMAP day (`7-Oct-2026`, quoted or not).
fn imap_day(raw: &str) -> Date {
    let raw = raw.trim_matches('"');
    let mut parts = raw.split('-');
    let day: i8 = parts.next().unwrap().parse().unwrap();
    let month = match parts.next().unwrap() {
        "Jan" => 1,
        "Feb" => 2,
        "Mar" => 3,
        "Apr" => 4,
        "May" => 5,
        "Jun" => 6,
        "Jul" => 7,
        "Aug" => 8,
        "Sep" => 9,
        "Oct" => 10,
        "Nov" => 11,
        _ => 12,
    };
    let year: i16 = parts.next().unwrap().parse().unwrap();
    Date::new(year, month, day).unwrap()
}

/// The UTC day an RFC 3339 instant falls on.
fn day_of(instant: &str) -> Date {
    instant
        .parse::<Timestamp>()
        .unwrap()
        .to_zoned(TimeZone::UTC)
        .date()
}

/// The instant `days` days and `hours` hours before 1 October 2026.
fn back(days: i64, hours: i64) -> String {
    let top: Timestamp = "2026-10-01T00:00:00Z".parse().unwrap();
    top.checked_sub(SignedDuration::from_hours(24 * days + hours))
        .unwrap()
        .to_string()
}

/// `count` messages, UID `count` the newest, one a day back from the top,
/// each received when it was sent.
fn daily(count: u32) -> Vec<Message> {
    (1..=count)
        .map(|uid| {
            let date = back(i64::from(count - uid), 12);
            Message {
                uid,
                received: date.clone(),
                date,
            }
        })
        .collect()
}

fn state(capabilities: Vec<Capability<'static>>) -> ImapState {
    let mut state = ImapState::new(&Url::parse("imaps://jane@example.org").unwrap());
    state.capabilities = capabilities;
    state
}

#[test]
fn a_sorted_floor_is_the_fiftieth_by_date_whatever_the_uids() {
    // NOTE: the newest UID is an import dated years back: by UID it would
    // be among the 50 newest and drag their floor to 2019.
    let mut messages = daily(120);
    messages.push(Message {
        uid: 121,
        date: String::from("2019-03-01T10:00:00Z"),
        received: back(0, 1),
    });
    let mut server = Scripted::new(messages);
    let mut state = state(vec![Capability::Sort(None)]);

    let mut floor = Floor::new(Some("2026-10-01T00:00:00Z"), 50);
    ImapSession::bind(&mut server, &mut state)
        .floor("INBOX", &mut floor)
        .unwrap();

    assert_eq!(floor.reply().floor, Some(back(49, 12)));
    assert_eq!(server.sent("UID SEARCH"), 0);
    let sort = server
        .commands
        .iter()
        .find(|command| command.starts_with("UID SORT"))
        .unwrap();
    assert!(sort.contains("SENTBEFORE \"02-Oct-2026\""), "{sort}");
    assert!(
        sort.contains("SENTSINCE \"03-Jul-2026\""),
        "90 days below: {sort}"
    );
    assert_eq!(server.headers_read.len(), 50, "the 50 newest by date alone");
}

#[test]
fn a_mailbox_the_server_refuses_to_open_is_told_from_a_failure() {
    let refusals = [
        // NOTE: Gmail, on a label a fresh session does not see yet.
        "TAG NO Unknown Mailbox: pimalaya-e2e-1 (Failure)
",
        // NOTE: Exchange, on a folder deleted since the roster.
        "TAG NO neverest-live-1 doesn't exist.
",
        "TAG NO [NONEXISTENT] Mailbox does not exist
",
    ];
    for reply in refusals {
        let mut server = Scripted::new(daily(3));
        server.examine_refused = Some(reply);
        let mut state = state(Vec::new());

        let mut floor = Floor::new(None, 50);
        let err = ImapSession::bind(&mut server, &mut state)
            .floor("INBOX", &mut floor)
            .unwrap_err();

        assert_eq!(err.status, Some(MAILBOX_REFUSED), "{reply}");
        assert!(
            err.message.contains("EXAMINE failed: NO"),
            "{}",
            err.message
        );
        assert!(state.selected.is_none(), "no mailbox left open");
    }

    let mut server = Scripted::new(daily(3));
    server.examine_refused = Some(
        "* BYE going away
",
    );
    let mut state = state(Vec::new());
    let mut floor = Floor::new(None, 50);
    let err = ImapSession::bind(&mut server, &mut state)
        .floor("INBOX", &mut floor)
        .unwrap_err();
    assert_eq!(err.status, None, "a closed session is no refusal");
}

#[test]
fn a_body_is_bounded_by_twice_its_declared_size() {
    assert_eq!(literal_bound(0), 64 * 1024);
    assert_eq!(literal_bound(1_000_000), 2_000_000 + 64 * 1024);
    assert_eq!(literal_bound(u64::MAX), MAX_MESSAGE_SIZE);
}

#[test]
fn a_sorted_floor_widens_its_window_and_reads_no_header_twice() {
    // NOTE: one message every three days: 30 within 90 days, the 20 more
    // a chunk needs within a year.
    let messages: Vec<Message> = (1..=200)
        .map(|uid| {
            let date = back(3 * i64::from(200 - uid), 12);
            Message {
                uid,
                received: date.clone(),
                date,
            }
        })
        .collect();
    let mut server = Scripted::new(messages);
    let mut state = state(vec![Capability::Sort(None)]);

    let mut floor = Floor::new(Some("2026-10-01T00:00:00Z"), 50);
    ImapSession::bind(&mut server, &mut state)
        .floor("INBOX", &mut floor)
        .unwrap();

    assert_eq!(floor.reply().floor, Some(back(3 * 49, 12)));
    assert_eq!(server.sent("UID SORT"), 2, "90 days, then a year");
    assert_eq!(
        server.headers_read.len(),
        50,
        "the first window's headers are not read again"
    );

    // NOTE: a round over the floor right after reads none of them again.
    let request: MailRequest = from_str(&format!(
        r#"{{"listing":{{"kind":"round"}},"scope":{{"since":"{}"}}}}"#,
        back(3 * 49, 12)
    ))
    .unwrap();
    let page = ImapSession::bind(&mut server, &mut state)
        .list_page("INBOX", &request)
        .unwrap();
    assert_eq!(page.items.len(), 50);
    assert_eq!(server.headers_read.len(), 50, "the floor's headers reused");
}

#[test]
fn without_sort_the_floor_is_the_fiftieth_by_date_among_the_newest_uids() {
    let mut messages = daily(60);
    // NOTE: among the newest UIDs, a message dated 2019 that arrived with
    // its neighbours: it is no longer among the 50 newest by date.
    messages[54].date = String::from("2019-03-01T10:00:00Z");
    let mut server = Scripted::new(messages);
    let mut state = state(Vec::new());

    let mut floor = Floor::new(None, 50);
    ImapSession::bind(&mut server, &mut state)
        .floor("INBOX", &mut floor)
        .unwrap();

    let reply = floor.reply();
    assert_eq!(reply.dated, 50);
    assert_eq!(
        reply.floor,
        Some(back(50, 12)),
        "UID 10's, the 50th by date"
    );
    assert_eq!(server.sent("UID SORT"), 0);
    assert_eq!(server.sent("UID SEARCH ALL"), 1);
}

#[test]
fn a_header_fetch_refused_for_a_gone_message_reads_the_others() {
    let mut without = daily(60);
    without.remove(54);
    let mut server = Scripted::new(without);
    let mut held = state(Vec::new());
    let mut expected = Floor::new(None, 50);
    ImapSession::bind(&mut server, &mut held)
        .floor("INBOX", &mut expected)
        .unwrap();

    // NOTE: Gmail still names UID 55 in a search, then refuses the
    // FETCH holding it with NO, naming none.
    let mut server = Scripted::new(daily(60));
    server.gone = vec![55];
    let mut state = state(Vec::new());
    let mut floor = Floor::new(None, 50);
    ImapSession::bind(&mut server, &mut state)
        .floor("INBOX", &mut floor)
        .unwrap();

    assert_eq!(floor.reply().floor, expected.reply().floor);
    assert!(!server.headers_read.contains(&55));
}

#[test]
fn without_sort_a_settled_sample_reads_two_chunks() {
    let mut server = Scripted::new(daily(300));
    let mut state = state(Vec::new());

    let mut floor = Floor::new(None, 50);
    ImapSession::bind(&mut server, &mut state)
        .floor("INBOX", &mut floor)
        .unwrap();

    assert_eq!(floor.reply().floor, Some(back(49, 12)));
    assert_eq!(
        server.headers_read.len(),
        100,
        "the second chunk holds nothing newer: the sample is settled"
    );
}

/// `imports` messages above `daily(count)`'s UIDs, as an import leaves
/// them: dated 2025 in no order, received when dated.
fn imported(count: u32, imports: u32) -> Vec<Message> {
    let mut messages = daily(count);
    for index in 0..imports {
        let date = format!(
            "2025-{:02}-{:02}T08:00:00Z",
            1 + (index * 7) % 12,
            1 + (index * 13) % 28
        );
        messages.push(Message {
            uid: count + 1 + index,
            received: date.clone(),
            date,
        });
    }
    messages
}

#[test]
fn without_sort_imported_mail_among_the_newest_uids_widens_the_sample() {
    // NOTE: Gmail IMAP after an import: the 300 newest UIDs are mail of
    // 2025 whose INTERNALDATE is its Date, the 100 below them this
    // autumn's. Their 50 newest by UID would floor the inbox in 2025.
    let mut server = Scripted::new(imported(100, 300));
    let mut state = state(Vec::new());

    let mut floor = Floor::new(None, 50);
    ImapSession::bind(&mut server, &mut state)
        .floor("INBOX", &mut floor)
        .unwrap();

    let reply = floor.reply();
    assert_eq!(reply.floor, Some(back(49, 12)), "this autumn's 50th");
    assert_eq!(reply.dates.first(), Some(&back(0, 12)));
    assert_eq!(server.headers_read.len(), 400, "50, 50, 100, then 200");
}

#[test]
fn without_sort_a_sample_is_bounded() {
    let mut server = Scripted::new(imported(100, 2000));
    let mut state = state(Vec::new());

    let mut floor = Floor::new(None, 50);
    ImapSession::bind(&mut server, &mut state)
        .floor("INBOX", &mut floor)
        .unwrap();

    assert_eq!(
        server.headers_read.len(),
        800,
        "four widenings past the first chunk"
    );
    assert!(floor.full(), "the 50th of the sample all the same");
}

#[test]
fn a_search_is_cached_until_the_mailbox_moves() {
    let mut server = Scripted::new(daily(30));
    let mut state = state(Vec::new());
    let request: MailRequest = from_str(&format!(
        r#"{{"listing":{{"kind":"round"}},"scope":{{"since":"{}"}}}}"#,
        back(20, 0)
    ))
    .unwrap();

    for _ in 0..2 {
        ImapSession::bind(&mut server, &mut state)
            .list_page("INBOX", &request)
            .unwrap();
    }
    assert_eq!(server.sent("UID SEARCH"), 1, "searched once per scope");

    // NOTE: a message arrives: UIDNEXT moves.
    server.messages.push(Message {
        uid: 31,
        date: back(0, 1),
        received: back(0, 1),
    });
    server.uid_next = 32;
    let page = ImapSession::bind(&mut server, &mut state)
        .list_page("INBOX", &request)
        .unwrap();
    assert_eq!(server.sent("UID SEARCH"), 2, "searched again");
    assert_eq!(page.items.len(), 21);

    // NOTE: a marker moves: HIGHESTMODSEQ moves, and the header fields
    // are read again rather than trusted.
    let read = server.headers_read.len();
    server.modseq += 1;
    ImapSession::bind(&mut server, &mut state)
        .list_page("INBOX", &request)
        .unwrap();
    assert_eq!(server.sent("UID SEARCH"), 3);
    assert_eq!(server.headers_read.len(), read + 22);
}

#[test]
fn bodies_stream_in_one_fetch_straight_to_files() {
    let mut server = Scripted::new(daily(5));
    let mut state = state(Vec::new());
    let dir = tempdir().unwrap();
    let ids: Vec<String> = ["5", "3", "999", "1", "4"]
        .iter()
        .map(|id| id.to_string())
        .collect();

    let read = ImapSession::bind(&mut server, &mut state)
        .fetch_sources("INBOX", &ids, dir.path())
        .unwrap();

    let mut got: Vec<&str> = read.iter().map(|source| source.id.as_str()).collect();
    got.sort_unstable();
    assert_eq!(got, ["1", "3", "4", "5"], "a message gone is left out");
    for source in &read {
        let uid: u32 = source.id.parse().unwrap();
        let body = server.message(uid).unwrap().body();
        assert_eq!(fs::read_to_string(&source.path).unwrap(), body);
        assert_eq!(source.size, body.len() as u64);
    }
    assert_eq!(server.sent("UID FETCH"), 2, "the sizes, then one batch");
    let batched = server
        .commands
        .iter()
        .filter(|command| command.ends_with("(UID BODY.PEEK[])"))
        .count();
    assert_eq!(batched, 1);
}

#[test]
fn a_refused_batch_is_read_one_message_after_another() {
    let mut server = Scripted::new(daily(3));
    server.refuse_batches = true;
    let mut state = state(Vec::new());
    let dir = tempdir().unwrap();
    let ids: Vec<String> = ["1", "2", "3"].iter().map(|id| id.to_string()).collect();

    let read = ImapSession::bind(&mut server, &mut state)
        .fetch_sources("INBOX", &ids, dir.path())
        .unwrap();

    assert_eq!(read.len(), 3);
    for source in &read {
        let uid: u32 = source.id.parse().unwrap();
        assert_eq!(
            fs::read_to_string(&source.path).unwrap(),
            server.message(uid).unwrap().body()
        );
    }
    let alone = server
        .commands
        .iter()
        .filter(|command| command.ends_with("BODY.PEEK[]") || command.ends_with("BODY.PEEK[])"))
        .filter(|command| !command.contains("(UID "))
        .count();
    assert_eq!(alone, 3);
}

#[test]
fn batches_hold_fifty_messages_or_twelve_megabytes() {
    let uids: Vec<u32> = (1..=120).collect();
    let mut sizes: HashMap<u32, u64> = uids.iter().map(|uid| (*uid, 10_000)).collect();
    sizes.insert(60, 20 * 1024 * 1024);
    sizes.remove(&70);
    sizes.insert(80, 7 * 1024 * 1024);
    sizes.insert(81, 7 * 1024 * 1024);

    let cut = batches(&uids, &sizes);
    let lengths: Vec<usize> = cut.iter().map(Vec::len).collect();
    assert_eq!(cut[0].len(), BODY_BATCH);
    assert_eq!(
        cut[1],
        (51..=59).collect::<Vec<_>>(),
        "cut before the large one"
    );
    assert_eq!(cut[2], vec![60], "a message past the bytes alone");
    assert!(
        cut.iter().all(|batch| !batch.contains(&70)),
        "gone, no size"
    );
    assert!(
        cut.iter()
            .all(|batch| !(batch.contains(&80) && batch.contains(&81))),
        "two of 7 MB do not share a batch"
    );
    assert_eq!(lengths.iter().sum::<usize>(), 119);
}

#[test]
fn a_server_with_move_relocates_in_one_command() {
    let capabilities = [Capability::Move, Capability::UidPlus];
    assert_eq!(relocation(&capabilities), Relocation::Move);
}

#[test]
fn a_server_without_move_copies_then_disposes() {
    assert_eq!(
        relocation(&[Capability::UidPlus]),
        Relocation::CopyThenDispose
    );
    assert_eq!(relocation(&[]), Relocation::CopyThenDispose);
}

#[test]
fn only_uidplus_expunges_one_message() {
    assert_eq!(disposal(&[Capability::UidPlus]), Disposal::Expunge);
    assert_eq!(
        disposal(&[Capability::Move]),
        Disposal::Mark,
        "a plain EXPUNGE would take every message marked"
    );
}

#[test]
fn a_fetched_header_block_names_the_member() {
    let fields = b"Date: Wed, 07 Oct 2026 10:00:00 +0200\r\n\
From: =?UTF-8?Q?Ren=C3=A9?= <Rene@Example.org>\r\n\
Subject: Hello\r\n\
Message-ID: <m1@example.org>\r\n\
Content-Type: multipart/mixed; boundary=x\r\n\r\n"
        .to_vec();
    let items = vec![
        MessageDataItem::Uid(NonZeroU32::new(42).unwrap()),
        MessageDataItem::Flags(vec![FlagFetch::Flag(Flag::Seen)]),
        MessageDataItem::Rfc822Size(1234),
        MessageDataItem::BodyExt {
            section: Some(Section::Header(None)),
            origin: None,
            data: NString::try_from(fields).unwrap(),
        },
    ];

    let (uid, header) = header(items).expect("a UID names a member");
    assert_eq!(uid, 42, "the UID is the identity, as ever");
    assert_eq!(header.flags, vec![String::from("\\Seen")]);
    assert_eq!(header.received, None, "no INTERNALDATE fetched");
    let summary = header.summary;
    assert_eq!(summary.date.as_deref(), Some("2026-10-07T08:00:00Z"));
    assert_eq!(summary.subject, "Hello");
    assert_eq!(summary.sender.as_deref(), Some("rene@example.org"));
    assert_eq!(summary.sender_name.as_deref(), Some("René"));
    assert_eq!(summary.size, Some(1234));
    assert_eq!(
        summary.attachment,
        Some(true),
        "multipart/mixed, without the body"
    );
}

/// The bridge speaks no IMAP STARTTLS, so a plain `imap://` account could
/// only sign in in clear: it is refused before the greeting is read.
#[test]
fn a_plain_imap_url_is_refused_before_any_byte() {
    let mut server = Scripted::new(Vec::new());
    let mut state = ImapState::new(&Url::parse("imap://jane@example.org:143").unwrap());
    let credentials = Credentials {
        login: "jane",
        password: "secret",
    };

    let err = ImapSession::bind(&mut server, &mut state)
        .connect(&credentials)
        .unwrap_err();

    assert!(err.message.contains("unencrypted"), "{}", err.message);
    assert!(server.commands.is_empty());
}

/// A server of several mailboxes answering the writes a relocation and
/// an append send, each message a UID and a `Message-ID`.
struct Boxes {
    mailboxes: HashMap<String, Vec<(u32, String, bool)>>,
    selected: String,
    uid_next: u32,
    pending: VecDeque<u8>,
    commands: Vec<String>,
    /// The command after whose effect the connection dies before its answer.
    dies_after: Option<&'static str>,
    dead: bool,
    /// The append waiting for its literal: its tag, mailbox and size.
    literal: Option<(String, String, usize)>,
    received: Vec<u8>,
}

impl Boxes {
    fn new(mailboxes: &[(&str, &[(u32, &str)])]) -> Self {
        Self {
            mailboxes: mailboxes
                .iter()
                .map(|(name, messages)| {
                    let held = messages
                        .iter()
                        .map(|(uid, id)| (*uid, id.to_string(), false))
                        .collect();
                    (name.to_string(), held)
                })
                .collect(),
            selected: String::new(),
            uid_next: 100,
            pending: VecDeque::new(),
            commands: Vec::new(),
            dies_after: None,
            dead: false,
            literal: None,
            received: Vec::new(),
        }
    }

    /// The `Message-ID`s `mailbox` holds, `\Deleted` ones left out.
    fn ids(&self, mailbox: &str) -> Vec<&str> {
        self.mailboxes[mailbox]
            .iter()
            .filter(|(_, _, deleted)| !deleted)
            .map(|(_, id, _)| id.as_str())
            .collect()
    }

    fn answer(&mut self, line: &str) -> String {
        let (tag, command) = line.trim_end().split_once(' ').unwrap();
        self.commands.push(command.to_string());
        let unquote = |name: &str| name.trim_matches('"').to_string();

        if let Some(name) = command
            .strip_prefix("SELECT ")
            .or_else(|| command.strip_prefix("EXAMINE "))
        {
            self.selected = unquote(name);
            let count = self.mailboxes[&self.selected].len();
            return format!(
                "* {count} EXISTS\r\n* OK [UIDVALIDITY 7] v\r\n* OK [UIDNEXT {}] n\r\n\
                 {tag} OK done\r\n",
                self.uid_next
            );
        }
        if let Some(id) = command.strip_prefix("UID SEARCH HEADER MESSAGE-ID ") {
            let id = unquote(id);
            let uids: Vec<String> = self.mailboxes[&self.selected]
                .iter()
                .filter(|(_, held, _)| format!("<{held}>") == id)
                .map(|(uid, _, _)| uid.to_string())
                .collect();
            return format!("* SEARCH {}\r\n{tag} OK searched\r\n", uids.join(" "));
        }
        if let Some(fetch) = command.strip_prefix("UID FETCH ") {
            let uid: u32 = fetch.split_once(' ').unwrap().0.parse().unwrap();
            let Some((_, id, _)) = self.mailboxes[&self.selected]
                .iter()
                .find(|(held, _, _)| *held == uid)
                .cloned()
            else {
                return format!("{tag} OK fetched\r\n");
            };
            let header = format!(
                "Date: Wed, 07 Oct 2026 10:00:00 +0000\r\nSubject: s\r\nMessage-ID: <{id}>\r\n\r\n"
            );
            return format!(
                "* 1 FETCH (UID {uid} FLAGS () RFC822.SIZE 99 INTERNALDATE \
                 \"07-Oct-2026 10:00:00 +0000\" BODY[HEADER.FIELDS (MESSAGE-ID)] {{{}}}\r\n\
                 {header})\r\n{tag} OK fetched\r\n",
                header.len()
            );
        }
        if let Some(copy) = command.strip_prefix("UID COPY ") {
            let (uid, target) = copy.split_once(' ').unwrap();
            let uid: u32 = uid.parse().unwrap();
            let id = self.mailboxes[&self.selected]
                .iter()
                .find(|(held, _, _)| *held == uid)
                .map(|(_, id, _)| id.clone())
                .unwrap();
            self.uid_next += 1;
            let next = self.uid_next;
            self.mailboxes
                .get_mut(&unquote(target))
                .unwrap()
                .push((next, id, false));
            return format!("{tag} OK copied\r\n");
        }
        if let Some(store) = command.strip_prefix("UID STORE ") {
            let uid: u32 = store.split_once(' ').unwrap().0.parse().unwrap();
            let selected = self.selected.clone();
            for held in self.mailboxes.get_mut(&selected).unwrap() {
                if held.0 == uid {
                    held.2 = true;
                }
            }
            return format!("{tag} OK stored\r\n");
        }
        if command.starts_with("UID EXPUNGE ") {
            return format!("{tag} OK expunged\r\n");
        }
        if let Some(append) = command.strip_prefix("APPEND ") {
            let (mailbox, _) = append.split_once(' ').unwrap();
            let size = append
                .rsplit_once('{')
                .and_then(|(_, size)| size.trim_end_matches('}').parse().ok())
                .unwrap();
            self.literal = Some((tag.to_string(), unquote(mailbox), size));
            self.received.clear();
            return "+ go ahead\r\n".into();
        }
        format!("{tag} BAD unscripted\r\n")
    }
}

impl ImapWire for Boxes {
    fn imap_read(&mut self, _url: &str) -> Result<Vec<u8>, BridgeError> {
        if self.dead {
            return Err("Transport read failed: SocketException: Connection reset".into());
        }
        Ok(self.pending.drain(..).collect())
    }

    fn imap_write(&mut self, _url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        if let Some((tag, mailbox, size)) = self.literal.clone() {
            self.received.extend_from_slice(bytes);
            if self.received.len() >= size + 2 {
                let id = crate::mail::message_id(&self.received[..size]).unwrap();
                self.uid_next += 1;
                let next = self.uid_next;
                self.mailboxes
                    .get_mut(&mailbox)
                    .unwrap()
                    .push((next, id, false));
                self.literal = None;
                self.pending
                    .extend(format!("{tag} OK appended\r\n").into_bytes());
            }
            return Ok(());
        }

        let line = String::from_utf8_lossy(bytes).to_string();
        let reply = self.answer(&line);
        if self
            .dies_after
            .is_some_and(|prefix| self.commands.last().unwrap().starts_with(prefix))
        {
            self.dies_after = None;
            self.dead = true;
            return Ok(());
        }
        self.pending.extend(reply.into_bytes());
        Ok(())
    }
}

/// A relocation whose COPY lost its answer is run again on a new
/// connection, as the app's verbs are: the copy it made is found by its
/// `Message-ID` and not made twice.
#[test]
fn a_relocation_run_again_after_a_lost_copy_files_one_copy() {
    let mut server = Boxes::new(&[("INBOX", &[(1, "m1@example.org")]), ("Archive", &[])]);
    server.dies_after = Some("UID COPY");

    let mut first = state(vec![Capability::UidPlus]);
    let lost = ImapSession::bind(&mut server, &mut first).relocate("INBOX", "1", "Archive");
    assert!(
        lost.is_err(),
        "the connection died before the COPY answered"
    );
    assert_eq!(server.ids("Archive"), ["m1@example.org"]);

    server.dead = false;
    let mut again = state(vec![Capability::UidPlus]);
    ImapSession::bind(&mut server, &mut again)
        .relocate("INBOX", "1", "Archive")
        .unwrap();

    assert_eq!(server.ids("Archive"), ["m1@example.org"], "one copy");
    assert!(server.ids("INBOX").is_empty(), "the original disposed of");
    assert_eq!(
        server
            .commands
            .iter()
            .filter(|c| c.starts_with("UID COPY"))
            .count(),
        1
    );
}

/// A first relocation copies as ever.
#[test]
fn a_relocation_without_move_copies_a_message_the_target_lacks() {
    let mut server = Boxes::new(&[
        ("INBOX", &[(1, "m1@example.org")]),
        ("Archive", &[(5, "other@example.org")]),
    ]);
    let mut session = state(vec![Capability::UidPlus]);
    ImapSession::bind(&mut server, &mut session)
        .relocate("INBOX", "1", "Archive")
        .unwrap();

    assert_eq!(
        server.ids("Archive"),
        ["other@example.org", "m1@example.org"]
    );
    assert!(server.ids("INBOX").is_empty());
}

/// An append whose answer was lost stays staged and comes back: the copy
/// it made is found and not appended twice.
#[test]
fn an_append_of_a_message_the_mailbox_holds_is_skipped() {
    let message = b"Message-ID: <s1@example.org>\r\nSubject: sent\r\n\r\nbody\r\n".to_vec();
    let mut server = Boxes::new(&[("Sent", &[])]);
    let mut session = state(Vec::new());

    ImapSession::bind(&mut server, &mut session)
        .append("Sent", message.clone(), &["\\Seen".into()])
        .unwrap();
    ImapSession::bind(&mut server, &mut session)
        .append("Sent", message, &["\\Seen".into()])
        .unwrap();

    assert_eq!(server.ids("Sent"), ["s1@example.org"]);
    assert_eq!(
        server
            .commands
            .iter()
            .filter(|c| c.starts_with("APPEND"))
            .count(),
        1
    );
}
