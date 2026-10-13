//! # Contact fields
//!
//! The neutral field model of a card ([`ContactFields`]), which Java maps
//! to ContactsContract rows and edit-form fields, and the store's index.
//!
//! [`ContactFields::project`] reads a vCard; [`ContactFields::apply`]
//! patches an edit back through the vcard-rs CST, so every property the
//! model does not manage (PHOTO, CATEGORIES, KIND, X-*) survives byte for
//! byte. X-* are neither read nor written; TYPE values pass lowercase.
//!
//! The mapping itself is designed in docs/contacts-mapping.md.

use core::str::FromStr;

use std::borrow::Cow;

use io_pimdir::summary::contact::derive;
use serde::{Deserialize, Serialize};
use serde_json::{Value, to_value};

use crate::{contact::form::name_summary, offline::summary::SummaryJson};
use vcard::{
    param::VcardParam,
    prop::{
        VcardProp, VcardPropKind, VcardPropName, adr::ADR, anniversary::ANNIVERSARY, bday::BDAY,
        categories::CATEGORIES, email::EMAIL, r#fn::FN, gender::GENDER, impp::IMPP, lang::LANG,
        n::N, nickname::NICKNAME, note::NOTE, org::ORG, related::RELATED, role::ROLE, tel::TEL,
        title::TITLE, uid::UID, url::URL,
    },
    tree::{cst::VcardCst, line::VcardLine, prop::lens::VcardPropLens},
    value::{
        VcardValue,
        adr::VcardAdr,
        datetime::VcardDateAndOrTime,
        gender::VcardGender,
        language::VcardLanguageTag,
        n::VcardN,
        org::VcardOrg,
        text::{VcardText, VcardTextList},
        uri::VcardUri,
    },
    version::VcardVersion,
};

/// The neutral field model of one card, both ways across the bridge.
///
/// Fields are declared in the alphabetical order of their keys, the
/// order the model always had on the wire. Single-valued fields are
/// absent when the card lacks them; Java may omit any key it sends.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ContactFields {
    /// The ADR entries.
    #[serde(default)]
    pub addresses: Vec<ContactAddress>,
    /// The first full ANNIVERSARY, as `yyyy-mm-dd`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub anniversary: Option<String>,
    /// The first full BDAY, as `yyyy-mm-dd`.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub birthday: Option<String>,
    /// The CATEGORIES values, projected for the phone's groups but never
    /// applied: the edit form does not manage them.
    #[serde(default)]
    pub categories: Vec<String>,
    /// The first FN.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub display_name: Option<String>,
    /// The EMAIL entries.
    #[serde(default)]
    pub emails: Vec<ContactEmail>,
    /// The first GENDER.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub gender: Option<ContactGender>,
    /// The IMPP URIs.
    #[serde(default)]
    pub impps: Vec<String>,
    /// The LANG tags.
    #[serde(default)]
    pub languages: Vec<String>,
    /// The first N.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub name: Option<ContactName>,
    /// The NICKNAME values, every property's list flattened.
    #[serde(default)]
    pub nicknames: Vec<String>,
    /// The NOTE values.
    #[serde(default)]
    pub notes: Vec<String>,
    /// The first ORG.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub organization: Option<ContactOrganization>,
    /// The TEL entries.
    #[serde(default)]
    pub phones: Vec<ContactPhone>,
    /// The RELATED entries.
    #[serde(default)]
    pub relations: Vec<ContactRelation>,
    /// The first ROLE.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub role: Option<String>,
    /// The first TITLE.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub title: Option<String>,
    /// The first UID, projected but never applied.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub uid: Option<String>,
    /// The URL values.
    #[serde(default)]
    pub websites: Vec<String>,
}

/// One ADR entry of the field model.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ContactAddress {
    /// The locality.
    #[serde(default)]
    pub city: String,
    /// The country name.
    #[serde(default)]
    pub country: String,
    /// The extended address.
    #[serde(default)]
    pub ext: String,
    /// The post office box.
    #[serde(default)]
    pub pobox: String,
    /// The postal code.
    #[serde(default)]
    pub postcode: String,
    /// Whether the entry is preferred.
    #[serde(default)]
    pub pref: bool,
    /// The region.
    #[serde(default)]
    pub region: String,
    /// The street address, one line per street component.
    #[serde(default)]
    pub street: String,
    /// The lowercase TYPE values, pref excluded.
    #[serde(default)]
    pub types: Vec<String>,
}

/// One EMAIL entry of the field model.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ContactEmail {
    /// The email address.
    #[serde(default)]
    pub address: String,
    /// Whether the entry is preferred.
    #[serde(default)]
    pub pref: bool,
    /// The lowercase TYPE values, pref excluded.
    #[serde(default)]
    pub types: Vec<String>,
}

/// The GENDER of the field model.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ContactGender {
    /// The free-text gender identity.
    #[serde(default)]
    pub identity: String,
    /// The sex code (RFC 6350 6.2.7).
    #[serde(default)]
    pub sex: String,
}

