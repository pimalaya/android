//! Blocking CardDAV client over the JNI transport: drives io-pim-discovery's
//! RFC 6764 discovery and io-webdav's sans-io CardDAV coroutines, doing
//! all socket I/O by upcalling the Java `Transport` on each yield. TLS
//! lives in Java; this crate only ever sees plaintext bytes.
//!
//! Session state is not cached: each native call builds a client, runs
//! one operation, and drops it. The Java transport owns the sockets,
//! one per origin (DNS resolver, HTTPS servers), so a single discovery
//! or CardDAV cycle can talk to several endpoints; the bridge only
//! tells it which URL each yield wants.
//!
//! The struct and its transport layer (`read` / `write`, the generic
//! coroutine runners `run` / `run_redirect`) live here; each backend's
//! operations sit in a submodule, reached as extra `impl Client` blocks
//! through `crate::client::...`. The free `to_card` / `to_addressbook`
//! and error helpers shared by more than one backend live in
//! [`convert`].

mod caldav;
mod carddav;
pub(crate) mod convert;
mod discovery;
mod dispatch;
mod gcal;
pub(crate) mod gmail;
pub(crate) mod gmail_sync;
mod google;
mod graph;
mod graph_calendar;
pub(crate) mod graph_mail;
pub(crate) mod imap;
mod jmap;
pub(crate) mod jmap_sync;
pub(crate) mod listing;
pub(crate) mod smtp;
mod sync_collection;
mod throttle;
pub(crate) mod trust;

use core::error::Error as StdError;
use std::{collections::HashSet, time::Duration};

use io_webdav::{
    coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
    rfc4918::coroutine::WebdavRedirectYield,
};
use jni::{
    Env, JValue, JValueOwned,
    errors::Error,
    jni_sig, jni_str,
    objects::{JByteArray, JObject, JString},
    signature::MethodSignature,
    strings::JNIStr,
};
use percent_encoding::percent_decode_str;
use serde_json::{Value, from_str, json};
use url::Url;

use crate::{
    client::{
        convert::coroutine_error,
        throttle::{Exchange, Wire, throttled_read, throttled_write},
        trust::{CrossOrigin, Redirects, refuse_cleartext_credential},
    },
    types::BridgeError,
};

/// Sent as the `User-Agent` on every WebDAV request; shared by the
/// CardDAV verbs and the discovery walk.
pub(crate) const USER_AGENT: &str = concat!("pimalaya-android/", env!("CARGO_PKG_VERSION"));

/// One native call's CardDAV client: a mutable `Env` and the Java
/// transport it upcalls for socket I/O. It also carries the HTTP request in
/// flight and the time spent waiting on throttling servers
/// ([`throttle`]), both per native call.
pub struct Client<'a, 'local> {
    env: &'a mut Env<'local>,
    transport: &'a JObject<'local>,
    exchange: Exchange,
    waited: Duration,
    /// The Gmail account this native call's requests are paced under, once
    /// it sent one ([`throttle::pace_gmail`]): a mail call speaks to one
    /// account.
    gmail_pace: Option<String>,
    /// Whether this native call serves someone waiting on it, a message
    /// being opened, rather than background work: its Gmail requests
    /// skip the pacing queue ([`throttle::pace_gmail`]).
    pub(crate) urgent: bool,
    /// The URLs whose plain stream STARTTLS upgraded ([`Self::starttls`]):
    /// a credential may go out on those.
    upgraded: Vec<String>,
    /// The store's driver (`OfflineDriver`) a mail step asks which of a
    /// listed page the store holds ([`Self::held`]); [`None`] outside a
    /// step.
    pub(crate) store: Option<&'a JObject<'local>>,
}

impl<'a, 'local> Client<'a, 'local> {
    /// Wraps the JNI context for one native call.
    pub fn new(env: &'a mut Env<'local>, transport: &'a JObject<'local>) -> Self {
        Self {
            env,
            transport,
            exchange: Exchange::default(),
            waited: Duration::ZERO,
            gmail_pace: None,
            urgent: false,
            upgraded: Vec::new(),
            store: None,
        }
    }
}

