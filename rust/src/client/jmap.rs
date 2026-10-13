//! # JMAP
//!
//! The [`Jmap`] backend (RFC 8620): the session fetch and its cache, the
//! auth header, the resume loops and the paging every domain shares.
//!
//! One session serves contacts (RFC 9610), mail (RFC 8621) and calendars
//! (draft-ietf-jmap-calendars), whose verbs each domain adds in its own
//! jmap file.

use core::{
    error::Error as StdError,
    hash::{Hash, Hasher},
};

use std::{
    collections::BTreeMap,
    sync::{Mutex, MutexGuard},
    time::{Duration, Instant},
};

use io_http::{rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic};
use io_jmap::{
    calendars::JMAP_CALENDARS_CAPABILITY,
    coroutine::{JmapCoroutine, JmapCoroutineState, JmapYield},
    rfc8620::{
        changes::*, coroutine::JmapRedirectYield, error::JmapMethodError, session::JmapSession,
        session_get::*,
    },
    rfc8621::{JMAP_MAIL_CAPABILITY, email_submission::JMAP_SUBMISSION_CAPABILITY},
    rfc9610::{JMAP_CONTACTS_CAPABILITY, contact_card::changes::*},
};
use secrecy::{ExposeSecret, SecretString};
use serde::Serialize;
use serde_json::to_string;
use sha2::{Digest, Sha256};
use siphasher::sip::SipHasher13;
use url::Url;

use crate::{
    client::{
        Auth, Client, Host,
        trust::{CrossOrigin, Redirects},
    },
    error::{BridgeError, REFUSED, coroutine_error},
    mail::listing::JMAP_PAGE,
};

/// A JMAP account reached through its session resource.
pub struct Jmap<'c, H> {
    pub client: &'c mut Client<H>,
    pub session_url: Url,
    pub auth: Auth<'c>,
}

