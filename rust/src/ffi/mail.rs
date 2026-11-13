//! # Mail exports
//!
//! The mail exports: the session verbs, submission, and the pure parse,
//! describe and compose ones.
//!
//! A session verb runs on a session the caller opened and holds
//! ([`mail::session`]), so a pass walking an account then writing three
//! markers is one connection, not four. Each call passes the handle and the
//! transport, never again the endpoint or the credential.
//!
//! The backend is told apart once, when the session opens, not per verb.

use std::{
    fs::{self, File},
    path::Path,
};

use io_pimdir::intent::PimdirSubmit;
use jni::{
    EnvUnowned,
    errors::Error,
    objects::{JByteArray, JClass, JObject, JString},
};
use serde::Serialize;
use serde_json::{from_str, to_string};

use crate::{
    client::Client,
    error::BridgeError,
    ffi::{ErrorReply, error_json, host::JniHost, parse_account, parse_url, read_string},
    mail::{
        self,
        compose::{self, Draft},
        listing::{Floor, MailRequest, Named},
        markdown, read,
        session::{
            FlagWrite, Mail, MailSession, MailStep, MailStepRequest, MessageAppend, MessageFetch,
            MessageTransfer, Refused,
        },
        smtp,
    },
    offline::{blobs, wire::sorted},
};

