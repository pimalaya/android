//! The sync driver's policy, as pure decisions over JSON facts.
//!
//! The store is pimdir now, serviced from Java against Android's own
//! SQLite, and the row-to-placement translation that used to live here
//! went with it: the schema expresses membership, staging and
//! divergence directly, so there is nothing left to map. What remains
//! is the policy no table can be read for: how a push change becomes a
//! backend call ([`push_plan`]), how an account-wide delta projects
//! onto one book's enumerate ([`account_snapshot`]), and when a
//! 412-rejected push may retry unguarded ([`retry_unguarded`]).

use serde_json::{Map, Value, json};

use crate::account::Backend;

/// Whether a 412-rejected push may retry unguarded: the last enumerate
/// proves the handle unchanged at the staged base revision, listed
/// with that very ETag or unlisted by a delta (a delta only lists what
/// changed). Some servers, posteo's SabreDAV among them, serve listing
/// ETags their If-Match never matches. Takes `{"listed": {handle:
/// etag}?, "complete": bool, "handle", "ifMatch"?}`.
pub fn retry_unguarded(facts: &Value) -> bool {
    let Some(listed) = facts.get("listed").and_then(Value::as_object) else {
        return false;
    };
    let Some(if_match) = facts.get("ifMatch").and_then(Value::as_str) else {
        return false;
    };
    let handle = str_field(facts, "handle");

    match listed.get(handle) {
        Some(etag) => etag.as_str() == Some(if_match),
        None => !bool_field(facts, "complete"),
    }
}

/// Projects an account-wide delta (JMAP, Google) onto one book's
/// enumerate: cards member of the book are its items, and on an
/// incremental round a changed card that left the book (still held
/// locally but no longer listing it) rides as vanished. Takes
/// `{"bookId"?, "complete": bool, "changed": [{"handle", "books",
/// "known": bool}], "vanished": [handle]}`, returns `{"members":
/// [index], "vanished": [handle]}`.
pub fn account_snapshot(facts: &Value) -> Result<Value, String> {
    let changed = facts
        .get("changed")
        .and_then(Value::as_array)
        .ok_or_else(|| "Invalid account delta: no changed array".to_string())?;
    let book_id = facts.get("bookId").and_then(Value::as_str);
    let complete = bool_field(facts, "complete");

    let mut members: Vec<usize> = Vec::new();
    let mut vanished: Vec<String> = facts
        .get("vanished")
        .and_then(Value::as_array)
        .map(|handles| {
            handles
                .iter()
                .filter_map(Value::as_str)
                .map(str::to_string)
                .collect()
        })
        .unwrap_or_default();

    for (index, card) in changed.iter().enumerate() {
        let books = card.get("books").and_then(Value::as_array);
        let member = match (book_id, books) {
            (Some(id), Some(books)) => books.iter().any(|book| book.as_str() == Some(id)),
            _ => false,
        };

        if member {
            members.push(index);
        } else if !complete && bool_field(card, "known") {
            vanished.push(str_field(card, "handle").to_string());
        }
    }

    Ok(json!({ "members": members, "vanished": vanished }))
}

/// Plans one push change: a pending create is a membership patch when
/// the body already lives on the account (add with an origin, on an
/// account-level backend), a genuine create otherwise (a Google create
/// lands in the myContacts system group, so a create aimed at another
/// group patches the membership right after); a staged removal is a
/// membership patch when the card is not deleted on an account-level
/// backend, the card's deletion otherwise. Takes `{"op": "add" |
/// "remove", "collection", "bookId"?, "origin": bool, "deleted":
/// bool}`, returns `{"action": "membership" | "create" | "delete",
/// "postCreateBooks"?: [bookId]}`.
pub fn push_plan(facts: &Value) -> Result<Value, String> {
    let collection = str_field(facts, "collection");
    let backend = Backend::of(collection);
    let book_id = facts.get("bookId").and_then(Value::as_str);

    match str_field(facts, "op") {
        "add" => {
            if bool_field(facts, "origin") && backend.account_level() {
                return Ok(json!({ "action": "membership" }));
            }

            let mut plan = Map::new();
            plan.insert("action".into(), "create".into());
            if backend == Backend::Google
                && let Some(book_id) = book_id
                && book_id != "myContacts"
            {
                plan.insert("postCreateBooks".into(), json!([book_id]));
            }
            Ok(Value::Object(plan))
        }
        "remove" => {
            if backend.account_level() && !bool_field(facts, "deleted") {
                Ok(json!({ "action": "membership" }))
            } else {
                Ok(json!({ "action": "delete" }))
            }
        }
        op => Err(format!("Unknown push op `{op}`")),
    }
}

