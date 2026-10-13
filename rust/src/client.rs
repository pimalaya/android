//! # Client
//!
//! One native call's [`Client`] over the app's [`Host`]: the transport
//! policy every byte passes through, and the plumbing domains share.
//!
//! A native call builds a client, runs one operation and drops it, the
//! Java transport owning the sockets (one per origin, TLS included) and
//! the bridge naming the URL each yield wants. [`throttle`] paces and
//! retries HTTP, [`trust`] guards credentials and redirects.
//!
//! [`webdav`], [`msgraph`] and [`jmap`] hold the backends serving several
//! domains, each domain adding its verbs in its own file.

#[cfg(test)]
pub mod fake;
pub mod jmap;
pub mod msgraph;
pub mod throttle;
pub mod trust;
pub mod webdav;

use core::error::Error as StdError;
use std::{collections::HashSet, thread, time::Duration};

use io_webdav::{
    coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
    rfc4918::coroutine::WebdavRedirectYield,
};
use jiff::Timestamp;
use percent_encoding::percent_decode_str;
use serde::{Deserialize, Serialize};
use serde_json::{from_str, to_string};
use url::Url;

use crate::{
    client::{
        throttle::Exchange,
        trust::{CrossOrigin, Redirects, refuse_cleartext_credential},
    },
    error::{BridgeError, coroutine_error},
};

/// The `User-Agent` of every WebDAV request and of the discovery walk.
pub const USER_AGENT: &str = concat!("pimalaya-android/", env!("CARGO_PKG_VERSION"));

/// How a request proves who sends it.
#[derive(Clone, Copy)]
pub enum Auth<'a> {
    /// A login and its password: HTTP Basic, SASL PLAIN or LOGIN.
    Basic { login: &'a str, password: &'a str },
    /// An OAuth 2.0 access token.
    Bearer(&'a str),
}

impl<'a> Auth<'a> {
    /// The credential as Java hands it over, an empty login meaning a token.
    pub fn new(login: &'a str, password: &'a str) -> Self {
        if login.is_empty() {
            Self::Bearer(password)
        } else {
            Self::Basic { login, password }
        }
    }

    /// The password or the token, for the token-only backends (Graph, Google).
    pub fn secret(&self) -> &'a str {
        match *self {
            Self::Basic { password, .. } => password,
            Self::Bearer(token) => token,
        }
    }
}

/// What the bridge asks of the app: its sockets and its store.
pub trait Host {
    /// Reads the next chunk from the stream open on `url`, empty at its end.
    fn read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError>;

    /// Writes and flushes bytes to the stream open on `url`.
    fn write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError>;

    /// Opens a new HTTP request on `url`'s origin, answering if reused.
    ///
    /// The transport drops a connection whose last answer was not read to
    /// its end; reused means the request goes out on one that served another.
    fn begin(&mut self, url: &str) -> bool;

    /// Marks the answer on `url`'s connection read to its end.
    fn settle(&mut self, url: &str);

    /// Drops `url`'s connection, the next use opening another.
    fn reset(&mut self, url: &str);

    /// Upgrades the URL's plain stream to TLS in place (RFC 3207).
    ///
    /// The handshake verifies the URL's host.
    fn starttls(&mut self, url: &str) -> Result<(), BridgeError>;

    /// One request to the app's `OfflineDriver`, JSON in, JSON out.
    fn serve(&mut self, request: &str) -> Result<String, BridgeError>;

    /// Waits `delay`.
    fn sleep(&mut self, delay: Duration) {
        thread::sleep(delay);
    }

    /// The time now, for `Retry-After` dates and waits to the next minute.
    fn now(&self) -> Timestamp {
        Timestamp::now()
    }
}

/// One native call's client over the [`Host`] serving its I/O.
///
/// It also carries the HTTP request in flight and the time spent waiting
/// on throttling servers ([`throttle`]), both per native call.
pub struct Client<H> {
    pub host: H,
    exchange: Exchange,
    waited: Duration,
    /// The Gmail account paced under, once a request went: one per mail call.
    gmail_pace: Option<String>,
    /// Whether someone waits on this call: its Gmail requests skip the queue.
    pub urgent: bool,
    /// The URLs STARTTLS upgraded ([`Self::starttls`]), open to credentials.
    upgraded: Vec<String>,
}

/// Parses an absolute URL off the JNI wire.
pub fn parse_url(raw: &str) -> Result<Url, BridgeError> {
    Url::parse(raw).map_err(|err| format!("Invalid URL `{raw}`: {err}").into())
}

impl<H: Host> Client<H> {
    /// Wraps the host for one native call.
    pub fn new(host: H) -> Self {
        Self {
            host,
            exchange: Exchange::default(),
            waited: Duration::ZERO,
            gmail_pace: None,
            urgent: false,
            upgraded: Vec::new(),
        }
    }
}

