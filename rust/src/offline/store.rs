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
    codec::{PimdirAction, action_from_payload},
    hash::HASH_ALGO,
    sql,
    sync::SYNC_VERSION,
};
use serde::{Deserialize, Serialize};

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
}

/// The versions this build creates and opens.
pub fn identity() -> Identity {
    Identity {
        format_version: sql::FORMAT_VERSION,
        sync_version: SYNC_VERSION,
        derivation: DERIVATION,
        version: sql::VERSION,
        hash_algo: HASH_ALGO,
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

/// The columns of `store_meta` the check reads, each absent where the
/// store lacks it.
#[derive(Debug, Default, Deserialize)]
#[serde(rename_all = "camelCase")]
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
#[derive(Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ActionFacts {
    /// The collection it names beside its own, `queue.target_collection`.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub target_collection: Option<String>,
}

/// Reads a queue row as the owner applies it: its payload parsed strictly
/// as its schema types it, and the body an `add` or an `update` names
/// being the one the row pins (`object_hash`), else the row is malformed
/// and parked whole, never applied in part (§15.3). A producer runs it
/// before appending, so it never queues what an owner would park.
pub fn action(kind: &str, payload: &str, object_hash: Option<&str>) -> Result<ActionFacts, String> {
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

    Ok(ActionFacts {
        target_collection: action
            .target_collection()
            .map(|target| target.as_str().to_string()),
    })
}

#[cfg(test)]
mod tests {
    use std::collections::BTreeMap;

    use crate::offline::store::{MetaFacts, StoreFacts, action, check};

    /// io-pimdir's payload vectors (spec/vectors/payloads.json): every valid
    /// payload reads, every malformed one is refused.
    #[test]
    fn the_payload_vectors_read_as_io_pimdir_reads_them() {
        let path = crate::offline::tests::io_pimdir_spec("vectors/payloads.json");
        let vectors: serde_json::Value =
            serde_json::from_str(&std::fs::read_to_string(&path).unwrap()).unwrap();
        let cases = vectors["cases"].as_array().unwrap();
        assert!(cases.len() >= 30, "{}", path.display());

        for case in cases {
            let kind = case["action"].as_str().unwrap();
            let payload = &case["payload"];
            // NOTE: the row pins the body the payload names, as a producer
            // writes it; a payload naming none pins none.
            let object = payload["object"].as_str();
            let read = action(kind, &payload.to_string(), object);
            assert_eq!(
                read.is_ok(),
                case["valid"].as_bool().unwrap(),
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
                format_version: Some("storage-draft-08".into()),
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
        facts.meta.as_mut().unwrap().format_version = Some("storage-draft-07".into());
        assert_eq!(check(&facts).unwrap_err().code, "store-version");
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
