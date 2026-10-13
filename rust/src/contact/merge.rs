//! # Merge
//!
//! Three-way conflict merges, the multi-card union, and the list dedup
//! they lean on, all over vcard-rs's merge.

use core::str::FromStr;

use std::collections::BTreeMap;

use serde::Serialize;
use serde_json::{Value, to_value};
use vcard::{
    prop::{VcardPropKind, rev::REV, uid::UID},
    tree::{cst::VcardCst, merge::VcardMerge, prop::lens::VcardPropLens},
};

use crate::contact::fields::{ContactFields, sort_arrays, text_prop};

/// The model's list sections, compared across merged cards for the
/// conflict view's changed set.
const LIST_FIELDS: &[&str] = &[
    "phones",
    "emails",
    "addresses",
    "websites",
    "nicknames",
    "notes",
    "impps",
    "languages",
    "relations",
];

/// The model's single-valued fields the merge form offers alternative
/// values for, as dot paths.
const SINGLE_FIELDS: &[&str] = &[
    "displayName",
    "name.prefix",
    "name.given",
    "name.middle",
    "name.family",
    "name.suffix",
    "organization.company",
    "organization.department",
    "title",
    "role",
    "birthday",
    "anniversary",
    "gender.sex",
    "gender.identity",
];

/// The three-way merge of a conflicted push (see [`merge_conflict`]).
#[derive(Serialize)]
pub struct ConflictMerge {
    /// The number of same-field collisions the local side won.
    conflicts: usize,
    /// The merged card.
    vcard: String,
}

/// Merges a conflicted push three-way, local winning same-field collisions.
///
/// An update beats a removal, so nothing is silently lost. An empty base
/// is unknown: remote stands in, so the local edit rides through (merging
/// against `local` would let the remote win).
pub fn merge_conflict(base: &str, local: &str, remote: &str) -> Result<ConflictMerge, String> {
    let base = if base.is_empty() { remote } else { base };
    let base = VcardCst::parse(base).map_err(|err| format!("Invalid vCard: {err}"))?;
    let local = VcardCst::parse(local).map_err(|err| format!("Invalid vCard: {err}"))?;
    let remote = VcardCst::parse(remote).map_err(|err| format!("Invalid vCard: {err}"))?;

    let report = VcardMerge {
        base: &base,
        left: &local,
        right: &remote,
    }
    .merge();

    Ok(ConflictMerge {
        conflicts: report.conflicts.len(),
        vcard: report.merged.to_string(),
    })
}

/// The conflict form's inputs (see [`merge_conflict_form`]).
#[derive(Serialize)]
pub struct ConflictForm {
    /// The two candidates of every conflicted single field, by dot path.
    alternatives: BTreeMap<&'static str, Vec<String>>,
    /// The changed list sections, always empty but non-null, which turns
    /// the form's conflict mode on.
    changed: Vec<&'static str>,
    /// The merged card's field model, the form prefill.
    model: ContactFields,
    /// Whether nothing needs the user.
    resolved: bool,
    /// The merged card.
    vcard: String,
}

/// The conflict form of a both-sides-edited row, shaped as [`merge_cards`]'s.
///
/// The newer side by `REV` wins collisions, prefilling the form; each
/// single field both sides moved apart offers its two values, the
/// winner's first. Lists set-merge and never conflict.
pub fn merge_conflict_form(base: &str, local: &str, remote: &str) -> Result<ConflictForm, String> {
    let remote_newer = match (rev_seconds(remote), rev_seconds(local)) {
        (Some(remote), Some(local)) => remote > local,
        _ => false,
    };
    let (winner, loser) = if remote_newer {
        (remote, local)
    } else {
        (local, remote)
    };

    // NOTE: with no ancestor, merging against the loser lets the
    // winner dominate; a real base gives a true three-way.
    let base_for_merge = if base.is_empty() { loser } else { base };
    let merged = {
        let base_cst =
            VcardCst::parse(base_for_merge).map_err(|err| format!("Invalid vCard: {err}"))?;
        let left = VcardCst::parse(winner).map_err(|err| format!("Invalid vCard: {err}"))?;
        let right = VcardCst::parse(loser).map_err(|err| format!("Invalid vCard: {err}"))?;
        VcardMerge {
            base: &base_cst,
            left: &left,
            right: &right,
        }
        .merge()
        .merged
        .to_string()
    };

    let mut model = ContactFields::project(&merged)?;
    dedup_lists(&mut model);

    let base_model = if base.is_empty() {
        ContactFields::default()
    } else {
        ContactFields::project(base)?
    };
    let winner_model = ContactFields::project(winner)?;
    let loser_model = ContactFields::project(loser)?;

    let mut alternatives = BTreeMap::new();
    for field in SINGLE_FIELDS {
        let base_value = base_model.single_field(field);
        let winner_value = winner_model.single_field(field);
        let loser_value = loser_model.single_field(field);
        if winner_value != base_value && loser_value != base_value && winner_value != loser_value {
            alternatives.insert(*field, vec![winner_value.into(), loser_value.into()]);
        }
    }

    // NOTE: owned here so the sync pass and the tap-time form share one
    // auto-resolve verdict.
    let resolved = alternatives.is_empty();

    Ok(ConflictForm {
        alternatives,
        changed: Vec::new(),
        model,
        resolved,
        vcard: merged,
    })
}