/// The N of the field model, each component's values space-joined.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ContactName {
    /// The family names.
    #[serde(default)]
    pub family: String,
    /// The given names.
    #[serde(default)]
    pub given: String,
    /// The additional names.
    #[serde(default)]
    pub middle: String,
    /// The honorific prefixes.
    #[serde(default)]
    pub prefix: String,
    /// The honorific suffixes.
    #[serde(default)]
    pub suffix: String,
}

/// The ORG of the field model.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ContactOrganization {
    /// The organization name, the first component.
    #[serde(default)]
    pub company: String,
    /// The organizational units, the other components space-joined.
    #[serde(default)]
    pub department: String,
}

/// One TEL entry of the field model.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ContactPhone {
    /// The phone number, as entered.
    #[serde(default)]
    pub number: String,
    /// Whether the entry is preferred.
    #[serde(default)]
    pub pref: bool,
    /// The lowercase TYPE values, pref excluded.
    #[serde(default)]
    pub types: Vec<String>,
}

/// One RELATED entry of the field model.
#[derive(Clone, Debug, Default, Deserialize, Serialize)]
pub struct ContactRelation {
    /// Whether the entry is preferred.
    #[serde(default)]
    pub pref: bool,
    /// The lowercase TYPE values, pref excluded.
    #[serde(default)]
    pub types: Vec<String>,
    /// The related URI, or free text.
    #[serde(default)]
    pub value: String,
}

impl ContactFields {
    /// Projects a vCard onto the neutral field model.
    pub fn project(vcard: &str) -> Result<Self, String> {
        let card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
        let version = card.version();

        let mut fields = Self::default();

        for line in &card.props {
            let Ok(kind) = VcardPropKind::from_str(line.name.get()) else {
                continue;
            };

            match kind {
                VcardPropKind::Uid => {
                    let uid = UID::decode(line, version);
                    fields.uid.get_or_insert_with(|| text(&uid.0));
                }
                VcardPropKind::Fn => {
                    let name = FN::decode(line, version);
                    fields.display_name.get_or_insert_with(|| text(&name.0));
                }
                VcardPropKind::N => {
                    let n = N::decode(line, version);
                    fields.name.get_or_insert_with(|| ContactName {
                        family: n.family.join(" "),
                        given: n.given.join(" "),
                        middle: n.additional.join(" "),
                        prefix: n.prefixes.join(" "),
                        suffix: n.suffixes.join(" "),
                    });
                }
                VcardPropKind::Nickname => {
                    let all = NICKNAME::decode(line, version);
                    fields.nicknames.extend(all.0.iter().map(text));
                }
                VcardPropKind::Tel => {
                    let tel = TEL::decode(line, version);
                    let (types, pref) = types(line);
                    fields.phones.push(ContactPhone {
                        number: text(&tel.0),
                        pref,
                        types,
                    });
                }
                VcardPropKind::Email => {
                    let email = EMAIL::decode(line, version);
                    let (types, pref) = types(line);
                    fields.emails.push(ContactEmail {
                        address: text(&email.0),
                        pref,
                        types,
                    });
                }
                VcardPropKind::Adr => {
                    let adr = ADR::decode(line, version);
                    let (types, pref) = types(line);
                    fields.addresses.push(ContactAddress {
                        city: adr.locality.join(" "),
                        country: adr.country.join(" "),
                        ext: adr.extended.join(" "),
                        pobox: adr.po_box.join(" "),
                        postcode: adr.postal_code.join(" "),
                        pref,
                        region: adr.region.join(" "),
                        street: adr.street.join("\n"),
                        types,
                    });
                }
                VcardPropKind::Org => {
                    let org = ORG::decode(line, version);
                    let mut components = org.0.iter().map(|component| component.as_ref());
                    let company = components.next().unwrap_or_default();
                    let department = components.collect::<Vec<_>>().join(" ");
                    fields
                        .organization
                        .get_or_insert_with(|| ContactOrganization {
                            company: company.to_string(),
                            department,
                        });
                }
                VcardPropKind::Title => {
                    let title = TITLE::decode(line, version);
                    fields.title.get_or_insert_with(|| text(&title.0));
                }
                VcardPropKind::Role => {
                    let role = ROLE::decode(line, version);
                    fields.role.get_or_insert_with(|| text(&role.0));
                }
                VcardPropKind::Url => {
                    let url = URL::decode(line, version);
                    fields.websites.push(text(&url.0));
                }
                VcardPropKind::Bday => {
                    if let Some(date) = full_date(&line.raw_value_str()) {
                        fields.birthday.get_or_insert(date);
                    }
                }
                VcardPropKind::Anniversary => {
                    if let Some(date) = full_date(&line.raw_value_str()) {
                        fields.anniversary.get_or_insert(date);
                    }
                }
                VcardPropKind::Gender => {
                    let gender = GENDER::decode(line, version);
                    fields.gender.get_or_insert_with(|| ContactGender {
                        identity: text(&gender.identity),
                        sex: text(&gender.sex),
                    });
                }
                VcardPropKind::Impp => {
                    let impp = IMPP::decode(line, version);
                    fields.impps.push(text(&impp.0));
                }
                VcardPropKind::Lang => {
                    let lang = LANG::decode(line, version);
                    fields.languages.push(text(&lang.0));
                }
                VcardPropKind::Related => {
                    let related = RELATED::decode(line, version);
                    let (types, pref) = types(line);
                    fields.relations.push(ContactRelation {
                        pref,
                        types,
                        value: text(&related.0),
                    });
                }
                VcardPropKind::Note => {
                    let note = NOTE::decode(line, version);
                    fields.notes.push(text(&note.0));
                }
                // NOTE: projected for the phone's groups, but not managed
                // by apply: the edit form does not expose categories, so
                // the patch must preserve them.
                VcardPropKind::Categories => {
                    let all = CATEGORIES::decode(line, version);
                    fields.categories.extend(all.0.iter().map(text));
                }
                _ => {}
            }
        }

        Ok(fields)
    }

