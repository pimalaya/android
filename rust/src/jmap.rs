//! JMAP ContactCard (RFC 9610) to vCard projection and back, via
//! vcard-rs's JSContact conversion (RFC 9555). The ContactCard's
//! JSContact payload (RFC 9553) converts losslessly: unmapped members
//! and properties ride the vCardProps / JSPROP escape hatches both
//! ways, so the vCard document of record round-trips. FN maps only
//! from the Card's name.full: the RFC 9555 letter derives a
//! DERIVED=true FN from the name components when full is absent
//! (vCard formally requires one), but the app never mints a display
//! name into the document of record, one reason this conversion runs
//! on vcard-rs rather than calcard.
//!
//! JMAP has no per-card ETag; the revision surfaced to the app is a
//! hash of the card's JSON, which only drives the "unchanged, skip"
//! path of the local store. Updates carry no If-Match equivalent and
//! are last-write-wins, like Microsoft Graph.
//!
//! The CalendarEvent half is the write direction of the JSCalendar
//! conversion the listing does through ical-rs: the staged iCalendar
//! object as the one JSCalendar entry `CalendarEvent/set` takes, and the
//! update patch between two such entries.

use std::{
    collections::BTreeMap,
    hash::{DefaultHasher, Hash, Hasher},
};

use ical::{
    component::IcalComponent, jscalendar::IcalJscalendarVersion, prop::IcalPropKind,
    tree::cst::IcalCst,
};
use io_jmap::rfc9610::contact_card::JmapContactCard;
use serde::Serialize;
use serde_json::{Map, Value, to_string};
use vcard::{tree::cst::VcardCst, vcard::Vcard};

use crate::types::Card;

/// JMAP ContactCard to the JNI-facing card shape: the projected vCard
/// document, the ContactCard id as both display id and addressing key
/// (uri), the JSON hash as ETag, and the AddressBook memberships as
/// the card's books (RFC 9610 addressBookIds is natively m:n).
pub fn to_card(card: JmapContactCard) -> Result<Card, String> {
    let etag = etag(&card);
    let vcard = to_vcard(&card.card)?;
    let books = card
        .address_book_ids
        .iter()
        .filter(|(_, member)| **member)
        .map(|(id, _)| id.clone())
        .collect();
    let id = card
        .id
        .ok_or_else(|| "JMAP ContactCard is missing its id".to_string())?;

    Ok(Card {
        uri: id.clone(),
        id,
        etag,
        vcard,
        books,
    })
}

/// Projects the JSContact Card properties onto a vCard document.
pub fn to_vcard(card: &Map<String, Value>) -> Result<String, String> {
    let json = Value::Object(card.clone());
    let vcard =
        Vcard::from_jscontact(&json).map_err(|err| format!("Invalid JSContact card: {err}"))?;

    Ok(vcard.to_string())
}

/// Projects a vCard document onto JSContact Card properties, the
/// create payload of `ContactCard/set`.
pub fn to_jscontact(vcard: &str) -> Result<Map<String, Value>, String> {
    let cst = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;

    match cst.decode().to_jscontact() {
        Value::Object(map) => Ok(map),
        _ => Err("JSContact conversion did not produce a card object".to_string()),
    }
}

/// `ContactCard/set` update patch from the edited vCard: each
/// top-level JSContact property that differs from the base vCard (the
/// state last synced with the server), plus a null for every property
/// the edit removed. Without a base the patch carries every property,
/// which cannot clear server-side ones the vCard lost track of.
pub fn to_patch(vcard: &str, base_vcard: Option<&str>) -> Result<BTreeMap<String, Value>, String> {
    let new = to_jscontact(vcard)?;
    let mut patch = BTreeMap::new();

    match base_vcard {
        Some(base) => {
            let base = to_jscontact(base)?;

            for (key, value) in &new {
                if base.get(key) != Some(value) {
                    patch.insert(key.clone(), value.clone());
                }
            }

            for key in base.keys() {
                // NOTE: the uid is immutable in spirit (RFC 9610 §3
                // keys groups on it); never null it out.
                if !new.contains_key(key) && key != "uid" {
                    patch.insert(key.clone(), Value::Null);
                }
            }
        }
        None => patch.extend(new),
    }

    // NOTE: the JMAP envelope is not part of the JSContact payload;
    // addressBookIds in particular must survive the update.
    patch.remove("id");
    patch.remove("addressBookIds");

    Ok(patch)
}

