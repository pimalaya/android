//! # Accounts
//!
//! Account base URLs and the backend each one names: a DAV context root,
//! or a sentinel scheme for an API with no root (Graph, JMAP, Google).
//!
//! Base URLs are opaque to Java, which threads them back into the bridge.
//! The domains route on [`Backend`] and address collections through the
//! pieces extracted here; onboarding lives in [`discovery`] and [`oauth`].

mod discovery;
pub mod oauth;

use url::Url;

use crate::client::Auth;

/// Sentinel scheme of Microsoft Graph accounts: their base URL is
/// `msgraph://<email>` instead of a CardDAV context root.
pub const MSGRAPH_PREFIX: &str = "msgraph://";

/// Sentinel scheme of Google People API accounts: their base URL is
/// `google://<email>` instead of a CardDAV context root.
pub const GOOGLE_PREFIX: &str = "google://";

/// Sentinel scheme of JMAP accounts, base URL `jmap://<host>[/path]`.
///
/// The part after it is the HTTPS session URL; a bare host triggers
/// `/.well-known/jmap` discovery.
pub const JMAP_PREFIX: &str = "jmap://";

/// Sentinel scheme of the built-in local account, never synced.
///
/// Its cards live only in the app's store, so it never reaches a
/// [`Backend`]; the account info tells it apart to keep the local book out
/// of the sync loop.
pub const LOCAL_PREFIX: &str = "local://";

/// Path segment of the default Contacts folder (empty Graph folder id).
pub const MSGRAPH_DEFAULT_FOLDER: &str = "contacts";

/// Path segment of the single Google Contacts book (empty book id).
pub const GOOGLE_DEFAULT_BOOK: &str = "contacts";

/// The protocol behind an account base URL, told apart by its sentinel
/// scheme (no sentinel means a CardDAV context root).
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub enum Backend {
    /// CardDAV (RFC 6352), the base URL being the context root.
    Carddav,
    /// Microsoft Graph contacts, behind [`MSGRAPH_PREFIX`].
    Graph,
    /// JMAP for Contacts (RFC 9610), behind [`JMAP_PREFIX`].
    Jmap,
    /// Google People API, behind [`GOOGLE_PREFIX`].
    Google,
}

impl Backend {
    /// The backend behind an account base URL.
    pub fn of(base_url: &str) -> Self {
        if base_url.starts_with(MSGRAPH_PREFIX) {
            Self::Graph
        } else if base_url.starts_with(GOOGLE_PREFIX) {
            Self::Google
        } else if base_url.starts_with(JMAP_PREFIX) {
            Self::Jmap
        } else {
            Self::Carddav
        }
    }

    /// True when cards are account-level with m:n book memberships.
    ///
    /// JMAP and Google cards are listed once per account; CardDAV and
    /// Graph cards live in the one collection they were listed from.
    pub fn account_level(self) -> bool {
        matches!(self, Self::Jmap | Self::Google)
    }

    /// True when mail submits through the session it reads from.
    ///
    /// RFC 8621 section 7, Graph `sendMail`, Gmail `messages.send`; an IMAP
    /// account submits over SMTP instead.
    pub fn submits_over_session(self) -> bool {
        matches!(self, Self::Jmap | Self::Graph | Self::Google)
    }

    /// The backend tag on the JNI wire.
    pub fn name(self) -> &'static str {
        match self {
            Self::Carddav => "carddav",
            Self::Graph => "graph",
            Self::Jmap => "jmap",
            Self::Google => "google",
        }
    }
}

/// One account as an operation addresses it.
pub struct Account<'a> {
    /// The base URL, a context root or a backend sentinel.
    pub base_url: &'a str,
    /// The backend behind the base URL.
    pub backend: Backend,
    /// The account's credential.
    pub auth: Auth<'a>,
}

impl<'a> Account<'a> {
    /// The account as Java hands it over, its backend told from the URL.
    pub fn new(base_url: &'a str, login: &'a str, password: &'a str) -> Self {
        Self {
            base_url,
            backend: Backend::of(base_url),
            auth: Auth::new(login, password),
        }
    }
}

/// Builds an account base URL of the given kind.
///
/// `googleCarddav` is Google's CardDAV principal root for the email,
/// `google` and `msgraph` wrap the email behind their sentinel, `jmap` an
/// HTTPS session URL (or bare host) behind [`JMAP_PREFIX`].
pub fn base_url(kind: &str, value: &str) -> Result<String, String> {
    match kind {
        "googleCarddav" => Ok(format!(
            "https://www.googleapis.com/carddav/v1/principals/{value}/"
        )),
        "google" => Ok(format!("{GOOGLE_PREFIX}{value}")),
        "msgraph" => Ok(format!("{MSGRAPH_PREFIX}{value}")),
        "jmap" => {
            let host = value
                .strip_prefix("https://")
                .or_else(|| value.strip_prefix("http://"))
                .unwrap_or(value);
            Ok(format!("{JMAP_PREFIX}{host}"))
        }
        kind => Err(format!("Unknown account base kind `{kind}`")),
    }
}

/// The HTTPS session URL behind a JMAP base URL ([`JMAP_PREFIX`] off).
pub fn jmap_session_url(base_url: &str) -> Result<Url, String> {
    let host = base_url
        .strip_prefix(JMAP_PREFIX)
        .ok_or_else(|| format!("Not a JMAP base URL: `{base_url}`"))?;
    let session_url = format!("https://{host}");

    Url::parse(&session_url).map_err(|err| format!("Invalid JMAP session URL: {err}"))
}

