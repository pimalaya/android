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
    ffi::{ErrorReply, error_json, host::JniHost, parse_account, read_string},
    mail::{
        self,
        compose::{self, Draft},
        listing::{Floor, MailRequest, Named},
        markdown, read,
        session::{
            FlagWrite, Mail, MailSession, MailStep, MailStepRequest, MessageAppend, MessageFetch,
            MessageTransfer, Refused,
        },
    },
    offline::wire::sorted,
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
        let raw = env.convert_byte_array(&source).unwrap_or_default();
        let json = match read::parse(&raw) {
            Ok(message) => to_string(&message).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.parseMessageFile`: `parseMessage` of the message in file `source`.
///
/// Read here: no copy of the message on the Java heap, none across the
/// boundary.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_parseMessageFile<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    source: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let source = read_string(env, &source);
        let json = match fs::read(&source)
            .map_err(|err| BridgeError::from(err.to_string()))
            .and_then(|raw| read::parse(&raw))
        {
            Ok(message) => to_string(&message).unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

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

/// `Native.messagePart`: writes one decoded part of a message to a file.
///
/// The part by IMAP section, from file `source` to `target`; returns
/// `{"size"}`, or an error for no such part. Written from the parser's own
/// buffer: tens of megabytes are one buffer, never a base64 Java string.
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
        let written = fs::read(&source)
            .and_then(|raw| Ok((raw, File::create(&target)?)))
            .map_err(|err| BridgeError::from(err.to_string()))
            .and_then(|(raw, mut file)| read::write_part(&raw, &section, &mut file));
        let json = match written {
            Ok(size) => {
                to_string(&SizeReply { size }).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

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
        let raw = env.convert_byte_array(&source).unwrap_or_default();
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
/// session account's submit URL or through the session's API.
///
/// Returns `{messageId}`, the provider's own or null, or `{"error": "..",
/// "permanent": bool, "uncertain": bool}`. The submission opens its own
/// connection, SMTP being a second server.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_submitMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    handle: i64,
    source: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let raw = env.convert_byte_array(&source).unwrap_or_default();

        let mut client = Client::new(JniHost::new(env, &transport, None));
        let submitted = unsafe { bind(handle, &mut client) }
            .map_err(Refused::transient)
            .and_then(|mut mail| mail.submit(&raw));
        let json = match submitted {
            Ok(message_id) => to_string(&SubmittedReply { message_id })
                .unwrap_or_else(|err| error_json(err.to_string())),
            Err(refused) => to_string(&RefusedReply {
                error: refused.message,
                permanent: refused.permanent,
                uncertain: refused.uncertain,
            })
            .unwrap_or_else(|err| error_json(err.to_string())),
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
}

#[cfg(test)]
mod tests {
    use crate::ffi::mail::{borrow, drop_handle};

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
