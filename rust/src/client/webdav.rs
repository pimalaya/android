//! # WebDAV
//!
//! The [`Webdav`] backend and its RFC 6578 `sync-collection` enumeration,
//! shared by CardDAV and CalDAV, which add their verbs in their domains.
//!
//! The enumeration is neither addressbook- nor calendar-shaped: it asks a
//! collection what changed since a token and answers hrefs and ETags.

use io_http::{rfc6750::bearer::HttpAuthBearer, rfc7617::basic::HttpAuthBasic};
use io_webdav::{
    coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
    rfc4918::{GETETAG, WebdavAuth, send::WebdavSendError},
    rfc6578::sync_collection::{
        WebdavSyncCollection, WebdavSyncCollectionError, WebdavSyncCollectionOptions,
        WebdavSyncDelta,
    },
};
use url::Url;

use crate::{
    client::{Auth, Client, Host, USER_AGENT},
    error::{BridgeError, coroutine_error},
};

/// A member href's resource name; [`None`] for the collection itself.
pub fn href_resource(href: &str) -> Option<String> {
    let name = href.trim_end_matches('/');
    let name = &name[name.rfind('/').map_or(0, |slash| slash + 1)..];

    if name.is_empty() || href.ends_with('/') {
        return None;
    }
    Some(name.to_string())
}

impl Auth<'_> {
    /// The WebDAV auth scheme: Bearer (RFC 6750) for a token, else Basic.
    ///
    /// Basic is the only password scheme DAV servers negotiate in practice.
    pub fn webdav(&self) -> WebdavAuth {
        match *self {
            Self::Basic { login, password } => {
                WebdavAuth::Basic(HttpAuthBasic::new(login, password))
            }
            Self::Bearer(token) => WebdavAuth::Bearer(HttpAuthBearer::new(token)),
        }
    }
}

/// A WebDAV server signed in to.
pub struct Webdav<'c, H> {
    pub client: &'c mut Client<H>,
    pub auth: Auth<'c>,
}

impl<H: Host> Webdav<'_, H> {
    /// Runs a `sync-collection` REPORT on `url`, draining truncated pages.
    ///
    /// Without the REPORT, the token-less `PROPFIND` fallback answers an
    /// initial round, and a round carrying a cursor answers [`None`] for the
    /// caller to re-run as one, as a rejected sync token does.
    pub fn sync_collection(
        &mut self,
        url: &Url,
        sync_token: Option<&str>,
    ) -> Result<Option<WebdavSyncDelta>, BridgeError> {
        let auth = self.auth.webdav();
        let mut opts = WebdavSyncCollectionOptions::default();
        let mut token = sync_token.map(str::to_string);
        let mut delta = WebdavSyncDelta::default();

        loop {
            let coroutine = WebdavSyncCollection::new(
                url,
                &auth,
                USER_AGENT,
                url.path(),
                token.as_deref(),
                &[GETETAG],
                opts,
            );
            let page = match self.client.run_sync_collection(url, coroutine)? {
                SyncRound::Page(page) => page,
                SyncRound::InvalidToken => return Ok(None),
                SyncRound::UnsupportedReport if opts.fallback => {
                    return Err(format!("Cannot enumerate the collection at {url}").into());
                }
                SyncRound::UnsupportedReport if sync_token.is_some() => return Ok(None),
                // NOTE: Google answers a token-less `sync-collection` 400
                // (sync tokens at Depth 0 only): such a first round is
                // listed instead, and so every round after it.
                SyncRound::BadRequest(_) if token.is_none() && !opts.fallback => {
                    opts.fallback = true;
                    delta = WebdavSyncDelta::default();
                    continue;
                }
                SyncRound::BadRequest(err) => return Err(err),
                SyncRound::UnsupportedReport => {
                    opts.fallback = true;
                    token = None;
                    delta = WebdavSyncDelta::default();
                    continue;
                }
            };

            delta.changed.extend(page.changed);
            delta.vanished.extend(page.vanished);
            delta.sync_token = page.sync_token;

            if !page.truncated {
                return Ok(Some(delta));
            }
            token = delta.sync_token.clone();
        }
    }
}

impl<H: Host> Client<H> {
    /// Runs one `sync-collection` round, surfacing the refusals acted on.
    ///
    /// [`Client::run`] would erase the error variants they ride in.
    fn run_sync_collection(
        &mut self,
        target: &Url,
        mut coroutine: WebdavSyncCollection,
    ) -> Result<SyncRound, BridgeError> {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                WebdavCoroutineState::Complete(Ok(delta)) => return Ok(SyncRound::Page(delta)),
                WebdavCoroutineState::Complete(Err(
                    WebdavSyncCollectionError::InvalidSyncToken,
                )) => {
                    return Ok(SyncRound::InvalidToken);
                }
                WebdavCoroutineState::Complete(Err(
                    WebdavSyncCollectionError::UnsupportedReport,
                )) => {
                    return Ok(SyncRound::UnsupportedReport);
                }
                WebdavCoroutineState::Complete(Err(
                    err @ WebdavSyncCollectionError::Send(WebdavSendError::HttpStatus {
                        status: 400,
                        ..
                    }),
                )) => return Ok(SyncRound::BadRequest(coroutine_error(&err))),
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
}

/// What one `sync-collection` round answered.
enum SyncRound {
    /// The round ran and returned this page of the delta.
    Page(WebdavSyncDelta),
    /// The server rejected the sync token: enumerate from scratch.
    InvalidToken,
    /// No `sync-collection` REPORT: the `PROPFIND` fallback enumerates.
    UnsupportedReport,
    /// The server refused the request as malformed.
    BadRequest(BridgeError),
}
