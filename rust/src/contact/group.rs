//! # Groups
//!
//! The merged-view grouping: clusters the replica pool into contacts
//! and reports the duplicate review's group facts.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::contact::fields::strings;

/// The replica pool [`group_contacts`] groups, as Java sends it.
#[derive(Deserialize)]
pub struct ReplicaPool {
    /// The replicas, in pool order.
    replicas: Vec<PoolReplica>,
    /// The link rows: a natural key to the cluster the user linked it
    /// into (a null cluster leaves the key unbound).
    #[serde(default)]
    links: HashMap<String, Option<String>>,
    /// The refs of the replicas the user detached.
    #[serde(default)]
    detached: Vec<String>,
}

/// One replica of a [`ReplicaPool`], its strings read verbatim (refs
/// carry meaningful bytes) and empty when Java omits them.
#[derive(Deserialize)]
pub struct PoolReplica {
    /// The replica reference.
    #[serde(rename = "ref", default)]
    reference: String,
    /// The vCard UID.
    #[serde(default)]
    uid: String,
    /// The display name.
    #[serde(default)]
    name: String,
    /// The card id, standing in for a nameless card's name.
    #[serde(default)]
    id: String,
}

/// The merged contacts [`group_contacts`] found.
#[derive(Serialize)]
pub struct ContactGroups {
    /// The groups, sorted by their primary replica's display name.
    groups: Vec<ContactGroup>,
}

/// One merged contact of [`ContactGroups`].
#[derive(Serialize)]
pub struct ContactGroup {
    /// The group key: a cluster, `uid\0<uid>` or `ref\0<ref>`.
    key: String,
    /// The indexes of the members in the pool, in pool order.
    replicas: Vec<usize>,
}

/// Groups the replica pool into merged contacts (docs/merged-view.md),
/// sorted by their primary replica's display name, case-insensitive.
///
/// A replica's natural key is its UID, or its ref when it has none or was
/// detached; a link row rebinds a natural key to the user's cluster.
pub fn group_contacts(pool: &ReplicaPool) -> ContactGroups {
    let replicas = &pool.replicas;
    let detached = strings(&pool.detached);

    let mut order: Vec<String> = Vec::new();
    let mut members: HashMap<String, Vec<usize>> = HashMap::new();
    for (index, replica) in replicas.iter().enumerate() {
        let reference = &replica.reference;
        let uid = &replica.uid;

        let natural = if uid.is_empty() || detached.contains(reference) {
            format!("ref\0{reference}")
        } else {
            format!("uid\0{uid}")
        };
        let key = pool
            .links
            .get(&natural)
            .and_then(Option::as_deref)
            .unwrap_or(&natural)
            .to_string();

        members
            .entry(key.clone())
            .or_insert_with(|| {
                order.push(key);
                Vec::new()
            })
            .push(index);
    }

    order.sort_by_cached_key(|key| {
        let primary = &replicas[members[key][0]];
        let shown = if primary.name.is_empty() {
            &primary.id
        } else {
            &primary.name
        };
        shown.to_lowercase()
    });

    let groups = order
        .into_iter()
        .map(|key| ContactGroup {
            replicas: members[&key].clone(),
            key,
        })
        .collect();
    ContactGroups { groups }
}

/// One member of a duplicate review group, as Java sends it.
#[derive(Deserialize)]
pub struct DuplicateMember {
    /// The replica reference, read verbatim.
    #[serde(rename = "ref", default)]
    reference: String,
    /// The addressbook URL.
    #[serde(default)]
    book: String,
}

/// The duplicate review's facts of one group (see [`duplicate_group`]).
#[derive(Serialize)]
pub struct DuplicateGroup {
    /// The dismissal key.
    key: String,
    /// Whether Link may be offered.
    linkable: bool,
}

/// The duplicate review's facts of one group.
///
/// The dismissal key's format is frozen, the store persisting it. Link
/// needs two cards each in its own book: a same-book pair sharing a UID
/// would collide.
pub fn duplicate_group(members: &[DuplicateMember]) -> DuplicateGroup {
    let mut refs: Vec<&str> = Vec::new();
    let mut ref_by_book: HashMap<&str, &str> = HashMap::new();
    let mut linkable = true;
    for member in members {
        let reference = member.reference.as_str();
        let book = member.book.as_str();

        if !refs.contains(&reference) {
            refs.push(reference);
        }
        match ref_by_book.get(book) {
            Some(other) if *other != reference => linkable = false,
            Some(_) => {}
            None => {
                ref_by_book.insert(book, reference);
            }
        }
    }

    linkable = linkable && refs.len() >= 2;
    refs.sort();
    DuplicateGroup {
        key: refs.join("\u{1f}"),
        linkable,
    }
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, from_value, json, to_value};

    use crate::{contact::group::*, ffi::tests::assert_key_order};

    fn grouped(input: Value) -> Value {
        let groups = group_contacts(&from_value(input).unwrap());
        assert_key_order(&groups);
        to_value(groups).unwrap()
    }

    fn facts(members: Value) -> Value {
        let members: Vec<DuplicateMember> = from_value(members).unwrap();
        let facts = duplicate_group(&members);
        assert_key_order(&facts);
        to_value(facts).unwrap()
    }

    /// Replicas group by UID, a detached one by its ref, a link rebinds a
    /// natural key, and groups sort by primary display name (or id).
    #[test]
    fn group_contacts_links_detaches_and_sorts() {
        let input = json!({
            "replicas": [
                { "ref": "a\nk1", "uid": "u1", "name": "zoe", "id": "k1" },
                { "ref": "b\nk2", "uid": "u1", "name": "Zoe", "id": "k2" },
                { "ref": "c\nk3", "uid": "u1", "name": "Ann", "id": "k3" },
                { "ref": "d\nk4", "uid": "", "name": "", "id": "Bob-id" },
                { "ref": "e\nk5", "uid": "u2", "name": "Solo", "id": "k5" },
            ],
            "links": { "uid\u{0}u2": "cluster-1", "ref\u{0}d\nk4": "cluster-1" },
            "detached": ["c\nk3"],
        });

        let reply = grouped(input);
        let groups = reply["groups"].as_array().unwrap();

        // NOTE: Ann (detached), then the linked Bob+Solo cluster, then
        // Zoe.
        assert_eq!(groups.len(), 3);
        assert_eq!(groups[0]["key"], "ref\u{0}c\nk3");
        assert_eq!(groups[0]["replicas"], json!([2]));
        assert_eq!(groups[1]["key"], "cluster-1");
        assert_eq!(groups[1]["replicas"], json!([3, 4]));
        assert_eq!(groups[2]["key"], "uid\u{0}u1");
        assert_eq!(groups[2]["replicas"], json!([0, 1]));
    }

    /// The dismissal key is the sorted distinct refs joined; Link
    /// needs at least two cards, each in its own addressbook.
    #[test]
    fn duplicate_group_keys_and_gates_link() {
        let linkable = json!([
            { "ref": "b", "book": "book2" },
            { "ref": "a", "book": "book1" },
            { "ref": "a", "book": "book1" },
        ]);
        let reply = facts(linkable);
        assert_eq!(reply["key"], "a\u{1f}b");
        assert_eq!(reply["linkable"], true);

        let same_book = json!([
            { "ref": "a", "book": "book1" },
            { "ref": "b", "book": "book1" },
        ]);
        assert_eq!(facts(same_book)["linkable"], false);

        let single = json!([{ "ref": "a", "book": "book1" }]);
        assert_eq!(facts(single)["linkable"], false);
    }
}
