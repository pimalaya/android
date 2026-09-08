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
    objects::{JByteArray, JClass, JObject, JString},
};
use serde_json::{from_str, json, to_string};

use crate::{
    account::{self, Backend},
    client::{self, Client},
    ffi::{error_json, parse_url, read_string},
    mail::{self, Draft},
    types::{BridgeError, Credentials, MailWalk},
};

/// `Native.syncMail`: connects to the account's mail server, lists its
/// mailboxes and returns the newest `limit` messages of each. Returns a
/// JSON object of `{mailboxes, messages}`, the roster carrying the RFC
/// 6154 role of each mailbox and the messages their envelope spine.
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
            Ok(walk) => to_string(&walk).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.fetchMessageSource`: reads one message whole, as the RFC 5322
/// bytes the server holds. Returns a JSON object of `{source}`, the
/// message base64-encoded, since a Java string is UTF-8 and a message is
/// not: what the caller decodes back is what the server sent, which is
/// what makes it storable.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_fetchMessageSource<'local>(
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
        let json = match read_source(&mut client, &url, &credentials, &mailbox, &id) {
            Ok(source) => json!({ "source": mail::base64(&source) }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.parseMessage`: one stored message to what the reader draws.
/// Returns a JSON object of
/// `{subject, from, fromAddress, to, cc, date, kind, body, attachments}`.
///
/// No transport, because there is nothing to reach for: the bytes are
/// the argument. This is what a message opened a second time costs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_parseMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let raw = env.convert_byte_array(&source).unwrap_or_default();
        let json = match mail::parse(&raw) {
            Ok(message) => to_string(&message).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Reads one message's source with whichever backend its base URL names.
///
/// The mailbox is only IMAP's concern: a JMAP `Email` id addresses the
/// message across the whole account, and the same message filed in two
/// mailboxes is one object with one id.
fn read_source(
    client: &mut Client<'_, '_>,
    base_url: &str,
    credentials: &Credentials,
    mailbox: &str,
    id: &str,
) -> Result<Vec<u8>, BridgeError> {
    match Backend::of(base_url) {
        Backend::Jmap => {
            let session_url = account::jmap_session_url(base_url)?;
            client.fetch_jmap_source(&session_url, credentials, id)
        }
        _ => {
            let url = parse_url(base_url)?;
            client::imap::fetch_source(client, &url, credentials, mailbox, id)
        }
    }
}

/// Walks the account's mail with whichever backend its base URL names.
fn sync_account(
    client: &mut Client<'_, '_>,
    base_url: &str,
    credentials: &Credentials,
    limit: u32,
) -> Result<MailWalk, BridgeError> {
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

/// `Native.composeMessage`: one draft to the RFC 5322 message an outbox
/// holds. Returns a JSON object of `{source}`, the message
/// base64-encoded, or `{"error": ".."}`.
///
/// No transport, because composing reaches for nothing: it is what lets
/// a message be written and queued with the radio off, which is the
/// whole of the outbox.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_composeMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    draft: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let draft = read_string(env, &draft);
        let json = match compose_message(&draft) {
            Ok(source) => json!({ "source": mail::base64(&source) }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.submitMessage`: hands one stored message over, then files a
/// copy in the account's sent mailbox. Returns `{"mailbox": ".."}` naming
/// where the copy landed, `{"mailbox": null}` when the account named no
/// sent mailbox, or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_submitMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    url: JString<'local>,
    submit_url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
    source: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let url = read_string(env, &url);
        let submit_url = read_string(env, &submit_url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let raw = env.convert_byte_array(&source).unwrap_or_default();
        let credentials = Credentials {
            login: &login,
            password: &password,
        };

        let mut client = Client::new(env, &transport);
        let json = match submit_message(&mut client, &url, &submit_url, &credentials, &raw) {
            Ok(sent) => json!({ "mailbox": sent }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Composes one draft, refusing what no backend here could ever send.
///
/// The refusal is here rather than at the submission because the outbox
/// is where the sender is still looking: a JMAP account submits through
/// `EmailSubmission/set` (RFC 8621 section 7), which is a different shape
/// entirely, and queueing a message nothing could hand over would only
/// fail later, out of sight.
fn compose_message(draft: &str) -> Result<Vec<u8>, BridgeError> {
    let draft: Draft = from_str(draft).map_err(|err| format!("Invalid draft: {err}"))?;
    mail::compose(&draft)
}

/// Submits one stored message over SMTP and files the copy the sender
/// keeps.
fn submit_message(
    client: &mut Client<'_, '_>,
    base_url: &str,
    submit_url: &str,
    credentials: &Credentials,
    raw: &[u8],
) -> Result<Option<String>, BridgeError> {
    if Backend::of(base_url) == Backend::Jmap {
        return Err("Sending from a JMAP account is not supported yet".into());
    }
    if submit_url.is_empty() {
        return Err("This account has no server to send through".into());
    }

    let composed = mail::envelope(raw)?;

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
