//! # Errors
//!
//! The bridge's failure type, [`BridgeError`], and how failures are
//! classified: the HTTP status of a coroutine failure, and which failed
//! writes have converged or been refused for good.

use core::{error::Error as StdError, fmt};

use io_gcal::v3::send::GcalSendError;
use io_gmail::v1::send::GmailSendError;
use io_gpeople::v1::{rest::people::vcard::GpeoplePersonVcardError, send::GpeopleSendError};
use io_gtasks::v1::send::GtasksSendError;
use io_jmap::rfc8620::{
    blob_download::JmapBlobDownloadError, blob_upload::JmapBlobUploadError, send::JmapSendError,
    session_get::JmapSessionGetError,
};
use io_msgraph::v1::{
    rest::users::contacts::vcard::MsgraphContactVcardError, send::MsgraphSendError,
};
use io_webdav::rfc4918::{follow_redirects::WebdavFollowRedirectsError, send::WebdavSendError};
use serde::Serialize;

/// One failed bridge operation, the `{"error", "status"?}` reply Java parses.
///
/// Java branches on the status (412 retries unguarded, 404 converges a
/// removal, 401 refreshes the token), so it is a field, not message prose.
#[derive(Debug, Default, Serialize)]
pub struct BridgeError {
    /// Human-readable failure message.
    #[serde(rename = "error")]
    pub message: String,
    /// Status of the failed HTTP round, absent on non-HTTP failures.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
    /// Whether the server refused the change as it stands (pimdir SYNC
    /// §4), which no later attempt makes it take: a sync answers its push
    /// `Refused`, which the engine holds, where any other failure is pushed
    /// again. Set by the mail connectors alone ([`refuses`]).
    #[serde(skip_serializing_if = "core::ops::Not::not")]
    pub refused: bool,
}

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.message.fmt(f)
    }
}

impl From<String> for BridgeError {
    fn from(message: String) -> Self {
        Self {
            message,
            ..Default::default()
        }
    }
}

impl From<&str> for BridgeError {
    fn from(message: &str) -> Self {
        message.to_string().into()
    }
}

impl From<GpeoplePersonVcardError> for BridgeError {
    fn from(err: GpeoplePersonVcardError) -> Self {
        err.to_string().into()
    }
}

impl From<MsgraphContactVcardError> for BridgeError {
    fn from(err: MsgraphContactVcardError) -> Self {
        err.to_string().into()
    }
}

