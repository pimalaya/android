//! # Accounts
//!
//! Account base URLs and the backend each one names: a DAV context root,
//! or a sentinel scheme for an API with no root (Graph, JMAP, Google).
//!
//! Base URLs are opaque to Java, which threads them back into the bridge
//! and decides by the facts [`AccountInfo`] answers, never by the backend.
//! The domains route on [`Backend`] and address collections through the
//! pieces extracted here; onboarding lives in [`discovery`] and [`oauth`].

mod discovery;
pub mod oauth;

use secrecy::{ExposeSecret, SecretString};
use serde::{Deserialize, Serialize};
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
    /// CardDAV (RFC 6352), the base URL being the context root; also the
    /// CalDAV and IMAP base URLs, which name no sentinel either.
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

    /// True when cards and mail are account-level, in m:n collections.
    ///
    /// JMAP and Google items are listed once per account; CardDAV, IMAP and
    /// Graph items live in the one collection they were listed from.
    pub fn account_level(self) -> bool {
        matches!(self, Self::Jmap | Self::Google)
    }

    /// True when the session names the domains it serves (RFC 8620 section
    /// 2), so a sign-in reads them before probing a domain.
    pub fn announces_capabilities(self) -> bool {
        matches!(self, Self::Jmap)
    }

    /// True when mail with no origin uploads (IMAP APPEND, RFC 9051 section
    /// 6.3.12); the APIs file their own copies.
    pub fn appends_mail(self) -> bool {
        matches!(self, Self::Carddav)
    }

    /// How changed cards are read in bulk.
    pub fn card_fetch(self) -> CardFetch {
        match self {
            Self::Carddav => CardFetch::Multiget,
            Self::Graph => CardFetch::JsonBatch,
            Self::Jmap => CardFetch::Changes,
            Self::Google => CardFetch::BatchGet,
        }
    }

    /// How a round of card changes is pushed.
    pub fn card_push(self) -> CardPush {
        match self {
            Self::Carddav => CardPush::Parallel,
            Self::Graph | Self::Jmap => CardPush::Round,
            Self::Google => CardPush::Batch,
        }
    }

    /// True when mailboxes are labels over an All Mail holding every
    /// message (Gmail).
    pub fn has_all_mail(self) -> bool {
        matches!(self, Self::Google)
    }

    /// True when a reply's `\Answered` marker is kept (IMAP, JMAP
    /// `$answered`); Graph and Gmail refuse to set it.
    pub fn keeps_answered(self) -> bool {
        matches!(self, Self::Carddav | Self::Jmap)
    }

    /// True when a `\Deleted` marker is kept, so a delete needs no trash.
    ///
    /// JMAP has no such keyword (RFC 8621 section 4.1.1), Graph and Gmail
    /// refuse to set it.
    pub fn keeps_deleted(self) -> bool {
        matches!(self, Self::Carddav)
    }

    /// How a mail step walks the account.
    pub fn mail_step(self) -> MailStepKind {
        match self {
            Self::Carddav => MailStepKind::Store,
            Self::Graph => MailStepKind::Mailboxes,
            Self::Jmap | Self::Google => MailStepKind::Account,
        }
    }

    /// True when a created card's resource is named by the client,
    /// `<id>.vcf` (a WebDAV PUT, RFC 4918 section 9.7).
    pub fn names_resources(self) -> bool {
        matches!(self, Self::Carddav)
    }

    /// The protocol family a screen labels the account by.
    pub fn protocol(self) -> Protocol {
        match self {
            Self::Carddav => Protocol::Standard,
            Self::Graph => Protocol::Graph,
            Self::Jmap => Protocol::Jmap,
            Self::Google => Protocol::Google,
        }
    }

    /// True when a card write may answer no revision, read back instead: a
    /// server storing other than it was sent answers no ETag (as CalDAV,
    /// RFC 4791 section 5.3.4).
    pub fn reads_back_revision(self) -> bool {
        matches!(self, Self::Carddav)
    }

    /// How many mail sessions one account runs at once.
    ///
    /// Four over plain HTTP (Graph, JMAP), three for IMAP (a login each),
    /// two for Gmail, paced process-wide so more would queue on one bucket.
    pub fn sessions(self) -> u32 {
        match self {
            Self::Carddav => 3,
            Self::Graph | Self::Jmap => 4,
            Self::Google => 2,
        }
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

/// How a backend reads changed cards in bulk.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum CardFetch {
    /// One `addressbook-multiget` REPORT per chunk (RFC 6352 section 8.7).
    Multiget,
    /// JSON batching of single reads, a complete round's listing priming
    /// the bodies its delta rows lack (Graph `$batch`).
    JsonBatch,
    /// The changes carry the cards, one missing read alone (JMAP).
    Changes,
    /// One read naming many cards (People `people:batchGet`).
    BatchGet,
}

