//! # Raw properties
//!
//! The advanced raw-property editor: lists, recomposes and rewrites a
//! card's property lines while keeping every untouched line intact.

use core::str::FromStr;

use serde::{Deserialize, Serialize};
use vcard::{
    prop::{VcardPropKind, adr::ADR, gender::GENDER, n::N},
    tree::{cst::VcardCst, line::VcardLine, prop::lens::VcardPropLens},
    version::VcardVersion,
};

use crate::contact::fields::strings;

/// One raw property line of a card, as the advanced editor lists it.
#[derive(Serialize)]
pub struct CardProp {
    /// The labeled components of a structured value (N, ADR, GENDER),
    /// absent for plain values.
    #[serde(skip_serializing_if = "Option::is_none")]
    components: Option<Vec<PropComponent>>,
    /// The whole content line, unfolded.
    line: String,
    /// The property name, as written.
    name: String,
    /// The parameters, as written.
    params: Vec<PropParam>,
    /// The raw value, everything after the first colon.
    value: String,
}

/// One parameter of a property line, both ways.
#[derive(Deserialize, Serialize)]
pub struct PropParam {
    /// The parameter name.
    #[serde(default)]
    name: String,
    /// The parameter values.
    #[serde(default)]
    values: Vec<String>,
}

/// One labeled component of a structured value, both ways.
#[derive(Deserialize, Serialize)]
pub struct PropComponent {
    /// The component label (see [`card_prop_labels`]).
    #[serde(default)]
    name: String,
    /// The component's items, comma-separated.
    #[serde(default)]
    value: String,
}

/// One property's structured parts, as the advanced editor sends them.
#[derive(Deserialize)]
pub struct PropParts {
    /// The property name.
    #[serde(default)]
    name: String,
    /// The parameters.
    #[serde(default)]
    params: Vec<PropParam>,
    /// The plain value, ignored when components are given.
    #[serde(default)]
    value: String,
    /// The components of a structured value, in label order.
    #[serde(default)]
    components: Option<Vec<PropComponent>>,
}

/// Lists the card's raw property lines for the advanced editor, in
/// source order, unfolded, envelope excluded (VERSION included).
pub fn card_props(vcard: &str) -> Result<Vec<CardProp>, String> {
    let card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;

    let props = card
        .props
        .iter()
        .map(|prop| {
            let line = raw_line(prop);
            let value = line
                .find(':')
                .map(|colon| &line[colon + 1..])
                .unwrap_or("")
                .to_string();
            let params = prop
                .params
                .iter()
                .map(|param| PropParam {
                    name: param.name.get().to_string(),
                    values: param
                        .values
                        .iter()
                        .map(|value| value.get().to_string())
                        .collect(),
                })
                .collect();
            CardProp {
                components: structured_components(prop, card.version()),
                line,
                name: prop.name.get().to_string(),
                params,
                value,
            }
        })
        .collect();

    Ok(props)
}

/// Recomposes one property from its parts and rewrites it like
/// [`card_set_prop`] (-1 appends).
///
/// Separators and quotes are dropped from parameter values: the CST's head
/// parser is not quote-aware, so a quoted separator would not round-trip.
pub fn card_set_prop_parts(vcard: &str, index: i64, prop: &PropParts) -> Result<String, String> {
    let name = prop.name.trim().to_uppercase();
    if name.is_empty() {
        return Err("Empty property name".into());
    }

    let mut line = name;
    for param in &prop.params {
        let param_name = param.name.trim();
        if param_name.is_empty() {
            continue;
        }
        line.push(';');
        line.push_str(param_name);
        let values: Vec<String> = strings(&param.values)
            .iter()
            .map(|value| quote_param(value))
            .collect();
        if !values.is_empty() {
            line.push('=');
            line.push_str(&values.join(","));
        }
    }
    line.push(':');
    if let Some(components) = &prop.components {
        let composed = components
            .iter()
            .map(|component| {
                component
                    .value
                    .split(',')
                    .map(|item| escape_component(item.trim()))
                    .collect::<Vec<_>>()
                    .join(",")
            })
            .collect::<Vec<_>>()
            .join(";");
        line.push_str(&composed);
    } else {
        line.push_str(&prop.value);
    }

    card_set_prop(vcard, index, &line)
}

