//! Where a credential may go, over a scripted server: the redirect
//! runners refusing a downgrade, another server and an endless chain, the
//! blob download following a CDN without the token, and the one cleartext
//! rule every write passes.

use std::{collections::VecDeque, time::Duration};

use io_http::rfc7617::basic::HttpAuthBasic;
use io_jmap::rfc8620::{blob_download::JmapBlobDownload, session_get::JmapSessionGet};
use io_webdav::{rfc4918::WebdavAuth, rfc5397::current_user_principal::WebdavCurrentUserPrincipal};
use secrecy::SecretString;
use url::Url;

use super::*;
use crate::client::{
    USER_AGENT,
    jmap::run_jmap_redirect,
    run_webdav_redirect,
    throttle::{Exchange, Wire},
};

/// A server answering each request with the next scripted answer.
struct Scripted {
    answers: VecDeque<Vec<u8>>,
    pending: Vec<u8>,
    /// Every request written, with the URL it went to.
    writes: Vec<(String, String)>,
    exchange: Exchange,
    waited: Duration,
}

impl Scripted {
    fn new(answers: &[&str]) -> Self {
        Self {
            answers: answers
                .iter()
                .map(|answer| answer.as_bytes().to_vec())
                .collect(),
            pending: Vec::new(),
            writes: Vec::new(),
            exchange: Exchange::default(),
            waited: Duration::ZERO,
        }
    }

    fn authorized(&self, index: usize) -> bool {
        self.writes[index]
            .1
            .to_ascii_lowercase()
            .contains("\r\nauthorization:")
    }
}

impl Wire for Scripted {
    fn wire_read(&mut self, _url: &str) -> Result<Vec<u8>, BridgeError> {
        Ok(std::mem::take(&mut self.pending))
    }

    fn wire_write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        self.writes
            .push((url.to_string(), String::from_utf8_lossy(bytes).into_owned()));
        self.pending = self.answers.pop_front().unwrap_or_default();
        Ok(())
    }

    fn wire_begin(&mut self, _url: &str) -> bool {
        false
    }

    fn wire_settle(&mut self, _url: &str) {}

    fn wire_reset(&mut self, _url: &str) {}

    fn exchange(&mut self) -> &mut Exchange {
        &mut self.exchange
    }

    fn waited(&mut self) -> &mut Duration {
        &mut self.waited
    }
}

fn moved(location: &str) -> String {
    format!("HTTP/1.1 301 Moved Permanently\r\nLocation: {location}\r\nContent-Length: 0\r\n\r\n")
}

/// A multistatus naming the principal.
fn principal_found() -> String {
    let body = "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\"><d:response><d:href>/</d:href>\
    <d:propstat><d:prop><d:current-user-principal><d:href>/principals/jane/</d:href>\
    </d:current-user-principal></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat>\
    </d:response></d:multistatus>";
    format!(
        "HTTP/1.1 207 Multi-Status\r\nContent-Type: application/xml\r\nContent-Length: {}\r\n\r\n{body}",
        body.len()
    )
}

/// The principal PROPFIND of a DAV account, signed in with a password.
fn principal(wire: &mut Scripted) -> Result<Option<Url>, BridgeError> {
    let start: Url = "https://dav.example.org/".parse().unwrap();
    let auth = WebdavAuth::Basic(HttpAuthBasic::new("jane", "secret"));
    run_webdav_redirect(wire, &start, |url| {
        WebdavCurrentUserPrincipal::new(url, &auth, USER_AGENT)
    })
}

/// A redirect on the same server keeps the credential, the request it
/// lands on being the account's.
#[test]
fn a_redirect_on_the_same_server_keeps_the_credential() {
    let mut wire = Scripted::new(&[&moved("/dav/"), &principal_found()]);

    let found = principal(&mut wire).unwrap();

    assert_eq!(
        found.unwrap().as_str(),
        "https://dav.example.org/principals/jane/"
    );
    assert_eq!(wire.writes.len(), 2);
    assert_eq!(wire.writes[1].0, "https://dav.example.org/dav/");
    assert!(wire.authorized(1));
}

