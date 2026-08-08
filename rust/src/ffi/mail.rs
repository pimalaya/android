//! Mail JNI entry points.
//!
//! Read-only, and one call per account rather than one per mailbox: both
//! backends authenticate once and walk every mailbox inside that one
//! authentication, which is the whole difference from the WebDAV side's
//! per-collection calls.
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
use serde_json::to_string;

use crate::{
    account::{self, Backend},
    client::{self, Client},
    ffi::{error_json, parse_url, read_string},
    types::{BridgeError, Credentials, Message},
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