/// The card's REV as a comparable instant, `None` when absent or
/// unparseable.
fn rev_seconds(vcard: &str) -> Option<i64> {
    let card = VcardCst::parse(vcard).ok()?;
    let version = card.version();
    card.props
        .iter()
        .find_map(|line| match VcardPropKind::from_str(line.name.get()) {
            Ok(VcardPropKind::Rev) => REV::decode(line, version).to_unix_seconds(),
            _ => None,
        })
}

/// Rewrites the card's UID: a plain copy is a new identity, and
/// CardDAV servers enforce UID uniqueness per collection. Every other
/// byte is preserved.
pub fn set_uid(vcard: &str, uid: &str) -> Result<String, String> {
    let mut card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;

    card.remove::<UID>();
    card.push(text_prop(VcardPropKind::Uid, vec![], uid));

    Ok(String::from_utf8_lossy(&card.to_bytes()).into_owned())
}

/// The union of several cards (see [`merge_cards`]).
#[derive(Serialize)]
pub struct CardsMerge {
    /// Every distinct value of every conflicted single field, by dot path,
    /// the prefilled one included.
    alternatives: BTreeMap<&'static str, Vec<String>>,
    /// The list sections the cards do not agree on, order aside.
    changed: Vec<&'static str>,
    /// The union's field model, the merge form prefill.
    model: ContactFields,
    /// The union document.
    vcard: String,
}

/// Merges several vCards into one union, by a three-way merge against an
/// empty base: a divergent single value keeps the first card's.
///
/// Unlike single-card editing, merging reformats: list entries matching
/// an earlier one case-insensitively on their identifying fields collapse.
pub fn merge_cards(cards: &[String]) -> Result<CardsMerge, String> {
    let Some(first) = cards.first() else {
        return Err("No cards to merge".into());
    };

    let version = VcardCst::parse(first.as_str())
        .map_err(|err| format!("Invalid vCard: {err}"))?
        .version();
    let empty = format!("BEGIN:VCARD\r\nVERSION:{}\r\nEND:VCARD\r\n", &*version);

    let mut merged = first.clone();
    for card in &cards[1..] {
        merged = {
            let base =
                VcardCst::parse(empty.as_str()).map_err(|err| format!("Invalid vCard: {err}"))?;
            let left =
                VcardCst::parse(merged.as_str()).map_err(|err| format!("Invalid vCard: {err}"))?;
            let right =
                VcardCst::parse(card.as_str()).map_err(|err| format!("Invalid vCard: {err}"))?;
            VcardMerge {
                base: &base,
                left: &left,
                right: &right,
            }
            .merge()
            .merged
            .to_string()
        };
    }

    let mut model = ContactFields::project(&merged)?;
    dedup_lists(&mut model);
    let models = cards
        .iter()
        .map(|card| ContactFields::project(card))
        .collect::<Result<Vec<_>, _>>()?;

    let mut alternatives = BTreeMap::new();
    for field in SINGLE_FIELDS {
        let mut values: Vec<&str> = Vec::new();
        for candidate in &models {
            let value = candidate.single_field(field);
            if !values.contains(&value) {
                values.push(value);
            }
        }
        // NOTE: present-vs-absent is a conflict too, so the empty state
        // rides along as a pickable alternative for a field one card
        // carries and another lacks.
        if values.len() > 1 && values.iter().any(|value| !value.is_empty()) {
            alternatives.insert(*field, values.into_iter().map(String::from).collect());
        }
    }

    let serialized = models
        .iter()
        .map(to_value)
        .collect::<Result<Vec<_>, _>>()
        .map_err(|err| format!("Unserializable field model: {err}"))?;
    let mut changed: Vec<&'static str> = Vec::new();
    for list in LIST_FIELDS {
        let mut seen: Vec<String> = Vec::new();
        for candidate in &serialized {
            let mut entries = candidate
                .get(*list)
                .cloned()
                .unwrap_or(Value::Array(Vec::new()));
            sort_arrays(&mut entries);
            let key = entries.to_string();
            if !seen.contains(&key) {
                seen.push(key);
            }
        }
        if seen.len() > 1 {
            changed.push(*list);
        }
    }

    Ok(CardsMerge {
        alternatives,
        changed,
        model,
        vcard: merged,
    })
}

