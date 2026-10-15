//! # Edit form
//!
//! The edit form's type tables, which the spinners are built from, and
//! the view and summary helpers that project the field model onto the form.

use serde::Serialize;

use crate::contact::fields::{
    ContactEmail, ContactFields, ContactGender, ContactPhone, ContactRelation, strings, trimmed,
};

/// Spinner position to vCard TYPE set of the phone rows.
const PHONE_TYPES: [&[&str]; 8] = [
    &["cell"],
    &["home"],
    &["work"],
    &["cell", "work"],
    &["fax", "home"],
    &["fax", "work"],
    &["pager"],
    &[],
];

/// Spinner position to vCard TYPE set of the email and address rows.
const HOME_WORK_OTHER: [&[&str]; 3] = [&["home"], &["work"], &[]];

/// Spinner position to RELATED type set.
const RELATION_TYPES: [&[&str]; 8] = [
    &["spouse"],
    &["child"],
    &["parent"],
    &["sibling"],
    &["friend"],
    &["colleague"],
    &["emergency"],
    &[],
];

/// Spinner position to GENDER sex code (RFC 6350 §6.2.7); position 0
/// means unset.
const GENDER_SEXES: [&str; 6] = ["", "M", "F", "O", "N", "U"];

/// The edit form's view support of one field model (see [`form_view`]).
#[derive(Serialize)]
pub struct FormView {
    /// Each address row's spinner position and summary.
    addresses: Vec<FormAddress>,
    /// The anniversary parsed for the picker, absent unless a full date.
    #[serde(skip_serializing_if = "Option::is_none")]
    anniversary: Option<FormDate>,
    /// The birthday parsed for the picker, absent unless a full date.
    #[serde(skip_serializing_if = "Option::is_none")]
    birthday: Option<FormDate>,
    /// Each email row's spinner position.
    emails: Vec<usize>,
    /// The gender row, absent when the model has no gender.
    #[serde(skip_serializing_if = "Option::is_none")]
    gender: Option<FormGender>,
    /// The composed name summary.
    name: String,
    /// The organization summary.
    organization: String,
    /// Each phone row's spinner position.
    phones: Vec<usize>,
    /// Each relation row's spinner position.
    relations: Vec<usize>,
}

/// One address row of a [`FormView`].
#[derive(Serialize)]
pub struct FormAddress {
    /// The type spinner position.
    index: usize,
    /// The row summary.
    summary: String,
}

/// A model date as picker parts, the month 1-based.
#[derive(Serialize)]
pub struct FormDate {
    /// The day of the month.
    day: i64,
    /// The month, 1-based.
    month: i64,
    /// The year.
    year: i64,
}

/// The gender row of a [`FormView`].
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct FormGender {
    /// The trimmed free-text identity.
    identity: String,
    /// The gender_types position of the sex code.
    sex_index: usize,
}

/// The edit form's view support, computed from the field model in one
/// pass.
///
/// The Java form only builds views from it and localizes the labels the
/// spinner positions address.
pub fn form_view(model: &ContactFields) -> FormView {
    let name = name_summary(model);
    let organization = organization_summary(model);

    let gender = model.gender.as_ref().map(|gender| FormGender {
        identity: gender.identity.trim().to_string(),
        sex_index: gender_sex_index(&gender.sex),
    });

    let birthday = date_parts(trimmed(&model.birthday));
    let anniversary = date_parts(trimmed(&model.anniversary));

    let phones = model
        .phones
        .iter()
        .map(|entry| phone_type_index(&entry.types))
        .collect();
    let emails = model
        .emails
        .iter()
        .map(|entry| type_index(&entry.types, &HOME_WORK_OTHER))
        .collect();
    let relations = model
        .relations
        .iter()
        .map(|entry| type_index(&entry.types, &RELATION_TYPES))
        .collect();

    let addresses = model
        .addresses
        .iter()
        .map(|entry| FormAddress {
            index: type_index(&entry.types, &HOME_WORK_OTHER),
            summary: address_summary([&entry.street, &entry.city, &entry.postcode, &entry.country]),
        })
        .collect();

    FormView {
        addresses,
        anniversary,
        birthday,
        emails,
        gender,
        name,
        organization,
        phones,
        relations,
    }
}

/// One typed entry saved from a dialog (see [`form_entry`]).
#[derive(Serialize)]
#[serde(untagged)]
pub enum FormEntry {
    /// A TEL entry.
    Phone(ContactPhone),
    /// An EMAIL entry.
    Email(ContactEmail),
    /// A RELATED entry.
    Relation(ContactRelation),
    /// An address's TYPE set alone.
    Address { types: Vec<String> },
    /// A GENDER with a sex code or an identity.
    Gender(ContactGender),
    /// An unset GENDER, an empty object.
    Unset {},
}