    /// Patches the edited field model back onto the vCard.
    ///
    /// Managed properties are rewritten canonically at the end of the card;
    /// every other line keeps its bytes, so unmanaged data survives.
    pub fn apply(&self, vcard: &str) -> Result<String, String> {
        let mut card = VcardCst::parse(vcard).map_err(|err| format!("Invalid vCard: {err}"))?;
        let version = card.version();

        // NOTE: FN persists only for a real display name; the views
        // compose one on the fly otherwise, never minting into the record.
        card.remove::<FN>();
        let display = trimmed(&self.display_name);
        if !display.is_empty() {
            card.push(text_prop(VcardPropKind::Fn, vec![], display));
        }

        card.remove::<N>();
        if let Some(name) = &self.name {
            let n = VcardN {
                family: single_component(&name.family),
                given: single_component(&name.given),
                additional: single_component(&name.middle),
                prefixes: single_component(&name.prefix),
                suffixes: single_component(&name.suffix),
            };

            let empty = n.family.is_empty()
                && n.given.is_empty()
                && n.additional.is_empty()
                && n.prefixes.is_empty()
                && n.suffixes.is_empty();
            if !empty {
                card.push(VcardProp {
                    group: None,
                    name: VcardPropName::Kind(VcardPropKind::N),
                    params: vec![],
                    value: VcardValue::N(n),
                });
            }
        }

        card.remove::<NICKNAME>();
        let nicknames: Vec<Cow<'static, str>> = strings(&self.nicknames)
            .into_iter()
            .map(Cow::Owned)
            .collect();
        if !nicknames.is_empty() {
            card.push(VcardProp {
                group: None,
                name: VcardPropName::Kind(VcardPropKind::Nickname),
                params: vec![],
                value: VcardValue::TextList(VcardTextList(nicknames)),
            });
        }

        card.remove::<TEL>();
        for phone in &self.phones {
            let number = phone.number.trim();
            if !number.is_empty() {
                card.push(text_prop(
                    VcardPropKind::Tel,
                    type_params(&phone.types, phone.pref, version),
                    number,
                ));
            }
        }

        card.remove::<EMAIL>();
        for email in &self.emails {
            let address = email.address.trim();
            if !address.is_empty() {
                card.push(text_prop(
                    VcardPropKind::Email,
                    type_params(&email.types, email.pref, version),
                    address,
                ));
            }
        }

        card.remove::<ADR>();
        for adr in &self.addresses {
            let value = VcardAdr {
                po_box: single_component(&adr.pobox),
                extended: single_component(&adr.ext),
                street: street_components(&adr.street),
                locality: single_component(&adr.city),
                region: single_component(&adr.region),
                postal_code: single_component(&adr.postcode),
                country: single_component(&adr.country),
                ..Default::default()
            };

            let empty = value.po_box.is_empty()
                && value.extended.is_empty()
                && value.street.is_empty()
                && value.locality.is_empty()
                && value.region.is_empty()
                && value.postal_code.is_empty()
                && value.country.is_empty();
            if !empty {
                card.push(VcardProp {
                    group: None,
                    name: VcardPropName::Kind(VcardPropKind::Adr),
                    params: type_params(&adr.types, adr.pref, version),
                    value: VcardValue::Adr(value),
                });
            }
        }

        card.remove::<ORG>();
        if let Some(org) = &self.organization {
            let company = org.company.trim();
            let department = org.department.trim();

            if !company.is_empty() || !department.is_empty() {
                let mut components: Vec<Cow<'static, str>> = vec![Cow::Owned(company.to_string())];
                if !department.is_empty() {
                    components.push(Cow::Owned(department.to_string()));
                }
                card.push(VcardProp {
                    group: None,
                    name: VcardPropName::Kind(VcardPropKind::Org),
                    params: vec![],
                    value: VcardValue::Org(VcardOrg(components)),
                });
            }
        }

        card.remove::<TITLE>();
        let title = trimmed(&self.title);
        if !title.is_empty() {
            card.push(text_prop(VcardPropKind::Title, vec![], title));
        }

        card.remove::<ROLE>();
        let role = trimmed(&self.role);
        if !role.is_empty() {
            card.push(text_prop(VcardPropKind::Role, vec![], role));
        }

        card.remove::<BDAY>();
        if let Some(date) = full_date(trimmed(&self.birthday)) {
            card.push(VcardProp {
                group: None,
                name: VcardPropName::Kind(VcardPropKind::Bday),
                params: vec![],
                value: VcardValue::DateAndOrTime(VcardDateAndOrTime(Cow::Owned(date))),
            });
        }

        card.remove::<URL>();
        for site in strings(&self.websites) {
            card.push(VcardProp {
                group: None,
                name: VcardPropName::Kind(VcardPropKind::Url),
                params: vec![],
                value: VcardValue::Uri(VcardUri(Cow::Owned(site))),
            });
        }

        card.remove::<NOTE>();
        for note in strings(&self.notes) {
            card.push(text_prop(VcardPropKind::Note, vec![], &note));
        }

        card.remove::<ANNIVERSARY>();
        if let Some(date) = full_date(trimmed(&self.anniversary)) {
            card.push(VcardProp {
                group: None,
                name: VcardPropName::Kind(VcardPropKind::Anniversary),
                params: vec![],
                value: VcardValue::DateAndOrTime(VcardDateAndOrTime(Cow::Owned(date))),
            });
        }

        card.remove::<GENDER>();
        if let Some(gender) = &self.gender {
            let sex = gender.sex.trim();
            let identity = gender.identity.trim();

            if !sex.is_empty() || !identity.is_empty() {
                card.push(VcardProp {
                    group: None,
                    name: VcardPropName::Kind(VcardPropKind::Gender),
                    params: vec![],
                    value: VcardValue::Gender(VcardGender {
                        sex: Cow::Owned(sex.to_string()),
                        identity: Cow::Owned(identity.to_string()),
                    }),
                });
            }
        }

        card.remove::<IMPP>();
        for impp in strings(&self.impps) {
            card.push(VcardProp {
                group: None,
                name: VcardPropName::Kind(VcardPropKind::Impp),
                params: vec![],
                value: VcardValue::Uri(VcardUri(Cow::Owned(impp))),
            });
        }

        card.remove::<LANG>();
        for tag in strings(&self.languages) {
            card.push(VcardProp {
                group: None,
                name: VcardPropName::Kind(VcardPropKind::Lang),
                params: vec![],
                value: VcardValue::LanguageTag(VcardLanguageTag(Cow::Owned(tag))),
            });
        }

        // NOTE: RELATED defaults to a URI value; a free-text relation (no
        // URI scheme) must say VALUE=text (RFC 6350 6.6.6).
        card.remove::<RELATED>();
        for relation in &self.relations {
            let value = relation.value.trim();
            if value.is_empty() {
                continue;
            }

            let mut params = type_params(&relation.types, relation.pref, version);
            if value.contains(':') {
                card.push(VcardProp {
                    group: None,
                    name: VcardPropName::Kind(VcardPropKind::Related),
                    params,
                    value: VcardValue::Uri(VcardUri(Cow::Owned(value.to_string()))),
                });
            } else {
                params.push(VcardParam::Value(Cow::Borrowed("text")));
                card.push(text_prop(VcardPropKind::Related, params, value));
            }
        }

        Ok(String::from_utf8_lossy(&card.to_bytes()).into_owned())
    }