/// A redirect from https to http is refused before anything is sent to
/// it: the password would go out in clear.
#[test]
fn a_redirect_to_plain_http_is_refused() {
    let mut wire = Scripted::new(&[&moved("http://dav.example.org/")]);

    let err = principal(&mut wire).unwrap_err();

    assert!(err.message.contains("unencrypted"), "{}", err.message);
    assert_eq!(wire.writes.len(), 1);
}

/// A credentialed DAV request is not followed to another server.
#[test]
fn a_dav_redirect_to_another_server_is_refused() {
    let mut wire = Scripted::new(&[&moved("https://evil.example/")]);

    let err = principal(&mut wire).unwrap_err();

    assert!(err.message.contains("another server"), "{}", err.message);
    assert_eq!(wire.writes.len(), 1);
}

/// A server redirecting to itself stops the chain after the bound
/// instead of pinning the sync forever.
#[test]
fn an_endless_redirect_stops_after_five_hops() {
    let looping = moved("https://dav.example.org/");
    let mut wire = Scripted::new(&[looping.as_str(); 8]);

    let err = principal(&mut wire).unwrap_err();

    assert!(
        err.message.contains("Too many redirects"),
        "{}",
        err.message
    );
    assert_eq!(wire.writes.len(), MAX_REDIRECTS + 1);
}

/// A JMAP download answered from a CDN is followed, without the token.
#[test]
fn a_blob_download_follows_a_cdn_without_the_token() {
    let mut wire = Scripted::new(&[
        "HTTP/1.1 302 Found\r\nLocation: https://cdn.example.net/b1?sig=x\r\nContent-Length: 0\r\n\r\n",
        "HTTP/1.1 200 OK\r\nContent-Length: 4\r\n\r\nblob",
    ]);
    let start: Url = "https://api.example.org/download/a1/b1".parse().unwrap();
    let auth = SecretString::from("Bearer token");

    let out = run_jmap_redirect(&mut wire, &start, CrossOrigin::Anonymous, |url| {
        JmapBlobDownload::new(&auth, url)
    })
    .unwrap();

    assert_eq!(out.data, b"blob");
    assert!(wire.authorized(0));
    assert_eq!(wire.writes[1].0, "https://cdn.example.net/b1?sig=x");
    assert!(!wire.authorized(1));
    assert!(!wire.writes[1].1.contains("token"));
}

/// Even without its token, a download is not followed to plain http.
#[test]
fn a_blob_download_is_not_followed_to_plain_http() {
    let mut wire = Scripted::new(&[&moved("http://cdn.example.net/b1")]);
    let start: Url = "https://api.example.org/download/a1/b1".parse().unwrap();
    let auth = SecretString::from("Bearer token");

    let err = run_jmap_redirect(&mut wire, &start, CrossOrigin::Anonymous, |url| {
        JmapBlobDownload::new(&auth, url)
    })
    .unwrap_err();

    assert!(err.message.contains("unencrypted"), "{}", err.message);
    assert_eq!(wire.writes.len(), 1);
}

/// The session resource means nothing without the token: another server
/// is refused.
#[test]
fn a_jmap_session_redirect_to_another_server_is_refused() {
    let mut wire = Scripted::new(&[&moved("https://evil.example/jmap/session")]);
    let start: Url = "https://api.example.org/jmap/session".parse().unwrap();
    let auth = SecretString::from("Bearer token");

    let err = run_jmap_redirect(&mut wire, &start, CrossOrigin::Refuse, |url| {
        JmapSessionGet::new(&auth, url)
    })
    .unwrap_err();

    assert!(err.message.contains("another server"), "{}", err.message);
    assert_eq!(wire.writes.len(), 1);
}

