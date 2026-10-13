//! # Trust
//!
//! Where a credential may go: two rules, each enforced once.
//!
//! Every byte written passes [`refuse_cleartext_credential`]
//! ([`Client::write`]): no credential over an unencrypted connection.
//! Every redirect passes [`Redirects::follow`]: at most [`MAX_REDIRECTS`],
//! never off https, never with the credential to another origin.
//!
//! [`Client::write`]: crate::client::Client::write

use std::borrow::Cow;

use url::{Host, Origin, Url};

use crate::error::BridgeError;

/// The most redirects one request follows.
///
/// RFC 9110 section 15.4 leaves the bound to the client; browsers stop
/// near 20, a sync needs far fewer.
const MAX_REDIRECTS: usize = 5;

/// What a redirect to another origin does to a credentialed request.
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum CrossOrigin {
    /// Refused: a server sending the credential elsewhere is not trusted.
    Refuse,
    /// Followed without the credential, as fetch does.
    ///
    /// A JMAP download answered from a CDN, the target carrying its own
    /// authorization.
    Anonymous,
}

/// One request's redirect chain, from the origin its credential belongs to.
pub struct Redirects {
    origin: Origin,
    cross_origin: CrossOrigin,
    hops: usize,
    credentialed: bool,
}

impl Redirects {
    /// A chain starting at `start`, credentialed.
    pub fn new(start: &Url, cross_origin: CrossOrigin) -> Self {
        Self {
            origin: start.origin(),
            cross_origin,
            hops: 0,
            credentialed: true,
        }
    }

    /// Takes one redirect from `from` to `to`, or refuses it.
    pub fn follow(&mut self, from: &Url, to: &Url) -> Result<(), BridgeError> {
        self.hops += 1;
        if self.hops > MAX_REDIRECTS {
            return Err(format!(
                "Too many redirects from {}",
                from.origin().ascii_serialization()
            )
            .into());
        }

        let scheme = to.scheme();
        if scheme != "https" && scheme != "http" {
            return Err(format!("Refused a redirect to a {scheme} URL").into());
        }
        if from.scheme() == "https" && scheme != "https" {
            return Err(format!(
                "Refused a redirect from {} to unencrypted {}",
                from.origin().ascii_serialization(),
                to.origin().ascii_serialization()
            )
            .into());
        }

        if self.credentialed && to.origin() != self.origin {
            match self.cross_origin {
                CrossOrigin::Refuse => {
                    return Err(format!(
                        "Refused a redirect from {} to another server, {}",
                        self.origin.ascii_serialization(),
                        to.origin().ascii_serialization()
                    )
                    .into());
                }
                CrossOrigin::Anonymous => {
                    log::debug!(
                        "redirected to {}, following without credentials",
                        to.origin().ascii_serialization()
                    );
                    self.credentialed = false;
                }
            }
        }

        Ok(())
    }

    /// The request bytes to write, without `Authorization` once uncredentialed.
    pub fn request<'b>(&self, bytes: &'b [u8]) -> Cow<'b, [u8]> {
        if self.credentialed {
            Cow::Borrowed(bytes)
        } else {
            strip_authorization(bytes)
        }
    }
}

/// Refuses `bytes` carrying a credential to an unencrypted `url`.
///
/// A credential is an `Authorization` header, an IMAP `AUTHENTICATE` or
/// `LOGIN`, an SMTP `AUTH`. Unencrypted is plain `http`, or `imap` and
/// `smtp` not `upgraded` by STARTTLS; `tcp` (the DNS resolver's) passes.
pub fn refuse_cleartext_credential(
    url: &str,
    bytes: &[u8],
    upgraded: bool,
) -> Result<(), BridgeError> {
    let scheme = url
        .split_once("://")
        .map_or("", |(scheme, _)| scheme)
        .to_ascii_lowercase();
    let carries = match scheme.as_str() {
        "http" => http_head(bytes).is_some_and(|head| head.iter().any(|line| authorization(line))),
        "imap" if !upgraded => lines(bytes).any(imap_credential),
        "smtp" if !upgraded => lines(bytes).any(|line| starts_with_ignore_case(line, b"AUTH ")),
        _ => false,
    };

    if carries {
        return Err(format!("Refused to send credentials unencrypted to {}", server(url)).into());
    }

    Ok(())
}

/// The scheme, host and port of `url`, for an error to name.
fn server(url: &str) -> String {
    match Url::parse(url) {
        Ok(url) => {
            let host = url.host_str().unwrap_or_default();
            match url.port() {
                Some(port) => format!("{}://{host}:{port}", url.scheme()),
                None => format!("{}://{host}", url.scheme()),
            }
        }
        Err(_) => "the server".into(),
    }
}

/// Refuses a credential to `url` unless encrypted from its first byte.
///
/// `smtp` passes too: the bridge always upgrades it before any credential.
pub fn require_tls(url: &str) -> Result<(), BridgeError> {
    let scheme = url
        .split_once("://")
        .map_or("", |(scheme, _)| scheme)
        .to_ascii_lowercase();
    match scheme.as_str() {
        "https" | "imaps" | "smtps" | "smtp" => Ok(()),
        _ => Err(format!("Refused to sign in unencrypted to {}", server(url)).into()),
    }
}

