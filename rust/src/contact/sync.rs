//! # Contact sync policy
//!
//! The sync driver's policy, as pure decisions over JSON facts.
//!
//! The pimdir schema expresses membership, staging and divergence, so
//! only what no table says is decided here: a push change's backend call
//! ([`push_plan`]), an account-wide delta onto one book
//! ([`account_snapshot`]), a 412 retry unguarded ([`retry_unguarded`]).

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};

use crate::account::Backend;

/// Whether a 412-rejected push may retry unguarded.
///
/// Only when the last enumerate proves the handle unchanged: listed with
/// the staged ETag, or unlisted by a delta. Some servers (posteo's
/// SabreDAV) list ETags their If-Match never matches.
pub fn retry_unguarded(facts: &RetryFacts) -> RetryVerdict {
    let retry = match (&facts.listed, &facts.if_match) {
        (Some(listed), Some(if_match)) => match listed.get(&facts.handle) {
            Some(etag) => etag.as_ref() == Some(if_match),
            None => !facts.complete,
        },
        _ => false,
    };

    RetryVerdict { retry }
}

/// The facts [`retry_unguarded`] decides over.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct RetryFacts {
    /// The last enumerate's ETag per handle (null when unknown), absent
    /// when nothing was listed.
    #[serde(default)]
    listed: Option<BTreeMap<String, Option<String>>>,
    /// Whether the last enumerate listed the whole collection.
    complete: bool,
    /// The handle of the rejected push.
    #[serde(default)]
    handle: String,
    /// The staged base revision the push was guarded with, if any.
    #[serde(default)]
    if_match: Option<String>,
}

/// The decision of [`retry_unguarded`].
#[derive(Serialize)]
pub struct RetryVerdict {
    /// Whether the push may retry without If-Match.
    retry: bool,
}

/// Projects an account-wide delta (JMAP, Google) onto one book.
///
/// On an incremental round, a known card that left the book rides as
/// vanished.
pub fn account_snapshot(facts: &AccountSnapshotFacts) -> AccountSnapshot {
    let mut members: Vec<usize> = Vec::new();
    let mut vanished = facts.vanished.clone();

    for (index, card) in facts.changed.iter().enumerate() {
        let member = match &facts.book_id {
            Some(id) => card.books.contains(id),
            None => false,
        };

        if member {
            members.push(index);
        } else if !facts.complete && card.known {
            vanished.push(card.handle.clone());
        }
    }

    AccountSnapshot { members, vanished }
}

/// The account-wide delta [`account_snapshot`] projects.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountSnapshotFacts {
    /// The book to project onto, absent when unknown.
    #[serde(default)]
    book_id: Option<String>,
    /// Whether the delta is a complete listing rather than incremental.
    complete: bool,
    /// The changed cards.
    changed: Vec<AccountSnapshotCard>,
    /// The handles the delta reports gone.
    vanished: Vec<String>,
}

/// One changed card of an [`AccountSnapshotFacts`] delta.
#[derive(Deserialize)]
pub struct AccountSnapshotCard {
    /// The card's handle, absent until the card was fetched.
    #[serde(default)]
    handle: String,
    /// The ids of the books listing the card.
    books: Vec<String>,
    /// Whether the card is held locally.
    known: bool,
}

/// The decision of [`account_snapshot`].
#[derive(Serialize)]
pub struct AccountSnapshot {
    /// The indexes, in the delta's `changed`, of the book's members.
    members: Vec<usize>,
    /// The handles gone from the book: the delta's own, then the known
    /// cards that left it.
    vanished: Vec<String>,
}

/// Plans one push change as a membership patch, a create or a delete.
///
/// On an account-level backend, an add whose body lives on the account and
/// a removal not deleting the card are patches. A Google create lands in
/// myContacts, so one aimed at another group patches it right after.
pub fn push_plan(facts: &PushPlanFacts) -> Result<PushPlan, String> {
    let backend = Backend::of(&facts.collection);

    let action = match &facts.op {
        PushOp::Add if facts.origin && backend.account_level() => PushAction::Membership,
        PushOp::Add => PushAction::Create,
        PushOp::Remove if backend.account_level() && !facts.deleted => PushAction::Membership,
        PushOp::Remove => PushAction::Delete,
        PushOp::Other(op) => return Err(format!("Unknown push op `{op}`")),
    };

    let mut plan = PushPlan {
        action,
        post_create_books: None,
    };
    if plan.action == PushAction::Create
        && backend == Backend::Google
        && let Some(book_id) = &facts.book_id
        && book_id != "myContacts"
    {
        plan.post_create_books = Some(vec![book_id.clone()]);
    }

    Ok(plan)
}

/// The push change [`push_plan`] plans.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushPlanFacts {
    /// What the change does.
    op: PushOp,
    /// The collection URL the change targets.
    collection: String,
    /// The book id behind the collection, absent when unknown.
    #[serde(default)]
    book_id: Option<String>,
    /// Whether a pending create carries an origin (its body lives on
    /// the account already).
    origin: bool,
    /// Whether a staged removal deletes the card itself.
    deleted: bool,
}