/// The staged iCalendar object as the one JSCalendar entry a
/// CalendarEvent is: ical-rs converts a calendar to a JSCalendar 2.0
/// Group, the version draft-ietf-jmap-calendars builds on, the
/// overriding components folded into their series' `recurrenceOverrides`
/// and every `EXDATE` an `excluded` override, so a series with its
/// occurrences is one entry. An object converting to any other number of
/// entries is no one CalendarEvent and is refused, as is a series of more
/// than one rule.
///
/// NOTE: 2.0 holds one `recurrenceRule`; ical-rs keeps a further `RRULE`
/// in the `iCalendar` hatch, which Stalwart 0.16 drops, so the series
/// would lose a rule silently.
pub fn to_jscalendar_event(ical: &str) -> Result<Map<String, Value>, String> {
    let cst = IcalCst::parse(ical).map_err(|err| format!("Invalid iCalendar: {err}"))?;
    let calendar = cst.decode();
    let rules = |component: &IcalComponent| {
        component
            .props
            .iter()
            .filter(|prop| prop.name == IcalPropKind::RRule.into())
            .count()
    };
    if calendar
        .components
        .iter()
        .any(|component| rules(component) > 1)
    {
        return Err("A JMAP event holds one recurrence rule, not several".to_string());
    }

    let mut entries = match calendar.to_jscalendar_as(IcalJscalendarVersion::V2_0) {
        Value::Object(mut group) => group.remove("entries"),
        _ => None,
    };
    let entry = match entries.as_mut() {
        Some(Value::Array(entries)) if entries.len() == 1 => entries.pop(),
        _ => None,
    };
    let Some(Value::Object(mut entry)) = entry else {
        return Err("The calendar object is not one JMAP event".to_string());
    };

    // NOTE: 2.0 states the calendar's `METHOD` on each entry, but the
    // draft has a stored event carry none, a server refusing one as
    // invalid; it belongs to a scheduling message.
    entry.remove("method");

    Ok(entry)
}

/// `CalendarEvent/set` update patch turning the `base` entry into the
/// `staged` one: each top-level member that differs, whole, and a null
/// for each one the edit removed.
///
/// `recurrenceOverrides` is patched per recurrence id instead, so one
/// occurrence moved or excluded sends that occurrence alone. An override
/// is sent whole rather than reached into: it is a PatchObject itself,
/// where a null inside means removal rather than a null value (the
/// draft's patching section). The uid is never nulled out.
pub fn to_event_patch(
    staged: &Map<String, Value>,
    base: &Map<String, Value>,
) -> BTreeMap<String, Value> {
    const OVERRIDES: &str = "recurrenceOverrides";
    let mut patch = BTreeMap::new();

    for (key, value) in staged {
        if base.get(key) == Some(value) {
            continue;
        }
        if key == OVERRIDES
            && let (Value::Object(overrides), Some(Value::Object(held))) = (value, base.get(key))
        {
            for (id, over) in overrides {
                if held.get(id) != Some(over) {
                    patch.insert(format!("{OVERRIDES}/{}", pointer(id)), over.clone());
                }
            }
            for id in held.keys().filter(|id| !overrides.contains_key(*id)) {
                patch.insert(format!("{OVERRIDES}/{}", pointer(id)), Value::Null);
            }
            continue;
        }
        patch.insert(key.clone(), value.clone());
    }

    for key in base.keys() {
        if !staged.contains_key(key) && key != "uid" {
            patch.insert(key.clone(), Value::Null);
        }
    }

    patch
}

/// One key as a JSON pointer segment (RFC 6901 section 4).
fn pointer(key: &str) -> String {
    key.replace('~', "~0").replace('/', "~1")
}

