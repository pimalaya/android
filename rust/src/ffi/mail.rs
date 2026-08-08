//! Mail JNI entry points.
//!
//! IMAP only, read-only, and one call per account rather than one per
//! mailbox: IMAP is a session, so connecting and authenticating once and
//! walking every mailbox inside that session is the whole difference
//! from the WebDAV side's per-collection calls.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
};
use serde_json::to_string;

use crate::{
    client::Client,
    ffi::{error_json, parse_url, read_string},
    types::Credentials,
};

/// `Native.syncMail`: connects to the account's IMAP server, lists its
/// mailboxes and returns the newest `limit` messages of each. Returns a
/// JSON array of `{mailbox, uid, subject, from, date, seen}` objects.
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

        let json = match parse_url(&url) {
            Ok(url) => {
                let mut client = Client::new(env, &transport);
                match crate::client::imap::sync_account(&mut client, &url, &credentials, limit) {
                    Ok(messages) => {
                        to_string(&messages).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}