/// Refuses an OAuth endpoint (`what` names it) that is not https.
///
/// The code, the PKCE verifier and every refresh token travel to it. Plain
/// http is left to a loopback host, a custom client's own test server.
pub fn require_https(url: &Url, what: &str) -> Result<(), String> {
    let loopback = match url.host() {
        Some(Host::Domain(host)) => host.eq_ignore_ascii_case("localhost"),
        Some(Host::Ipv4(ip)) => ip.is_loopback(),
        Some(Host::Ipv6(ip)) => ip.is_loopback(),
        None => false,
    };
    match url.scheme() {
        "https" => Ok(()),
        "http" if loopback => Ok(()),
        scheme => Err(format!(
            "Refused {what} {}: {scheme} is not https",
            server(url.as_str())
        )),
    }
}

/// A server-named href (a principal, a home set) a credential may follow.
///
/// On the naming origin, or over https on a sibling host under the same
/// parent domain (iCloud's `p42-caldav` homes from `caldav.icloud.com`):
/// an absolute href can name any host and scheme.
pub fn trusted_href(base: &Url, href: Url) -> Result<Url, BridgeError> {
    if href.origin() == base.origin() {
        return Ok(href);
    }

    let siblings = href.scheme() == "https"
        && base.scheme() == "https"
        && match (base.host(), href.host()) {
            (Some(Host::Domain(base)), Some(Host::Domain(href))) => {
                parent(base).is_some_and(|parent| under(href, parent))
            }
            _ => false,
        };
    if siblings {
        return Ok(href);
    }

    Err(format!(
        "Refused {} named by {}: another server",
        href.origin().ascii_serialization(),
        base.origin().ascii_serialization()
    )
    .into())
}

/// The domain a host sits under, when a site rather than a public suffix.
///
/// Two labels at least, and not a generic second level such as `co.uk`.
fn parent(host: &str) -> Option<&str> {
    const GENERIC: [&str; 12] = [
        "ac", "co", "com", "edu", "gob", "gov", "ltd", "me", "ne", "net", "or", "org",
    ];

    let (_, parent) = host.split_once('.')?;
    let mut labels = parent.split('.');
    let first = labels.next()?;
    match labels.count() {
        0 => None,
        1 if GENERIC.contains(&first) => None,
        _ => Some(parent),
    }
}

/// Whether `host` is `domain` or sits under it.
fn under(host: &str, domain: &str) -> bool {
    host.eq_ignore_ascii_case(domain)
        || host.len().checked_sub(domain.len() + 1).is_some_and(|dot| {
            host.as_bytes()[dot] == b'.' && host[dot + 1..].eq_ignore_ascii_case(domain)
        })
}

/// The header lines of the HTTP request head `bytes` start with, if any.
fn http_head(bytes: &[u8]) -> Option<Vec<&[u8]>> {
    let mut lines = lines(bytes);
    let request = lines.next()?;
    if !(request.ends_with(b" HTTP/1.1") || request.ends_with(b" HTTP/1.0")) {
        return None;
    }
    Some(lines.take_while(|line| !line.is_empty()).collect())
}

/// Whether one header line is `Authorization`.
fn authorization(line: &[u8]) -> bool {
    starts_with_ignore_case(line, b"authorization:")
}

/// `bytes` without their request head's `Authorization` header.
fn strip_authorization(bytes: &[u8]) -> Cow<'_, [u8]> {
    let Some(head) = http_head(bytes) else {
        return Cow::Borrowed(bytes);
    };
    if !head.iter().any(|line| authorization(line)) {
        return Cow::Borrowed(bytes);
    }

    let mut stripped = Vec::with_capacity(bytes.len());
    let mut rest = bytes;
    let mut in_head = true;
    while !rest.is_empty() {
        let end = rest
            .windows(2)
            .position(|pair| pair == b"\r\n")
            .map_or(rest.len(), |at| at + 2);
        let (line, next) = rest.split_at(end);
        if in_head && line == b"\r\n" {
            in_head = false;
        }
        if !(in_head && authorization(line)) {
            stripped.extend_from_slice(line);
        }
        rest = next;
    }
    Cow::Owned(stripped)
}

/// Whether an IMAP line is `AUTHENTICATE` or `LOGIN` (RFC 9051 sec. 6.2).
fn imap_credential(line: &[u8]) -> bool {
    let mut words = line
        .split(|byte| *byte == b' ')
        .filter(|word| !word.is_empty());
    let _tag = words.next();
    words.next().is_some_and(|command| {
        command.eq_ignore_ascii_case(b"AUTHENTICATE") || command.eq_ignore_ascii_case(b"LOGIN")
    })
}

/// The CRLF- or LF-separated lines of `bytes`, without their ends.
fn lines(bytes: &[u8]) -> impl Iterator<Item = &[u8]> {
    bytes
        .split(|byte| *byte == b'\n')
        .map(|line| line.strip_suffix(b"\r").unwrap_or(line))
}

fn starts_with_ignore_case(line: &[u8], prefix: &[u8]) -> bool {
    line.len() >= prefix.len() && line[..prefix.len()].eq_ignore_ascii_case(prefix)
}

#[cfg(test)]
mod tests;