    /// The display name to show, never persisted.
    ///
    /// FN, else composed from the name parts, else the first nickname,
    /// email or phone, so a nameless card still reads as something.
    pub fn display_name(&self) -> String {
        let display = trimmed(&self.display_name);
        if !display.is_empty() {
            return display.to_string();
        }

        let composed = name_summary(self);
        if !composed.is_empty() {
            return composed;
        }

        let nickname = self.nicknames.first().map_or("", String::as_str).trim();
        if !nickname.is_empty() {
            return nickname.to_string();
        }

        let email = self.first_email();
        if !email.is_empty() {
            return email.to_string();
        }
        self.first_phone().to_string()
    }

    /// A single-valued field reached by its dot path (the keys of the
    /// merge alternatives), verbatim, empty when absent.
    pub fn single_field(&self, path: &str) -> &str {
        let name = self.name.as_ref();
        let org = self.organization.as_ref();
        let gender = self.gender.as_ref();

        match path {
            "uid" => verbatim(&self.uid),
            "displayName" => verbatim(&self.display_name),
            "name.prefix" => name.map_or("", |name| name.prefix.as_str()),
            "name.given" => name.map_or("", |name| name.given.as_str()),
            "name.middle" => name.map_or("", |name| name.middle.as_str()),
            "name.family" => name.map_or("", |name| name.family.as_str()),
            "name.suffix" => name.map_or("", |name| name.suffix.as_str()),
            "organization.company" => org.map_or("", |org| org.company.as_str()),
            "organization.department" => org.map_or("", |org| org.department.as_str()),
            "title" => verbatim(&self.title),
            "role" => verbatim(&self.role),
            "birthday" => verbatim(&self.birthday),
            "anniversary" => verbatim(&self.anniversary),
            "gender.sex" => gender.map_or("", |gender| gender.sex.as_str()),
            "gender.identity" => gender.map_or("", |gender| gender.identity.as_str()),
            _ => "",
        }
    }

