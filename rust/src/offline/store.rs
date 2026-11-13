//! # Store
//!
//! What the Java owner of pimdir.db decides by io-pimdir's rules rather
//! than its own: whether it opens a store at all (STORAGE §6), and which
//! versions it stamps one with (§4).
//!
//! Drafts are exact (§4.2): a store another draft wrote is refused, never
//! reconciled nor migrated, and the app is reinstalled. Java reads the
//! facts off the file without writing anything ([`StoreFacts`]); the
//! verdict is [`check`]'s.

use std::collections::BTreeMap;

use io_pimdir::{
    codec::{
        PimdirAction, PimdirPayloadOutcome, UNCONFIRMED, action_from_payload, payload_outcome,
    },
    hash::{self, HASH_ALGO},
    intent::{PimdirSubmit, SUBMIT},
    sql,
    sync::SYNC_VERSION,
};
use serde::{Deserialize, Serialize};

use crate::mail::compose;

/// The Annex A revision io-pimdir derives summaries, addresses and sort
/// keys under, recorded in `store_meta.derivation` (STORAGE §6); the
/// crate's own `client::schema::DERIVATION`, which it does not export.
pub const DERIVATION: i64 = 3;

/// The versions a store this app creates is stamped with and checked
/// against.
#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Identity {
    /// `store_meta.format_version` (§4.2).
    pub format_version: &'static str,
    /// `store_meta.sync_version`, written by the first sync write (§4.3).
    pub sync_version: &'static str,
    /// `store_meta.derivation` (§6).
    pub derivation: i64,
    /// The schema version, `PRAGMA user_version` (§6).
    pub version: i64,
    /// `store_meta.hash_algo` (§5).
    pub hash_algo: &'static str,
    /// The reserved prefix of `queue.error` (§15.2): an intent parked under
    /// it is one whose outcome is unknown.
    pub unconfirmed: &'static str,
}

/// The versions this build creates and opens.
pub fn identity() -> Identity {
    Identity {
        format_version: sql::FORMAT_VERSION,
        sync_version: SYNC_VERSION,
        derivation: DERIVATION,
        version: sql::VERSION,
        hash_algo: HASH_ALGO,
        unconfirmed: UNCONFIRMED,
    }
}

/// What Java read off an existing pimdir.db, read-only.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct StoreFacts {
    /// `PRAGMA user_version`.
    #[serde(default)]
    pub user_version: i64,
    /// Every table, index and trigger the file declares, by name, with its
    /// text; SQLite's own and the platform's (`android_metadata`) left out.
    #[serde(default)]
    pub objects: BTreeMap<String, String>,
    /// The same of a database the canonical migrations alone created.
    #[serde(default)]
    pub canonical: BTreeMap<String, String>,
    /// The `store_meta` row, absent where the table or the row is.
    #[serde(default)]
    pub meta: Option<MetaFacts>,
}

/// The columns of `store_meta` the check reads, by their names in the
/// table, each absent where the store lacks it; the row's other columns
/// are not read.
#[derive(Debug, Default, Deserialize)]
pub struct MetaFacts {
    #[serde(default)]
    pub format: Option<String>,
    #[serde(default)]
    pub format_version: Option<String>,
    #[serde(default)]
    pub version: Option<i64>,
    #[serde(default)]
    pub hash_algo: Option<String>,
    #[serde(default)]
    pub sync_version: Option<String>,
}

/// Why a store is not opened, with io-pimdir's stable code (`PimdirError`'s
/// `code()`) and what was found.
#[derive(Debug, Eq, PartialEq, Serialize)]
pub struct Refusal {
    /// The refusal's code: `store-foreign`, `store-version`,
    /// `store-version-mismatch`, `store-hash-algo`, `store-corrupt` or
    /// `sync-version`.
    pub code: &'static str,
    /// What was found, for the log; never shown as the reason alone.
    pub detail: String,
}