/// Coroutine runners shared by more than one backend: the resume loops
/// that pump a sans-io WebDAV coroutine, routing every read and write
/// yield to the Java transport stream opened for the target URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Drives a plain (non-redirect) coroutine against `target`.
    pub(crate) fn run<C, T, E>(&mut self, target: &Url, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: WebdavCoroutine<Yield = WebdavYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                WebdavCoroutineState::Complete(Ok(value)) => return Ok(value),
                WebdavCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                WebdavCoroutineState::Yielded(WebdavYield::WantsWrite(bytes)) => {
                    self.http_write(target.as_str(), &bytes)?;
                    arg = None;
                }
                WebdavCoroutineState::Yielded(WebdavYield::WantsRead) => {
                    arg = Some(self.http_read(target.as_str())?);
                }
            }
        }
    }

    /// Drives a redirect-aware coroutine, rebuilding it against the
    /// new target whenever the server answers 3xx, within the bounds of
    /// [`Redirects`]: a WebDAV request carries the account's credential,
    /// so a redirect to another server is refused.
    pub(crate) fn run_redirect<C, T, E>(
        &mut self,
        start: &Url,
        make: impl Fn(&Url) -> C,
    ) -> Result<T, BridgeError>
    where
        C: WebdavCoroutine<Yield = WebdavRedirectYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        run_webdav_redirect(self, start, make)
    }
}

/// [`Client::run_redirect`] over any [`Wire`].
pub(crate) fn run_webdav_redirect<W, C, T, E>(
    wire: &mut W,
    start: &Url,
    make: impl Fn(&Url) -> C,
) -> Result<T, BridgeError>
where
    W: Wire,
    C: WebdavCoroutine<Yield = WebdavRedirectYield, Return = Result<T, E>>,
    E: StdError + 'static,
{
    let mut redirects = Redirects::new(start, CrossOrigin::Refuse);
    let mut target = start.clone();

    loop {
        let mut coroutine = make(&target);
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                WebdavCoroutineState::Complete(Ok(value)) => return Ok(value),
                WebdavCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsWrite(bytes)) => {
                    throttled_write(wire, target.as_str(), &redirects.request(&bytes))?;
                    arg = None;
                }
                WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsRead) => {
                    arg = Some(throttled_read(wire, target.as_str())?);
                }
                WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsRedirect {
                    url, ..
                }) => {
                    redirects.follow(&target, &url)?;
                    target = url;
                    break;
                }
            }
        }
    }
}

