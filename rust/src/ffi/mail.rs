//! Mail JNI entry points.
//!
//! The reads are one call per account rather than one per mailbox: both
//! backends authenticate once and walk every mailbox inside that one
//! authentication, which is the whole difference from the WebDAV side's
//! per-collection calls. The writes are one call per message, since
//! that is what a reader does one of at a time.
//!
//! Two backends, told apart by the account's base URL the way the card
//! entry points do it: an IMAP session behind an `imaps://` URL, or the
//! RFC 8621 verbs behind the `jmap://` marker. A match here rather than
//! a dispatch module, which for two branches would be indirection with
//! nothing to dispatch.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use serde_json::{from_str, json, to_string};

use crate::{
    account::{self, Backend},
    client::{self, Client},
    ffi::{error_json, parse_url, read_string},
    mail::{self, Draft},
    types::{BridgeError, Credentials, Message, MessageBody},
};

/// `Native.syncMail`: connects to the account's mail server, lists its
/// mailboxes and returns the newest `limit` messages of each. Returns a
/// JSON array of `{mailbox, id, subject, from, date, seen}` objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_syncMail<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    limit: i32,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };
        let limit = limit.max(1) as u32;

        let mut client = Client::new(env, &transport);
        let json = match sync_account(&mut client, &url, &credentials, limit) {
            Ok(messages) => to_string(&messages).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.fetchMessage`: reads one message whole, headers and the one
/// body a reader sees. Returns a JSON object of
/// `{subject, from, fromAddress, to, cc, date, kind, body, attachments}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_fetchMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    mailbox: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let mailbox = read_string(env, &mailbox);
        let id = read_string(env, &id);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match read_message(&mut client, &url, &credentials, &mailbox, &id) {
            Ok(message) => to_string(&message).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Reads one message with whichever backend its base URL names.
///
/// The mailbox is only IMAP's concern: a JMAP `Email` id addresses the
/// message across the whole account, and the same message filed in two
/// mailboxes is one object with one id.
fn read_message(
    client: &mut Client<'_, '_>,
    base_url: &str,
    credentials: &Credentials,
    mailbox: &str,
    id: &str,
) -> Result<MessageBody, BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => {
            let session_url = account::jmap_session_url(base_url)?;
            client.fetch_jmap_message(&session_url, credentials, id)
        }
        _ => {
            let url = parse_url(base_url)?;
            client::imap::fetch_message(client, &url, credentials, mailbox, id)
        }
    }
}

/// Walks the account's mail with whichever backend its base URL names.
fn sync_account(
    client: &mut Client<'_, '_>,
    base_url: &str,
    credentials: &Credentials,
    limit: u32,
) -> Result<Vec<Message>, BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => {
            let session_url = account::jmap_session_url(base_url)?;
            client.sync_jmap_account(&session_url, credentials, limit)
        }
        // NOTE: a mail account with no sentinel is an `imaps://` URL,
        // the only other endpoint the connection flow builds.
        _ => {
            let url = parse_url(base_url)?;
            client::imap::sync_account(client, &url, credentials, limit)
        }
    }
}

/// `Native.setMessageFlag`: adds or removes one marker (`\Seen`,
/// `\Answered`, `\Flagged`) on one message. Returns an empty JSON object,
/// or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_setMessageFlag<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    mailbox: JString<'local>,
    id: JString<'local>,
    flag: JString<'local>,
    add: bool,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let mailbox = read_string(env, &mailbox);
        let id = read_string(env, &id);
        let flag = read_string(env, &flag);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match set_flag(&mut client, &url, &credentials, &mailbox, &id, &flag, add) {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.deleteMessage`: deletes one message into the account's trash.
/// Returns `{"mailbox": ".."}` naming where it landed, `{"mailbox": null}`
/// when the account named no trash and the message was marked deleted
/// where it is, or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_deleteMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    mailbox: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let mailbox = read_string(env, &mailbox);
        let id = read_string(env, &id);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match delete_message(&mut client, &url, &credentials, &mailbox, &id) {
            Ok(trash) => json!({ "mailbox": trash }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Writes one marker with whichever backend the base URL names.
fn set_flag(
    client: &mut Client<'_, '_>,
    base_url: &str,
    credentials: &Credentials,
    mailbox: &str,
    id: &str,
    flag: &str,
    add: bool,
) -> Result<(), BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => {
            let session_url = account::jmap_session_url(base_url)?;
            client.set_jmap_keyword(&session_url, credentials, id, flag, add)
        }
        _ => {
            let url = parse_url(base_url)?;
            let mut session = client::imap::ImapSession::new(client, &url);
            session.connect(credentials)?;
            session.store_flag(mailbox, id, flag, add)
        }
    }
}

/// Deletes one message with whichever backend the base URL names.
fn delete_message(
    client: &mut Client<'_, '_>,
    base_url: &str,
    credentials: &Credentials,
    mailbox: &str,
    id: &str,
) -> Result<Option<String>, BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => {
            let session_url = account::jmap_session_url(base_url)?;
            client
                .delete_jmap_message(&session_url, credentials, id)
                .map(Some)
        }
        _ => {
            let url = parse_url(base_url)?;
            let mut session = client::imap::ImapSession::new(client, &url);
            session.connect(credentials)?;
            session.delete_message(mailbox, id)
        }
    }
}

/// `Native.sendMessage`: composes one draft and hands it over, then
/// files a copy in the account's sent mailbox. Returns
/// `{"mailbox": ".."}` naming where the copy landed, `{"mailbox": null}`
/// when the account named no sent mailbox, or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_sendMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    url: JString<'local>,
    submit_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    draft: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let url = read_string(env, &url);
        let submit_url = read_string(env, &submit_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let draft = read_string(env, &draft);
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match send_message(&mut client, &url, &submit_url, &credentials, &draft) {
            Ok(sent) => json!({ "mailbox": sent }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Submits one draft with whichever backend the base URL names, and
/// files the copy the sender keeps.
///
/// SMTP only for now. A JMAP account submits through
/// `EmailSubmission/set` (RFC 8621 section 7), which is a different
/// shape entirely: the message is created as an `Email` first and the
/// submission names it. Refusing beats composing a message that goes
/// nowhere.
fn send_message(
    client: &mut Client<'_, '_>,
    base_url: &str,
    submit_url: &str,
    credentials: &Credentials,
    draft: &str,
) -> Result<Option<String>, BridgeError> {
    if Backend::of(base_url) == Backend::Jmap {
        return Err("Sending from a JMAP account is not supported yet".into());
    }
    if submit_url.is_empty() {
        return Err("This account has no server to send through".into());
    }

    let draft: Draft = from_str(draft).map_err(|err| format!("Invalid draft: {err}"))?;
    let composed = mail::compose(&draft)?;

    client::smtp::send(client, &parse_url(submit_url)?, credentials, &composed)?;

    // NOTE: after the submission and never instead of it. A copy filed
    // for a message that was not sent is a lie the sender reads as a
    // sent message; a message sent with no copy filed is only a missing
    // record, which the next sync cannot invent but nobody loses over.
    let url = parse_url(base_url)?;
    let mut session = client::imap::ImapSession::new(client, &url);
    session.connect(credentials)?;
    session.append_sent(composed.message)
}