impl Refusal {
    fn new(code: &'static str, detail: impl Into<String>) -> Self {
        Self {
            code,
            detail: detail.into(),
        }
    }
}

/// Whether the app opens an existing store, refused in io-pimdir's order
/// (`client::schema::check`, STORAGE §6 steps 1 and 2): a database that is
/// not a pimdir store, one of another draft or lacking the marker, stamps
/// disagreeing, another algorithm, a schema whose text is not the
/// canonical one, then sync state another engine version wrote (§4.3).
///
/// A database declaring nothing at `user_version` 0 is a fresh one the
/// caller creates, never refused here.
pub fn check(facts: &StoreFacts) -> Result<(), Refusal> {
    if facts.user_version == 0 {
        return match facts.objects.is_empty() {
            true => Ok(()),
            false => Err(Refusal::new(
                "store-foreign",
                "tables at user_version 0, another program's database",
            )),
        };
    }

    let Some(meta) = facts
        .meta
        .as_ref()
        .filter(|meta| meta.format.as_deref() == Some("pimdir"))
    else {
        let format = facts.meta.as_ref().and_then(|meta| meta.format.as_deref());
        return Err(Refusal::new(
            "store-foreign",
            format!("format {}", format.unwrap_or("absent")),
        ));
    };

    let Some(format_version) = meta.format_version.as_deref() else {
        return Err(Refusal::new(
            "store-version",
            "no format_version, a draft before storage-draft-08",
        ));
    };
    if format_version != sql::FORMAT_VERSION {
        return Err(Refusal::new("store-version", format_version));
    }
    if meta.version != Some(facts.user_version) {
        return Err(Refusal::new(
            "store-version-mismatch",
            format!(
                "user_version {} and store_meta.version {:?}",
                facts.user_version, meta.version
            ),
        ));
    }
    if meta.hash_algo.as_deref() != Some(HASH_ALGO) {
        return Err(Refusal::new(
            "store-hash-algo",
            meta.hash_algo.clone().unwrap_or_default(),
        ));
    }

    // NOTE: a draft's store holds version 1 alone (§4.2), and its schema
    // is 0001_init.sql's to the word: anything else is damage.
    if facts.user_version != sql::VERSION {
        return Err(Refusal::new("store-corrupt", "user_version"));
    }
    let differs = facts
        .canonical
        .iter()
        .find(|(name, text)| {
            facts.objects.get(*name).map(|held| normalised(held)) != Some(normalised(text))
        })
        .map(|(name, _)| name)
        .or_else(|| {
            facts
                .objects
                .keys()
                .find(|name| !facts.canonical.contains_key(*name))
        });
    if let Some(object) = differs {
        return Err(Refusal::new("store-corrupt", object.clone()));
    }

    match meta.sync_version.as_deref() {
        Some(found) if found != SYNC_VERSION => Err(Refusal::new("sync-version", found)),
        _ => Ok(()),
    }
}

/// A declaring statement as the schema check compares it: every `--`
/// comment dropped and whitespace collapsed to single spaces, as
/// io-pimdir's `client::schema::normalised`.
fn normalised(sql: &str) -> String {
    let mut out = String::new();
    for line in sql.lines() {
        let code = line.split_once("--").map_or(line, |(code, _)| code);
        for word in code.split_whitespace() {
            if !out.is_empty() {
                out.push(' ');
            }
            out.push_str(word);
        }
    }
    out
}

/// What a queue row states, read strictly as its payload schema types it
/// (STORAGE §15.3, Annex B.2, spec/payloads/).
#[derive(Debug, Default, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionFacts {
    /// The collection it names beside its own, `queue.target_collection`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_collection: Option<String>,
    /// Why the row is skipped rather than read: its payload is unknown,
    /// an action or a version of one io-pimdir holds no schema for, left
    /// pending and untouched for a later owner (§15.2), never parked.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub skipped: Option<String>,
}