/// Collapses the model's duplicated list entries, keeping the first of
/// each: object entries match on their identifying fields, string
/// entries on themselves, both trimmed and case-insensitive.
fn dedup_lists(model: &mut ContactFields) {
    dedup_entries(&mut model.phones, |phone| match_key(&[&phone.number]));
    dedup_entries(&mut model.emails, |email| match_key(&[&email.address]));
    dedup_entries(&mut model.relations, |relation| {
        match_key(&[&relation.value])
    });
    dedup_entries(&mut model.addresses, |adr| {
        match_key(&[
            &adr.pobox,
            &adr.ext,
            &adr.street,
            &adr.city,
            &adr.region,
            &adr.postcode,
            &adr.country,
        ])
    });
    for list in [
        &mut model.websites,
        &mut model.nicknames,
        &mut model.notes,
        &mut model.categories,
        &mut model.impps,
        &mut model.languages,
    ] {
        dedup_entries(list, |item| match_key(&[item]));
    }
}

/// Drops the list's entries whose match key duplicates an earlier
/// entry's.
fn dedup_entries<T>(entries: &mut Vec<T>, key: impl Fn(&T) -> String) {
    let mut seen: Vec<String> = Vec::new();
    entries.retain(|entry| {
        let key = key(entry);
        if seen.contains(&key) {
            false
        } else {
            seen.push(key);
            true
        }
    });
}

/// The identifying fields of an entry, trimmed, lowercased and joined
/// by the unit separator.
fn match_key(fields: &[&String]) -> String {
    fields
        .iter()
        .map(|field| field.trim().to_lowercase())
        .collect::<Vec<_>>()
        .join("\u{1f}")
}

#[cfg(test)]
mod tests {
    use serde::Serialize;
    use serde_json::{Value, json, to_value};

    use crate::{contact::merge::*, ffi::tests::assert_key_order};

    fn wire(reply: Result<impl Serialize, String>) -> Result<Value, String> {
        let reply = reply?;
        assert_key_order(&reply);
        Ok(to_value(reply).unwrap())
    }

    #[test]
    fn merge_cards_unions_and_reports_alternatives() {
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:John Doe\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:b\r\nFN:Jon Doe\r\n\
            TEL:+332222\r\nEMAIL:j@doe.org\r\nEND:VCARD\r\n";

        let merged = wire(merge_cards(&[a.to_string(), b.to_string()])).unwrap();

        assert_eq!(merged["model"]["displayName"], "John Doe");
        assert_eq!(merged["model"]["phones"].as_array().unwrap().len(), 2);
        assert_eq!(merged["model"]["emails"][0]["address"], "j@doe.org");
        assert_eq!(
            merged["alternatives"]["displayName"],
            json!(["John Doe", "Jon Doe"]),
        );
        assert!(merged["alternatives"].get("title").is_none());
        assert_eq!(merged["changed"], json!(["phones", "emails"]));
    }

    #[test]
    fn merge_cards_dedups_similar_list_items() {
        // NOTE: the same number under different types, and the same
        // address in a different case, prefill as one entry each.
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TEL;TYPE=home:+331111\r\nEMAIL:jane@doe.org\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:b\r\nFN:Jane\r\n\
            TEL;TYPE=work:+331111\r\nEMAIL:JANE@doe.org\r\n\
            EMAIL:j@doe.org\r\nEND:VCARD\r\n";

        let merged = wire(merge_cards(&[a.to_string(), b.to_string()])).unwrap();

        assert_eq!(merged["model"]["phones"].as_array().unwrap().len(), 1);
        assert_eq!(merged["model"]["emails"].as_array().unwrap().len(), 2);
        assert_eq!(merged["model"]["emails"][0]["address"], "jane@doe.org");
    }