/// Transport upcalls: the JNI bridge to the Java `Transport`, reading
/// and writing bytes on the stream open for a given URL.
impl<'a, 'local> Client<'a, 'local> {
    /// Upcalls the Java transport to read the next chunk from the
    /// stream open on `url`.
    ///
    /// Any failure drops the connection ([`Self::reset`]), so the next
    /// use opens another rather than talking on a dead or desynced one.
    pub(crate) fn read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        let read = self.read_upcall(url);
        if read.is_err() {
            self.reset(url);
        }
        read
    }

    fn read_upcall(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        // NOTE: an empty slice signals EOF to the coroutine.
        let url = self.env.new_string(url).map_err(|err| err.to_string())?;
        let value = self
            .env
            .call_method(
                self.transport,
                jni_str!("read"),
                jni_sig!("(Ljava/lang/String;)[B"),
                &[JValue::Object(&url)],
            )
            .map_err(|err| clear_and_fail(self.env, "transport read", err))?;
        let object = value.l().map_err(|err| err.to_string())?;
        let array = unsafe { JByteArray::from_raw(self.env, object.into_raw()) };

        self.env
            .convert_byte_array(&array)
            .map_err(|err| err.to_string().into())
    }

    /// Upcalls the Java transport to write and flush bytes to the
    /// stream open on `url`, dropping the connection on a failure.
    ///
    /// Every byte the bridge sends comes through here, so this is where
    /// a credential is refused an unencrypted connection
    /// ([`refuse_cleartext_credential`]).
    pub(crate) fn write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        let upgraded = self.upgraded.iter().any(|done| done == url);
        refuse_cleartext_credential(url, bytes, upgraded)?;

        let written = self.write_upcall(url, bytes);
        if written.is_err() {
            self.reset(url);
        }
        written
    }

    fn write_upcall(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        let url = self.env.new_string(url).map_err(|err| err.to_string())?;
        let array = self
            .env
            .byte_array_from_slice(bytes)
            .map_err(|err| err.to_string())?;

        self.env
            .call_method(
                self.transport,
                jni_str!("write"),
                jni_sig!("(Ljava/lang/String;[B)V"),
                &[JValue::Object(&url), JValue::Object(&array)],
            )
            .map_err(|err| clear_and_fail(self.env, "transport write", err))?;

        Ok(())
    }

    /// Opens a new HTTP request on `url`'s origin: the transport drops
    /// a connection whose last answer was not read to its end, and
    /// answers whether the request goes out on one that served another.
    pub(crate) fn begin(&mut self, url: &str) -> bool {
        self.upcall(url, jni_str!("begin"), jni_sig!("(Ljava/lang/String;)Z"))
            .and_then(|value| value.z().ok())
            .unwrap_or(false)
    }

    /// Marks the answer on `url`'s connection read to its end.
    pub(crate) fn settle(&mut self, url: &str) {
        self.upcall(url, jni_str!("settle"), jni_sig!("(Ljava/lang/String;)V"));
    }

    /// Drops `url`'s connection, the next use opening another.
    pub(crate) fn reset(&mut self, url: &str) {
        self.upgraded.retain(|done| done != url);
        log::debug!(
            "connection to {} reset",
            url.split('?').next().unwrap_or(url)
        );
        self.upcall(url, jni_str!("reset"), jni_sig!("(Ljava/lang/String;)V"));
    }

    /// One bookkeeping upcall taking the URL alone, none of whose
    /// failures is the caller's: logged, its exception cleared.
    fn upcall<'sig, 'args, N, S>(
        &mut self,
        url: &str,
        name: N,
        sig: S,
    ) -> Option<JValueOwned<'local>>
    where
        N: AsRef<JNIStr>,
        S: AsRef<MethodSignature<'sig, 'args>>,
    {
        let called = self.env.new_string(url).and_then(|url| {
            self.env
                .call_method(self.transport, name, sig, &[JValue::Object(&url)])
        });
        match called {
            Ok(value) => Some(value),
            Err(err) => {
                let failed = clear_and_fail(self.env, "transport upcall", err);
                log::warn!("{failed}");
                None
            }
        }
    }

    /// Upgrades the URL's plain stream to TLS in place (RFC 3207), the
    /// handshake verifying the URL's host.
    pub(crate) fn starttls(&mut self, url: &str) -> Result<(), BridgeError> {
        let jurl = self.env.new_string(url).map_err(|err| err.to_string())?;

        self.env
            .call_method(
                self.transport,
                jni_str!("starttls"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&jurl)],
            )
            .map_err(|err| clear_and_fail(self.env, "transport starttls", err))?;

        self.upgraded.push(url.to_string());
        Ok(())
    }
}

/// The store upcall: what a mail step asks the app's store.
impl<'a, 'local> Client<'a, 'local> {
    /// Which of `ids` the store holds, asked of the store's driver
    /// (`OfflineDriver.serve` with `{"op": "held", "handles"}`, answered
    /// `{"held"}`): a step asks it once per page it lists, so no step
    /// carries every handle the account holds. Fails outside a step.
    pub(crate) fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError> {
        if ids.is_empty() {
            return Ok(HashSet::new());
        }
        let store = self.store.ok_or("No store to ask what it holds")?;
        let request = json!({ "op": "held", "handles": ids }).to_string();
        let request = self
            .env
            .new_string(request)
            .map_err(|err| err.to_string())?;
        let value = self
            .env
            .call_method(
                store,
                jni_str!("serve"),
                jni_sig!("(Ljava/lang/String;)Ljava/lang/String;"),
                &[JValue::Object(&request)],
            )
            .map_err(|err| clear_and_fail(self.env, "store held", err))?;
        let object = value.l().map_err(|err| err.to_string())?;
        let reply = unsafe { JString::from_raw(self.env, object.into_raw()) };
        let reply = reply
            .try_to_string(self.env)
            .map_err(|err| err.to_string())?;

        let reply: Value = from_str(&reply).map_err(|err| err.to_string())?;
        if let Some(error) = reply.get("error") {
            let error = error
                .as_str()
                .map_or_else(|| error.to_string(), String::from);
            return Err(error.into());
        }
        Ok(reply
            .get("held")
            .and_then(Value::as_array)
            .into_iter()
            .flatten()
            .filter_map(Value::as_str)
            .map(String::from)
            .collect())
    }
}