/// How a backend pushes a round of card changes.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum CardPush {
    /// One guarded request per change, run in parallel: no batch verb.
    Parallel,
    /// One request carrying the round (JMAP `/set`, Graph `$batch`).
    Round,
    /// The batch verbs, memberships patched apart (People API).
    Batch,
}

/// How a mail step walks an account.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum MailStepKind {
    /// The account as one listing, newest first (Gmail, JMAP).
    Account,
    /// Each stepped mailbox by date (Graph).
    Mailboxes,
    /// Widened from the store, the server listing per mailbox (IMAP).
    Store,
}

/// The protocol family a screen labels an account by.
#[derive(Clone, Copy, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum Protocol {
    /// The domain's open standard: IMAP, CalDAV or CardDAV.
    Standard,
    /// Microsoft Graph.
    Graph,
    /// JMAP (RFC 8620).
    Jmap,
    /// Google's APIs: Gmail, Calendar, People.
    Google,
    /// The on-device account, with no transport.
    Local,
}

/// What an account base URL tells of its backend: the facts Java decides
/// by, never the backend itself.
#[derive(Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountInfo {
    /// See [`Backend::account_level`].
    pub account_level: bool,
    /// See [`Backend::announces_capabilities`].
    pub announces_capabilities: bool,
    /// See [`Backend::appends_mail`].
    pub appends_mail: bool,
    /// See [`Backend::card_fetch`].
    pub card_fetch: CardFetch,
    /// See [`Backend::card_push`].
    pub card_push: CardPush,
    /// See [`Backend::has_all_mail`].
    pub has_all_mail: bool,
    /// The host the base URL reaches, the providers' APIs by their name;
    /// a URL naming none reads whole.
    pub host: String,
    /// See [`Backend::keeps_answered`].
    pub keeps_answered: bool,
    /// See [`Backend::keeps_deleted`].
    pub keeps_deleted: bool,
    /// See [`Backend::mail_step`].
    pub mail_step: MailStepKind,
    /// See [`Backend::names_resources`].
    pub names_resources: bool,
    /// See [`Backend::protocol`].
    pub protocol: Protocol,
    /// See [`Backend::reads_back_revision`].
    pub reads_back_revision: bool,
    /// See [`Backend::sessions`].
    pub sessions: u32,
    /// See [`Backend::submits_over_session`].
    pub submits_over_session: bool,
}

impl AccountInfo {
    /// What the base URL tells, the on-device account included.
    pub fn of(base_url: &str) -> Self {
        let host = host(base_url);
        let backend = Backend::of(base_url);
        let info = Self {
            account_level: backend.account_level(),
            announces_capabilities: backend.announces_capabilities(),
            appends_mail: backend.appends_mail(),
            card_fetch: backend.card_fetch(),
            card_push: backend.card_push(),
            has_all_mail: backend.has_all_mail(),
            host,
            keeps_answered: backend.keeps_answered(),
            keeps_deleted: backend.keeps_deleted(),
            mail_step: backend.mail_step(),
            names_resources: backend.names_resources(),
            protocol: backend.protocol(),
            reads_back_revision: backend.reads_back_revision(),
            sessions: backend.sessions(),
            submits_over_session: backend.submits_over_session(),
        };

        // NOTE: the local account has no transport, so it is not a
        // Backend; it reads as a standards server whose cards never leave
        // the app's store, so none is named or read back.
        if base_url.starts_with(LOCAL_PREFIX) {
            return Self {
                names_resources: false,
                protocol: Protocol::Local,
                reads_back_revision: false,
                ..info
            };
        }
        info
    }
}

