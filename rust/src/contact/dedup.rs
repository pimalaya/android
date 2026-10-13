//! # Duplicates
//!
//! Union-find duplicate detection for the semi-automatic duplicate
//! remover.

use std::collections::HashMap;

use serde::{Deserialize, Serialize};

use crate::contact::fields::ContactFields;

/// One card offered to [`find_duplicates`], as Java sends it.
#[derive(Deserialize)]
pub struct DuplicateCard {
    /// The card's replica reference.
    #[serde(rename = "ref", default)]
    pub reference: String,
    /// The card's vCard.
    #[serde(default)]
    pub vcard: String,
}

/// The likely-duplicate groups [`find_duplicates`] found.
#[derive(Serialize)]
pub struct Duplicates<'a> {
    /// The groups, in union-find root order.
    groups: Vec<DuplicateSet<'a>>,
}

/// One group of likely duplicates.
#[derive(Serialize)]
pub struct DuplicateSet<'a> {
    /// The distinct match kinds that joined the group (`email`, `phone`,
    /// `name`).
    reasons: Vec<&'static str>,
    /// The members' references, in input order.
    refs: Vec<&'a str>,
}

/// Finds groups of likely duplicates for the semi-automatic remover.
///
/// Cards group on an exact normalized email, phone or name, never fuzzy:
/// false positives are the killer here. Cards sharing a UID are one
/// contact and never group with each other alone.
pub fn find_duplicates(cards: &[DuplicateCard]) -> Result<Duplicates<'_>, String> {
    let mut uids: Vec<String> = Vec::new();
    let mut keys: Vec<Vec<(&'static str, String)>> = Vec::new();
    for card in cards {
        let model = ContactFields::project(&card.vcard)?;
        uids.push(model.single_field("uid").to_string());
        keys.push(match_keys(&model));
    }

    let mut parent: Vec<usize> = (0..cards.len()).collect();
    let mut reasons: Vec<(usize, &'static str)> = Vec::new();
    let mut seen: HashMap<String, usize> = HashMap::new();
    for (index, card_keys) in keys.iter().enumerate() {
        for (reason, key) in card_keys {
            match seen.get(key.as_str()) {
                None => {
                    seen.insert(key.clone(), index);
                }
                Some(&other) => {
                    let (a, b) = (find(&mut parent, index), find(&mut parent, other));
                    if a != b {
                        parent[a] = b;
                    }
                    reasons.push((find(&mut parent, index), reason));
                }
            }
        }
    }

    let mut groups: HashMap<usize, Vec<usize>> = HashMap::new();
    for index in 0..cards.len() {
        groups
            .entry(find(&mut parent, index))
            .or_default()
            .push(index);
    }

    let mut out = Vec::new();
    let mut roots: Vec<usize> = groups.keys().copied().collect();
    roots.sort_unstable();
    for root in roots {
        let members = &groups[&root];
        if members.len() < 2 {
            continue;
        }

        // NOTE: members sharing a UID are one logical contact, so a
        // group must span at least two distinct contacts to matter.
        let mut contacts: Vec<&str> = Vec::new();
        for &member in members {
            let uid = uids[member].as_str();
            let key = if uid.is_empty() {
                cards[member].reference.as_str()
            } else {
                uid
            };
            if !contacts.contains(&key) {
                contacts.push(key);
            }
        }
        if contacts.len() < 2 {
            continue;
        }

        let refs: Vec<&str> = members
            .iter()
            .map(|&member| cards[member].reference.as_str())
            .collect();
        let mut group_reasons: Vec<&'static str> = Vec::new();
        for (reason_root, reason) in &reasons {
            if find(&mut parent, *reason_root) == root && !group_reasons.contains(reason) {
                group_reasons.push(reason);
            }
        }
        out.push(DuplicateSet {
            reasons: group_reasons,
            refs,
        });
    }

    Ok(Duplicates { groups: out })
}

/// The card's exact-match keys: every email, every phone, the name.
fn match_keys(model: &ContactFields) -> Vec<(&'static str, String)> {
    let mut keys = Vec::new();

    for email in &model.emails {
        let address = email.address.trim().to_lowercase();
        if !address.is_empty() {
            keys.push(("email", format!("e\u{1f}{address}")));
        }
    }

    for phone in &model.phones {
        let digits: String = phone
            .number
            .trim()
            .chars()
            .filter(char::is_ascii_digit)
            .collect();
        // NOTE: the trailing digits absorb a present-vs-absent country
        // code (the AOSP aggregator's rule); short numbers match whole.
        if digits.len() >= 6 {
            let tail = &digits[digits.len().saturating_sub(8)..];
            keys.push(("phone", format!("p\u{1f}{tail}")));
        }
    }

    let name = model
        .display_name()
        .to_lowercase()
        .split_whitespace()
        .collect::<Vec<_>>()
        .join(" ");
    if !name.is_empty() {
        keys.push(("name", format!("n\u{1f}{name}")));
    }

    keys
}

/// Union-find root lookup with path compression.
fn find(parent: &mut [usize], mut index: usize) -> usize {
    while parent[index] != index {
        parent[index] = parent[parent[index]];
        index = parent[index];
    }
    index
}

#[cfg(test)]
mod tests {
    use serde_json::{json, to_value};

    use crate::{contact::dedup::*, ffi::tests::assert_key_order};

    #[test]
    fn find_duplicates_groups_matches_and_skips_uid_linked() {
        let cards = vec![
            (
                "a".to_string(),
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane Doe\r\n\
                    TEL:+33 6 12 34 56 78\r\nEND:VCARD\r\n"
                    .to_string(),
            ),
            (
                "b".to_string(),
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:b\r\nFN:Janette\r\n\
                    TEL:06 12 34 56 78\r\nEND:VCARD\r\n"
                    .to_string(),
            ),
            // NOTE: same email, but one contact already (shared UID).
            (
                "c".to_string(),
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:x\r\nFN:Bob\r\n\
                    EMAIL:bob@example.org\r\nEND:VCARD\r\n"
                    .to_string(),
            ),
            (
                "d".to_string(),
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:x\r\nFN:Bobby\r\n\
                    EMAIL:BOB@example.org\r\nEND:VCARD\r\n"
                    .to_string(),
            ),
        ];

        let cards: Vec<DuplicateCard> = cards
            .into_iter()
            .map(|(reference, vcard)| DuplicateCard { reference, vcard })
            .collect();

        let found = find_duplicates(&cards).unwrap();
        assert_key_order(&found);
        let found = to_value(found).unwrap();
        let groups = found["groups"].as_array().unwrap();

        assert_eq!(groups.len(), 1, "got: {groups:?}");
        assert_eq!(groups[0]["refs"], json!(["a", "b"]));
        assert_eq!(groups[0]["reasons"], json!(["phone"]));
    }
}