/// A credential never goes out unencrypted, whatever the protocol: an
/// `Authorization` header on http, an IMAP login on imap, an SMTP AUTH
/// before STARTTLS. The same bytes pass on a TLS scheme, and a plain
/// request carrying none passes too.
#[test]
fn credentials_are_refused_an_unencrypted_connection() {
    let request =
        b"PROPFIND / HTTP/1.1\r\nHost: dav.example.org\r\nAuthorization: Basic amFuZTpz\r\n\r\n";
    let err = refuse_cleartext_credential("http://dav.example.org/", request, false).unwrap_err();
    assert_eq!(
        err.message,
        "Refused to send credentials unencrypted to http://dav.example.org"
    );
    assert!(refuse_cleartext_credential("https://dav.example.org/", request, false).is_ok());
    let anonymous = b"GET /.well-known/carddav HTTP/1.1\r\nHost: example.org\r\n\r\n";
    assert!(refuse_cleartext_credential("http://example.org/", anonymous, false).is_ok());

    let login = b"A1 AUTHENTICATE PLAIN\r\n";
    let imap = "imap://jane%40example.org@mail.example.org:143";
    let err = refuse_cleartext_credential(imap, login, false).unwrap_err();
    assert_eq!(
        err.message,
        "Refused to send credentials unencrypted to imap://mail.example.org:143"
    );
    assert!(refuse_cleartext_credential(imap, b"a login jane secret\r\n", false).is_err());
    assert!(refuse_cleartext_credential(imap, b"A0 CAPABILITY\r\n", false).is_ok());
    assert!(refuse_cleartext_credential("imaps://mail.example.org", login, false).is_ok());

    let auth = b"AUTH PLAIN AGphbmUAcw==\r\n";
    let smtp = "smtp://smtp.example.org:587";
    assert!(refuse_cleartext_credential(smtp, auth, false).is_err());
    assert!(refuse_cleartext_credential(smtp, auth, true).is_ok());
    assert!(refuse_cleartext_credential(smtp, b"EHLO localhost\r\n", false).is_ok());
}

/// Signing in at all is refused on a scheme that cannot be encrypted
/// before the credential goes out.
#[test]
fn signing_in_requires_tls() {
    for url in [
        "https://dav.example.org/",
        "imaps://mail.example.org",
        "smtps://smtp.example.org",
        "smtp://smtp.example.org:587",
    ] {
        assert!(require_tls(url).is_ok(), "{url}");
    }
    assert_eq!(
        require_tls("imap://mail.example.org:143")
            .unwrap_err()
            .message,
        "Refused to sign in unencrypted to imap://mail.example.org:143"
    );
    assert!(require_tls("http://dav.example.org/").is_err());
}

/// An href the server names is followed on its own origin and on a
/// sibling host of the same site over https (iCloud's calendar home),
/// never to another site, another scheme, or under a public suffix.
#[test]
fn a_server_named_href_stays_on_the_server() {
    let check = |base: &str, href: &str| {
        trusted_href(&base.parse().unwrap(), href.parse().unwrap()).is_ok()
    };

    assert!(check(
        "https://dav.example.org/",
        "https://dav.example.org/p/"
    ));
    assert!(check(
        "https://caldav.icloud.com/",
        "https://p42-caldav.icloud.com/123/calendars/"
    ));
    assert!(!check(
        "https://dav.example.org/",
        "https://evil.example/p/"
    ));
    assert!(!check(
        "https://dav.example.org/",
        "http://dav.example.org/p/"
    ));
    assert!(!check(
        "https://dav.example.org/",
        "https://example.org.evil.net/"
    ));
    assert!(!check("https://example.co.uk/", "https://evil.co.uk/"));
    assert!(!check("https://example.org/", "https://evil.org/"));
}

/// An OAuth endpoint is https, a loopback one aside.
#[test]
fn oauth_endpoints_require_https() {
    let check = |url: &str| require_https(&url.parse().unwrap(), "token endpoint");

    assert!(check("https://auth.example.org/token").is_ok());
    assert!(check("http://127.0.0.1:8080/token").is_ok());
    assert!(check("http://localhost/token").is_ok());
    assert_eq!(
        check("http://auth.example.org/token").unwrap_err(),
        "Refused token endpoint http://auth.example.org: http is not https"
    );
}

/// Stripping the credential leaves the rest of the request whole.
#[test]
fn stripping_the_credential_keeps_the_rest() {
    let request =
        b"GET /b HTTP/1.1\r\nHost: a\r\nauthorization: Bearer t\r\nAccept: */*\r\n\r\nbody";
    assert_eq!(
        &*strip_authorization(request),
        b"GET /b HTTP/1.1\r\nHost: a\r\nAccept: */*\r\n\r\nbody"
    );
    let body = b"Authorization: not a header, a body chunk";
    assert_eq!(&*strip_authorization(body), body);
}