/// `Native.openMailSession`: connects and authenticates a mail session.
///
/// Returns `{"handle": n, "expungesOne": bool}`, `expungesOne` when a delete
/// for good erases one message alone (UIDPLUS on IMAP). Java's `MailSession`
/// owns the handle until `closeMailSession`, using it from one thread.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_openMailSession<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);

        let json = match parse_account(&account) {
            Err(err) => error_json(err),
            Ok(account) => {
                let mut client = Client::new(JniHost::new(env, &transport, None));
                match MailSession::open(&mut client, account) {
                    Ok(opened) => to_string(&OpenedReply {
                        expunges_one: opened.expunges_one(),
                        handle: into_handle(opened),
                    })
                    .unwrap_or_else(|err| error_json(err.to_string())),
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.closeMailSession`: frees the session a handle names.
///
/// The sockets under it are the transport's, so closing that is the
/// caller's other half of the same act.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_closeMailSession(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: i64,
) {
    env.with_env(|_| -> Result<(), Error> {
        unsafe { drop_handle(handle) };
        Ok(())
    })
    .resolve::<ErrorReply>()
}

/// `Native.listMailboxes`: the account's mailboxes and their RFC 6154 roles.
///
/// Returns a JSON array of `{name, role}` objects. The roster alone: the
/// session lasts the pass, so each mailbox is enumerated in its turn.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_listMailboxes<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mut client = Client::new(JniHost::new(env, &transport, None));
        let json =
            match unsafe { bind(handle, &mut client) }.and_then(|mut mail| mail.list_mailboxes()) {
                Ok(roster) => to_string(&roster).unwrap_or_else(|err| error_json(err.to_string())),
                Err(err) => error_json(err),
            };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.enumerateMailbox`: answers the engine's `enumerate` with a page.
///
/// `request` is a [`MailRequest`] (pimdir SYNC §4, §5), the yield the app
/// read. Returns a [`MailPage`](mail::listing::MailPage) (`{checkpoint?,
/// complete, cursor?, cursorRejected?, items, last, vanished}`), each item a
/// [`Named`] (`{flags, handle, linkId, sortKey?, summary?}`).
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

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let page = match from_str::<MailRequest>(&request) {
            Ok(request) => unsafe { bind(handle, &mut client) }
                .and_then(|mut mail| mail.enumerate(&mailbox, &request)),
            Err(err) => Err(format!("Invalid listing request: {err}").into()),
        };
        let json = match page {
            Ok(page) => to_string(&page).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.nameMessages`: names the messages a Graph delta listed bare.
///
/// `handles` is a JSON array of ids, each read with its summary. Returns
/// `{items}`, named as listed ones are, a message gone since left out.
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

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let named = match from_str::<Vec<String>>(&handles) {
            Ok(ids) => unsafe { bind(handle, &mut client) }
                .and_then(|mut mail| mail.name_messages(&mailbox, &ids)),
            Err(err) => Err(format!("Invalid message ids: {err}").into()),
        };
        let json = match named {
            Ok(items) => {
                to_string(&NamedReply { items }).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.mailFloor`: the `Date` floor of a mailbox's next `count` messages.
///
/// The `count`th newest `Date` before `before` (RFC 3339 `Z`, or empty), the
/// next sync's scope. Returns `{"floor", "dated", "dates"}`, `floor` null
/// when the mailbox is whole below, `dates` those taken, newest first.
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

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let asked = Floor::new(Some(before.as_str()), count.max(1) as usize);
        let json = match unsafe { bind(handle, &mut client) }
            .and_then(|mut mail| mail.floor(&mailbox, asked))
        {
            Ok(reply) => to_string(&reply).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.mailStep`: one Gmail, JMAP or Graph step (the app's `MailStep`).
///
/// About `count` messages the store lacks, read whole into files of `dir`,
/// from `mailbox` or else the account; `request` is a [`MailStepRequest`],
/// `store` asked by [`Client::held`]. Replies [`mail::session::MailStepReply`].
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_mailStep<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    request: JString<'local>,
    dir: JString<'local>,
    store: JObject<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let request = read_string(env, &request);
        let dir = read_string(env, &dir);

        let store = (!store.is_null()).then_some(&store);
        let mut client = Client::new(JniHost::new(env, &transport, store));
        let stepped = match from_str::<MailStepRequest>(&request) {
            Ok(request) => unsafe { bind(handle, &mut client) }.and_then(|mut mail| {
                mail.step(&MailStep {
                    mailbox: &mailbox,
                    request: &request,
                    dir: Path::new(&dir),
                })
            }),
            Err(err) => Err(format!("Invalid step request: {err}").into()),
        };
        let json = match stepped {
            Ok(step) => to_string(&step).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.fetchMessageSources`: reads messages whole into files, not strings.
///
/// Answers `{items, gone}` ([`mail::session::MessageSources`]), one file of
/// `dir` per id of `ids` (JSON) in `mailbox`: one gone (404, `notFound`,
/// sizeless UID) in `gone`, one not served in neither. An expired token fails.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_fetchMessageSources<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    mailbox: JString<'local>,
    ids: JString<'local>,
    dir: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mailbox = read_string(env, &mailbox);
        let ids = read_string(env, &ids);
        let dir = read_string(env, &dir);

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let read = match from_str::<Vec<String>>(&ids) {
            Ok(ids) => unsafe { bind(handle, &mut client) }.and_then(|mut mail| {
                mail.fetch_sources(&MessageFetch {
                    mailbox: &mailbox,
                    ids: &ids,
                    dir: Path::new(&dir),
                })
            }),
            Err(err) => Err(format!("Invalid message ids: {err}").into()),
        };
        let json = match read {
            Ok(sources) => to_string(&sources).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.parseMessage`: one stored message to what the reader draws.
///
/// Returns `{subject, from, fromAddress, to, cc, date, kind, body,
/// attachments, attachmentMark}`, each attachment `{name, mime, size,
/// part}`. No transport nor session: the bytes are the argument.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_parseMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let raw = env.convert_byte_array(&source)?;
        let json = match read::parse(&raw) {
            Ok(message) => to_string(&message).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.parseMessageFile`: `parseMessage` of the stored message in file
/// `source`, checked against `hash`, the name its row gives it.
///
/// Read here: no copy of the message on the Java heap, none across the
/// boundary. Bytes failing the name answer `{"error", "failing": true}`,
/// nothing parsed (STORAGE §7).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_parseMessageFile<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
    hash: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let source = read_string(env, &source);
        let hash = read_string(env, &hash);
        let json = parse_stored(&source, &hash);

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.describeMessage`: what the store files of the message in `source`.
///
/// `{hash, size, summary, attachments}` ([`read::describe`]), read once, so
/// the app moves a file a step, download or open wrote into its blob store
/// without reading it.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_describeMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let source = read_string(env, &source);
        let json = match fs::read(&source) {
            Ok(raw) => {
                to_string(&read::describe(&raw)).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err.to_string()),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.messagePart`: writes one decoded part of a stored message to a
/// file.
///
/// The part by IMAP section, from file `source`, checked against `hash`,
/// the name its row gives it, to `target`; returns `{"size"}`, an error for
/// no such part, or `{"error", "failing": true}` for bytes failing the
/// name, nothing written (STORAGE §7). Written from the parser's own
/// buffer: tens of megabytes are one buffer, never a base64 Java string.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_messagePart<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
    hash: JString<'local>,
    section: JString<'local>,
    target: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let source = read_string(env, &source);
        let hash = read_string(env, &hash);
        let section = read_string(env, &section);
        let target = read_string(env, &target);
        let json = write_stored_part(&source, &hash, &section, &target);

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.joinMailRun`: has a session work for the pool run `run`.
///
/// It shares what it reads of a Gmail account (listing, envelopes, history)
/// with the run's other sessions, dropped with the run; 0 leaves the run.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_joinMailRun(
    mut env: EnvUnowned<'_>,
    _class: JClass<'_>,
    handle: i64,
    run: i64,
) {
    env.with_env(|_| -> Result<(), Error> {
        if let Ok(session) = unsafe { borrow(handle) } {
            session.join_run(run);
        }
        Ok(())
    })
    .resolve::<ErrorReply>()
}

/// `Native.setMessageFlag`: adds or removes one marker on one message.
///
/// `\Seen`, `\Answered` or `\Flagged`. Returns `{}`.
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

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let write = FlagWrite {
            mailbox: &mailbox,
            id: &id,
            flag: &flag,
            add,
        };
        let json =
            match unsafe { bind(handle, &mut client) }.and_then(|mut mail| mail.set_flag(&write)) {
                Ok(()) => String::from("{}"),
                Err(err) => error_json(err),
            };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.relocateMessage`: moves one message from `mailbox` to `target`.
///
/// pimdir SYNC §4, a `Remove` carrying `to`. Returns `{}`.
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

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let transfer = MessageTransfer {
            mailbox: &mailbox,
            id: &id,
            target: &target,
        };
        let json = match unsafe { bind(handle, &mut client) }
            .and_then(|mut mail| mail.relocate(&transfer))
        {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.copyMessage`: copies one message from `mailbox` to `target`.
///
/// On the server: pimdir SYNC §4, an `Add` carrying an origin. Returns `{}`.
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

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let transfer = MessageTransfer {
            mailbox: &mailbox,
            id: &id,
            target: &target,
        };
        let json =
            match unsafe { bind(handle, &mut client) }.and_then(|mut mail| mail.copy(&transfer)) {
                Ok(()) => String::from("{}"),
                Err(err) => error_json(err),
            };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.destroyMessage`: deletes one message for good.
///
/// pimdir SYNC §4, a `Remove` carrying no `to`. Returns `{}`.
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

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let json = match unsafe { bind(handle, &mut client) }
            .and_then(|mut mail| mail.destroy(&mailbox, &id))
        {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.detachMessage`: takes one message out of `mailbox` alone.
///
/// The push of a removal naming no destination on a source listed
/// account-wide while the account holds the message in another mailbox.
/// Returns `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_detachMessage<'local>(
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

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let json = match unsafe { bind(handle, &mut client) }
            .and_then(|mut mail| mail.detach(&mailbox, &id))
        {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.appendMessage`: appends one message to `mailbox`, IMAP only.
///
/// The push of a create staged with no origin, `flags` a JSON array of
/// markers. Returns `{}`.
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
        let raw = env.convert_byte_array(&source)?;
        let flags = read_string(env, &flags);

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let appended = match from_str::<Vec<String>>(&flags) {
            Ok(flags) => unsafe { bind(handle, &mut client) }.and_then(|mut mail| {
                mail.append(MessageAppend {
                    mailbox: &mailbox,
                    raw,
                    flags: &flags,
                })
            }),
            Err(err) => Err(format!("Invalid markers: {err}").into()),
        };
        let json = match appended {
            Ok(()) => String::from("{}"),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.composeMessage`: one draft to the RFC 5322 message an outbox holds.
///
/// Returns `{source}`, the message base64-encoded. No transport: composing
/// reaches for nothing, so a message is written and queued with the radio
/// off, which is the whole of the outbox.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_composeMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    draft: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let draft = read_string(env, &draft);
        let composed = match from_str::<Draft>(&draft) {
            Ok(draft) => compose::compose(&draft),
            Err(err) => Err(format!("Invalid draft: {err}").into()),
        };
        let json = match composed {
            Ok(source) => to_string(&SourceReply {
                source: mail::base64(&source),
            })
            .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.markdownRich`: a rich draft's Markdown as its editor shows it.
///
/// Returns `{text, styles}`, each style `{kind, start, end, url?}` in UTF-16
/// units ([`markdown::rich`]). Offline and infallible: any text is Markdown.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_markdownRich<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    text: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let text = read_string(env, &text);
        let json =
            to_string(&markdown::rich(&text)).unwrap_or_else(|err| error_json(err.to_string()));

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.submitMessage`: hands one stored message over, over SMTP to the
/// session account's submit URL or through the session's API, as the
/// `submit` intent's `payload` states (STORAGE Annex B.2).
///
/// Returns `{messageId}`, the provider's own or null, or `{"error": "..",
/// "permanent": bool, "uncertain": bool}`, a malformed payload refused for
/// good. The submission opens its own connection, SMTP being a second
/// server.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_submitMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    source: JByteArray<'local>,
    payload: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let raw = env.convert_byte_array(&source)?;
        let payload = read_string(env, &payload);

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let submitted = PimdirSubmit::from_payload(&payload)
            .map_err(|err| Refused {
                permanent: true,
                uncertain: false,
                status: None,
                message: format!("Malformed submit payload: {err}"),
            })
            .and_then(|intent| {
                unsafe { bind(handle, &mut client) }
                    .map_err(Refused::transient)
                    .and_then(|mut mail| mail.submit(&raw, &intent))
            });
        let json = match submitted {
            Ok(message_id) => to_string(&SubmittedReply { message_id })
                .unwrap_or_else(|err| error_json(err.to_string())),
            Err(refused) => to_string(&RefusedReply {
                error: refused.message,
                permanent: refused.permanent,
                uncertain: refused.uncertain,
                status: refused.status,
            })
            .unwrap_or_else(|err| error_json(err.to_string())),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.checkSubmission`: whether the account's submission server takes
/// its sending credential, authenticating and closing with nothing sent.
///
/// Returns `{}`, or `{"error": ".."}` for a refusal or no submit URL.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_checkSubmission<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    account: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let account = read_string(env, &account);

        let json = match parse_account(&account) {
            Err(err) => error_json(err),
            Ok(account) if account.submit_url.is_empty() => {
                error_json("This account has no server to send through")
            }
            Ok(account) => {
                let mut client = Client::new(JniHost::new(env, &transport, None));
                let checked = parse_url(&account.submit_url)
                    .map_err(BridgeError::from)
                    .and_then(|url| smtp::check(&mut client, &url, &account.submit_auth()));
                match checked {
                    Ok(()) => "{}".to_string(),
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// The handle a session crosses to Java as.
///
/// A raw pointer: Java owns one handle per session, uses it from one thread
/// at a time and frees it once. Its `MailSession` holds it to that; nothing
/// here can check it.
pub fn into_handle(session: MailSession) -> i64 {
    Box::into_raw(Box::new(session)) as i64
}

/// Borrows the session a handle names, for the length of one call.
///
/// # Safety
///
/// `handle` must be one [`into_handle`] returned and [`drop_handle`] has
/// not yet been given, and no other borrow of it may be live.
pub unsafe fn borrow<'a>(handle: i64) -> Result<&'a mut MailSession, BridgeError> {
    if handle == 0 {
        return Err("This mail session is closed".into());
    }
    Ok(unsafe { &mut *(handle as *mut MailSession) })
}

/// Binds the session a handle names to the client of one call.
///
/// # Safety
///
/// The same contract as [`borrow`].
unsafe fn bind<'s, 'c, H>(
    handle: i64,
    client: &'c mut Client<H>,
) -> Result<Mail<'s, 'c, H>, BridgeError> {
    let session = unsafe { borrow(handle) }?;
    Ok(Mail { session, client })
}

/// Frees the session a handle names.
///
/// # Safety
///
/// The same contract as [`borrow`], and the handle must not be used
/// again afterwards.
pub unsafe fn drop_handle(handle: i64) {
    if handle != 0 {
        drop(unsafe { Box::from_raw(handle as *mut MailSession) });
    }
}

/// The reply of `openMailSession`.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct OpenedReply {
    expunges_one: bool,
    handle: i64,
}

/// The reply of `nameMessages`.
#[derive(Serialize)]
struct NamedReply {
    #[serde(serialize_with = "sorted")]
    items: Vec<Named>,
}

/// The reply of `messagePart`.
#[derive(Serialize)]
struct SizeReply {
    size: usize,
}

/// The reply of `composeMessage`, the message in base64.
#[derive(Serialize)]
struct SourceReply {
    source: String,
}

/// The reply of an accepted `submitMessage`, `messageId` null where the
/// provider stamped none of its own.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct SubmittedReply {
    message_id: Option<String>,
}

/// The reply of a refused `submitMessage` ([`Refused`]).
#[derive(Serialize)]
struct RefusedReply {
    error: String,
    permanent: bool,
    uncertain: bool,
    #[serde(skip_serializing_if = "Option::is_none")]
    status: Option<u16>,
}

/// The reply of a read of a stored message whose bytes fail its name.
#[derive(Serialize)]
struct FailingReply {
    error: String,
    failing: bool,
}

/// The stored message in file `source`, its bytes hashing to `hash`, or
/// the reply of the read that failed ([`blobs::verified`]).
fn stored(source: &str, hash: &str) -> Result<Vec<u8>, String> {
    match blobs::verified(Path::new(source), hash) {
        Ok(Some(raw)) => Ok(raw),
        Ok(None) => {
            let failing = FailingReply {
                error: format!("The body {hash} fails its name"),
                failing: true,
            };
            Err(to_string(&failing).unwrap_or_else(|err| error_json(err.to_string())))
        }
        Err(err) => Err(error_json(err.to_string())),
    }
}

/// The reply of `parseMessageFile`.
fn parse_stored(source: &str, hash: &str) -> String {
    let raw = match stored(source, hash) {
        Ok(raw) => raw,
        Err(reply) => return reply,
    };
    match read::parse(&raw) {
        Ok(message) => to_string(&message).unwrap_or_else(|err| error_json(err.to_string())),
        Err(err) => error_json(err),
    }
}

/// The reply of `messagePart`.
fn write_stored_part(source: &str, hash: &str, section: &str, target: &str) -> String {
    let raw = match stored(source, hash) {
        Ok(raw) => raw,
        Err(reply) => return reply,
    };
    let written = File::create(target)
        .map_err(|err| BridgeError::from(err.to_string()))
        .and_then(|mut file| read::write_part(&raw, section, &mut file));
    match written {
        Ok(size) => {
            to_string(&SizeReply { size }).unwrap_or_else(|err| error_json(err.to_string()))
        }
        Err(err) => error_json(err),
    }
}

#[cfg(test)]
mod tests {
    use std::{fs, os::unix::fs::symlink};

    use io_pimdir::hash;
    use serde_json::{Value, from_str};

    use crate::{
        ffi::mail::{borrow, drop_handle, parse_stored, write_stored_part},
        offline::blobs,
    };

    /// A stored message is parsed, and a part of it written out, only once
    /// its bytes hash to the name its row gives it (STORAGE §5, §7): the
    /// part a killed exclusive copy left under the name, and a link to the
    /// very bytes, answer `failing`, nothing parsed nor written. A file
    /// that cannot be read is an error of its own.
    #[test]
    fn a_stored_message_failing_its_name_is_not_read() {
        let message = b"From: Ada <ada@example.org>\r\nSubject: Opened\r\n\
            Content-Type: multipart/mixed; boundary=b\r\n\r\n\
            --b\r\nContent-Type: text/plain\r\n\r\nHello\r\n\
            --b\r\nContent-Type: application/pdf; name=a.pdf\r\n\
            Content-Disposition: attachment; filename=a.pdf\r\n\
            Content-Transfer-Encoding: base64\r\n\r\nJVBERi0=\r\n--b--\r\n";
        let name = hash::hash(message).0;
        let dir = tempfile::tempdir().unwrap();
        let (temp, source) = (dir.path().join(".tmp"), dir.path().join(&name));
        let target = dir.path().join("part");
        let (at, to) = (source.to_str().unwrap(), target.to_str().unwrap());
        let reply = |json: String| from_str::<Value>(&json).unwrap();

        fs::write(&temp, message).unwrap();
        blobs::claimed(&temp, &source).unwrap();
        assert_eq!(reply(parse_stored(at, &name))["subject"], "Opened");
        assert_eq!(reply(write_stored_part(at, &name, "2", to))["size"], 5);
        assert_eq!(fs::read(&target).unwrap(), b"%PDF-");
        fs::remove_file(&target).unwrap();

        // NOTE: what the copy holds under the name when its writer is
        // killed: the first bytes, which still parse as a message.
        fs::write(&source, &message[..message.len() - 12]).unwrap();
        for failing in [
            reply(parse_stored(at, &name)),
            reply(write_stored_part(at, &name, "2", to)),
        ] {
            assert_eq!(failing["failing"], true, "a part: {failing}");
            assert!(failing["error"].as_str().unwrap().contains(&name));
        }
        assert!(!target.exists(), "a part of a failing body was written");

        fs::remove_file(&source).unwrap();
        fs::write(dir.path().join("elsewhere"), message).unwrap();
        symlink(dir.path().join("elsewhere"), &source).unwrap();
        let linked = reply(parse_stored(at, &name));
        assert_eq!(linked["failing"], true, "a link holds no body: {linked}");

        fs::remove_file(&source).unwrap();
        let missing = reply(parse_stored(at, &name));
        assert!(missing["error"].is_string(), "{missing}");
        assert_eq!(missing.get("failing"), None, "a missing file fails no name");
    }

    #[test]
    fn a_closed_session_is_refused_rather_than_dereferenced() {
        // NOTE: Java zeroes a handle it frees, so a verb after that is the
        // one use-after-free this can catch; the rest is the caller's
        // contract, hence a single caller class.
        let refused = unsafe { borrow(0) };
        assert!(refused.is_err(), "a zero handle names no session");
    }

    #[test]
    fn freeing_nothing_is_not_a_free() {
        // NOTE: an idempotent close; a second one is a no-op, never a second
        // `Box::from_raw`.
        unsafe { drop_handle(0) };
    }
}
