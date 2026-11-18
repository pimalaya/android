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
    mail::base64,
};

/// How many truncated rounds one `sync-collection` drain follows, so a
/// server answering truncated forever cannot spin the sync.
const MAX_SYNC_ROUNDS: usize = 32;

/// A body a DAV server sent: the text the phone is shown, read lossily,
/// and, where the bytes are not UTF-8, their base64, what the store keeps
/// (a Latin-1 vCard 2.1 is stored as served, never with U+FFFD).
pub fn served(bytes: Vec<u8>) -> (String, Option<String>) {
    match String::from_utf8(bytes) {
        Ok(text) => (text, None),
        Err(err) => {
            let bytes = err.into_bytes();
            let text = String::from_utf8_lossy(&bytes).into_owned();
            (text, Some(base64(&bytes)))
        }
    }
}

/// Whether a multiget row answered without its data names a member gone
/// (404, 410, RFC 4918 9.2.1); any other is read alone, and failing that
/// fails the fetch ([`unreadable`]).
pub fn gone_row(status: Option<u16>) -> bool {
    matches!(status, Some(404 | 410))
}

/// Whether a read of one member found it gone (404, 410).
pub fn is_gone(err: &BridgeError) -> bool {
    gone_row(err.status)
}

/// The failure of a member a multiget and a read alone both left unread:
/// the fetch fails rather than leave it out, which a complete round would
/// read as removed.
pub fn unreadable(url: &Url, id: &str, err: BridgeError) -> BridgeError {
    BridgeError {
        message: format!("{url}: {id} unreadable, read alone too: {}", err.message),
        status: err.status,
        ..Default::default()
    }
}

/// Whether a body read out of a multistatus may have lost bytes: io-webdav
/// reads the whole answer as UTF-8, each ill-formed sequence replaced by
/// U+FFFD, so a body holding one is read again by GET, whose bytes are the
/// server's ([`served`]).
pub fn maybe_lossy(text: &str) -> bool {
    text.contains('\u{fffd}')
}

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
    /// The drain follows each truncated page's token, [`MAX_SYNC_ROUNDS`] at
    /// most: a page cut short with no new token, or the last round spent,
    /// ends it, the delta answered `truncated` under the last token known,
    /// so the caller reads it as no complete listing (RFC 6578 section 3.6).
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
        let mut rounds = 0;

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
            rounds += 1;

            if !page.truncated {
                delta.sync_token = page.sync_token;
                return Ok(Some(delta));
            }
            let stalled = page.sync_token.is_none() || page.sync_token == token;
            if stalled || rounds == MAX_SYNC_ROUNDS {
                log::warn!("{url}: listing cut short after {rounds} rounds, read as a delta");
                delta.sync_token = page.sync_token.or(token);
                delta.truncated = true;
                return Ok(Some(delta));
            }
            token = page.sync_token;
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

#[cfg(test)]
pub mod tests {
    //! # Drain tests
    //!
    //! The `sync-collection` drain over a fake DAV server answering every
    //! REPORT truncated (a `507` row, RFC 6578 section 3.6) with no token
    //! to follow, up to a point past which it answers whole, so a drain
    //! without a bound still ends.

    use url::Url;

    use crate::client::{
        Auth, Client,
        fake::{FakeRequest, FakeResponse, FakeServer},
    };

    /// A multistatus naming `c1.vcf` changed, cut short by a `507` row when
    /// `truncated`, and naming no sync token.
    fn page(truncated: bool) -> FakeResponse {
        let cut = match truncated {
            true => {
                "<d:response><d:href>/book/</d:href>\
                 <d:status>HTTP/1.1 507 Insufficient Storage</d:status></d:response>"
            }
            false => "",
        };
        let body = format!(
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>\
             <d:multistatus xmlns:d=\"DAV:\">\
             <d:response><d:href>/book/c1.vcf</d:href><d:propstat><d:prop>\
             <d:getetag>\"e1\"</d:getetag></d:prop>\
             <d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response>\
             {cut}</d:multistatus>"
        );
        FakeResponse {
            content_type: "application/xml; charset=utf-8",
            body: body.into_bytes(),
            ..FakeResponse::empty(207)
        }
    }

    /// A server cutting every `REPORT` short, its first hundred at least,
    /// counting them in `reports`.
    pub fn cutting(reports: &mut usize) -> FakeServer<impl FnMut(&FakeRequest) -> FakeResponse> {
        FakeServer::new(move |request: &FakeRequest| {
            assert_eq!(request.method, "REPORT");
            *reports += 1;
            page(*reports <= 100)
        })
    }

    /// A drain over a server cutting every answer short stops within its
    /// bound, with no token to follow, and answers the cut listing as such,
    /// each member once.
    #[test]
    fn a_drain_cut_short_with_no_token_stops_and_says_so() {
        let mut reports = 0;
        let mut client = Client::new(cutting(&mut reports));
        let url: Url = "https://dav.example.com/book/".parse().unwrap();

        let delta = super::Webdav {
            client: &mut client,
            auth: Auth::Bearer("token"),
        }
        .sync_collection(&url, None)
        .unwrap()
        .unwrap();
        drop(client);

        assert!(reports <= super::MAX_SYNC_ROUNDS, "{reports} reports");
        assert!(delta.truncated, "a cut listing is not whole");
        assert_eq!(delta.changed.len(), 1, "each member once");
    }
}