/// Catches and clears any pending Java exception, surfacing its class
/// and message (a bare JNI error only says "Java exception was thrown").
/// The lowercase op is capitalized so the user-facing message reads as a
/// sentence.
/// The user a mail endpoint signs in as with a token, named in its URL's
/// userinfo the way RFC 5092 section 3.2 names an IMAP one.
///
/// A token carries no login, and SASL `XOAUTH2` still wants one: the
/// connection flow writes the address into the URL it stores, so the
/// credential stays the bare token every other protocol sends.
pub(crate) fn url_user(url: &str) -> Result<String, BridgeError> {
    let url = Url::parse(url).map_err(|err| format!("Invalid endpoint URL `{url}`: {err}"))?;
    let user = percent_decode_str(url.username()).decode_utf8_lossy();

    if user.is_empty() {
        return Err(format!(
            "No user to sign in to {} with a token",
            url.origin().ascii_serialization()
        )
        .into());
    }

    Ok(user.into_owned())
}

pub(crate) fn clear_and_fail(env: &mut Env, op: &str, err: Error) -> String {
    let mut chars = op.chars();
    let op = match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => String::new(),
    };

    match env.exception_catch() {
        Err(Error::CaughtJavaException { name, msg, .. }) => {
            format!("{op} failed: {name}: {msg}")
        }
        _ => format!("{op} failed: {err}"),
    }
}

#[cfg(test)]
mod tests {
    use crate::client::{carddav::normalize_vcard, discovery::search_domain};

    /// An email address searches by its domain part; a bare domain
    /// searches as itself with no email; an input without any domain
    /// is rejected.
    #[test]
    fn url_user_decodes_the_userinfo_an_address_was_written_into() {
        use crate::client::url_user;

        assert_eq!(
            url_user("imaps://alice%40example.com@outlook.office365.com:993").unwrap(),
            "alice@example.com"
        );
        assert!(url_user("smtp://smtp.office365.com:587").is_err());
    }

    #[test]
    fn search_domain_accepts_emails_and_bare_domains() {
        let (email, domain) = search_domain("user@Example.COM.").unwrap();
        assert_eq!(email, "user@Example.COM.");
        assert_eq!(domain, "example.com");

        let (email, domain) = search_domain(" example.com ").unwrap();
        assert_eq!(email, "");
        assert_eq!(domain, "example.com");

        assert!(search_domain("").is_err());
        assert!(search_domain("user@").is_err());
    }

    /// The CardDAV write path repairs a vCard 3.0 that lacks the
    /// mandatory `N` via vcard-rs; an already valid card round-trips
    /// unchanged, and unparseable input passes straight through. The
    /// injection itself is covered in vcard-rs.
    #[test]
    fn normalize_vcard_supplies_the_mandatory_n() {
        use std::borrow::Cow;

        let missing = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:x\r\nFN:Only\r\nEND:VCARD\r\n";
        assert!(normalize_vcard(missing).contains("N:;;;;\r\n"));

        let with_n = "BEGIN:VCARD\r\nVERSION:3.0\r\nN:Doe;Jane;;;\r\nFN:Jane\r\nEND:VCARD\r\n";
        assert_eq!(&*normalize_vcard(with_n), with_n);

        assert!(matches!(normalize_vcard("not a vcard"), Cow::Borrowed(_)));
    }
}
