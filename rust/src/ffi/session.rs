//! The mail session a caller holds across native calls.
//!
//! Every other entry point in this bridge is one call: it builds a
//! client, does one thing and drops it, and the Java transport closes
//! the socket after it. That is right for a request and wrong for a
//! conversation. An IMAP connection is a conversation, so a pass that
//! walks an account and then writes three markers paid four greetings,
//! four TLS handshakes and four authentications to send three commands.
//!
//! A session is opened once, handed to Java as a handle, and given back
//! with a fresh environment on every call. What it holds is only what
//! outlives a call: the protocol state, the endpoint and the credential.
//! It holds no JNI reference, because a local one dies when the call
//! returns and a global one would be a registration to leak; the
//! transport stays Java's and rides in as an argument.
//!
//! JMAP and Graph sessions carry no protocol state, HTTP being
//! self-contained. They exist anyway so that every backend is opened,
//! used and closed the same way, and so that the transport under them
//! pools its socket for the whole pass, which is the same win by the
//! other route.

use std::{collections::BTreeMap, sync::Arc};

use url::Url;

use crate::{
    account::{self, Backend},
    client::{
        self, Client,
        gmail_sync::{self, GmailRun},
        imap::ImapState,
    },
    ffi::parse_url,
    types::{BridgeError, Credentials, Mailbox},
};

/// One account's live mail connection, held across native calls.
pub struct MailSession {
    /// Where mail is read from, as the account stores it.
    base_url: String,
    login: String,
    password: String,
    kind: MailKind,
    /// What the pool run this session works for read of a Gmail account,
    /// shared with the run's other sessions; the session's own until it
    /// joins one ([`Self::join_run`]).
    gmail: Arc<GmailRun>,
}

/// The protocol state a session carries, if its backend has any.
enum MailKind {
    Imap(Box<ImapState>),
    Jmap(MailListing),
    Graph(MailListing),
    Gmail(MailListing),
}

/// What an HTTP mail session remembers across a pass.
///
/// No protocol state, an HTTP request carrying its own, but a pass's
/// worth of answers all the same: the mailbox ids the roster named, which
/// every later verb addresses a mailbox by.
///
/// What a Gmail pass reads beyond the roster, the account's listing and
/// envelopes, is the pool run's rather than one session's
/// ([`GmailRun`]).
#[derive(Default)]
pub struct MailListing {
    pub ids: BTreeMap<String, String>,
}

impl MailListing {
    /// Keeps the ids a roster named, by path, and answers the roster.
    pub fn remember(&mut self, listed: Vec<(String, Mailbox)>) -> Vec<Mailbox> {
        self.ids.clear();
        let mut roster = Vec::with_capacity(listed.len());
        for (id, mailbox) in listed {
            self.ids.insert(mailbox.name.clone(), id);
            roster.push(mailbox);
        }
        roster
    }

    /// The id of the mailbox named `mailbox`, reading the roster first
    /// when this session holds no id for it.
    ///
    /// Every verb that addresses a mailbox by id comes through here, so a
    /// session never has to have listed the mailboxes before using one: a
    /// session opened to widen one mailbox, a fill worker's, or one
    /// reopened after its connection died starts with no roster, and used
    /// to refuse every mailbox it was asked for. A name the roster just
    /// read does not hold is a mailbox that is not there (any more).
    pub fn resolve(
        &mut self,
        mailbox: &str,
        roster: impl FnOnce() -> Result<Vec<(String, Mailbox)>, BridgeError>,
    ) -> Result<String, BridgeError> {
        if let Some(id) = self.ids.get(mailbox) {
            return Ok(id.clone());
        }
        self.remember(roster()?);
        match self.ids.get(mailbox) {
            Some(id) => Ok(id.clone()),
            None => Err(format!("No mailbox named `{mailbox}`").into()),
        }
    }
}

impl MailSession {
    /// Opens and authenticates a session for `base_url`.
    pub fn open(
        client: &mut Client<'_, '_>,
        base_url: &str,
        login: &str,
        password: &str,
    ) -> Result<Self, BridgeError> {
        let credentials = Credentials { login, password };
        let kind = match Backend::of(base_url) {
            Backend::Jmap => {
                // Opened eagerly so a session that cannot authenticate
                // fails here rather than on the first verb that uses it.
                let session_url = account::jmap_session_url(base_url)?;
                client.jmap_session_check(&session_url, &credentials)?;
                MailKind::Jmap(MailListing::default())
            }
            Backend::Graph => {
                client.graph_mail_check(password)?;
                MailKind::Graph(MailListing::default())
            }
            Backend::Google => {
                client.gmail_history_id(password)?;
                MailKind::Gmail(MailListing::default())
            }
            _ => {
                let url = parse_url(base_url)?;
                MailKind::Imap(Box::new(client::imap::open(client, &url, &credentials)?))
            }
        };

        Ok(Self {
            base_url: base_url.into(),
            login: login.into(),
            password: password.into(),
            kind,
            gmail: Arc::default(),
        })
    }