/// Reads a queue row as the owner applies it: its payload parsed strictly
/// as its schema types it, and the body an `add` or an `update` names
/// being the one the row pins (`object_hash`), a `submit` pinning the
/// message it sends, else the row is malformed and parked whole, never
/// applied in part (§15.3). A payload is valid, malformed or unknown, by
/// io-pimdir's judgement (`payload_outcome`): an unknown one, another
/// action or another integer `v` of a known one, is skipped and read no
/// further (§15.2), and one stating no usable `v` is malformed. A producer
/// runs it before appending, so it never queues what an owner would park.
pub fn action(kind: &str, payload: &str, object_hash: Option<&str>) -> Result<ActionFacts, String> {
    if payload_outcome(kind, payload) == PimdirPayloadOutcome::Unknown {
        return Ok(ActionFacts {
            skipped: Some(format!("A {kind} payload unknown to this app")),
            ..ActionFacts::default()
        });
    }
    let action = action_from_payload(kind, payload).map_err(|err| err.to_string())?;

    let named = match &action {
        PimdirAction::Add { object, .. } => object.as_ref(),
        PimdirAction::Update { object, .. } => Some(object),
        _ => None,
    };
    if let Some(named) = named
        && Some(named.as_str()) != object_hash
    {
        return Err(format!(
            "The payload names the body {} where the row pins {}",
            named.as_str(),
            object_hash.unwrap_or("none")
        ));
    }
    if kind == SUBMIT && object_hash.is_none() {
        return Err("A submit pins the message it sends, and this row pins none".into());
    }

    Ok(ActionFacts {
        target_collection: action
            .target_collection()
            .map(|target| target.as_str().to_string()),
        skipped: None,
    })
}

