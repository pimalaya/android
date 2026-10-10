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
use serde_json::{from_str, json, to_string};

use crate::{
    client::{
        self, Client,
        convert::REFUSED,
        gmail_sync::{self, LiveGmail},
        listing::{Floor, FloorReply, MailPage, MailRequest, Named},
    },
    ffi::{
        error_json, parse_url, read_string,
        session::{self, MailSession},
    },
    mail::{self, Draft},
    types::{BridgeError, Mailbox},
};

/// `Native.openMailSession`: connects to the account's mail server and
/// authenticates, answering `{"handle": n, "expungesOne": bool}` or
/// `{"error": ".."}`; `expungesOne` says whether a delete for good erases
/// one message alone (UIDPLUS on IMAP).
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
            Ok(opened) => json!({
                "expungesOne": opened.expunges_one(),
                "handle": session::into_handle(opened),
            })
            .to_string(),
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

/// `Native.enumerateMailbox`: one page of a mailbox's listing, the
/// engine's `enumerate` yield answered (pimdir SYNC §4, §5).
///
/// `request` is the yield as the engine wrote it, `{listing, scope}`, and
/// `covered` when the store's coverage holds the scope: a delta from the
/// checkpoint, or a round over the scope from its first page or resumed
/// from a cursor. The answer is the reply the engine reads, `{items,
/// vanished, complete, last, cursor?, checkpoint?}` with every item
/// `{handle, flags, linkId, summary, sortKey}` named by its meta, but for
/// a Graph delta's, listed by `{handle, flags, linkId}` alone for the
/// caller to name (`nameMessages`) where the store binds it not; or
/// `{cursorRejected: true}` when the source refused the cursor.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_enumerateMailbox<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    request: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let request = read_string(env, &request);

        let mut client = Client::new(env, &transport);
        let json = match enumerate(&mut client, handle, &mailbox, &request) {
            Ok(page) => to_string(&page).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.nameMessages`: the messages a listing named by id and markers
/// alone (a Graph delta), each read with its summary. `handles` is a JSON
/// array of ids; the answer is `{items}`, every item named as a listed
/// one is, a message gone since it was listed left out.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_nameMessages<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    handles: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let handles = read_string(env, &handles);

        let mut client = Client::new(env, &transport);
        let json = match name_messages(&mut client, handle, &mailbox, &handles) {
            Ok(items) => json!({ "items": items }).to_string(),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Names messages listed by id alone; only Graph lists any.
fn name_messages(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    handles: &str,
) -> Result<Vec<Named>, BridgeError> {
    let ids: Vec<String> =
        from_str(handles).map_err(|err| format!("Invalid message ids: {err}"))?;
    let session = unsafe { session::borrow(handle) }?;
    if !session.is_graph() {
        return Err("Only a Graph listing names messages by id alone".into());
    }
    let id = mailbox_id(client, session, mailbox)?;
    client.name_graph_messages(session.credentials().password, &id, &ids)
}

/// `Native.mailFloor`: the floor of a mailbox's next chunk, the oldest
/// `Date` among its `count` newest messages dated before `before` (RFC
/// 3339 `Z`, empty for no ceiling). Returns `{"floor", "dated"}`, `floor`
/// null when fewer than `count` dated messages lie below the ceiling: the
/// mailbox is whole below it.
///
/// A chunk is a number of messages, never a span of time: the floor is
/// all the caller keeps, and it is the scope the next sync lists from.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_mailFloor<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    before: JString<'local>,
    count: i32,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let before = read_string(env, &before);

        let mut client = Client::new(env, &transport);
        let asked = Floor::new(Some(before.as_str()), count.max(1) as usize);
        let json = match floor(&mut client, handle, &mailbox, asked) {
            Ok(reply) => to_string(&reply).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// A chunk's floor with whichever backend the session speaks.
fn floor(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    mut floor: Floor,
) -> Result<FloorReply, BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_imap() {
        session.imap(client)?.floor(mailbox, &mut floor)?;
        return Ok(floor.reply());
    }

    let id = mailbox_id(client, session, mailbox)?;
    if session.is_jmap() {
        let url = session.jmap_url()?;
        client.jmap_floor(&url, &session.credentials(), &id, &mut floor)?;
        return Ok(floor.reply());
    }
    if session.is_graph() {
        client.graph_floor(session.credentials().password, &id, &mut floor)?;
        return Ok(floor.reply());
    }

    // NOTE: one floor per account, walked once per pool run whichever
    // mailbox asks, the envelopes it read kept for the rounds after it.
    let run = session.gmail_run();
    let token = session.credentials().password.to_string();
    let before = floor.scope().until.clone();
    let mut source = LiveGmail {
        client,
        token: &token,
    };
    gmail_sync::floor(&mut source, &run, &id, before.as_deref(), floor.count())
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
/// `{subject, from, fromAddress, to, cc, date, kind, body, attachments,
/// attachmentMark}`, each attachment `{name, mime, size, part}`.
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

/// `Native.messagePart`: writes the decoded bytes of one part of a stored
/// message, read from the file at `source`, by its IMAP section, to the
/// file at `target`, answering `{"size"}`, or `{"error": ".."}` when the
/// message holds no such part. Files on both sides, so no copy of the
/// part crosses the boundary: an attachment of tens of megabytes is one
/// decoded buffer, never a base64 string on the Java heap.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_messagePart<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
    section: JString<'local>,
    target: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let source = read_string(env, &source);
        let section = read_string(env, &section);
        let target = read_string(env, &target);
        let written = std::fs::read(&source)
            .map_err(|err| BridgeError::from(err.to_string()))
            .and_then(|raw| mail::part(&raw, &section))
            .and_then(|bytes| {
                std::fs::write(&target, &bytes)
                    .map(|()| bytes.len())
                    .map_err(|err| BridgeError::from(err.to_string()))
            });
        let json = match written {
            Ok(size) => json!({ "size": size }).to_string(),
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
/// mailboxes is one object with one id. Graph and Gmail message ids
/// address it across the mailbox the same way.
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
    if session.is_graph() {
        return client.fetch_graph_source(session.credentials().password, id);
    }
    if session.is_gmail() {
        // NOTE: a session of no pool run is the reader's, opening the
        // message for someone waiting on it: no queue behind the fill.
        client.urgent = session.alone();
        return client.fetch_gmail_source(session.credentials().password, id);
    }

    client::imap::fetch_source(&mut session.imap(client)?, mailbox, id)
}

/// The account's mailboxes with whichever backend the session speaks.
///
/// An HTTP session remembers the id each path maps to, since every later
/// verb addresses a mailbox by id and the roster is the only place that
/// answer comes from.
fn list_mailboxes(client: &mut Client<'_, '_>, handle: i64) -> Result<Vec<Mailbox>, BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_imap() {
        return client::imap::list_mailboxes(&mut session.imap(client)?);
    }

    let listed = http_roster(client, session)?;
    Ok(session.listing().remember(listed))
}

/// The roster of an HTTP session, each mailbox with the id it is
/// addressed by.
fn http_roster(
    client: &mut Client<'_, '_>,
    session: &MailSession,
) -> Result<Vec<(String, Mailbox)>, BridgeError> {
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.list_jmap_mailbox_roster(&url, &session.credentials());
    }
    if session.is_graph() {
        return client.list_graph_mailboxes(session.credentials().password);
    }
    client.list_gmail_mailboxes(session.credentials().password)
}