    /// The credential every verb signs in with, borrowed from the session.
    pub fn credentials(&self) -> Credentials<'_> {
        Credentials {
            login: &self.login,
            password: &self.password,
        }
    }

    /// The JMAP session resource this account is reached through.
    pub fn jmap_url(&self) -> Result<Url, BridgeError> {
        Ok(account::jmap_session_url(&self.base_url)?)
    }

    /// Whether the backend behind this session speaks JMAP.
    pub fn is_jmap(&self) -> bool {
        matches!(self.kind, MailKind::Jmap(_))
    }

    /// Whether the backend behind this session is Microsoft Graph.
    pub fn is_graph(&self) -> bool {
        matches!(self.kind, MailKind::Graph(_))
    }

    /// Whether the backend behind this session is the Gmail API.
    pub fn is_gmail(&self) -> bool {
        matches!(self.kind, MailKind::Gmail(_))
    }

    /// Whether a delete for good erases one message alone: every HTTP
    /// backend does, an IMAP server only with UIDPLUS (RFC 4315).
    pub fn expunges_one(&self) -> bool {
        match &self.kind {
            MailKind::Imap(state) => state.expunges_one(),
            _ => true,
        }
    }

    /// What the run this session works for read of its Gmail account.
    pub fn gmail_run(&self) -> Arc<GmailRun> {
        Arc::clone(&self.gmail)
    }

    /// Joins the pool run numbered `run`, sharing what it reads of a
    /// Gmail account with the run's other sessions; 0 leaves it for a
    /// run of the session's own, empty.
    pub fn join_run(&mut self, run: i64) {
        let account = format!("{}\u{0}{}", self.base_url, self.login);
        self.gmail = gmail_sync::join(&account, run);
    }

    /// Whether the backend behind this session is IMAP.
    pub fn is_imap(&self) -> bool {
        matches!(self.kind, MailKind::Imap(_))
    }

    /// What this HTTP session remembers of the pass.
    pub fn listing(&mut self) -> &mut MailListing {
        match &mut self.kind {
            MailKind::Jmap(listing) | MailKind::Graph(listing) | MailKind::Gmail(listing) => {
                listing
            }
            MailKind::Imap(_) => unreachable!("no mailbox listing on an IMAP session"),
        }
    }

    /// The IMAP session bound to the call about to use it.
    ///
    /// Refuses a JMAP session rather than opening a second connection
    /// behind the caller's back: a verb reaching for this on a JMAP
    /// account is a verb that has not said what it does there.
    pub fn imap<'a, 'b, 'local>(
        &'a mut self,
        client: &'a mut Client<'b, 'local>,
    ) -> Result<client::imap::ImapSession<'a, 'b, 'local>, BridgeError> {
        match &mut self.kind {
            MailKind::Imap(state) => Ok(client::imap::ImapSession::bind(client, state)),
            MailKind::Jmap(_) | MailKind::Graph(_) | MailKind::Gmail(_) => {
                Err("This account speaks HTTP, which has no IMAP session".into())
            }
        }
    }
}

/// The handle a session crosses to Java as.
///
/// A raw pointer, and the contract that comes with one: Java owns
/// exactly one handle per session, uses it from one thread at a time and
/// frees it once. [`MailSessionHandle`] on the Java side is what holds
/// it to that; nothing here can check it.
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_closed_session_is_refused_rather_than_dereferenced() {
        // The Java side zeroes the handle when it frees one, and a verb
        // arriving after that is the one shape of use-after-free this can
        // still catch. Everything past it is the caller's contract, which
        // is why the caller is one class.
        let refused = unsafe { borrow(0) };
        assert!(refused.is_err(), "a zero handle names no session");
    }

    fn roster(names: &[&str]) -> Vec<(String, Mailbox)> {
        names
            .iter()
            .map(|name| {
                (
                    format!("id-{name}"),
                    Mailbox {
                        name: name.to_string(),
                        role: String::new(),
                    },
                )
            })
            .collect()
    }

    #[test]
    fn a_fresh_session_reads_the_roster_on_its_first_lookup() {
        // A session opened to widen one mailbox, or by a fill worker, has
        // never listed the mailboxes: the lookup reads the roster itself
        // rather than refusing a mailbox that is there.
        let mut listing = MailListing::default();
        let mut listed = 0;
        let id = listing.resolve("Archive", || {
            listed += 1;
            Ok(roster(&["Inbox", "Archive"]))
        });
        assert_eq!(id.unwrap(), "id-Archive");
        assert_eq!(listed, 1);

        let id = listing.resolve("Inbox", || {
            listed += 1;
            Ok(roster(&["Inbox", "Archive"]))
        });
        assert_eq!(id.unwrap(), "id-Inbox");
        assert_eq!(listed, 1, "the roster read once serves every mailbox");
    }

    #[test]
    fn a_mailbox_the_roster_does_not_hold_is_refused() {
        let mut listing = MailListing::default();
        listing.remember(roster(&["Inbox"]));
        let refused = listing.resolve("Gone", || Ok(roster(&["Inbox"])));
        assert_eq!(refused.unwrap_err().message, "No mailbox named `Gone`");
    }

    #[test]
    fn a_mailbox_made_since_the_roster_is_found_by_reading_it_again() {
        let mut listing = MailListing::default();
        listing.remember(roster(&["Inbox"]));
        let id = listing.resolve("Projets", || Ok(roster(&["Inbox", "Projets"])));
        assert_eq!(id.unwrap(), "id-Projets");
    }

    #[test]
    fn a_roster_that_fails_fails_the_lookup() {
        let mut listing = MailListing::default();
        let failed = listing.resolve("Inbox", || Err("offline".into()));
        assert_eq!(failed.unwrap_err().message, "offline");
        assert!(listing.ids.is_empty());
    }

    #[test]
    fn freeing_nothing_is_not_a_free() {
        // Closing twice is what an idempotent close looks like from here,
        // and it has to be a no-op rather than a second `Box::from_raw`.
        unsafe { drop_handle(0) };
    }
}