    /// The first email's address, verbatim, empty when none.
    fn first_email(&self) -> &str {
        self.emails
            .first()
            .map_or("", |email| email.address.as_str())
    }

    /// The first phone's number, verbatim, empty when none.
    fn first_phone(&self) -> &str {
        self.phones
            .first()
            .map_or("", |phone| phone.number.as_str())
    }
}

/// A canonical text property built from an owned value.
pub fn text_prop(
    kind: VcardPropKind,
    params: Vec<VcardParam<'static>>,
    value: &str,
) -> VcardProp<'static> {
    VcardProp {
        group: None,
        name: VcardPropName::Kind(kind),
        params,
        value: VcardValue::Text(VcardText(Cow::Owned(value.to_string()))),
    }
}

/// The entry's TYPE parameter (plus the pref flag, encoded as `TYPE=pref`
/// under vCard 3 and as `PREF=1` under vCard 4).
fn type_params(types: &[String], pref: bool, version: VcardVersion) -> Vec<VcardParam<'static>> {
    let mut types: Vec<Cow<'static, str>> = strings(types).into_iter().map(Cow::Owned).collect();

    let mut params = Vec::new();
    if pref {
        if version == VcardVersion::V4_0 {
            params.push(VcardParam::Pref(Cow::Borrowed("1")));
        } else {
            types.push(Cow::Borrowed("pref"));
        }
    }
    if !types.is_empty() {
        params.push(VcardParam::Type(types));
    }
    params
}

/// A structured-value component holding the trimmed string, or empty.
fn single_component(value: &str) -> Vec<Cow<'static, str>> {
    let text = value.trim();
    if text.is_empty() {
        Vec::new()
    } else {
        vec![Cow::Owned(text.to_string())]
    }
}

/// The street component: one value per line of the form field, matching
/// the newline join [`ContactFields::project`] uses on the way out.
fn street_components(value: &str) -> Vec<Cow<'static, str>> {
    value
        .split('\n')
        .map(str::trim)
        .filter(|line| !line.is_empty())
        .map(|line| Cow::Owned(line.to_string()))
        .collect()
}

/// The list's items trimmed, the empty ones dropped.
pub fn strings(items: &[String]) -> Vec<String> {
    items
        .iter()
        .map(|item| item.trim())
        .filter(|item| !item.is_empty())
        .map(str::to_string)
        .collect()
}

/// An optional field trimmed, empty when absent.
pub fn trimmed(value: &Option<String>) -> &str {
    verbatim(value).trim()
}

/// An optional field as is, empty when absent.
fn verbatim(value: &Option<String>) -> &str {
    value.as_deref().unwrap_or("")
}

fn text(value: impl AsRef<str>) -> String {
    value.as_ref().into()
}

/// Collects every TYPE parameter value, lowercased, folding the vCard 3
/// `TYPE=PREF` marker and the vCard 4 `PREF` parameter into one flag.
fn types(line: &VcardLine) -> (Vec<String>, bool) {
    let mut types = Vec::new();
    let mut pref = false;

    for param in &line.params {
        if param.name.get().eq_ignore_ascii_case("TYPE") {
            for value in &param.values {
                let value = value.get().to_ascii_lowercase();
                if value == "pref" {
                    pref = true;
                } else if !value.is_empty() {
                    types.push(value);
                }
            }
        } else if param.name.get().eq_ignore_ascii_case("PREF") {
            pref = true;
        }
    }

    (types, pref)
}

/// Normalizes a BDAY value to `yyyy-mm-dd`, or None for anything partial
/// (year-less dates have no standard vCard 3 form, so they do not sync).
pub fn full_date(raw: &str) -> Option<String> {
    let date = raw.trim();
    let digits = |s: &str| s.bytes().all(|b| b.is_ascii_digit());

    let dashed: Vec<&str> = date.split('-').collect();
    if let [y, m, d] = dashed[..]
        && y.len() == 4
        && m.len() == 2
        && d.len() == 2
        && digits(y)
        && digits(m)
        && digits(d)
    {
        return Some(format!("{y}-{m}-{d}"));
    }

    if date.len() == 8 && digits(date) {
        return Some(format!("{}-{}-{}", &date[..4], &date[4..6], &date[6..]));
    }

    None
}