/// The `submit` payload queuing `message` for `source` to send from
/// `from` (STORAGE Annex B.2): the envelope recipients its `To`, `Cc` and
/// `Bcc` headers name, and its subject, for reports. No member of the
/// payload is the app's own: what a listing draws is derived from the
/// message, the row's body.
pub fn submit_payload(source: &str, from: &str, message: &[u8]) -> Result<String, String> {
    let rcpts = compose::envelope(message)
        .map_err(|err| err.message)?
        .recipients;
    let submit = PimdirSubmit {
        source: source.to_string(),
        from: from.to_string(),
        rcpts,
        subject: compose::subject(message),
        copy: None,
    };
    match submit.to_action(hash::hash(message)) {
        Ok(PimdirAction::Unknown { payload, .. }) => Ok(payload),
        Ok(_) => Err("A submit is an intent".into()),
        Err(err) => Err(err.to_string()),
    }
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::offline::store::{MetaFacts, StoreFacts, action, check, submit_payload};

    /// io-pimdir's payload vectors (spec/vectors/payloads.json): every valid
    /// payload reads, every malformed one is refused, every unknown one is
    /// skipped.
    #[test]
    fn the_payload_vectors_read_as_io_pimdir_reads_them() {
        let path = crate::offline::tests::io_pimdir_spec("vectors/payloads.json");
        let vectors: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        assert_eq!(vectors["v"], 2, "payloads.json at another version");
        let cases = vectors["cases"].as_array().unwrap();
        assert!(cases.len() >= 30, "{}", path.display());

        for case in cases {
            let kind = case["action"].as_str().unwrap();
            let payload = &case["payload"];
            // NOTE: the row pins the body the payload names, as a producer
            // writes it, and a submit the message it sends; any other
            // payload pins none.
            let message = "a".repeat(52);
            let object = match kind {
                "submit" => Some(message.as_str()),
                _ => payload["object"].as_str(),
            };
            let read = action(kind, &payload.to_string(), object);
            let outcome = match &read {
                Ok(facts) if facts.skipped.is_some() => "unknown",
                Ok(_) => "valid",
                Err(_) => "malformed",
            };
            assert_eq!(
                outcome,
                case["outcome"].as_str().unwrap(),
                "{kind} {payload}: {read:?}"
            );
        }
    }

    #[test]
    fn a_body_the_payload_names_is_the_one_the_row_pins() {
        let hash = "a".repeat(52);
        let add = format!(r#"{{"v": 1, "object": "{hash}", "flags": []}}"#);
        assert!(action("add", &add, Some(&hash)).is_ok());
        assert!(action("add", &add, None).is_err(), "no pinned body");
        assert!(action("add", &add, Some(&"b".repeat(52))).is_err());

        let malformed = r#"{"v": 1, "flags": []}"#;
        assert!(
            action("add", malformed, None).is_err(),
            "an add names its body"
        );
        let unknown = r#"{"v": 1, "seq": 3, "flags": [], "extra": 1}"#;
        assert!(
            action("set-flags", unknown, None).is_err(),
            "a member v1 lacks"
        );

        let moved = action("move", r#"{"v": 1, "seq": 3, "to": "acct/Trash"}"#, None).unwrap();
        assert_eq!(moved.target_collection.as_deref(), Some("acct/Trash"));
    }

    /// A row a later producer queued, or of an action nobody here knows,
    /// is skipped, left pending for a later owner, never parked (§15.2),
    /// as io-pimdir's drain skips it; a row of a known action stating no
    /// version, or one that is no integer, is malformed.
    #[test]
    fn a_later_payload_version_is_skipped_not_parked() {
        let hash = "a".repeat(52);
        let later = r#"{"v": 2, "source": "server", "from": "a@x.org", "rcpts": [], "new": 1}"#;
        let read = action("submit", later, Some(&hash)).unwrap();
        assert!(read.skipped.is_some(), "{read:?}");
        let later = r#"{"v": 2, "seq": 3}"#;
        assert!(action("remove", later, None).unwrap().skipped.is_some());

        let own = action("x-archive", r#"{"anything": true}"#, None).unwrap();
        assert!(own.skipped.is_some(), "{own:?}");

        let unversioned = r#"{"seq": 3}"#;
        assert!(action("remove", unversioned, None).is_err());
        let worded = r#"{"v": "2", "seq": 3}"#;
        assert!(action("remove", worded, None).is_err(), "no integer");
        let current = action("remove", r#"{"v": 1, "seq": 3}"#, None).unwrap();
        assert_eq!(current, Default::default(), "read, not skipped");
    }

    /// The message is the submit's body, `queue.object_hash`, never a
    /// member of its payload (Annex B.2).
    #[test]
    fn a_submit_pins_the_message_it_sends() {
        let submit = r#"{"v": 1, "source": "server", "from": "a@x.org", "rcpts": ["b@y.org"]}"#;
        assert!(action("submit", submit, Some(&"a".repeat(52))).is_ok());
        assert!(action("submit", submit, None).is_err());
    }

    /// The payload the app queued before draft-08 is no `submit` of Annex
    /// B.2: no `source` nor `rcpts`, and members v1 does not define.
    #[test]
    fn the_app_s_former_submit_payload_is_malformed() {
        let former = r#"{"v":1,"from":"jane@example.com","messageId":"m1@example.org","subject":"Hello","sortKey":"2026-01-05T09:00:00Z"}"#;
        assert!(action("submit", former, Some(&"a".repeat(52))).is_err());
    }

    #[test]
    fn the_app_s_submit_payload_is_annex_b2_s() {
        let message = b"Date: Mon, 5 Jan 2026 09:00:00 +0000\r\n\
            Message-ID: <m1@example.org>\r\n\
            From: Jane <jane@example.com>\r\n\
            To: Bob <bob@example.org>, carol@example.org\r\n\
            Cc: dan@example.org\r\n\
            Bcc: eve@example.org\r\n\
            Subject: =?UTF-8?B?UsOpdW5pb24=?=\r\n\
            \r\n\
            Hi\r\n";

        let payload = submit_payload("server", "jane@example.com", message).unwrap();
        let json: serde_json::Value = serde_json::from_str(&payload).unwrap();
        assert_eq!(
            json,
            serde_json::json!({
                "v": 1,
                "source": "server",
                "from": "jane@example.com",
                "rcpts": [
                    "bob@example.org",
                    "carol@example.org",
                    "dan@example.org",
                    "eve@example.org"
                ],
                "subject": "R\u{e9}union",
            })
        );
        let hash = io_pimdir::hash::hash(message);
        assert!(action("submit", &payload, Some(hash.as_str())).is_ok());

        let untitled = b"From: jane@example.com\r\nTo: bob@example.org\r\n\r\nHi\r\n";
        let payload = submit_payload("server", "jane@example.com", untitled).unwrap();
        assert!(!payload.contains("subject"), "{payload}");

        let nobody = b"From: jane@example.com\r\nSubject: x\r\n\r\nHi\r\n";
        assert!(submit_payload("server", "jane@example.com", nobody).is_err());
    }

    fn current() -> StoreFacts {
        let objects = BTreeMap::from([(
            "items".to_string(),
            "CREATE TABLE items (\n  a TEXT -- a comment\n)".to_string(),
        )]);
        StoreFacts {
            user_version: 1,
            canonical: BTreeMap::from([(
                "items".to_string(),
                "CREATE TABLE items ( a TEXT )".to_string(),
            )]),
            objects,
            meta: Some(MetaFacts {
                format: Some("pimdir".into()),
                format_version: Some("storage-draft-11".into()),
                version: Some(1),
                hash_algo: Some("blake3".into()),
                sync_version: None,
            }),
        }
    }

    #[test]
    fn a_store_of_this_draft_opens() {
        assert_eq!(check(&current()), Ok(()));
        assert_eq!(check(&StoreFacts::default()), Ok(()), "a fresh database");
    }

    /// A store an app before draft-08 wrote (io-pimdir 8907303's shape) has
    /// no `format_version`: refused, never reconciled.
    #[test]
    fn a_store_of_an_earlier_draft_is_refused() {
        let mut facts = current();
        facts.meta.as_mut().unwrap().format_version = None;
        facts.meta.as_mut().unwrap().hash_algo = Some("sha256-128".into());
        assert_eq!(check(&facts).unwrap_err().code, "store-version");

        let mut facts = current();
        facts.meta.as_mut().unwrap().format_version = Some("storage-draft-08".into());
        assert_eq!(check(&facts).unwrap_err().code, "store-version");

        let mut facts = current();
        facts.meta.as_mut().unwrap().format_version = Some("storage-draft-09".into());
        assert_eq!(check(&facts).unwrap_err().code, "store-version");

        let mut facts = current();
        facts.meta.as_mut().unwrap().format_version = Some("storage-draft-10".into());
        assert_eq!(check(&facts).unwrap_err().code, "store-version");

        let mut facts = current();
        facts.meta.as_mut().unwrap().sync_version = Some("sync-draft-10".into());
        assert_eq!(check(&facts).unwrap_err().code, "sync-version");
    }

    #[test]
    fn a_damaged_or_foreign_store_is_refused() {
        let mut facts = current();
        facts
            .objects
            .insert("extra".into(), "CREATE TABLE extra (x)".into());
        assert_eq!(check(&facts).unwrap_err().code, "store-corrupt");

        let mut facts = current();
        facts
            .objects
            .insert("items".into(), "CREATE TABLE items (b TEXT)".into());
        assert_eq!(check(&facts).unwrap_err().code, "store-corrupt");

        let mut facts = current();
        facts.user_version = 0;
        assert_eq!(check(&facts).unwrap_err().code, "store-foreign");

        let mut facts = current();
        facts.meta = None;
        assert_eq!(check(&facts).unwrap_err().code, "store-foreign");

        let mut facts = current();
        facts.meta.as_mut().unwrap().sync_version = Some("sync-draft-06".into());
        assert_eq!(check(&facts).unwrap_err().code, "sync-version");
    }
}