/// WebDAV coroutine runners, routing every yield to the target's stream.
impl<H: Host> Client<H> {
    /// Runs a coroutine that follows no redirect against `target`.
    pub fn run<C, T, E>(&mut self, target: &Url, mut coroutine: C) -> Result<T, BridgeError>
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

    /// Runs a coroutine that may answer 3xx, rebuilding it per target.
    ///
    /// [`Redirects`] bounds the chain: a WebDAV request carries the
    /// account's credential, so a redirect to another origin is refused.
    pub fn run_redirect<C, T, E>(
        &mut self,
        start: &Url,
        make: impl Fn(&Url) -> C,
    ) -> Result<T, BridgeError>
    where
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
                    WebdavCoroutineState::Complete(Err(err)) => {
                        return Err(coroutine_error(&err));
                    }
                    WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsWrite(bytes)) => {
                        self.http_write(target.as_str(), &redirects.request(&bytes))?;
                        arg = None;
                    }
                    WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsRead) => {
                        arg = Some(self.http_read(target.as_str())?);
                    }
                    WebdavCoroutineState::Yielded(WebdavRedirectYield::WantsRedirect {
                        url,
                        ..
                    }) => {
                        redirects.follow(&target, &url)?;
                        target = url;
                        break;
                    }
                }
            }
        }
    }
}

/// Transport policy, which every byte read or written passes through.
impl<H: Host> Client<H> {
    /// Reads the next chunk from the stream open on `url`.
    ///
    /// A failure drops the connection ([`Self::reset`]), so the next use
    /// opens another rather than talking on a dead or desynced one.
    pub fn read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        let read = self.host.read(url);
        if read.is_err() {
            self.reset(url);
        }
        read
    }

    /// Writes and flushes bytes to `url`'s stream, dropping it on failure.
    ///
    /// Every byte sent comes through here, so this is where a credential
    /// is refused an unencrypted connection ([`refuse_cleartext_credential`]).
    pub fn write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        let upgraded = self.upgraded.iter().any(|done| done == url);
        refuse_cleartext_credential(url, bytes, upgraded)?;

        let written = self.host.write(url, bytes);
        if written.is_err() {
            self.reset(url);
        }
        written
    }

    /// Drops `url`'s connection, the next use opening another.
    pub fn reset(&mut self, url: &str) {
        self.upgraded.retain(|done| done != url);
        log::debug!(
            "connection to {} reset",
            url.split('?').next().unwrap_or(url)
        );
        self.host.reset(url);
    }

    /// Upgrades the URL's plain stream to TLS in place (RFC 3207).
    ///
    /// The handshake verifies the URL's host.
    pub fn starttls(&mut self, url: &str) -> Result<(), BridgeError> {
        self.host.starttls(url)?;
        self.upgraded.push(url.to_string());
        Ok(())
    }
}

/// What a mail step asks the app's store.
impl<H: Host> Client<H> {
    /// Which of `ids` the store holds. Fails outside a step.
    ///
    /// Asked of `OfflineDriver.serve` as `{"op": "held", "handles"}`,
    /// answered `{"held"}`, once per listed page: no step carries every
    /// handle the account holds.
    pub fn held(&mut self, ids: &[String]) -> Result<HashSet<String>, BridgeError> {
        if ids.is_empty() {
            return Ok(HashSet::new());
        }
        let request = to_string(&HeldRequest {
            handles: ids,
            op: "held",
        })
        .map_err(|err| err.to_string())?;
        let reply = self.host.serve(&request)?;

        let reply: HeldReply = from_str(&reply).map_err(|err| err.to_string())?;
        if let Some(error) = reply.error {
            return Err(error.into());
        }
        Ok(reply.held.into_iter().collect())
    }
}

/// The `held` request to the store's driver.
#[derive(Serialize)]
struct HeldRequest<'a> {
    handles: &'a [String],
    op: &'static str,
}

/// The answer to a `held` request: the handles held, or the failure.
#[derive(Deserialize)]
struct HeldReply {
    #[serde(default)]
    error: Option<String>,
    #[serde(default)]
    held: Vec<String>,
}

/// The user a token signs in as, from the URL's userinfo (RFC 5092 3.2).
///
/// A token carries no login and SASL `XOAUTH2` wants one: the connection
/// flow writes the address into the stored URL, so the credential stays
/// the bare token every other protocol sends.
pub fn url_user(url: &str) -> Result<String, BridgeError> {
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

#[cfg(test)]
mod tests {
    use crate::contact::carddav::normalize_vcard;

    #[test]
    fn url_user_decodes_the_userinfo_an_address_was_written_into() {
        use crate::client::url_user;

        assert_eq!(
            url_user("imaps://alice%40example.com@outlook.office365.com:993").unwrap(),
            "alice@example.com"
        );
        assert!(url_user("smtp://smtp.office365.com:587").is_err());
    }

    /// A vCard 3.0 lacking `N` gets one; vcard-rs covers the injection.
    ///
    /// A valid card round-trips and unparseable input passes through.
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