    #[test]
    fn merge_conflict_reconciles_both_sides_against_the_base() {
        let base = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";
        let local = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TEL:+332222\r\nEND:VCARD\r\n";
        let remote = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Janet\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";

        let report = wire(merge_conflict(base, local, remote)).unwrap();
        let merged = report["vcard"].as_str().unwrap();

        assert!(merged.contains("FN:Janet\r\n"), "got: {merged}");
        assert!(merged.contains("TEL:+332222\r\n"), "got: {merged}");
        assert_eq!(report["conflicts"], 0);
    }

    #[test]
    fn merge_conflict_empty_base_lets_the_local_edit_win() {
        // NOTE: no captured base (the phone axis lost it), so the local
        // hub edit must win, not silently revert to the remote value.
        let local = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:ZZZ123\r\nEND:VCARD\r\n";
        let remote = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company2\r\nEND:VCARD\r\n";

        let report = wire(merge_conflict("", local, remote)).unwrap();
        let merged = report["vcard"].as_str().unwrap();

        assert!(merged.contains("ORG:ZZZ123\r\n"), "got: {merged}");
        assert!(!merged.contains("ORG:Company2\r\n"), "got: {merged}");
    }

    #[test]
    fn merge_conflict_form_surfaces_same_field_collisions_only() {
        // NOTE: ORG collides (both moved from Company20); TITLE moved on
        // the remote only, so it is auto-taken, not offered for review.
        let base = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company20\r\nTITLE:Dev\r\nREV:2026-07-11T17:00:00Z\r\nEND:VCARD\r\n";
        let local = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company30\r\nTITLE:Dev\r\nREV:2026-07-11T17:08:14Z\r\nEND:VCARD\r\n";
        let remote = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company31\r\nTITLE:Lead\r\nREV:2026-07-11T17:25:59Z\r\nEND:VCARD\r\n";

        let form = wire(merge_conflict_form(base, local, remote)).unwrap();

        assert_eq!(form["model"]["title"], "Lead");
        assert_eq!(form["model"]["organization"]["company"], "Company31");

        let alternatives = form["alternatives"].as_object().unwrap();
        assert_eq!(alternatives.len(), 1);
        let org = alternatives["organization.company"].as_array().unwrap();
        assert_eq!(org[0], "Company31");
        assert_eq!(org[1], "Company30");

        assert_eq!(form["resolved"], false);
        assert_eq!(form["changed"].as_array().unwrap().len(), 0);
    }

    #[test]
    fn merge_conflict_form_resolves_one_sided_changes() {
        // NOTE: each side moved a different field (local TITLE, remote
        // ORG), so no field collides and the merge takes both.
        let base = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company20\r\nTITLE:Dev\r\nREV:2026-07-11T17:00:00Z\r\nEND:VCARD\r\n";
        let local = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company20\r\nTITLE:Lead\r\nREV:2026-07-11T17:08:14Z\r\nEND:VCARD\r\n";
        let remote = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            ORG:Company30\r\nTITLE:Dev\r\nREV:2026-07-11T17:25:59Z\r\nEND:VCARD\r\n";

        let form = wire(merge_conflict_form(base, local, remote)).unwrap();

        assert!(form["alternatives"].as_object().unwrap().is_empty());
        assert_eq!(form["resolved"], true);
        assert_eq!(form["model"]["title"], "Lead");
        assert_eq!(form["model"]["organization"]["company"], "Company30");
    }

    #[test]
    fn merge_cards_flags_present_vs_absent_single_fields() {
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TITLE:Boss\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\nEND:VCARD\r\n";

        let merged = wire(merge_cards(&[a.to_string(), b.to_string()])).unwrap();

        let title = merged["alternatives"]["title"].as_array().unwrap();
        assert!(
            title.contains(&Value::String("Boss".into())),
            "got: {title:?}"
        );
        assert!(title.contains(&Value::String("".into())), "got: {title:?}");
    }

    #[test]
    fn set_uid_rewrites_only_the_uid() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:old\r\nFN:Jane\r\n\
            X-FOO:bar\r\nEND:VCARD\r\n";

        let fresh = set_uid(vcard, "new").unwrap();

        assert!(fresh.contains("UID:new\r\n"), "got: {fresh}");
        assert!(!fresh.contains("UID:old"), "got: {fresh}");
        assert!(fresh.contains("X-FOO:bar\r\n"), "got: {fresh}");
    }

    #[test]
    fn merge_cards_identical_documents_merge_clean() {
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:a\r\nFN:Jane\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";

        let merged = wire(merge_cards(&[a.to_string(), a.to_string()])).unwrap();

        assert_eq!(merged["model"]["phones"].as_array().unwrap().len(), 1);
        assert!(merged["alternatives"].as_object().unwrap().is_empty());
    }
}