/// The id an HTTP session addresses a mailbox by, the roster read on
/// demand when the session has not read it ([`session::MailListing::resolve`]),
/// so no caller has to list the mailboxes before widening one.
fn mailbox_id(
    client: &mut Client<'_, '_>,
    session: &mut MailSession,
    mailbox: &str,
) -> Result<String, BridgeError> {
    // NOTE: the listing is lifted out while the roster is read, which
    // needs the session's credentials, and put back whatever came of it.
    let mut listing = std::mem::take(session.listing());
    let id = listing.resolve(mailbox, || http_roster(client, session));
    *session.listing() = listing;
    id
}

/// One page of a mailbox's listing with whichever backend the session
/// speaks.
fn enumerate(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    request: &str,
) -> Result<MailPage, BridgeError> {
    let request: MailRequest =
        from_str(request).map_err(|err| format!("Invalid listing request: {err}"))?;
    let session = unsafe { session::borrow(handle) }?;

    if session.is_imap() {
        return session.imap(client)?.list_page(mailbox, &request);
    }

    let id = mailbox_id(client, session, mailbox)?;
    if session.is_gmail() {
        // NOTE: a label's page is the account's listing projected onto it,
        // read once for every session of the pool run.
        let run = session.gmail_run();
        let token = session.credentials().password.to_string();
        let mut source = LiveGmail {
            client,
            token: &token,
        };
        return gmail_sync::list_label(&mut source, &run, &id, &request);
    }
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.list_jmap_page(&url, &session.credentials(), &id, &request);
    }

    client.list_graph_page(session.credentials().password, &id, &request)
}