/// The operation of a [`PushPlanFacts`] change.
#[derive(Deserialize)]
#[serde(rename_all = "lowercase")]
pub enum PushOp {
    /// A pending create.
    Add,
    /// A staged removal.
    Remove,
    /// Any other op, refused by [`push_plan`].
    #[serde(untagged)]
    Other(String),
}

/// The decision of [`push_plan`].
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PushPlan {
    /// The backend call the change becomes.
    action: PushAction,
    /// The books a Google create patches its membership into right
    /// after; absent otherwise.
    #[serde(skip_serializing_if = "Option::is_none")]
    post_create_books: Option<Vec<String>>,
}

/// The backend call of a [`PushPlan`].
#[derive(PartialEq, Serialize)]
#[serde(rename_all = "lowercase")]
pub enum PushAction {
    /// A membership patch on the account-level card.
    Membership,
    /// A genuine create.
    Create,
    /// The card's deletion.
    Delete,
}

#[cfg(test)]
mod tests {
    use serde::{Serialize, de::DeserializeOwned};
    use serde_json::{Value, from_value, json, to_value};

    use crate::{contact::sync::*, ffi::tests::assert_key_order};

    fn parse<T: DeserializeOwned>(facts: Value) -> T {
        from_value(facts).unwrap()
    }

    fn wire(decision: &impl Serialize) -> Value {
        assert_key_order(decision);
        to_value(decision).unwrap()
    }

    /// A listed matching ETag proves the remote unchanged, a listed
    /// mismatch disproves it, and an unlisted handle only counts on an
    /// incremental round.
    #[test]
    fn retry_unguarded_reads_the_listing() {
        let listed = json!({
            "listed": { "a.vcf": "e1" }, "complete": true,
            "handle": "a.vcf", "ifMatch": "e1",
        });
        assert_eq!(
            wire(&retry_unguarded(&parse(listed))),
            json!({ "retry": true })
        );

        let mismatch = json!({
            "listed": { "a.vcf": "e2" }, "complete": false,
            "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(!retry_unguarded(&parse(mismatch)).retry);

        let unlisted_delta = json!({
            "listed": {}, "complete": false, "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(retry_unguarded(&parse(unlisted_delta)).retry);

        let unlisted_complete = json!({
            "listed": {}, "complete": true, "handle": "a.vcf", "ifMatch": "e1",
        });
        assert!(!retry_unguarded(&parse(unlisted_complete)).retry);

        let no_listing = json!({ "complete": false, "handle": "a.vcf", "ifMatch": "e1" });
        assert!(!retry_unguarded(&parse(no_listing)).retry);

        let no_guard = json!({ "listed": {}, "complete": false, "handle": "a.vcf" });
        assert!(!retry_unguarded(&parse(no_guard)).retry);
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

        let reply = wire(&account_snapshot(&parse(facts)));
        assert_eq!(reply["members"], json!([0]));
        assert_eq!(reply["vanished"], json!(["gone", "c2"]));

        let complete = json!({
            "bookId": "b1",
            "complete": true,
            "changed": [{ "handle": "c2", "books": ["b2"], "known": true }],
            "vanished": [],
        });
        assert_eq!(
            wire(&account_snapshot(&parse(complete)))["vanished"],
            json!([])
        );
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
        assert_eq!(
            wire(&push_plan(&parse(origin_add)).unwrap())["action"],
            "membership"
        );

        let google_add = json!({
            "op": "add", "collection": "google://a@b/g1",
            "bookId": "g1", "origin": false, "deleted": false,
        });
        let plan = wire(&push_plan(&parse(google_add)).unwrap());
        assert_eq!(plan["action"], "create");
        assert_eq!(plan["postCreateBooks"], json!(["g1"]));

        let google_default = json!({
            "op": "add", "collection": "google://a@b/contacts",
            "bookId": "myContacts", "origin": false, "deleted": false,
        });
        assert!(
            wire(&push_plan(&parse(google_default)).unwrap())
                .get("postCreateBooks")
                .is_none()
        );

        let carddav_add = json!({
            "op": "add", "collection": "https://dav/b1",
            "origin": true, "deleted": false,
        });
        assert_eq!(
            wire(&push_plan(&parse(carddav_add)).unwrap())["action"],
            "create"
        );

        let membership_remove = json!({
            "op": "remove", "collection": "jmap://host/b1",
            "bookId": "b1", "origin": false, "deleted": false,
        });
        assert_eq!(
            wire(&push_plan(&parse(membership_remove)).unwrap())["action"],
            "membership"
        );

        let deleted_remove = json!({
            "op": "remove", "collection": "jmap://host/b1",
            "bookId": "b1", "origin": false, "deleted": true,
        });
        assert_eq!(
            wire(&push_plan(&parse(deleted_remove)).unwrap())["action"],
            "delete"
        );
    }
}