/// What the app's store indexes a card under (see [`index`]).
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CardIndex {
    /// The first email address.
    pub email: String,
    /// Every non-empty email address, in document order.
    pub emails: Vec<String>,
    /// The normalized content hash, 16 hex digits.
    pub hash: String,
    /// The list row's fallback line.
    pub info: String,
    /// The display name to show.
    pub name: String,
    /// The first phone number.
    pub phone: String,
    /// The key the store sorts the card under.
    pub sort_key: String,
    /// The store's `text/vcard` summary, null when underivable.
    ///
    /// A JSON value rather than its type, so its keys keep the sorted
    /// order the reply always had (the type declares them in column order).
    pub summary: Option<Value>,
    /// The card's UID.
    pub uid: String,
}

/// Indexes a vCard for the app's store, once at write time.
///
/// The hash covers the sorted field model minus identity and unmanaged
/// data (UID, CATEGORIES, X-*, PHOTO), so linked replicas diverge only
/// when the content the edit form manages differs.
pub fn index(vcard: &str) -> Result<CardIndex, String> {
    let model = ContactFields::project(vcard)?;
    // NOTE: the summary and sort key come from io-pimdir over the bytes,
    // not from the model: two writers of one store must agree on them
    // property for property (STORAGE Annex A).
    let derived = derive(vcard.as_bytes());

    let name = model.display_name();
    let uid = model.single_field("uid").to_string();
    let email = model.first_email().to_string();
    // NOTE: every address, as the store's summary publishes (pimdir
    // SPEC.md Annex A): another reader would take a partial list as whole.
    let emails = model
        .emails
        .iter()
        .map(|email| email.address.as_str())
        .filter(|address| !address.is_empty())
        .map(str::to_string)
        .collect();
    let phone = model.first_phone().to_string();
    let info = fallback_info(&model);

    let mut canon = to_value(&model).map_err(|err| format!("Unserializable field model: {err}"))?;
    if let Some(map) = canon.as_object_mut() {
        map.remove("uid");
        map.remove("categories");
    }
    sort_arrays(&mut canon);
    let hash = fnv1a(canon.to_string().as_bytes());

    let summary = derived
        .summary
        .as_ref()
        .map(SummaryJson::from)
        .map(to_value)
        .transpose()
        .map_err(|err| format!("Unserializable card summary: {err}"))?;

    Ok(CardIndex {
        email,
        emails,
        hash: format!("{hash:016x}"),
        info,
        name,
        phone,
        sort_key: derived.sort_key.as_str().to_string(),
        summary,
        uid,
    })
}

/// The list row's fallback line for cards with no phone and no email:
/// the first valuable field the card carries, first line only.
fn fallback_info(model: &ContactFields) -> String {
    for field in ["organization.company", "title", "role"] {
        let value = model.single_field(field);
        if !value.is_empty() {
            return value.to_string();
        }
    }

    for list in [&model.websites, &model.nicknames, &model.notes] {
        let first = list.first().map_or("", String::as_str).trim();
        if !first.is_empty() {
            return first.lines().next().unwrap_or("").to_string();
        }
    }

    String::new()
}

/// Sorts every array of a serialized model by the items' serialized
/// form, so the content hash ignores entry order.
pub fn sort_arrays(value: &mut Value) {
    match value {
        Value::Array(items) => {
            for item in items.iter_mut() {
                sort_arrays(item);
            }
            items.sort_by_key(|item| item.to_string());
        }
        Value::Object(map) => {
            for (_, inner) in map.iter_mut() {
                sort_arrays(inner);
            }
        }
        _ => {}
    }
}

