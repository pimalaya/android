//! # Contact exports
//!
//! The contact exports: the card CRUD and sync verbs, and the vCard
//! projection, form and merge ones.
//!
//! A card verb dispatches on the account's base URL through [`Contacts`];
//! the CardDAV multiget and the Graph and Google batch reads drive their
//! backend struct. The vCard verbs take no transport: pure computation over
//! the text Java hands them.

use jni::{
    EnvUnowned,
    errors::Error,
    objects::{JClass, JObject, JString},
    sys::{jboolean, jint},
};
use serde::Serialize;
use serde_json::{from_str, to_string};

use crate::{
    account::{self, Account},
    client::{Auth, Client, jmap::Jmap, msgraph::Msgraph, webdav::Webdav},
    contact::{
        CardBooksUpdate, CardCreate, CardDelete, CardDelta, CardUpdate, Contacts,
        dedup::find_duplicates,
        fields::{ContactFields, index},
        form::{card_type_order, form_date, form_entry, form_view},
        gpeople::Gpeople,
        group::{DuplicateMember, duplicate_group, group_contacts},
        merge::{merge_cards, merge_conflict, merge_conflict_form, set_uid},
        push::PushChange,
    },
    error::BridgeError,
    ffi::{
        ErrorReply, error_json, host::JniHost, parse_books, parse_ref_cards, parse_strings,
        parse_url, read_string,
    },
};