/// The component labels of a structured property value (N, ADR, GENDER),
/// empty for plain values, mirroring the vcard-rs value types.
pub fn card_prop_labels(name: &str) -> Vec<&'static str> {
    match VcardPropKind::from_str(name) {
        Ok(VcardPropKind::N) => vec![
            "Family name",
            "Given name",
            "Additional names",
            "Prefixes",
            "Suffixes",
        ],
        Ok(VcardPropKind::Adr) => vec![
            "PO box",
            "Extended",
            "Street",
            "Locality",
            "Region",
            "Postal code",
            "Country",
        ],
        Ok(VcardPropKind::Gender) => vec!["Sex", "Identity"],
        _ => Vec::new(),
    }
}

/// The property's decoded components zipped with their labels, None
/// for plain values. Multi-valued components read as comma lists.
fn structured_components(line: &VcardLine, version: VcardVersion) -> Option<Vec<PropComponent>> {
    let kind = VcardPropKind::from_str(line.name.get()).ok()?;
    let values: Vec<String> = match kind {
        VcardPropKind::N => {
            let n = N::decode(line, version);
            vec![
                n.family.join(","),
                n.given.join(","),
                n.additional.join(","),
                n.prefixes.join(","),
                n.suffixes.join(","),
            ]
        }
        VcardPropKind::Adr => {
            let adr = ADR::decode(line, version);
            vec![
                adr.po_box.join(","),
                adr.extended.join(","),
                adr.street.join(","),
                adr.locality.join(","),
                adr.region.join(","),
                adr.postal_code.join(","),
                adr.country.join(","),
            ]
        }
        VcardPropKind::Gender => {
            let gender = GENDER::decode(line, version);
            vec![gender.sex.to_string(), gender.identity.to_string()]
        }
        _ => return None,
    };

    let components = card_prop_labels(line.name.get())
        .iter()
        .zip(values)
        .map(|(label, value)| PropComponent {
            name: label.to_string(),
            value,
        })
        .collect();
    Some(components)
}

/// One escaped item of a structured component (RFC 6350 3.4).
fn escape_component(item: &str) -> String {
    let mut out = String::with_capacity(item.len());
    for next in item.chars() {
        match next {
            '\\' => out.push_str("\\\\"),
            ';' => out.push_str("\\;"),
            ',' => out.push_str("\\,"),
            '\n' => out.push_str("\\n"),
            '\r' => {}
            _ => out.push(next),
        }
    }
    out
}

/// A parameter value, its separators and quotes dropped.
fn quote_param(value: &str) -> String {
    value
        .chars()
        .filter(|next| !matches!(next, '"' | ';' | ':' | ','))
        .collect()
}

/// Validates a hand-edited vCard source for the advanced editor: it
/// must reparse, and comes back re-serialized.
pub fn card_source(vcard: &str) -> Result<String, String> {
    let card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
    Ok(String::from_utf8_lossy(&card.to_bytes()).into_owned())
}

/// Rewrites the raw property line at `index` (a blank line removes it),
/// -1 appending.
///
/// The result must reparse; untouched lines keep their content, unfolded.
pub fn card_set_prop(vcard: &str, index: i64, line: &str) -> Result<String, String> {
    let card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
    let mut lines: Vec<String> = card.props.iter().map(raw_line).collect();

    let line = line.trim();
    if index < 0 {
        if line.is_empty() {
            return Err("Empty property".into());
        }
        lines.push(line.to_string());
    } else {
        let index = index as usize;
        if index >= lines.len() {
            return Err("No such property".into());
        }
        if line.is_empty() {
            lines.remove(index);
        } else {
            lines[index] = line.to_string();
        }
    }

    let mut source = String::from("BEGIN:VCARD\r\n");
    for entry in &lines {
        source.push_str(entry);
        source.push_str("\r\n");
    }
    source.push_str("END:VCARD\r\n");

    let fresh = VcardCst::parse(&source).map_err(|err| format!("Invalid vCard: {err}"))?;
    Ok(String::from_utf8_lossy(&fresh.to_bytes()).into_owned())
}