/// The book segment of an addressbook URL: the part after the base URL.
///
/// A URL not extending the base passes through whole.
pub fn book_segment<'a>(base_url: &str, addressbook_url: &'a str) -> &'a str {
    let prefix = format!("{base_url}/");
    match addressbook_url.strip_prefix(&prefix) {
        Some(segment) => segment,
        None => addressbook_url,
    }
}

/// The collection id of a JMAP collection URL: its last path segment.
///
/// Collection ids are unique only inside a JMAP account, so the app's URL
/// is `<base>/<account id>/<collection id>` and only the tail goes back to
/// the server; a bare id (no account named) reads back unchanged.
pub fn jmap_collection_id(collection_url: &str) -> &str {
    collection_url.rsplit('/').next().unwrap_or(collection_url)
}

/// The Graph folder id addressed by an addressbook URL, empty for the
/// default Contacts folder.
pub fn graph_folder<'a>(base_url: &str, addressbook_url: &'a str) -> &'a str {
    let folder = book_segment(base_url, addressbook_url);
    if folder == MSGRAPH_DEFAULT_FOLDER {
        ""
    } else {
        folder
    }
}

/// The card's addressing key, reconstructed for pre-uri local rows.
///
/// CardDAV resources are `<id>.vcf` (Graph shares the fallback, though its
/// rows always carry a uri); JMAP and Google address the bare server id.
pub fn addressing_key(backend: Backend, id: &str, uri: &str) -> String {
    if !uri.is_empty() {
        return uri.to_string();
    }

    match backend {
        Backend::Carddav | Backend::Graph => format!("{id}.vcf"),
        Backend::Jmap | Backend::Google => id.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use crate::account::*;

    /// Each sentinel routes to its backend; anything else is CardDAV.
    #[test]
    fn backend_of_tells_the_sentinels_apart() {
        assert_eq!(Backend::of("msgraph://user@outlook.com"), Backend::Graph);
        assert_eq!(Backend::of("google://user@gmail.com"), Backend::Google);
        assert_eq!(Backend::of("jmap://api.fastmail.com"), Backend::Jmap);
        assert_eq!(Backend::of("https://dav.example.com/"), Backend::Carddav);
        assert_eq!(Backend::of(""), Backend::Carddav);
    }

    /// JMAP and Google cards are account-level, CardDAV and Graph
    /// cards per-collection.
    #[test]
    fn account_level_splits_the_backends() {
        assert!(Backend::Jmap.account_level());
        assert!(Backend::Google.account_level());
        assert!(!Backend::Carddav.account_level());
        assert!(!Backend::Graph.account_level());
    }

    /// API mail backends send over the API; an IMAP base URL needs SMTP.
    #[test]
    fn mail_submits_over_the_session_on_every_api_backend() {
        assert!(Backend::of("jmap://api.fastmail.com").submits_over_session());
        assert!(Backend::of("msgraph://user@outlook.com").submits_over_session());
        assert!(Backend::of("google://user@gmail.com").submits_over_session());
        assert!(!Backend::of("imaps://imap.example.com:993").submits_over_session());
    }

    /// Every kind builds its shape; jmap strips an HTTP(S) scheme.
    #[test]
    fn base_url_builds_every_kind() {
        assert_eq!(
            base_url("googleCarddav", "user@gmail.com").unwrap(),
            "https://www.googleapis.com/carddav/v1/principals/user@gmail.com/",
        );
        assert_eq!(
            base_url("google", "user@gmail.com").unwrap(),
            "google://user@gmail.com",
        );
        assert_eq!(
            base_url("msgraph", "user@outlook.com").unwrap(),
            "msgraph://user@outlook.com",
        );
        assert_eq!(
            base_url("jmap", "https://api.fastmail.com/jmap/session").unwrap(),
            "jmap://api.fastmail.com/jmap/session",
        );
        assert_eq!(
            base_url("jmap", "example.com").unwrap(),
            "jmap://example.com",
        );
        assert!(base_url("imap", "example.com").is_err());
    }

    /// The session URL swaps the sentinel back for HTTPS.
    #[test]
    fn jmap_session_url_restores_https() {
        assert_eq!(
            jmap_session_url("jmap://api.fastmail.com/jmap/session")
                .unwrap()
                .as_str(),
            "https://api.fastmail.com/jmap/session",
        );
        assert!(jmap_session_url("https://api.fastmail.com").is_err());
    }

    /// Only the tail of a JMAP collection URL addresses the collection.
    #[test]
    fn jmap_collection_id_takes_the_last_segment() {
        assert_eq!(
            jmap_collection_id("jmap://api.example.com/session/u33dcb42b/book1"),
            "book1",
        );

        // NOTE: two accounts on one provider naming the same book.
        assert_eq!(
            jmap_collection_id("jmap://api.example.com/session/u9f1e2c04/book1"),
            "book1",
        );

        // NOTE: a session naming no account composed the bare id.
        assert_eq!(jmap_collection_id("jmap://api.example.com/book1"), "book1");
    }

    /// The book segment follows the base URL, an outside URL passes
    /// through, and the Graph default folder maps to the empty id.
    #[test]
    fn book_segment_and_graph_folder_extract_the_id() {
        assert_eq!(book_segment("jmap://host", "jmap://host/abc"), "abc");
        assert_eq!(book_segment("jmap://host", "elsewhere"), "elsewhere");
        assert_eq!(graph_folder("msgraph://user", "msgraph://user/f1"), "f1");
        assert_eq!(
            graph_folder("msgraph://user", "msgraph://user/contacts"),
            ""
        );
    }
}