/// `Native.joinMailRun`: has a session work for the pool run numbered
/// `run`, sharing with the run's other sessions what it reads of a Gmail
/// account (its listing, envelopes and history), which the run drops
/// with it; 0 leaves the run for the session's own.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_joinMailRun(
    _env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: i64,
    run: i64,
) {
    if let Ok(session) = unsafe { session::borrow(handle) } {
        session.join_run(run);
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

/// `Native.relocateMessage`: moves one message from `mailbox` into
/// `target` (pimdir SYNC §4, a `Remove` carrying `to`). Returns an empty
/// JSON object, or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_relocateMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    id: JString<'local>,
    target: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let id = read_string(env, &id);
        let target = read_string(env, &target);

        let mut client = Client::new(env, &transport);
        let json = match relocate(&mut client, handle, &mailbox, &id, &target) {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.copyMessage`: copies one message from `mailbox` into `target`
/// on the server (pimdir SYNC §4, an `Add` carrying an origin). Returns an
/// empty JSON object, or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_copyMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    id: JString<'local>,
    target: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let id = read_string(env, &id);
        let target = read_string(env, &target);

        let mut client = Client::new(env, &transport);
        let json = match copy(&mut client, handle, &mailbox, &id, &target) {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.destroyMessage`: deletes one message for good (pimdir SYNC §4,
/// a `Remove` carrying no `to`). Returns an empty JSON object, or
/// `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_destroyMessage<'local>(
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
        let json = match destroy(&mut client, handle, &mailbox, &id) {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.appendMessage`: appends one message to `mailbox` with `flags`
/// (a JSON array of markers), the push of a create staged with no origin.
/// IMAP only. Returns an empty JSON object, or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_appendMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    source: JByteArray<'local>,
    flags: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let raw = env.convert_byte_array(&source).unwrap_or_default();
        let flags = read_string(env, &flags);

        let mut client = Client::new(env, &transport);
        let json = match append(&mut client, handle, &mailbox, raw, &flags) {
            Ok(()) => String::from("{}"),
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
    if session.is_graph() {
        return client.set_graph_flag(session.credentials().password, id, flag, add);
    }
    if session.is_gmail() {
        client.set_gmail_flag(session.credentials().password, id, flag, add)?;
        // NOTE: dropped rather than patched, so a later read in the same
        // run asks Gmail what the change left.
        session.gmail_run().forget(id);
        return Ok(());
    }

    session.imap(client)?.store_flag(mailbox, id, flag, add)
}

/// Relocates one message with whichever backend the session speaks.
fn relocate(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    id: &str,
    target: &str,
) -> Result<(), BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_imap() {
        return session.imap(client)?.relocate(mailbox, id, target);
    }

    let from = mailbox_id(client, session, mailbox)?;
    let to = mailbox_id(client, session, target)?;
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.relocate_jmap_message(&url, &session.credentials(), id, &from, &to);
    }
    if session.is_graph() {
        return client.relocate_graph_message(session.credentials().password, id, &to);
    }

    client.relocate_gmail_message(session.credentials().password, id, &from, &to)?;
    // NOTE: dropped rather than patched, so a later read in the same run
    // asks Gmail what the change left.
    session.gmail_run().forget(id);
    Ok(())
}

/// Copies one message with whichever backend the session speaks.
fn copy(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    id: &str,
    target: &str,
) -> Result<(), BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_imap() {
        return session.imap(client)?.copy(mailbox, id, target);
    }

    let to = mailbox_id(client, session, target)?;
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.copy_jmap_message(&url, &session.credentials(), id, &to);
    }
    if session.is_graph() {
        return client.copy_graph_message(session.credentials().password, id, &to);
    }

    client.copy_gmail_message(session.credentials().password, id, &to)?;
    session.gmail_run().forget(id);
    Ok(())
}

/// Deletes one message for good with whichever backend the session
/// speaks.
fn destroy(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    id: &str,
) -> Result<(), BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.destroy_jmap_message(&url, &session.credentials(), id);
    }
    if session.is_graph() {
        return client.destroy_graph_message(session.credentials().password, id);
    }
    if session.is_gmail() {
        client.destroy_gmail_message(session.credentials().password, id)?;
        session.gmail_run().forget(id);
        return Ok(());
    }

    session.imap(client)?.destroy(mailbox, id)
}

/// Appends one message; only an IMAP session files mail it was handed.
fn append(
    client: &mut Client<'_, '_>,
    handle: i64,
    mailbox: &str,
    raw: Vec<u8>,
    flags: &str,
) -> Result<(), BridgeError> {
    let flags: Vec<String> = from_str(flags).map_err(|err| format!("Invalid markers: {err}"))?;
    let session = unsafe { session::borrow(handle) }?;
    if !session.is_imap() {
        return Err("Only an IMAP session appends a message".into());
    }
    session.imap(client)?.append(mailbox, raw, &flags)
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

/// `Native.submitMessage`: hands one stored message over. Returns a JSON
/// object of `{messageId}`, the provider's own or null, or
/// `{"error": "..", "permanent": bool}`.
///
/// The submission opens its own connection, SMTP being a second server.
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
            Ok(filed) => json!({ "messageId": filed }).to_string(),
            Err(refused) => {
                json!({ "error": refused.message, "permanent": refused.permanent }).to_string()
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Composes one draft into the message the outbox holds, the same bytes
/// whichever backend hands it over.
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

/// Submits one stored message, over SMTP or the session's own API.
/// Returns the `Message-ID` the provider filed its sent copy under when
/// it stamped one of its own.
///
/// The submission alone: the sent copy is a create staged in the sent
/// mailbox when the message was queued, landed by the provider's own
/// filing or pushed as an append on IMAP.
fn submit_message(
    client: &mut Client<'_, '_>,
    handle: i64,
    submit_url: &str,
    raw: &[u8],
) -> Result<Option<String>, Refused> {
    let session = unsafe { session::borrow(handle) }.map_err(Refused::transient)?;
    if session.is_jmap() {
        // NOTE: JMAP submits through the session it reads from, and the
        // server files the copy in Sent once it accepts the message.
        let url = session.jmap_url().map_err(Refused::transient)?;
        client
            .send_jmap_message(&url, &session.credentials(), raw)
            .map_err(|err| Refused {
                permanent: matches!(err.status, Some(413 | REFUSED)),
                message: err.to_string(),
            })?;
        return Ok(None);
    }
    if session.is_graph() || session.is_gmail() {
        // NOTE: Graph and Gmail submit through the session they read from
        // and file the sent copy themselves.
        let token = session.credentials().password;
        let refused = |err: BridgeError| Refused {
            permanent: matches!(err.status, Some(400 | 403 | 404 | 413 | 422)),
            message: err.to_string(),
        };
        if session.is_graph() {
            client.send_graph_message(token, raw).map_err(refused)?;
            return Ok(None);
        }

        // NOTE: Gmail replaces the Message-ID of what it sends, so its sent
        // copy is read back for the one it stamped. The message has left
        // either way: a failed read only leaves the staged copy unmatched.
        let id = client.send_gmail_message(token, raw).map_err(refused)?;
        let filed = match client.gmail_envelope(token, &id) {
            Ok(envelope) => envelope.and_then(|envelope| envelope.summary.message_id),
            Err(err) => {
                log::warn!("sent copy {id} unread: {err}");
                None
            }
        };
        return Ok(filed);
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
    Ok(None)
}