/// One typed entry saved from a dialog, its TYPE set drawn from the
/// spinner position.
///
/// `address` returns the TYPE set alone, the dialog owning the other
/// components; `gender` is empty when sex and identity are both unset.
pub fn form_entry(kind: &str, index: i64, value: &str, pref: bool) -> Result<FormEntry, String> {
    let position = index.max(0) as usize;

    let entry = match kind {
        "phone" => FormEntry::Phone(ContactPhone {
            number: value.to_string(),
            pref,
            types: table_types(&PHONE_TYPES, position),
        }),
        "email" => FormEntry::Email(ContactEmail {
            address: value.to_string(),
            pref,
            types: table_types(&HOME_WORK_OTHER, position),
        }),
        "relation" => FormEntry::Relation(ContactRelation {
            pref,
            types: table_types(&RELATION_TYPES, position),
            value: value.to_string(),
        }),
        "address" => FormEntry::Address {
            types: table_types(&HOME_WORK_OTHER, position),
        },
        "gender" => {
            let sex = GENDER_SEXES[position.min(GENDER_SEXES.len() - 1)];
            let identity = value.trim();
            if sex.is_empty() && identity.is_empty() {
                return Ok(FormEntry::Unset {});
            }
            FormEntry::Gender(ContactGender {
                identity: identity.to_string(),
                sex: sex.to_string(),
            })
        }
        kind => return Err(format!("Unknown form entry kind `{kind}`")),
    };

    Ok(entry)
}

/// Each spinner position's vCard TYPE set by kind (the sex code alone
/// for `gender`).
///
/// The single owner of that order: Java builds the spinners from it,
/// naming each set, so a position always reads back as its set.
pub fn card_type_order(kind: &str) -> Vec<Vec<&'static str>> {
    let table: &[&[&str]] = match kind {
        "phone" => &PHONE_TYPES,
        "email" | "address" => &HOME_WORK_OTHER,
        "relation" => &RELATION_TYPES,
        "gender" => return GENDER_SEXES.iter().map(|code| vec![*code]).collect(),
        _ => &[],
    };
    table.iter().map(|set| set.to_vec()).collect()
}

/// One picked date as the `yyyy-mm-dd` BDAY and ANNIVERSARY expect, the
/// month 1-based.
pub fn form_date(year: i64, month: i64, day: i64) -> String {
    format!("{year:04}-{month:02}-{day:02}")
}

/// The name parts composed for the form's Name row (the display name
/// has its own row).
pub fn name_summary(model: &ContactFields) -> String {
    let mut composed = Vec::new();
    if let Some(name) = &model.name {
        for part in [
            &name.prefix,
            &name.given,
            &name.middle,
            &name.family,
            &name.suffix,
        ] {
            let part = part.trim();
            if !part.is_empty() {
                composed.push(part);
            }
        }
    }
    composed.join(" ")
}

/// The organization summary: the company and the job title, joined by
/// a middot.
fn organization_summary(model: &ContactFields) -> String {
    let mut parts = Vec::new();
    for key in ["organization.company", "title"] {
        let value = model.single_field(key);
        if !value.is_empty() {
            parts.push(value);
        }
    }
    parts.join(" · ")
}

/// The address row summary: the first non-empty component in display
/// priority (street, city, postcode, country), cut at its first line
/// (the street stands for the block).
fn address_summary(parts: [&String; 4]) -> String {
    for part in parts {
        let part = part.trim();
        if !part.is_empty() {
            return part.lines().next().unwrap_or("").to_string();
        }
    }
    String::new()
}

/// The gender_types position of a GENDER sex code, 0 (unset) for
/// anything unknown.
fn gender_sex_index(sex: &str) -> usize {
    let sex = sex.trim();
    GENDER_SEXES
        .iter()
        .skip(1)
        .position(|code| code.eq_ignore_ascii_case(sex))
        .map(|at| at + 1)
        .unwrap_or(0)
}

/// The phone_types position of a TEL entry's TYPE set: the fax and
/// cell pairings first, then the plain types, the untyped "other" row
/// last.
fn phone_type_index(types: &[String]) -> usize {
    let types = strings(types);
    let has = |wanted: &str| types.iter().any(|t| t == wanted);

    if has("fax") {
        return if has("home") { 4 } else { 5 };
    }
    if has("cell") {
        return if has("work") { 3 } else { 0 };
    }
    if has("pager") {
        return 6;
    }
    if has("work") {
        return 2;
    }
    if has("home") {
        return 1;
    }
    7
}

