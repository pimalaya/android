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
        gmail::GmailEnvelope,
        listing::{Floor, Listing, MailPage, MailRequest, Named},
    },
    ffi::{
        error_json, parse_url, read_string,
        session::{self, MailSession},
    },
    mail::{self, Draft},
    types::{BridgeError, Mailbox},
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
    let Some(id) = session.listing().ids.get(mailbox).cloned() else {
        return Err(format!("No mailbox named `{mailbox}`").into());
    };
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
        let mut asked = Floor::new(Some(before.as_str()), count.max(1) as usize);
        let json = match floor(&mut client, handle, &mailbox, &mut asked) {
            Ok(()) => to_string(&asked.reply()).unwrap_or_else(|err| error_json(err.to_string())),
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
    floor: &mut Floor,
) -> Result<(), BridgeError> {
    let session = unsafe { session::borrow(handle) }?;
    if session.is_imap() {
        return session.imap(client)?.floor(mailbox, floor);
    }

    let Some(id) = session.listing().ids.get(mailbox).cloned() else {
        return Err(format!("No mailbox named `{mailbox}`").into());
    };
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.jmap_floor(&url, &session.credentials(), &id, floor);
    }
    if session.is_graph() {
        return client.graph_floor(session.credentials().password, &id, floor);
    }

    // NOTE: Gmail lists ids alone, so each is read for its metadata; the
    // session keeps what it read, and the round after this one reads none
    // of them again.
    let token = session.credentials().password.to_string();
    let scope = floor.scope().clone();
    let mut cursor: Option<String> = None;
    loop {
        let listed = client.list_gmail_page(&token, &id, &scope, cursor.as_deref())?;
        for message in &listed.ids {
            let date = gmail_envelope(client, session, &token, message)?
                .and_then(|envelope| envelope.summary.date);
            if floor.take(date.as_deref()) {
                return Ok(());
            }
        }
        match listed.next {
            Some(next) => cursor = Some(next),
            None => return Ok(()),
        }
    }
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
    let listed = if session.is_jmap() {
        let url = session.jmap_url()?;
        client.list_jmap_mailbox_roster(&url, &session.credentials())?
    } else if session.is_graph() {
        client.list_graph_mailboxes(session.credentials().password)?
    } else if session.is_gmail() {
        client.list_gmail_mailboxes(session.credentials().password)?
    } else {
        return client::imap::list_mailboxes(&mut session.imap(client)?);
    };

    let ids = &mut session.listing().ids;
    ids.clear();
    let mut roster = Vec::with_capacity(listed.len());
    for (id, mailbox) in listed {
        ids.insert(mailbox.name.clone(), id);
        roster.push(mailbox);
    }

    Ok(roster)
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

    let Some(id) = session.listing().ids.get(mailbox).cloned() else {
        return Err(format!("No mailbox named `{mailbox}`").into());
    };
    if session.is_gmail() {
        return list_gmail(client, session, &id, &request);
    }
    if session.is_jmap() {
        let url = session.jmap_url()?;
        return client.list_jmap_page(&url, &session.credentials(), &id, &request);
    }

    client.list_graph_page(session.credentials().password, &id, &request)
}

/// One page of a Gmail label's listing.
///
/// A delta replays the history from the checkpoint: a message it names
/// is read again, and is a member while it still carries the label, gone
/// from this mailbox otherwise; history Gmail no longer holds is refused
/// for the engine to open a round. A round lists the label's ids a page
/// at a time, the page token its cursor, each id read for its metadata,
/// the `historyId` taken before the first page its checkpoint, so what
/// moves during the round is replayed by the next delta rather than
/// missed.
fn list_gmail(
    client: &mut Client<'_, '_>,
    session: &mut MailSession,
    label: &str,
    request: &MailRequest,
) -> Result<MailPage, BridgeError> {
    let token = session.credentials().password.to_string();
    let scope = &request.scope;

    let (cursor, band) = match &request.listing {
        Listing::Delta { checkpoint } => {
            let Some((touched, next)) = client.gmail_history(&token, checkpoint)? else {
                return Ok(MailPage::rejected());
            };

            let mut items = Vec::new();
            let mut vanished = Vec::new();
            for id in touched {
                match gmail_envelope(client, session, &token, &id)? {
                    Some(envelope) if envelope.labels.iter().any(|filed| filed == label) => {
                        items.extend(envelope.named(&id, scope));
                    }
                    _ => vanished.push(id),
                }
            }
            return Ok(MailPage::delta(items, vanished, next));
        }
        Listing::Round { cursor, band } => (cursor.as_deref(), *band),
    };

    let checkpoint = match (cursor, band) {
        (None, false) => Some(client.gmail_history_id(&token)?),
        _ => None,
    };
    let listed = match client.list_gmail_page(&token, label, scope, cursor) {
        Ok(listed) => listed,
        // NOTE: a page token Gmail no longer honours restarts the round,
        // which relists and refetches nothing already named.
        Err(err) if cursor.is_some() && err.status == Some(400) => {
            return Ok(MailPage::rejected());
        }
        Err(err) => return Err(err),
    };

    let mut items = Vec::with_capacity(listed.ids.len());
    for id in &listed.ids {
        if let Some(envelope) = gmail_envelope(client, session, &token, id)? {
            items.extend(envelope.named(id, scope));
        }
    }

    Ok(MailPage::round(items, listed.next, checkpoint))
}

/// One Gmail message's labels and summary, read once per pass.
///
/// The session lasts one pass, so what it holds was read during it: a
/// message two labels list, or two mailboxes' histories replay, costs
/// one read.
fn gmail_envelope(
    client: &mut Client<'_, '_>,
    session: &mut MailSession,
    token: &str,
    id: &str,
) -> Result<Option<GmailEnvelope>, BridgeError> {
    if let Some(envelope) = session.listing().envelopes.get(id) {
        return Ok(Some(envelope.clone()));
    }

    let envelope = client.gmail_envelope(token, id)?;
    if let Some(envelope) = &envelope {
        session
            .listing()
            .envelopes
            .insert(id.to_string(), envelope.clone());
    }
    Ok(envelope)
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
    if session.is_graph() {
        return client.set_graph_flag(session.credentials().password, id, flag, add);
    }
    if session.is_gmail() {
        client.set_gmail_flag(session.credentials().password, id, flag, add)?;
        // NOTE: dropped rather than patched, so a later read in the same
        // pass asks Gmail what the change left.
        session.listing().envelopes.remove(id);
        return Ok(());
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
    if session.is_graph() {
        return client
            .delete_graph_message(session.credentials().password, id)
            .map(Some);
    }
    if session.is_gmail() {
        let trash = client.delete_gmail_message(session.credentials().password, id)?;
        session.listing().envelopes.remove(id);
        return Ok(Some(trash));
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
    if session.is_graph() || session.is_gmail() {
        // NOTE: Graph and Gmail submit through the session they read from
        // and file the sent copy themselves, so there is no copy to append
        // and no mailbox to name: the next sync reads it from there.
        let token = session.credentials().password;
        let sent = match session.is_graph() {
            true => client.send_graph_message(token, raw),
            false => client.send_gmail_message(token, raw),
        };
        return sent.map(|()| None).map_err(|err| Refused {
            permanent: matches!(err.status, Some(400 | 403 | 404 | 413 | 422)),
            message: err.to_string(),
        });
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