/// `Native.jmapCapabilities`: the capabilities a JMAP session serves.
///
/// Among mail, submission, contacts and calendars, from a fresh session
/// fetch. Returns a JSON array of URNs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_jmapCapabilities<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let account = Account::new(&base_url, &login, &password);

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let served = account::jmap_session_url(account.base_url)
            .map_err(BridgeError::from)
            .and_then(|session_url| {
                Jmap {
                    client: &mut client,
                    session_url,
                    auth: account.auth,
                }
                .capabilities()
            });
        let json = match served {
            Ok(urns) => to_string(&urns).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.listAddressbooks`: lists the account's addressbooks.
///
/// Returns a JSON array of addressbooks carrying absolute collection URLs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_listAddressbooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let account = Account::new(&base_url, &login, &password);

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let mut contacts = Contacts {
            client: &mut client,
            account: &account,
        };
        let json = match contacts.list_addressbooks() {
            Ok(books) => to_string(&books).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.listCards`: lists the cards of an addressbook collection.
///
/// CardDAV or Graph. Returns a JSON array of `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_listCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let account = Account::new(&base_url, &login, &password);

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let mut contacts = Contacts {
            client: &mut client,
            account: &account,
        };
        let json = match contacts.list_cards(&addressbook_url) {
            Ok(cards) => to_string(&cards).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.createCard`: creates the card in an addressbook collection.
///
/// Returns the created `{id, uri, etag, vcard}`, the id server-assigned on
/// the backends naming the resource themselves.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_createCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let vcard = read_string(env, &vcard);
        let account = Account::new(&base_url, &login, &password);

        let card = CardCreate {
            id: &id,
            vcard: &vcard,
        };
        let mut client = Client::new(JniHost::new(env, &transport, None));
        let mut contacts = Contacts {
            client: &mut client,
            account: &account,
        };
        let json = match contacts.create_card(&addressbook_url, &card) {
            Ok(card) => to_string(&card).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.readCard`: reads the card at a resource name.
///
/// The name as the server returned it. Returns `{id, uri, etag, vcard}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_readCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uri: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uri = read_string(env, &uri);
        let account = Account::new(&base_url, &login, &password);

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let mut contacts = Contacts {
            client: &mut client,
            account: &account,
        };
        let json = match contacts.read_card(&addressbook_url, &uri) {
            Ok(card) => to_string(&card).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.updateCard`: updates the card at a resource name.
///
/// The name as the server returned it, empty falling back to the id. The
/// base vCard trims a patching backend's update to the changed fields, the
/// ETag guards a guarding one (both empty: unknown). Returns the card.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_updateCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    uri: JString<'local>,
    vcard: JString<'local>,
    base_vcard: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let uri = read_string(env, &uri);
        let vcard = read_string(env, &vcard);
        let base_vcard = read_string(env, &base_vcard);
        let etag = read_string(env, &etag);
        let account = Account::new(&base_url, &login, &password);
        let card = CardUpdate {
            id: &id,
            uri: &uri,
            vcard: &vcard,
            base_vcard: (!base_vcard.is_empty()).then_some(base_vcard.as_str()),
            etag: (!etag.is_empty()).then_some(etag.as_str()),
        };

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let mut contacts = Contacts {
            client: &mut client,
            account: &account,
        };
        let json = match contacts.update_card(&addressbook_url, &card) {
            Ok(card) => to_string(&card).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.deleteCard`: deletes the card at a resource name.
///
/// The name as the server returned it, empty falling back to the id; the
/// ETag guards a guarding backend (empty: unknown). Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_deleteCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    uri: JString<'local>,
    etag: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let uri = read_string(env, &uri);
        let etag = read_string(env, &etag);
        let account = Account::new(&base_url, &login, &password);
        let card = CardDelete {
            id: &id,
            uri: &uri,
            etag: (!etag.is_empty()).then_some(etag.as_str()),
        };

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let mut contacts = Contacts {
            client: &mut client,
            account: &account,
        };
        let json = match contacts.delete_card(&addressbook_url, &card) {
            Ok(()) => "{}".to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.createCards`: creates a batch of cards, Google only.
///
/// Takes a JSON array of vCards; Google is the one backend with a batch
/// create. Returns the created `{id, uri, etag, vcard}` in input order.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_createCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    vcards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let vcards = read_string(env, &vcards);
        let account = Account::new(&base_url, &login, &password);

        let json = match parse_strings(&vcards) {
            Ok(vcards) => {
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut contacts = Contacts {
                    client: &mut client,
                    account: &account,
                };
                match contacts.create_cards(&vcards) {
                    Ok(cards) => {
                        to_string(&cards).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.deleteCards`: deletes a batch of cards, Google only.
///
/// Takes a JSON array of card ids; Google is the one backend with a batch
/// delete. Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_deleteCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    ids: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let ids = read_string(env, &ids);
        let account = Account::new(&base_url, &login, &password);

        let json = match parse_strings(&ids) {
            Ok(ids) => {
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut contacts = Contacts {
                    client: &mut client,
                    account: &account,
                };
                match contacts.delete_cards(&ids) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pushCards`: pushes a round of changes as batch calls.
///
/// JMAP (ContactCard/set) and Graph ($batch) only. Takes `[{ref, op, id?,
/// vcard?, baseVcard?, add?, remove?}]`, returns one `{ref, accepted, id?,
/// etag?, error?}` per change.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pushCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    changes: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let changes = read_string(env, &changes);
        let account = Account::new(&base_url, &login, &password);

        let json = match from_str::<Vec<PushChange>>(&changes) {
            Ok(changes) => {
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut contacts = Contacts {
                    client: &mut client,
                    account: &account,
                };
                match contacts.push_cards(&addressbook_url, &changes) {
                    Ok(outcomes) => {
                        to_string(&outcomes).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(format!("Unreadable push changes: {err}")),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.syncCards`: lists a collection's changes since the cursor.
///
/// Empty or expired runs the initial round (members plus the next cursor),
/// a rejected CardDAV sync falling back to enumeration. Returns `{"changed":
/// [{id, uri, etag, vcard?, books?}], "vanished": [uri], "token", "complete"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_syncCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sync_token: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let sync_token = read_string(env, &sync_token);
        let sync_token = (!sync_token.is_empty()).then_some(sync_token.as_str());
        let account = Account::new(&base_url, &login, &password);

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let mut contacts = Contacts {
            client: &mut client,
            account: &account,
        };
        let json = match contacts.sync_cards(&addressbook_url, sync_token) {
            Ok(delta) => delta_json(delta),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.multigetCards`: batch-fetches cards by resource name.
///
/// Takes a JSON string array, fetched by REPORT `addressbook-multiget`.
/// Returns a JSON array of `{id, uri, etag, vcard}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_multigetCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    addressbook_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    uris: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let addressbook_url = read_string(env, &addressbook_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let uris = read_string(env, &uris);
        let auth = Auth::new(&login, &password);

        let json = match (parse_url(&addressbook_url), parse_strings(&uris)) {
            (Err(err), _) | (_, Err(err)) => error_json(err),
            (Ok(url), Ok(uris)) => {
                let uris: Vec<&str> = uris.iter().map(String::as_str).collect();
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut webdav = Webdav {
                    client: &mut client,
                    auth,
                };
                match webdav.multiget_cards(&url, &uris) {
                    Ok(cards) => {
                        to_string(&cards).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.readGraphCards`: reads Graph contacts by id, Graph only.
///
/// Takes a JSON string array, 20 to a `$batch`, a request the batch could
/// not serve sent alone, a contact Graph no longer holds left out. Returns a
/// JSON array of `{id, uri, etag, vcard}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_readGraphCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    ids: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let ids = read_string(env, &ids);

        let json = match parse_strings(&ids) {
            Err(err) => error_json(err),
            Ok(ids) => {
                let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut graph = Msgraph {
                    client: &mut client,
                    token: &token,
                };
                match graph.read_cards(&ids) {
                    Ok(cards) => {
                        to_string(&cards).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.readGoogleCards`: reads People contacts by id, Google only.
///
/// Takes a JSON string array, 200 to a `people:batchGet`, a contact Google
/// no longer holds left out. Returns `[{id, uri, etag, vcard, books}]`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_readGoogleCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token: JString<'local>,
    ids: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token = read_string(env, &token);
        let ids = read_string(env, &ids);

        let json = match parse_strings(&ids) {
            Err(err) => error_json(err),
            Ok(ids) => {
                let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut people = Gpeople {
                    client: &mut client,
                    token: &token,
                };
                match people.read_cards(&ids) {
                    Ok(cards) => {
                        to_string(&cards).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.updateCardBooks`: edits a card's addressbook memberships.
///
/// On an account-level backend; adds and removes are JSON string arrays of
/// book ids. Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_updateCardBooks<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    base_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    id: JString<'local>,
    add: JString<'local>,
    remove: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let id = read_string(env, &id);
        let add = read_string(env, &add);
        let remove = read_string(env, &remove);
        let account = Account::new(&base_url, &login, &password);

        let json = match (parse_books(&add), parse_books(&remove)) {
            (Err(err), _) | (_, Err(err)) => error_json(err),
            (Ok(add), Ok(remove)) => {
                let books = CardBooksUpdate {
                    id: &id,
                    add: &add,
                    remove: &remove,
                };
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let mut contacts = Contacts {
                    client: &mut client,
                    account: &account,
                };
                match contacts.update_card_books(&books) {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// Serializes any backend's delta.
fn delta_json(delta: CardDelta) -> String {
    to_string(&delta).unwrap_or_else(|err| error_json(err.to_string()))
}

/// Serializes a pure computation's reply.
fn reply_json(reply: &impl Serialize) -> String {
    to_string(reply).unwrap_or_else(|err| error_json(err.to_string()))
}

/// The `{"vcard"}` reply of the verbs rewriting a card.
#[derive(Serialize)]
struct VcardReply {
    /// The rewritten card.
    vcard: String,
}

/// The `{"order"}` reply of `Native.cardTypeOrder`.
#[derive(Serialize)]
struct TypeOrderReply {
    /// Each spinner position's TYPE set.
    order: Vec<Vec<&'static str>>,
}

/// The `{"value"}` reply of `Native.formDate`.
#[derive(Serialize)]
struct DateReply {
    /// The date on the model wire.
    value: String,
}

/// `Native.indexCard`: indexes a vCard for the store.
///
/// Display name, first email and phone, every email, UID, normalized hash.
/// Returns `{"name", "email", "emails", "phone", "info", "uid", "hash"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_indexCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match index(&vcard) {
            Ok(index) => reply_json(&index),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.mergeCards`: merges several vCards into one union document.
///
/// Takes a JSON string array; the union comes with its field model and the
/// per-field alternatives of the merge form (docs/merged-view.md). Returns
/// `{"vcard", "model", "alternatives"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_mergeCards<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    cards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let cards = read_string(env, &cards);

        let json = match parse_strings(&cards) {
            Err(err) => error_json(err),
            Ok(cards) => match merge_cards(&cards) {
                Ok(merged) => reply_json(&merged),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.mergeCardChanges`: three-way merges a conflicted push.
///
/// The staged local edit and the fetched remote against their base, the
/// local side winning same-field collisions. Returns `{"vcard", "conflicts"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_mergeCardChanges<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    local: JString<'local>,
    remote: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base = read_string(env, &base);
        let local = read_string(env, &local);
        let remote = read_string(env, &remote);

        let json = match merge_conflict(&base, &local, &remote) {
            Ok(merged) => reply_json(&merged),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.mergeConflictForm`: the conflict form of a row edited both sides.
///
/// The newer side by REV pre-fills collisions, both candidates offered per
/// field. Returns `{"vcard", "model", "alternatives", "changed"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_mergeConflictForm<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base: JString<'local>,
    local: JString<'local>,
    remote: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base = read_string(env, &base);
        let local = read_string(env, &local);
        let remote = read_string(env, &remote);

        let json = match merge_conflict_form(&base, &local, &remote) {
            Ok(form) => reply_json(&form),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.setCardUid`: rewrites the card's UID.
///
/// A plain copy is a new identity. Returns `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_setCardUid<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    uid: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let uid = read_string(env, &uid);

        let json = match set_uid(&vcard, &uid) {
            Ok(vcard) => reply_json(&VcardReply { vcard }),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.findDuplicates`: groups likely-duplicate cards for the remover.
///
/// Exact normalized email, phone or name matches. Takes `[{"ref", "vcard"}]`,
/// returns `{"groups": [{"refs": [...], "reasons": [...]}]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_findDuplicates<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    cards: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let cards = read_string(env, &cards);

        let json = match parse_ref_cards(&cards) {
            Err(err) => error_json(err),
            Ok(cards) => match find_duplicates(&cards) {
                Ok(found) => reply_json(&found),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.cardTypeOrder`: the type-set vocabulary of the form's spinners.
///
/// For `phone`, `email`, `address`, `relation` or `gender`, each position's
/// TYPE set in the order the Android string-arrays must mirror. Returns
/// `{"order": [[..], ..]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_cardTypeOrder<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let json = reply_json(&TypeOrderReply {
            order: card_type_order(&kind),
        });
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.projectCard`: projects a vCard onto the neutral field model.
///
/// The model the app maps to ContactsContract rows
/// (docs/contacts-mapping.md). Returns the model JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_projectCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);

        let json = match ContactFields::project(&vcard) {
            Ok(fields) => reply_json(&fields),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.applyCard`: patches the edited field model back onto the vCard.
///
/// Every unmanaged property survives (docs/contacts-mapping.md). Returns
/// `{"vcard": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_applyCard<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    vcard: JString<'local>,
    model: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let vcard = read_string(env, &vcard);
        let model = read_string(env, &model);

        let json = match from_str::<ContactFields>(&model) {
            Err(err) => error_json(format!("Invalid field model: {err}")),
            Ok(model) => match model.apply(&vcard) {
                Ok(vcard) => reply_json(&VcardReply { vcard }),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.formView`: the edit form's view support from the field model.
///
/// Summaries, type spinner positions, picker dates. Returns `{"name",
/// "organization", "gender"?, "birthday"?, "anniversary"?, "phones",
/// "emails", "relations", "addresses"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_formView<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    model: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let model = read_string(env, &model);

        let json = match from_str(&model) {
            Err(err) => error_json(format!("Invalid field model: {err}")),
            Ok(model) => reply_json(&form_view(&model)),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.formEntry`: one typed entry saved from an edit dialog.
///
/// Its TYPE set drawn from the spinner position: `phone`, `email` and
/// `relation` return the full entry, `address` the TYPE set alone,
/// `gender` the GENDER object, empty when unset.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_formEntry<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    index: jint,
    value: JString<'local>,
    pref: jboolean,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let value = read_string(env, &value);

        let json = match form_entry(&kind, index as i64, &value, pref) {
            Ok(entry) => reply_json(&entry),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.formDate`: one picked date on the model wire.
///
/// The vCard `yyyy-mm-dd` form, 1-based month. Returns `{"value": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_formDate<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    year: jint,
    month: jint,
    day: jint,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let value = form_date(year as i64, month as i64, day as i64);
        let json = reply_json(&DateReply { value });

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.groupContacts`: groups the replica pool into merged contacts.
///
/// Sorted by primary display name (docs/merged-view.md). Takes `{"replicas":
/// [{ref, uid, name, id}], "links": {member: cluster}, "detached": [ref]}`,
/// returns `{"groups": [{key, replicas: [index]}]}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_groupContacts<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    input: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let input = read_string(env, &input);

        let json = match from_str(&input) {
            Err(err) => error_json(format!("Invalid replica pool: {err}")),
            Ok(pool) => reply_json(&group_contacts(&pool)),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.duplicateGroup`: a duplicate review group's dismissal key.
///
/// And its Link eligibility. Takes `[{ref, book}]`, returns `{"key",
/// "linkable"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_duplicateGroup<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    members: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let members = read_string(env, &members);

        let json = match from_str::<Vec<DuplicateMember>>(&members) {
            Err(err) => error_json(format!("Invalid duplicate group: {err}")),
            Ok(members) => reply_json(&duplicate_group(&members)),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}