/// The spinner position of an entry's TYPE set against a table: the
/// first row whose lead type the entry carries, the last (untyped)
/// row otherwise.
fn type_index(types: &[String], table: &[&[&str]]) -> usize {
    let types = strings(types);
    for (index, row) in table.iter().enumerate() {
        if let Some(lead) = row.first()
            && types.iter().any(|t| t == lead)
        {
            return index;
        }
    }
    table.len() - 1
}

/// The TYPE set at a spinner position; anything past the end reads as
/// the last (untyped) row.
fn table_types(table: &[&[&str]], position: usize) -> Vec<String> {
    let row = table[position.min(table.len() - 1)];
    row.iter().map(|item| item.to_string()).collect()
}

/// A `yyyy-mm-dd` model value parsed into picker parts (1-based
/// month), None when absent or not a full numeric date.
fn date_parts(value: &str) -> Option<FormDate> {
    let parts: Vec<&str> = value.split('-').collect();
    let [year, month, day] = parts[..] else {
        return None;
    };

    let year: i64 = year.parse().ok()?;
    let month: i64 = month.parse().ok()?;
    let day: i64 = day.parse().ok()?;
    Some(FormDate { day, month, year })
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, from_value, json, to_value};

    use crate::{contact::form::*, ffi::tests::assert_key_order};

    fn view(model: Value) -> Value {
        let view = form_view(&from_value(model).unwrap());
        assert_key_order(&view);
        to_value(view).unwrap()
    }

    fn entry(kind: &str, index: i64, value: &str, pref: bool) -> Result<Value, String> {
        let entry = form_entry(kind, index, value, pref)?;
        assert_key_order(&entry);
        Ok(to_value(entry).unwrap())
    }

    /// The form view composes the summaries, positions every typed
    /// entry's spinner and parses the picker dates in one pass.
    #[test]
    fn form_view_supports_the_whole_page() {
        let model = json!({
            "name": { "prefix": "Dr", "given": "Jane", "family": "Doe" },
            "organization": { "company": "ACME" },
            "title": "Boss",
            "gender": { "sex": "f", "identity": " she " },
            "birthday": "1985-04-12",
            "anniversary": "not-a-date",
            "phones": [
                { "number": "1", "types": ["fax", "home"] },
                { "number": "2", "types": [] },
            ],
            "emails": [{ "address": "a@b", "types": ["work"] }],
            "relations": [{ "value": "Bob", "types": ["colleague"] }],
            "addresses": [
                { "street": "12 Main St\nBuilding B", "types": ["home"] },
                { "city": "Paris", "types": [] },
            ],
        });

        let view = view(model);

        assert_eq!(view["name"], "Dr Jane Doe");
        assert_eq!(view["organization"], "ACME · Boss");
        assert_eq!(view["gender"]["sexIndex"], 2);
        assert_eq!(view["gender"]["identity"], "she");
        assert_eq!(
            view["birthday"],
            json!({ "year": 1985, "month": 4, "day": 12 })
        );
        assert!(view.get("anniversary").is_none());
        assert_eq!(view["phones"], json!([4, 7]));
        assert_eq!(view["emails"], json!([1]));
        assert_eq!(view["relations"], json!([5]));
        assert_eq!(view["addresses"][0]["index"], 0);
        assert_eq!(view["addresses"][0]["summary"], "12 Main St");
        assert_eq!(view["addresses"][1]["index"], 2);
        assert_eq!(view["addresses"][1]["summary"], "Paris");
    }

    /// Every form entry kind resolves its spinner position to the
    /// documented shape; the gender pair empties to an empty object.
    #[test]
    fn form_entry_builds_every_kind() {
        let phone = entry("phone", 3, "0612", true).unwrap();
        assert_eq!(
            phone,
            json!({ "number": "0612", "types": ["cell", "work"], "pref": true }),
        );

        let email = entry("email", 99, "a@b", false).unwrap();
        assert_eq!(email["types"], json!([]));

        let relation = entry("relation", 0, "Bob", false).unwrap();
        assert_eq!(relation["value"], "Bob");
        assert_eq!(relation["types"], json!(["spouse"]));

        let address = entry("address", 1, "", false).unwrap();
        assert_eq!(address, json!({ "types": ["work"] }));

        let gender = entry("gender", 2, "she", false).unwrap();
        assert_eq!(gender, json!({ "sex": "F", "identity": "she" }));
        assert_eq!(entry("gender", 0, "  ", false).unwrap(), json!({}));

        assert!(entry("website", 0, "", false).is_err());
        assert_eq!(form_date(1985, 4, 2), "1985-04-02");
    }
}