fn str_field<'m>(value: &'m Value, key: &str) -> &'m str {
    value.get(key).and_then(Value::as_str).unwrap_or("")
}

fn bool_field(value: &Value, key: &str) -> bool {
    value.get(key).and_then(Value::as_bool).unwrap_or(false)
}

#[cfg(test)]
mod tests {
    use super::*;

    /// A listed matching ETag proves the remote unchanged, a listed
    /// mismatch disproves it, and an unlisted handle only counts on an
    /// incremental round.
    #[test]
    fn retry_unguarded_reads_the_listing() {
        let listed = json!({
            "listed": { "a.vcf": "e1" }, "complete": true,
            "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(retry_unguarded(&listed));

        let mismatch = json!({
            "listed": { "a.vcf": "e2" }, "complete": false,
            "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(!retry_unguarded(&mismatch));

        let unlisted_delta = json!({
            "listed": {}, "complete": false, "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(retry_unguarded(&unlisted_delta));

        let unlisted_complete = json!({
            "listed": {}, "complete": true, "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(!retry_unguarded(&unlisted_complete));

        let no_listing = json!({ "complete": false, "handle": "a.vcf", "ifMatch": "e1" });
        assert!(!retry_unguarded(&no_listing));

        let no_guard = json!({ "listed": {}, "complete": false, "handle": "a.vcf" });
        assert!(!retry_unguarded(&no_guard));
    }

    /// Members of the book are items; on an incremental round a known
    /// card that left the book rides as vanished, an unknown one is
    /// skipped.
    #[test]
    fn account_snapshot_projects_the_delta() {
        let facts = json!({
            "bookId": "b1",
            "complete": false,
            "changed": [
                { "handle": "c1", "books": ["b1", "b2"], "known": true },
                { "handle": "c2", "books": ["b2"], "known": true },
                { "handle": "c3", "books": ["b2"], "known": false },
            ],
            "vanished": ["gone"],
        });

        let reply = account_snapshot(&facts).unwrap();
        assert_eq!(reply["members"], json!([0]));
        assert_eq!(reply["vanished"], json!(["gone", "c2"]));

        let complete = json!({
            "bookId": "b1",
            "complete": true,
            "changed": [{ "handle": "c2", "books": ["b2"], "known": true }],
            "vanished": [],
        });
        assert_eq!(account_snapshot(&complete).unwrap()["vanished"], json!([]));
    }

    /// An origin add on an account-level backend patches memberships;
    /// a Google create aimed outside myContacts patches right after;
    /// removals patch memberships while other books hold the card.
    #[test]
    fn push_plan_decides_membership_vs_create_and_delete() {
        let origin_add = json!({
            "op": "add", "collection": "jmap://host/b1",
            "bookId": "b1", "origin": true, "deleted": false,
        });
        assert_eq!(push_plan(&origin_add).unwrap()["action"], "membership");

        let google_add = json!({
            "op": "add", "collection": "google://a@b/g1",
            "bookId": "g1", "origin": false, "deleted": false,
        });
        let plan = push_plan(&google_add).unwrap();
        assert_eq!(plan["action"], "create");
        assert_eq!(plan["postCreateBooks"], json!(["g1"]));

        let google_default = json!({
            "op": "add", "collection": "google://a@b/contacts",
            "bookId": "myContacts", "origin": false, "deleted": false,
        });
        assert!(
            push_plan(&google_default)
                .unwrap()
                .get("postCreateBooks")
                .is_none()
        );

        let carddav_add = json!({
            "op": "add", "collection": "https://dav/b1",
            "origin": true, "deleted": false,
        });
        assert_eq!(push_plan(&carddav_add).unwrap()["action"], "create");

        let membership_remove = json!({
            "op": "remove", "collection": "jmap://host/b1",
            "bookId": "b1", "origin": false, "deleted": false,
        });
        assert_eq!(
            push_plan(&membership_remove).unwrap()["action"],
            "membership"
        );

        let deleted_remove = json!({
            "op": "remove", "collection": "jmap://host/b1",
            "bookId": "b1", "origin": false, "deleted": true,
        });
        assert_eq!(push_plan(&deleted_remove).unwrap()["action"], "delete");
    }
}
