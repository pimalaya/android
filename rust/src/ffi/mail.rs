//! Mail JNI entry points.
//!
//! Every verb here but the two pure ones runs on a session the caller
//! opened and holds ([`crate::ffi::session`]), so a pass that walks an
//! account and then writes three markers is one connection rather than
//! four. What the caller passes back on each call is the handle and the
//! transport; what it never passes again is the endpoint or the
//! credential, which the session holds.
//!
//! Two backends, told apart when the session is opened rather than on
//! every verb: an IMAP session behind an `imaps://` URL, or the RFC 8621
//! verbs behind the `jmap://` marker.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JByteArray, JClass, JObject, JString},
};
use serde::Serialize;
use serde_json::{from_str, json, to_string};

use crate::{
    client::{self, Client},
    ffi::{
        error_json, parse_url, read_string,
        session::{self, MailSession},
    },
    mail::{self, Draft},
    types::{BridgeError, Mailbox, Message},
};

/// `Native.openMailSession`: connects to the account's mail server and
/// authenticates, answering `{"handle": n}` or `{"error": ".."}`.
///
/// The handle is a pointer the caller owns until it gives it back to
/// `closeMailSession`, and it is only ever used from one thread at a
/// time; the Java `MailSession` is what holds it to that.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_openMailSession<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    url: JString<'local>,
    login: JString<'local>,
    password: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let url = read_string(env, &url);
        let login = read_string(env, &login);
        let password = read_string(env, &password);

        let mut client = Client::new(env, &transport);
        let json = match MailSession::open(&mut client, &url, &login, &password) {
            Ok(opened) => json!({ "handle": session::into_handle(opened) }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.closeMailSession`: frees the session a handle names.
///
/// The sockets under it are the transport's, so closing that is the
/// caller's other half of the same act.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_closeMailSession(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: i64,
) {
    unsafe { session::drop_handle(handle) };
}

/// `Native.listMailboxes`: the account's mailboxes and the RFC 6154 role
/// of each. Returns a JSON array of `{name, role}` objects.
///
/// The roster alone. It used to carry a window of every mailbox's
/// messages with it, because every native call was a connection; a
/// session lasts the pass now, so each mailbox is enumerated when its
/// turn comes and only what changed is read.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_listMailboxes<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mut client = Client::new(env, &transport);
        let json = match list_mailboxes(&mut client, handle) {
            Ok(roster) => to_string(&roster).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.enumerateMailbox`: one mailbox's spine from the cursor the
/// last pass stored, as `{items, vanished, complete, checkpoint}`, each
/// item `{id, flags}` and no envelope.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_enumerateMailbox<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    cursor: JString<'local>,
    limit: i32,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let cursor = read_string(env, &cursor);
        let limit = limit.max(1) as u32;

        let mut client = Client::new(env, &transport);
        let json = match enumerate(&mut client, handle, &mailbox, &cursor, limit) {
            Ok(round) => to_string(&round).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.fetchEnvelopes`: the envelope spine of the named messages,
/// and of no others. Returns a JSON array of message objects.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_fetchEnvelopes<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    ids: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let ids = read_string(env, &ids);

        let mut client = Client::new(env, &transport);
        let json = match fetch_envelopes(&mut client, handle, &mailbox, &ids) {
            Ok(messages) => to_string(&messages).unwrap_or_else(|err| error_json(err.to_string())),
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
    handle: i64,
    mailbox: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let id = read_string(env, &id);

        let mut client = Client::new(env, &transport);
        let json = match read_source(&mut client, handle, &mailbox, &id) {
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
/// No transport and no session, because there is nothing to reach for:
/// the bytes are the argument. This is what a message opened a second
/// time costs.
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

/// Reads one message's source with whichever backend the session speaks.
///
/// The mailbox is only IMAP's concern: a JMAP `Email` id addresses the
/// message across the whole account, and the same message filed in two
/// mailboxes is one object with one id.
fn read_source(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    id: &str,
) -> Result<Vec<u8>, BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.fetch_jmap_source(&url, &session.credentials(), id);
    }

    client::imap::fetch_source(&mut session.imap(client)?, mailbox, id)
}

/// The account's mailboxes with whichever backend the session speaks.
///
/// A JMAP session remembers the id each path maps to, since every later
/// verb addresses a mailbox by id and the roster is the only place that
/// answer comes from.
fn list_mailboxes(client: &mut Client<'_, '_>, handle: i64) -> Result<Vec<Mailbox>, BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if !session.is_jmap() {
        return client::imap::list_mailboxes(&mut session.imap(client)?);
    }

    let url = session.jmap_url()?;
    let listed = client.list_jmap_mailbox_roster(&url, &session.credentials())?;

    let ids = session.jmap_ids();
    ids.clear();
    let mut roster = Vec::with_capacity(listed.len());
    for (id, mailbox) in listed {
        ids.insert(mailbox.name.clone(), id);
        roster.push(mailbox);
    }

    Ok(roster)
}

/// One mailbox's spine with whichever backend the session speaks.
fn enumerate(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    cursor: &str,
    limit: u32,
) -> Result<EnumerationJson, BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    let cursor = Some(cursor).filter(|value| !value.is_empty());

    if !session.is_jmap() {
        let round = session.imap(client)?.enumerate(mailbox, cursor, limit)?;
        return Ok(round.into());
    }

    let url = session.jmap_url()?;
    let Some(id) = session.jmap_ids().get(mailbox).cloned() else {
        return Err(format!("No JMAP mailbox named `{mailbox}`").into());
    };
    let messages = client.query_jmap_mailbox(&url, &session.credentials(), &id, mailbox, limit)?;

    // Kept for the fetch that follows: with no `Email/changes` wired the
    // round already read what the fetch would ask for, and asking twice
    // would be the avoidable half of a whole round.
    let listed = session.jmap_listed();
    listed.clear();
    let mut items = Vec::with_capacity(messages.len());
    for message in messages {
        items.push(SpineJson {
            id: message.id.clone(),
            flags: flags_of(&message),
        });
        listed.insert(message.id.clone(), message);
    }

    Ok(EnumerationJson {
        items,
        vanished: Vec::new(),
        complete: true,
        checkpoint: String::new(),
    })
}

/// The named messages' envelopes with whichever backend the session
/// speaks.
fn fetch_envelopes(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    ids: &str,
) -> Result<Vec<Message>, BridgeError> {
    let ids: Vec<String> = from_str(ids).map_err(|err| format!("Invalid id list: {err}"))?;
    let session = unsafe { session::borrow(handle) }?;

    if !session.is_jmap() {
        let borrowed: Vec<&str> = ids.iter().map(String::as_str).collect();
        return session.imap(client)?.fetch_envelopes(mailbox, &borrowed);
    }

    let listed = session.jmap_listed();
    Ok(ids
        .iter()
        .filter_map(|id| listed.get(id).cloned())
        .collect())
}

/// The IMAP markers a JMAP message carries, named the IMAP way.
fn flags_of(message: &Message) -> Vec<String> {
    let mut flags = Vec::new();
    if message.seen {
        flags.push(String::from("\\Seen"));
    }
    if message.answered {
        flags.push(String::from("\\Answered"));
    }
    if message.flagged {
        flags.push(String::from("\\Flagged"));
    }
    flags
}

/// One enumerated mailbox on the JSON wire.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct EnumerationJson {
    items: Vec<SpineJson>,
    vanished: Vec<String>,
    complete: bool,
    checkpoint: String,
}

/// One member of an enumerated mailbox on the JSON wire.
#[derive(Serialize)]
struct SpineJson {
    id: String,
    flags: Vec<String>,
}

impl From<client::imap::Enumeration> for EnumerationJson {
    fn from(round: client::imap::Enumeration) -> Self {
        Self {
            items: round
                .items
                .into_iter()
                .map(|entry| SpineJson {
                    id: entry.id,
                    flags: entry.flags,
                })
                .collect(),
            vanished: round.vanished,
            complete: round.complete,
            checkpoint: round.checkpoint,
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
    handle: i64,
    mailbox: JString<'local>,
    id: JString<'local>,
    flag: JString<'local>,
    add: bool,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let id = read_string(env, &id);
        let flag = read_string(env, &flag);

        let mut client = Client::new(env, &transport);
        let json = match set_flag(&mut client, handle, &mailbox, &id, &flag, add) {
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
    handle: i64,
    mailbox: JString<'local>,
    id: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let id = read_string(env, &id);

        let mut client = Client::new(env, &transport);
        let json = match delete_message(&mut client, handle, &mailbox, &id) {
            Ok(trash) => json!({ "mailbox": trash }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Writes one marker with whichever backend the session speaks.
fn set_flag(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    id: &str,
    flag: &str,
    add: bool,
) -> Result<(), BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.set_jmap_keyword(&url, &session.credentials(), id, flag, add);
    }

    session.imap(client)?.store_flag(mailbox, id, flag, add)
}

/// Deletes one message with whichever backend the session speaks.
fn delete_message(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    id: &str,
) -> Result<Option<String>, BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client
            .delete_jmap_message(&url, &session.credentials(), id)
            .map(Some);
    }

    session.imap(client)?.delete_message(mailbox, id)
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
///
/// The submission opens its own connection, SMTP being a second server;
/// the copy is filed on the session, which is already open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_submitMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    submit_url: JString<'local>,
    source: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let submit_url = read_string(env, &submit_url);
        let raw = env.convert_byte_array(&source).unwrap_or_default();

        let mut client = Client::new(env, &transport);
        let json = match submit_message(&mut client, handle, &submit_url, &raw) {
            Ok(sent) => json!({ "mailbox": sent }).to_string(),
            Err(refused) => {
                json!({ "error": refused.message, "permanent": refused.permanent }).to_string()
            }
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

/// One submission that did not happen, and whether it ever could.
///
/// The queue reads this: a message the server refused is parked with
/// what it said, and everything else stays queued for the next drain.
struct Refused {
    message: String,
    permanent: bool,
}

impl Refused {
    /// A failure that says nothing about the message, so the message
    /// keeps its place in the queue.
    fn transient(err: impl ToString) -> Self {
        Self {
            message: err.to_string(),
            permanent: false,
        }
    }
}

/// Submits one stored message over SMTP and files the copy the sender
/// keeps.
fn submit_message(
    client: &mut Client<'_, '_>,
    handle: i64,
    submit_url: &str,
    raw: &[u8],
) -> Result<Option<String>, Refused> {
    let session = unsafe { session::borrow(handle) }.map_err(Refused::transient)?;
    if session.is_jmap() {
        return Err(Refused::transient(
            "Sending from a JMAP account is not supported yet",
        ));
    }
    if submit_url.is_empty() {
        return Err(Refused::transient(
            "This account has no server to send through",
        ));
    }

    let composed = mail::envelope(raw).map_err(Refused::transient)?;
    let submit = parse_url(submit_url).map_err(Refused::transient)?;
    client::smtp::send(client, &submit, &session.credentials(), &composed).map_err(|err| {
        Refused {
            permanent: err.is_permanent(),
            message: err.to_string(),
        }
    })?;

    // NOTE: after the submission and never instead of it. A copy filed
    // for a message that was not sent is a lie the sender reads as a
    // sent message; a message sent with no copy filed is only a missing
    // record, which the next sync cannot invent but nobody loses over.
    //
    // Which is also why a failure here is not the caller's to retry:
    // the message has gone, and running the action again to file its
    // copy would send it a second time.
    let filed = session
        .imap(client)
        .and_then(|mut imap| imap.append_sent(composed.message));

    match filed {
        Ok(mailbox) => Ok(mailbox),
        Err(err) => {
            log::warn!("could not file the sent copy: {err}");
            Ok(None)
        }
    }
}