/// The session fetch and the JMAP coroutine runners.
impl<H: Host> Jmap<'_, H> {
    /// Fetches the session resource afresh, caching it.
    ///
    /// A credential that cannot sign in then fails where the connection is
    /// opened rather than on whichever verb happens to run first.
    pub fn check_session(&mut self) -> Result<(), BridgeError> {
        self.fetch_session().map(|_| ())
    }

    /// The capability URNs served, from a fresh fetch.
    ///
    /// What the connection flow picks the domains to connect by
    /// ([`served_capabilities`]).
    pub fn capabilities(&mut self) -> Result<Vec<&'static str>, BridgeError> {
        let session = self.fetch_session()?;
        Ok(served_capabilities(&session))
    }

    /// The session resource (RFC 8620 §2), cached ([`SessionCache`]).
    pub fn session(&mut self) -> Result<JmapSession, BridgeError> {
        let key = session_key(&self.session_url, &self.auth.authorization());
        let cached = sessions().get(&key, Instant::now());
        match cached {
            Some(session) => Ok(session),
            None => self.fetch_session(),
        }
    }

    /// Fetches and caches the session resource, following redirects.
    ///
    /// A bare origin triggers /.well-known/jmap discovery.
    fn fetch_session(&mut self) -> Result<JmapSession, BridgeError> {
        let http_auth = self.auth.authorization();
        let session_url = self.session_url.clone();
        let out = self.run_redirect(&session_url, CrossOrigin::Refuse, |target| {
            JmapSessionGet::new(&http_auth, target)
        })?;
        let key = session_key(&session_url, &http_auth);
        sessions().put(key, Instant::now(), out.session.clone());
        Ok(out.session)
    }

    /// Runs a coroutine that may answer 3xx, within [`Redirects`].
    pub fn run_redirect<C, T, E>(
        &mut self,
        start: &Url,
        cross_origin: CrossOrigin,
        make: impl Fn(&Url) -> C,
    ) -> Result<T, BridgeError>
    where
        C: JmapCoroutine<Yield = JmapRedirectYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut redirects = Redirects::new(start, cross_origin);
        let mut target = start.clone();

        loop {
            let mut coroutine = make(&target);
            let mut arg: Option<Vec<u8>> = None;

            loop {
                match coroutine.resume(arg.as_deref()) {
                    JmapCoroutineState::Complete(Ok(out)) => return Ok(out),
                    JmapCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsWrite(bytes)) => {
                        self.client
                            .http_write(target.as_str(), &redirects.request(&bytes))?;
                        arg = None;
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRead) => {
                        arg = Some(self.client.http_read(target.as_str())?);
                    }
                    JmapCoroutineState::Yielded(JmapRedirectYield::WantsRedirect {
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

    /// Runs a JMAP method coroutine against the session's API URL.
    ///
    /// A failure drops the sessions cached for that API, so the next verb
    /// reads the session again rather than trusting a stale one.
    pub fn run<C, T, E>(&mut self, api_url: &Url, coroutine: C) -> Result<T, BridgeError>
    where
        C: JmapCoroutine<Yield = JmapYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        self.try_run(api_url, coroutine)?.map_err(|err| {
            sessions().forget(api_url);
            coroutine_error(&err)
        })
    }

    /// Runs one `ContactCard/changes` round, an uncomputable state [`None`].
    ///
    /// [`Self::run`] would erase the error variant the fallback needs.
    pub fn run_changes(
        &mut self,
        api_url: &Url,
        coroutine: JmapContactCardChanges,
    ) -> Result<Option<JmapChangesOutput>, BridgeError> {
        match self.try_run(api_url, coroutine)? {
            Ok(out) => Ok(Some(out)),
            Err(JmapContactCardChangesError::Changes(JmapChangesError::Method(
                JmapMethodError::CannotCalculateChanges { .. },
            ))) => Ok(None),
            Err(err) => {
                sessions().forget(api_url);
                Err(coroutine_error(&err))
            }
        }
    }

    /// Runs a JMAP method coroutine, keeping its own result inside.
    ///
    /// The outer error is the transport's, for the runners above to read
    /// the coroutine's as it completed.
    pub fn try_run<C, T, E>(
        &mut self,
        api_url: &Url,
        mut coroutine: C,
    ) -> Result<Result<T, E>, BridgeError>
    where
        C: JmapCoroutine<Yield = JmapYield, Return = Result<T, E>>,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(result) => return Ok(result),
                JmapCoroutineState::Yielded(JmapYield::WantsRead) => {
                    arg = Some(self.client.http_read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.client.http_write(api_url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// How long a cached session resource is trusted.
///
/// io-jmap's outputs do not surface the `sessionState` saying it changed,
/// so the cache is bounded by age, by any call failing on it, and by every
/// mail session opening afresh.
const SESSION_TTL: Duration = Duration::from_secs(15 * 60);

/// The session resources fetched so far, by session URL and credential.
///
/// One fetch serves every verb of a pass, of every domain it covers.
pub struct SessionCache {
    entries: BTreeMap<String, (Instant, JmapSession)>,
}

impl SessionCache {
    /// The session cached under `key`, unless older than [`SESSION_TTL`].
    fn get(&mut self, key: &str, now: Instant) -> Option<JmapSession> {
        let (at, session) = self.entries.get(key)?;
        if now.duration_since(*at) < SESSION_TTL {
            return Some(session.clone());
        }
        self.entries.remove(key);
        None
    }

    /// Caches one session, dropping those past [`SESSION_TTL`].
    ///
    /// A refreshed token is a new key: the old one would stay for good.
    fn put(&mut self, key: String, now: Instant, session: JmapSession) {
        self.entries
            .retain(|_, (at, _)| now.duration_since(*at) < SESSION_TTL);
        self.entries.insert(key, (now, session));
    }

    /// Drops every session reached through `api_url`.
    pub fn forget(&mut self, api_url: &Url) {
        self.entries
            .retain(|_, (_, session)| session.api_url != *api_url);
    }
}

/// The process's session cache.
pub fn sessions() -> MutexGuard<'static, SessionCache> {
    static SESSIONS: Mutex<SessionCache> = Mutex::new(SessionCache {
        entries: BTreeMap::new(),
    });
    SESSIONS.lock().unwrap_or_else(|err| err.into_inner())
}

/// The cache key of a session: its URL and a digest of the credential.
///
/// A refreshed token or another login never reads another's session.
fn session_key(session_url: &Url, auth: &SecretString) -> String {
    let digest = Sha256::digest(auth.expose_secret().as_bytes());
    let hex: String = digest.iter().map(|byte| format!("{byte:02x}")).collect();
    format!("{session_url} {hex}")
}

/// The requests of one JMAP verb, all over the session it started with.
pub struct JmapCalls<'j, 'c, H> {
    pub jmap: &'j mut Jmap<'c, H>,
    pub session: JmapSession,
    pub auth: SecretString,
}

impl<'c, H: Host> Jmap<'c, H> {
    /// The verb's calls over the session, cached or fetched, read once.
    pub fn calls(&mut self) -> Result<JmapCalls<'_, 'c, H>, BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
        Ok(JmapCalls {
            jmap: self,
            session,
            auth,
        })
    }
}

/// Reads a query a page at a time, answering it and whether it is whole.
///
/// `page` answers a page's objects, the stated total and its position. An
/// empty or non-advancing page short of the total is a capped listing: it
/// ends incomplete, so nothing left out reads as removed.
pub fn paged<T>(
    mut page: impl FnMut(u64) -> Result<(Vec<T>, Option<u64>, u64), BridgeError>,
) -> Result<(Vec<T>, bool), BridgeError> {
    let mut read = Vec::new();
    let mut position = 0;

    loop {
        let (objects, total, at) = page(position)?;
        let count = objects.len() as u64;
        read.extend(objects);
        let reached = at + count;
        if total.is_some_and(|total| reached >= total) {
            return Ok((read, true));
        }
        if count == 0 || reached <= position {
            return Ok((read, total.is_none() && count == 0));
        }
        position = reached;
    }
}

/// A failure no later pass changes, answered with [`REFUSED`].
pub fn refused(message: impl Into<String>) -> BridgeError {
    BridgeError {
        message: message.into(),
        status: Some(REFUSED),
    }
}

/// The capabilities this app reads that have a primary account.
///
/// Submission rides the mail account, which `EmailSubmission/set` names.
fn served_capabilities(session: &JmapSession) -> Vec<&'static str> {
    [
        (JMAP_MAIL_CAPABILITY, JMAP_MAIL_CAPABILITY),
        (JMAP_SUBMISSION_CAPABILITY, JMAP_MAIL_CAPABILITY),
        (JMAP_CONTACTS_CAPABILITY, JMAP_CONTACTS_CAPABILITY),
        (JMAP_CALENDARS_CAPABILITY, JMAP_CALENDARS_CAPABILITY),
    ]
    .into_iter()
    .filter(|(urn, account)| {
        session.capabilities.contains_key(*urn)
            && !session.primary_account_id_for(account).is_empty()
    })
    .map(|(urn, _)| urn)
    .collect()
}

impl Auth<'_> {
    /// The `Authorization` header value: Bearer for a token, else Basic.
    pub fn authorization(&self) -> SecretString {
        let value = match *self {
            Self::Basic { login, password } => {
                HttpAuthBasic::new(login, password).to_authorization()
            }
            Self::Bearer(token) => HttpAuthBearer::new(token).to_authorization(),
        };

        SecretString::from(value)
    }
}

/// The server's `maxObjectsInGet` (RFC 8620 §2), else [`JMAP_PAGE`].
pub fn max_objects_in_get(session: &JmapSession) -> u64 {
    session
        .core_capability()
        .max_objects_in_get
        .filter(|max| *max > 0)
        .unwrap_or(JMAP_PAGE)
}

/// One page of a listing: [`JMAP_PAGE`], capped by `maxObjectsInGet`.
pub fn page_size(session: &JmapSession) -> u64 {
    JMAP_PAGE.min(max_objects_in_get(session))
}

/// Revision token of a ContactCard or a CalendarEvent: a hash of its JSON.
///
/// serde_json maps are key-sorted, so the hash ignores the server's order.
/// SipHash-1-3 with zero keys, the values `DefaultHasher` gave, is pinned:
/// a toolchain changing it would turn every staged `If-Match` stale.
pub fn etag(object: &impl Serialize) -> Option<String> {
    let json = to_string(object).ok()?;
    let mut hasher = SipHasher13::new();
    json.hash(&mut hasher);

    Some(format!("{:016x}", hasher.finish()))
}

/// A JMAP collection's id behind its account id, bare when none serves it.
///
/// Ids are unique per JMAP account only and the app keys a collection by
/// URL: unscoped, two accounts' same-named books would merge into one row.
/// [`crate::account::jmap_collection_id`] strips the prefix back off.
pub fn jmap_collection_path(session: &JmapSession, capability: &str, id: &str) -> String {
    match session.primary_accounts.get(capability) {
        Some(account) if !account.is_empty() => format!("{account}/{id}"),
        _ => id.to_string(),
    }
}

#[cfg(test)]
mod tests {
    use std::time::{Duration, Instant};

    use serde_json::json;

    use crate::client::{fake::jmap_session, jmap::*};

    /// The revisions stored before the hash was pinned stay the same.
    #[test]
    fn a_revision_keeps_the_value_it_was_stored_with() {
        let card = json!({"@type": "Card", "uid": "u1", "name": {"full": "Ada Lovelace"}});
        let event = json!({
            "id": "e1",
            "title": "Réunion",
            "start": "2026-10-13T09:00:00",
            "recurrenceOverrides": {"2026-10-20T09:00:00": {"excluded": true}},
        });

        assert_eq!(etag(&json!({})).unwrap(), "5477a4babbc26733");
        assert_eq!(etag(&card).unwrap(), "26986cdfe1f04538");
        assert_eq!(etag(&event).unwrap(), "491141ae554746a9");
    }

    #[test]
    fn a_capability_is_served_only_with_an_account_to_use_it_in() {
        let session = jmap_session(
            json!({
                "urn:ietf:params:jmap:core": {},
                "urn:ietf:params:jmap:mail": {},
                "urn:ietf:params:jmap:submission": {},
                "urn:ietf:params:jmap:contacts": {},
                "urn:ietf:params:jmap:calendars": {},
            }),
            json!({
                "urn:ietf:params:jmap:mail": "a1",
                "urn:ietf:params:jmap:contacts": "a1",
            }),
        );

        assert_eq!(
            served_capabilities(&session),
            [
                JMAP_MAIL_CAPABILITY,
                JMAP_SUBMISSION_CAPABILITY,
                JMAP_CONTACTS_CAPABILITY
            ],
            "calendars are advertised with no primary account"
        );
    }

    #[test]
    fn a_cached_session_serves_until_it_ages_or_its_api_fails() {
        let mut cache = SessionCache {
            entries: BTreeMap::new(),
        };
        let url = Url::parse("https://api.example.com/jmap/session").unwrap();
        let jane = session_key(&url, &SecretString::from("Bearer jane"));
        let other = session_key(&url, &SecretString::from("Bearer other"));
        let now = Instant::now();

        cache.put(jane.clone(), now, jmap_session(json!({}), json!({})));

        assert!(cache.get(&jane, now + Duration::from_secs(60)).is_some());
        assert!(
            cache.get(&other, now).is_none(),
            "another credential misses"
        );
        assert!(cache.get(&jane, now + SESSION_TTL).is_none());
        assert!(
            cache.get(&jane, now).is_none(),
            "an aged session is dropped"
        );

        cache.put(jane.clone(), now, jmap_session(json!({}), json!({})));
        cache.forget(&Url::parse("https://api.example.com/jmap/").unwrap());

        assert!(cache.get(&jane, now).is_none());

        // NOTE: a refreshed token is a new key; the old one goes once aged.
        cache.put(jane.clone(), now, jmap_session(json!({}), json!({})));
        cache.put(other, now + SESSION_TTL, jmap_session(json!({}), json!({})));

        assert_eq!(cache.entries.len(), 1);
    }
}