/// FNV-1a 64: a stable hash across runs and releases, unlike the std
/// hasher (stored hashes compare against freshly computed ones).
fn fnv1a(bytes: &[u8]) -> u64 {
    let mut hash: u64 = 0xcbf29ce484222325;
    for byte in bytes {
        hash ^= u64::from(*byte);
        hash = hash.wrapping_mul(0x100000001b3);
    }
    hash
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, from_value, json, to_value};

    use crate::{contact::fields::*, ffi::tests::assert_key_order};

    fn projected(vcard: &str) -> Result<Value, String> {
        let fields = ContactFields::project(vcard)?;
        assert_key_order(&fields);
        Ok(to_value(fields).unwrap())
    }

    fn applied(vcard: &str, model: &Value) -> Result<String, String> {
        from_value::<ContactFields>(model.clone())
            .unwrap()
            .apply(vcard)
    }

    fn indexed(vcard: &str) -> Result<Value, String> {
        let index = index(vcard)?;
        assert_key_order(&index);
        Ok(to_value(index).unwrap())
    }

    fn card(props: &str) -> Value {
        let vcard = format!("BEGIN:VCARD\r\nVERSION:3.0\r\n{props}END:VCARD\r\n");
        projected(&vcard).unwrap()
    }

    #[test]
    fn index_extracts_display_fields() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Jane Doe\r\n\
            EMAIL:jane@doe.org\r\nEMAIL:b@x.org\r\nTEL;TYPE=cell:+331111\r\nEND:VCARD\r\n";

        let index = indexed(vcard).unwrap();
        assert_eq!(index["name"], "Jane Doe");
        assert_eq!(index["email"], "jane@doe.org");
        assert_eq!(index["phone"], "+331111");
        assert_eq!(index["uid"], "abc");
        assert!(!index["hash"].as_str().unwrap().is_empty());
    }

    #[test]
    fn index_hash_ignores_order_and_volatile_props() {
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Jane\r\n\
            TEL:+331111\r\nREV:20260707T000000Z\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:3.0\r\nTEL:+331111\r\nFN:Jane\r\n\
            UID:abc\r\nPRODID:-//other//tool//EN\r\nEND:VCARD\r\n";
        let c = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Janet\r\n\
            TEL:+331111\r\nEND:VCARD\r\n";

        assert_eq!(indexed(a).unwrap()["hash"], indexed(b).unwrap()["hash"]);
        assert_ne!(indexed(a).unwrap()["hash"], indexed(c).unwrap()["hash"]);
    }

    #[test]
    fn index_hash_ignores_identity_and_unmanaged_props() {
        // NOTE: per-replica identity (UID) and unmanaged data (X-*,
        // PHOTO) stay per replica under fan-out editing, so they must
        // not read as divergence.
        let a = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:abc\r\nFN:Jane\r\n\
            X-FOO:bar\r\nPHOTO;ENCODING=b:AAAA\r\nEND:VCARD\r\n";
        let b = "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:def\r\nFN:Jane\r\nEND:VCARD\r\n";

        assert_eq!(indexed(a).unwrap()["hash"], indexed(b).unwrap()["hash"]);
    }

    #[test]
    fn extended_fields_round_trip() {
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Jane\r\n\
            ANNIVERSARY:20200102\r\nGENDER:F;girl\r\n\
            IMPP:xmpp:jane@chat.example\r\nLANG:fr\r\nLANG:en\r\n\
            RELATED;TYPE=spouse;VALUE=text:John Doe\r\n\
            X-FOO:bar\r\nEND:VCARD\r\n";

        let model = projected(vcard).unwrap();
        assert_eq!(model["anniversary"], "2020-01-02");
        assert_eq!(model["gender"]["sex"], "F");
        assert_eq!(model["gender"]["identity"], "girl");
        assert_eq!(model["impps"][0], "xmpp:jane@chat.example");
        assert_eq!(model["languages"][1], "en");
        assert_eq!(model["relations"][0]["value"], "John Doe");
        assert_eq!(model["relations"][0]["types"][0], "spouse");

        let fresh = applied(vcard, &model).unwrap();
        assert!(fresh.contains("ANNIVERSARY:2020-01-02\r\n"), "got: {fresh}");
        assert!(fresh.contains("GENDER:F;girl\r\n"), "got: {fresh}");
        assert!(
            fresh.contains("IMPP:xmpp:jane@chat.example\r\n"),
            "got: {fresh}"
        );
        assert!(fresh.contains("LANG:fr\r\n"), "got: {fresh}");
        assert!(
            fresh.contains("RELATED;TYPE=spouse;VALUE=text:John Doe\r\n"),
            "got: {fresh}"
        );
        assert!(fresh.contains("X-FOO:bar\r\n"), "got: {fresh}");
    }

    #[test]
    fn names_and_identity() {
        let model = card("UID:abc-123\r\nFN:John Doe\r\nN:Doe;John;Quincy;Dr.;Jr.\r\n");
        assert_eq!(model["uid"], "abc-123");
        assert_eq!(model["displayName"], "John Doe");
        assert_eq!(model["name"]["family"], "Doe");
        assert_eq!(model["name"]["given"], "John");
        assert_eq!(model["name"]["middle"], "Quincy");
        assert_eq!(model["name"]["prefix"], "Dr.");
        assert_eq!(model["name"]["suffix"], "Jr.");
    }

    #[test]
    fn phones_collect_types_and_pref() {
        let model = card("TEL;TYPE=HOME,VOICE:+331111\r\nTEL;type=CELL;type=pref:+332222\r\n");
        let phones = model["phones"].as_array().unwrap();
        assert_eq!(phones[0]["number"], "+331111");
        assert_eq!(phones[0]["types"], json!(["home", "voice"]));
        assert_eq!(phones[0]["pref"], false);
        assert_eq!(phones[1]["types"], json!(["cell"]));
        assert_eq!(phones[1]["pref"], true);
    }

    #[test]
    fn address_components() {
        let model = card("ADR;TYPE=WORK:;;12 Main St;Paris;IDF;75000;France\r\n");
        let adr = &model["addresses"][0];
        assert_eq!(adr["street"], "12 Main St");
        assert_eq!(adr["city"], "Paris");
        assert_eq!(adr["region"], "IDF");
        assert_eq!(adr["postcode"], "75000");
        assert_eq!(adr["country"], "France");
        assert_eq!(adr["types"], json!(["work"]));
    }

    #[test]
    fn org_note_escapes() {
        let model = card("ORG:ACME\\, Inc.;R&D\r\nNOTE:line one\\nline two\r\nTITLE:Boss\r\n");
        assert_eq!(model["organization"]["company"], "ACME, Inc.");
        assert_eq!(model["organization"]["department"], "R&D");
        assert_eq!(model["notes"][0], "line one\nline two");
        assert_eq!(model["title"], "Boss");
    }

    #[test]
    fn birthday_full_dates_only() {
        assert_eq!(card("BDAY:1985-04-12\r\n")["birthday"], "1985-04-12");
        assert_eq!(card("BDAY:19850412\r\n")["birthday"], "1985-04-12");
        assert!(card("BDAY:--04-12\r\n").get("birthday").is_none());
    }

    #[test]
    fn custom_properties_ignored() {
        let model = card("FN:X\r\nX-PHONETIC-FIRST-NAME:Zhon\r\nX-ANNIVERSARY:2001-01-01\r\n");
        assert_eq!(model["displayName"], "X");
        assert!(model.get("birthday").is_none());
    }

    #[test]
    fn categories_projected_but_preserved_by_apply() {
        let vcard =
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:X\r\nCATEGORIES:friends,band\r\nEND:VCARD\r\n";
        assert_eq!(
            projected(vcard).unwrap()["categories"],
            json!(["friends", "band"])
        );

        let patched = applied(vcard, &json!({ "displayName": "Y" })).unwrap();
        assert!(patched.contains("CATEGORIES:friends,band\r\n"));
    }

    #[test]
    fn apply_rewrites_managed_and_preserves_the_rest() {
        let vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:abc\r\nFN:Old Name\r\nTEL;TYPE=HOME:+331111\r\nX-FOO:bar\r\nPHOTO;ENCODING=b;TYPE=JPEG:AAAA\r\nEND:VCARD\r\n";
        let model = json!({
            "displayName": "New Name",
            "phones": [{ "number": "+332222", "types": ["cell"], "pref": true }],
        });

        let patched = applied(vcard, &model).unwrap();
        assert!(patched.contains("FN:New Name\r\n"));
        assert!(patched.contains("TEL;TYPE=cell,pref:+332222\r\n"));
        assert!(!patched.contains("+331111"));
        assert!(patched.contains("UID:abc\r\n"));
        assert!(patched.contains("X-FOO:bar\r\n"));
        assert!(patched.contains("PHOTO;ENCODING=b;TYPE=JPEG:AAAA\r\n"));
    }

    #[test]
    fn apply_project_round_trip() {
        let vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:abc\r\nFN:X\r\nEND:VCARD\r\n";
        let model = json!({
            "displayName": "Jane Doe",
            "name": { "family": "Doe", "given": "Jane", "middle": "", "prefix": "", "suffix": "" },
            "phones": [{ "number": "+333333", "types": ["work"], "pref": false }],
            "emails": [{ "address": "jane@doe.org", "types": ["home"], "pref": false }],
            "addresses": [{
                "pobox": "", "ext": "", "street": "12 Main St", "city": "Paris",
                "region": "", "postcode": "75000", "country": "France",
                "types": ["home"], "pref": false,
            }],
            "organization": { "company": "ACME, Inc.", "department": "R&D" },
            "title": "Boss",
            "birthday": "1985-04-12",
            "websites": ["https://doe.org"],
            "notes": ["line one\nline two"],
        });

        let projected = projected(&applied(vcard, &model).unwrap()).unwrap();
        assert_eq!(projected["displayName"], "Jane Doe");
        assert_eq!(projected["name"]["family"], "Doe");
        assert_eq!(projected["phones"][0]["number"], "+333333");
        assert_eq!(projected["phones"][0]["types"], json!(["work"]));
        assert_eq!(projected["emails"][0]["address"], "jane@doe.org");
        assert_eq!(projected["addresses"][0]["street"], "12 Main St");
        assert_eq!(projected["addresses"][0]["postcode"], "75000");
        assert_eq!(projected["organization"]["company"], "ACME, Inc.");
        assert_eq!(projected["organization"]["department"], "R&D");
        assert_eq!(projected["title"], "Boss");
        assert_eq!(projected["birthday"], "1985-04-12");
        assert_eq!(projected["websites"][0], "https://doe.org");
        assert_eq!(projected["notes"][0], "line one\nline two");
    }

    #[test]
    fn apply_never_mints_fn_and_drops_emptied_fields() {
        let vcard =
            "BEGIN:VCARD\r\nVERSION:3.0\r\nFN:Old\r\nTITLE:Boss\r\nNOTE:gone\r\nEND:VCARD\r\n";
        let model = json!({
            "displayName": "",
            "name": { "family": "Doe", "given": "Jane" },
            "title": "",
            "notes": [],
        });

        // NOTE: an emptied display name drops FN instead of persisting a
        // composed one; the views compose on the fly (index name).
        let patched = applied(vcard, &model).unwrap();
        assert!(!patched.contains("FN:"), "got: {patched}");
        assert!(!patched.contains("TITLE"));
        assert!(!patched.contains("NOTE"));

        let indexed = indexed(&patched).unwrap();
        assert_eq!(indexed["name"], "Jane Doe");
    }
}