/// One property as a single unfolded content line, EOL dropped.
fn raw_line(line: &VcardLine) -> String {
    let text = line.to_string();
    let mut out = String::with_capacity(text.len());
    let mut chars = text.chars().peekable();

    while let Some(next) = chars.next() {
        if next == '\r' || next == '\n' {
            if next == '\r' && chars.peek() == Some(&'\n') {
                chars.next();
            }
            // NOTE: a fold break eats the continuation's leading blank.
            if matches!(chars.peek(), Some(' ') | Some('\t')) {
                chars.next();
            }
            continue;
        }
        out.push(next);
    }

    out
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, from_value, json, to_value};

    use crate::{contact::props::*, ffi::tests::assert_key_order};

    fn listed(vcard: &str) -> Value {
        let props = card_props(vcard).unwrap();
        assert_key_order(&props);
        to_value(props).unwrap()
    }

    fn parts(vcard: &str, index: i64, prop: &Value) -> Result<String, String> {
        card_set_prop_parts(vcard, index, &from_value(prop.clone()).unwrap())
    }

    #[test]
    fn card_props_lists_and_card_set_prop_rewrites() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Jane\r\n\
            X-FOO:bar\r\nEND:VCARD\r\n";

        let props = listed(vcard);
        let props = props.as_array().unwrap();
        assert_eq!(props.len(), 3);
        assert_eq!(props[0]["line"], "VERSION:4.0");
        assert_eq!(props[2]["name"], "X-FOO");
        assert_eq!(props[2]["value"], "bar");

        let edited = card_set_prop(vcard, 2, "X-FOO:baz").unwrap();
        assert!(edited.contains("X-FOO:baz\r\n"), "got: {edited}");
        assert!(!edited.contains("X-FOO:bar"), "got: {edited}");

        let removed = card_set_prop(&edited, 2, "").unwrap();
        assert!(!removed.contains("X-FOO"), "got: {removed}");

        let appended = card_set_prop(&removed, -1, "NOTE:hi").unwrap();
        assert!(appended.contains("NOTE:hi\r\n"), "got: {appended}");

        assert!(card_set_prop(vcard, 9, "X:y").is_err());
    }

    #[test]
    fn card_set_prop_parts_recomposes_and_card_source_validates() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Jane\r\nEND:VCARD\r\n";

        let prop = json!({
            "name": "tel",
            "params": [{ "name": "TYPE", "values": ["cell", "a;b"] }],
            "value": "+336",
        });
        let fresh = parts(vcard, -1, &prop).unwrap();
        assert!(fresh.contains("TEL;TYPE=cell,ab:+336\r\n"), "got: {fresh}");

        let props = listed(&fresh);
        let tel = &props.as_array().unwrap()[2];
        assert_eq!(tel["params"][0]["name"], "TYPE");
        assert_eq!(tel["params"][0]["values"][0], "cell");

        assert!(card_source(&fresh).unwrap().contains("FN:Jane\r\n"));
        assert!(parts(vcard, -1, &json!({ "value": "x" })).is_err());

        // NOTE: structured values recompose from labeled components,
        // items escaped, and card_props decodes them back.
        let structured = json!({
            "name": "N",
            "params": [],
            "components": [
                { "value": "Doe;X" },
                { "value": "Jane" },
                { "value": "" },
                { "value": "Dr" },
                { "value": "" },
            ],
        });
        let named = parts(vcard, -1, &structured).unwrap();
        assert!(named.contains("N:Doe\\;X;Jane;;Dr;\r\n"), "got: {named}");

        let props = listed(&named);
        let n = &props.as_array().unwrap()[2];
        assert_eq!(n["components"][0]["name"], "Family name");
        assert_eq!(n["components"][0]["value"], "Doe;X");
        assert_eq!(n["components"][3]["value"], "Dr");
        assert_eq!(card_prop_labels("ADR").len(), 7);
        assert!(card_prop_labels("TEL").is_empty());
    }
}