/// The host a base URL reaches, the providers' APIs by their name.
fn host(base_url: &str) -> String {
    let url = match Backend::of(base_url) {
        Backend::Graph => return "graph.microsoft.com".to_string(),
        Backend::Google => return "googleapis.com".to_string(),
        Backend::Jmap => jmap_session_url(base_url).ok(),
        Backend::Carddav => Url::parse(base_url).ok(),
    };

    match url.as_ref().and_then(Url::host_str) {
        Some(host) => host.to_string(),
        None => base_url.to_string(),
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

/// One account as Java's `Account` writes it, the argument of every
/// network export.
///
/// A missing key reads empty, as a null positional string did. The secret
/// is never printed: the struct has no `Debug`.
#[derive(Default, Deserialize)]
#[serde(default, rename_all = "camelCase")]
pub struct AccountJson {
    /// The account's own address, empty for none: Google and JMAP announce
    /// a calendar write to attendees only for a meeting it organizes.
    pub address: String,
    /// The base URL, a context root or a backend sentinel.
    pub base_url: String,
    /// The login, empty when the secret is a Bearer token.
    pub login: String,
    /// The password, or the OAuth 2.0 access token.
    pub secret: SecretString,
    /// Where mail is submitted, empty when the session submits itself.
    pub submit_url: String,
}

impl AccountJson {
    /// The account an operation addresses, its backend told from the URL.
    pub fn account(&self) -> Account<'_> {
        Account {
            base_url: &self.base_url,
            backend: Backend::of(&self.base_url),
            auth: Auth::new(&self.login, self.secret.expose_secret()),
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

    /// Every backend's facts, the on-device account's included: the table
    /// Java decides by.
    #[test]
    fn account_info_answers_every_backend() {
        use CardFetch::*;
        use CardPush::*;
        use MailStepKind::{Account as AccountStep, Mailboxes, Store};

        #[rustfmt::skip]
        let table = [
            // url, level, caps, appends, fetch, push, all mail, host
            // answered, deleted, step, names, protocol, reads back, sessions, submits
            ("google://jane@gmail.com", true, false, false, BatchGet, Batch, true, "googleapis.com",
                false, false, AccountStep, false, Protocol::Google, false, 2, true),
            ("msgraph://jane@outlook.com", false, false, false, JsonBatch, Round, false, "graph.microsoft.com",
                false, false, Mailboxes, false, Protocol::Graph, false, 4, true),
            ("jmap://api.fastmail.com/jmap/session", true, true, false, Changes, Round, false, "api.fastmail.com",
                true, false, AccountStep, false, Protocol::Jmap, false, 4, true),
            ("imaps://imap.example.org:993", false, false, true, Multiget, Parallel, false, "imap.example.org",
                true, true, Store, true, Protocol::Standard, true, 3, false),
            ("https://dav.example.org/", false, false, true, Multiget, Parallel, false, "dav.example.org",
                true, true, Store, true, Protocol::Standard, true, 3, false),
            ("local://on-this-device", false, false, true, Multiget, Parallel, false, "on-this-device",
                true, true, Store, false, Protocol::Local, false, 3, false),
        ];

        for row in table {
            let expected = AccountInfo {
                account_level: row.1,
                announces_capabilities: row.2,
                appends_mail: row.3,
                card_fetch: row.4,
                card_push: row.5,
                has_all_mail: row.6,
                host: row.7.to_string(),
                keeps_answered: row.8,
                keeps_deleted: row.9,
                mail_step: row.10,
                names_resources: row.11,
                protocol: row.12,
                reads_back_revision: row.13,
                sessions: row.14,
                submits_over_session: row.15,
            };
            assert_eq!(AccountInfo::of(row.0), expected, "{}", row.0);
        }
    }

    /// The host keeps what the URL names; a URL naming none reads whole.
    #[test]
    fn account_info_host_reads_the_url() {
        let hosts = [
            ("jmap://API.Example.org", "api.example.org"),
            ("imaps://IMAP.Example.org:993", "IMAP.Example.org"),
            (
                "imaps://jane%40example.org@imap.example.org:993",
                "imap.example.org",
            ),
            ("imap://imap.example.org:143", "imap.example.org"),
            ("https://DAV.Example.org/dav/", "dav.example.org"),
            ("https://[::1]:8443/dav/", "[::1]"),
            ("https://dav_host.example.org/", "dav_host.example.org"),
            (
                "https://www.googleapis.com/carddav/v1/principals/jane@gmail.com/",
                "www.googleapis.com",
            ),
            ("", ""),
        ];

        for (url, host) in hosts {
            assert_eq!(AccountInfo::of(url).host, host, "{url}");
        }
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
