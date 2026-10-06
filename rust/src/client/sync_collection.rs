//! The RFC 6578 `sync-collection` enumeration, shared by both WebDAV
//! backends.
//!
//! Nothing about it is addressbook-shaped or calendar-shaped: it asks a
//! collection what changed since a token and answers hrefs and ETags,
//! which is the same question a calendar has as an addressbook. It lived
//! beside the card verbs while it had one caller; the calendar is the
//! second, and a calendar that re-downloaded every body on every pass was
//! the cost of it not being reachable from there.

use io_webdav::{
    coroutine::{WebdavCoroutine, WebdavCoroutineState, WebdavYield},
    rfc4918::GETETAG,
    rfc6578::sync_collection::{
        WebdavSyncCollection, WebdavSyncCollectionError, WebdavSyncCollectionOptions,
        WebdavSyncDelta,
    },
};
use url::Url;

use crate::{
    client::{Client, USER_AGENT, convert::coroutine_error},
    types::BridgeError,
};

impl Client<'_, '_> {
    /// Runs a `sync-collection` REPORT against the collection at `url`,
    /// draining truncated result sets.
    ///
    /// A server implementing no `sync-collection` is enumerated with the
    /// `PROPFIND` fallback instead, which lists every member and returns
    /// no token: that answers an initial round, and turns a round
    /// carrying a cursor into [`None`] so the caller re-runs it as one.
    /// [`None`] is also what a rejected sync token answers.
    pub fn sync_dav_collection(
        &mut self,
        url: &Url,
        auth: &io_webdav::rfc4918::WebdavAuth,
        sync_token: Option<&str>,
    ) -> Result<Option<WebdavSyncDelta>, BridgeError> {
        let mut opts = WebdavSyncCollectionOptions::default();
        let mut token = sync_token.map(str::to_string);
        let mut delta = WebdavSyncDelta::default();

        loop {
            let coroutine = WebdavSyncCollection::new(
                url,
                auth,
                USER_AGENT,
                url.path(),
                token.as_deref(),
                &[GETETAG],
                opts,
            );
            let page = match self.run_sync_collection(url, coroutine)? {
                SyncRound::Page(page) => page,
                SyncRound::InvalidToken => return Ok(None),
                SyncRound::UnsupportedReport if opts.fallback => {
                    return Err(format!("Cannot enumerate the collection at {url}").into());
                }
                SyncRound::UnsupportedReport if sync_token.is_some() => return Ok(None),
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

    /// Drives one `sync-collection` round, surfacing the two refusals
    /// the caller acts on instead of erasing them (the generic
    /// [`Client::run`] erases the error variants they ride in).
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
                WebdavCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                WebdavCoroutineState::Yielded(WebdavYield::WantsWrite(bytes)) => {
                    self.write(target.as_str(), &bytes)?;
                    arg = None;
                }
                WebdavCoroutineState::Yielded(WebdavYield::WantsRead) => {
                    arg = Some(self.read(target.as_str())?);
                }
            }
        }
    }
}

/// What one `sync-collection` round answered.
enum SyncRound {
    /// The round ran and returned this page of the delta.
    Page(WebdavSyncDelta),
    /// The server rejected the sync token, so the collection has to be
    /// enumerated from scratch.
    InvalidToken,
    /// The server implements no `sync-collection` REPORT, so the
    /// `PROPFIND` fallback has to enumerate the collection instead.
    UnsupportedReport,
}