/// Revision token of a ContactCard or a CalendarEvent: a hash of its
/// JSON. serde_json maps are key-sorted, so the hash is independent of
/// the property order the server picked.
pub fn etag(object: &impl Serialize) -> Option<String> {
    let json = to_string(object).ok()?;
    let mut hasher = DefaultHasher::new();
    json.hash(&mut hasher);

    Some(format!("{:016x}", hasher.finish()))
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use super::*;

    const VCARD: &str = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Jane Doe\r\nEMAIL:jane@example.com\r\nTEL:+33612345678\r\nEND:VCARD\r\n";

    #[test]
    fn vcard_to_jscontact_maps_core_properties() {
        let card = to_jscontact(VCARD).unwrap();

        assert_eq!(
            card.get("uid").and_then(|uid| uid.as_str()),
            Some("abc"),
            "{card:?}"
        );
        assert!(card.contains_key("name"), "{card:?}");
        assert!(card.contains_key("emails"), "{card:?}");
        assert!(card.contains_key("phones"), "{card:?}");
    }

    #[test]
    fn jscontact_to_vcard_round_trips() {
        let card = to_jscontact(VCARD).unwrap();
        let vcard = to_vcard(&card).unwrap();

        assert!(vcard.contains("FN:Jane Doe"), "{vcard}");
        assert!(vcard.contains("UID:abc"), "{vcard}");
        assert!(vcard.contains("jane@example.com"), "{vcard}");
    }

    #[test]
    fn patch_without_base_carries_every_property() {
        let patch = to_patch(VCARD, None).unwrap();

        assert!(patch.contains_key("name"), "{patch:?}");
        assert!(patch.contains_key("emails"), "{patch:?}");
        assert!(!patch.contains_key("id"), "{patch:?}");
        assert!(!patch.contains_key("addressBookIds"), "{patch:?}");
    }

    #[test]
    fn patch_against_base_keeps_only_changes() {
        let edited = VCARD.replace("Jane Doe", "Jane Smith");
        let patch = to_patch(&edited, Some(VCARD)).unwrap();

        assert!(patch.contains_key("name"), "{patch:?}");
        assert!(!patch.contains_key("emails"), "{patch:?}");
        assert!(!patch.contains_key("phones"), "{patch:?}");
        assert!(!patch.contains_key("uid"), "{patch:?}");
    }

    #[test]
    fn patch_nulls_removed_properties() {
        let edited = VCARD.replace("TEL:+33612345678\r\n", "");
        let patch = to_patch(&edited, Some(VCARD)).unwrap();

        assert_eq!(patch.get("phones"), Some(&Value::Null));
        assert!(!patch.contains_key("uid"), "{patch:?}");
    }

    #[test]
    fn middle_name_rides_given2_and_round_trips() {
        // NOTE: RFC 9553 carries the middle name as the given2
        // component kind; folding it into given (or dropping it) would
        // destroy N's third component through every JMAP round-trip.
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nN:BB;Aa;g;;\r\nEND:VCARD\r\n";
        let card = to_jscontact(vcard).unwrap();

        let components = card
            .get("name")
            .and_then(|name| name.get("components"))
            .and_then(|components| components.as_array())
            .expect("name components");
        let given2 = components.iter().any(|component| {
            component.get("kind").and_then(|kind| kind.as_str()) == Some("given2")
                && component.get("value").and_then(|value| value.as_str()) == Some("g")
        });
        assert!(given2, "{card:?}");

        let round = to_vcard(&card).unwrap();
        assert!(round.contains("N:BB;Aa;g;;"), "{round}");
    }

    #[test]
    fn name_components_without_full_mint_no_display_name() {
        // NOTE: a card with name components but no name.full (display
        // name never set) must convert without an FN: the app never
        // mints one into the record, the lists compose on the fly.
        let card = json!({
            "@type": "Card",
            "version": "1.0",
            "uid": "abc",
            "name": {
                "components": [
                    { "kind": "given", "value": "Jane" },
                    { "kind": "surname", "value": "Doe" },
                ],
            },
        });

        let vcard = to_vcard(card.as_object().unwrap()).unwrap();

        assert!(!vcard.contains("FN"), "{vcard}");
        assert!(vcard.contains("Jane"), "{vcard}");
    }
}