/// A completed coroutine failure as the bridge error, with its HTTP status.
pub fn coroutine_error(err: &(impl StdError + 'static)) -> BridgeError {
    BridgeError {
        message: err.to_string(),
        status: http_status(err),
        refused: refuses(err),
    }
}

/// Whether a mail server refused a request as it stands (pimdir SYNC §4),
/// read from the failure's source chain: Graph, Gmail or JMAP answering a
/// status naming the request ([`names_the_request`]) that names no quota
/// nor rate limit, the account's state rather than the change's, which the
/// same change outlives. Any other failure cannot tell.
pub fn refuses(err: &(dyn StdError + 'static)) -> bool {
    let mut cause = Some(err);

    while let Some(err) = cause {
        if let Some(send) = err.downcast_ref::<MsgraphSendError>() {
            return send.api().is_some_and(|api| {
                names_the_request(api.status)
                    && api.code != "ErrorQuotaExceeded"
                    && !throttled(&api.message)
            });
        }
        if let Some(GmailSendError::Api(api)) = err.downcast_ref::<GmailSendError>() {
            let limited = api.reasons.iter().chain(&api.detail_reasons).any(|reason| {
                let reason = reason.to_ascii_lowercase();
                reason.contains("limitexceeded") || reason.contains("quota")
            }) || api.google_status.as_deref() == Some("RESOURCE_EXHAUSTED");
            return names_the_request(api.status) && !limited && !throttled(&api.message);
        }
        if let Some(JmapSendError::HttpStatus(status) | JmapSendError::Request { status, .. }) =
            err.downcast_ref()
        {
            return names_the_request(*status) && !throttled(&err.to_string());
        }
        cause = err.source();
    }

    false
}

/// Whether an HTTP status names the request itself (pimdir SYNC §4): any
/// from 400 to 499 but those naming the credentials (401, 407), a state
/// that passes (408, 423, 425, 429) or a stale base (409, 412), as
/// neverest reads it.
pub fn names_the_request(status: u16) -> bool {
    (400..500).contains(&status) && !matches!(status, 401 | 407 | 408 | 409 | 412 | 423 | 425 | 429)
}

/// Whether a server's text names a quota or a rate limit, without case.
fn throttled(text: &str) -> bool {
    let text = text.to_ascii_lowercase();
    ["rate limit", "ratelimit", "quota", "usage limit"]
        .iter()
        .any(|limit| text.contains(limit))
}

/// The HTTP status of the failed round, from the failure's source chain.
///
/// Some coroutines fail with their own status variant sourcing nothing,
/// read by name: JMAP's session get (its 401 must refresh the token),
/// blob upload (a 413 is too large for good) and blob download. A write
/// io-webdav reads as a duplicate `UID` keeps the status it came in:
/// Fastmail's 403 to a second resource of one `UID` is that item's
/// refusal, not the pass's.
fn http_status(err: &(dyn StdError + 'static)) -> Option<u16> {
    let mut cause = Some(err);

    while let Some(err) = cause {
        if let Some(
            WebdavSendError::HttpStatus { status, .. }
            | WebdavSendError::DuplicateUid { status, .. },
        ) = err.downcast_ref()
        {
            return Some(*status);
        }
        if let Some(WebdavFollowRedirectsError::HttpStatus { status, .. }) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(JmapSendError::HttpStatus(status) | JmapSendError::Request { status, .. }) =
            err.downcast_ref()
        {
            return Some(*status);
        }
        if let Some(JmapSessionGetError::HttpStatus(status)) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(JmapBlobUploadError::HttpStatus(status)) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(JmapBlobDownloadError::HttpStatus(status)) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(send) = err.downcast_ref::<MsgraphSendError>() {
            return send.status();
        }
        if let Some(send) = err.downcast_ref::<GpeopleSendError>() {
            return send.status();
        }
        if let Some(send) = err.downcast_ref::<GcalSendError>() {
            return send.status();
        }
        if let Some(send) = err.downcast_ref::<GtasksSendError>() {
            return send.status();
        }
        if let Some(send) = err.downcast_ref::<GmailSendError>() {
            return send.status();
        }
        cause = err.source();
    }

    None
}

/// A write on a message the server no longer holds (404) as converged.
///
/// Deleted, purged, or a draft saved under a new id: a removal, a move or
/// a marker has nothing left to apply to, so it answers [`None`].
pub fn converged<T>(result: Result<T, BridgeError>) -> Result<Option<T>, BridgeError> {
    match result {
        Ok(done) => Ok(Some(done)),
        Err(err) if err.status == Some(404) => Ok(None),
        Err(err) => Err(err),
    }
}

/// The status of a write the server refused for good (422, unprocessable).
pub const REFUSED: u16 = 422;

/// Whether a failed write is one the server will refuse every time.
///
/// A 4xx naming the request itself, not a precondition (409, 412), a
/// throttle (429, a rate or quota 403), the token (401) or a gone event
/// (404, 410), which a later pass may well see through.
pub fn refused_for_good(err: &BridgeError) -> bool {
    match err.status {
        Some(403) => !throttled(&err.message),
        Some(401 | 404 | 409 | 410 | 412 | 429) => false,
        Some(status) => (400..500).contains(&status),
        None => false,
    }
}

/// What a write of several parts answers for those that did not land.
///
/// A failure a later pass may see through keeps it waiting (412); only
/// refusals for good make it [`REFUSED`]; [`None`] when every part landed.
pub fn parts_failure(failures: Vec<BridgeError>) -> Option<BridgeError> {
    let refused = failures.iter().all(refused_for_good);
    let first = failures
        .iter()
        .find(|err| !refused_for_good(err))
        .or(failures.first())?;

    Some(BridgeError {
        message: first.message.clone(),
        status: Some(if refused { REFUSED } else { 412 }),
        ..Default::default()
    })
}

#[cfg(test)]
mod tests {
    use io_jmap::rfc8620::{
        blob_upload::JmapBlobUploadError, error::JmapRequestError, send::JmapSendError,
        session_get::JmapSessionGetError,
    };

    use crate::error::{BridgeError, coroutine_error, refused_for_good};

    #[test]
    fn bridge_error_serializes_the_wire_shape() {
        let plain: BridgeError = "Invalid URL".into();
        assert_eq!(
            serde_json::to_string(&plain).unwrap(),
            r#"{"error":"Invalid URL"}"#,
        );

        let http = BridgeError {
            message: "WebDAV server returned HTTP 412".into(),
            status: Some(412),
            ..Default::default()
        };
        assert_eq!(
            serde_json::to_string(&http).unwrap(),
            r#"{"error":"WebDAV server returned HTTP 412","status":412}"#,
        );
    }

    #[test]
    fn only_a_request_refused_as_such_is_refused_for_good() {
        let failed = |status, message: &str| BridgeError {
            message: message.into(),
            status: Some(status),
            ..Default::default()
        };

        assert!(refused_for_good(&failed(
            400,
            "ErrorOccurrenceCrossingBoundary"
        )));
        assert!(refused_for_good(&failed(403, "ErrorAccessDenied")));
        assert!(refused_for_good(&failed(422, "Unprocessable")));
        assert!(!refused_for_good(&failed(403, "Rate Limit Exceeded")));
        assert!(!refused_for_good(&failed(
            403,
            "Calendar usage limits exceeded."
        )));
        for status in [401, 404, 409, 410, 412, 429, 500, 503] {
            assert!(!refused_for_good(&failed(status, "")), "{status}");
        }
        assert!(!refused_for_good(&BridgeError::from("No connection")));
    }

    #[test]
    fn a_status_the_failure_carries_itself_still_crosses() {
        // NOTE: JmapSessionGetError sources nothing, and a JMAP round's
        // first call is a session get: its 401 must cross to refresh.
        let session = coroutine_error(&JmapSessionGetError::HttpStatus(401));
        assert_eq!(session.status, Some(401));
        assert!(session.message.contains("401"));

        // NOTE: a blob upload's 413 is a message the outbox parks.
        let upload = coroutine_error(&JmapBlobUploadError::HttpStatus(413));
        assert_eq!(upload.status, Some(413));

        assert_eq!(
            coroutine_error(&JmapSendError::HttpStatus(412)).status,
            Some(412)
        );

        // NOTE: a 401 answered as problem details must refresh as well.
        for status in [400, 401] {
            let error = JmapRequestError::Other {
                type_name: "urn:ietf:params:jmap:error:notRequest".into(),
                detail: None,
            };
            let refused = coroutine_error(&JmapSendError::Request { status, error });
            assert_eq!(refused.status, Some(status));
        }

        let parsed = coroutine_error(&JmapSessionGetError::NoPrimaryMailAccount);
        assert_eq!(parsed.status, None);
    }

    /// A status naming the request refuses a mail change as it stands, but
    /// those naming the credentials, a passing state or a stale base, and a
    /// quota or a rate limit whatever its status (pimdir SYNC §4).
    #[test]
    fn a_mail_refusal_is_told_by_its_status_and_never_by_a_quota() {
        use io_gmail::v1::send::{GmailApiError, GmailSendError};
        use io_msgraph::v1::send::{MsgraphApiError, MsgraphSendError};

        let graph = |status, code: &str| {
            let body = format!(r#"{{"error": {{"code": "{code}", "message": "m"}}}}"#);
            let api = MsgraphApiError::parse(status, [], body.as_bytes());
            coroutine_error(&MsgraphSendError::Api(Box::new(api))).refused
        };
        assert!(graph(400, "ErrorInvalidRequest"));
        assert!(graph(403, "ErrorAccessDenied"));
        assert!(!graph(403, "ErrorQuotaExceeded"));
        for status in [401, 407, 408, 409, 412, 423, 425, 429, 500, 503, 507] {
            assert!(!graph(status, "Other"), "{status}");
        }

        let gmail = |status, reason: &str| {
            let body = format!(
                r#"{{"error": {{"code": {status}, "message": "m",
                    "errors": [{{"reason": "{reason}", "message": "m"}}]}}}}"#
            );
            let api = GmailApiError::parse(status, body.as_bytes());
            coroutine_error(&GmailSendError::Api(api)).refused
        };
        assert!(gmail(400, "invalidArgument"));
        assert!(gmail(403, "insufficientPermissions"));
        assert!(!gmail(403, "userRateLimitExceeded"));
        assert!(!gmail(403, "quotaExceeded"));
        assert!(!gmail(429, "rateLimitExceeded"));
        assert!(!gmail(500, "backendError"));

        assert!(coroutine_error(&JmapSendError::HttpStatus(400)).refused);
        assert!(!coroutine_error(&JmapSendError::HttpStatus(412)).refused);
        assert!(!coroutine_error(&JmapSessionGetError::HttpStatus(403)).refused);
    }
}
