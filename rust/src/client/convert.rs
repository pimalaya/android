//! Free conversion and error helpers shared by more than one backend:
//! the completed-coroutine error mapping (with its HTTP-status probe)
//! and the push-change field and outcome helpers the Graph and JMAP
//! push paths both lean on.

use core::error::Error as StdError;

use io_gpeople::v1::send::GpeopleSendError;
use io_jmap::rfc8620::{send::JmapSendError, session_get::JmapSessionGetError};
use io_msgraph::v1::send::MsgraphSendError;
use io_webdav::rfc4918::{follow_redirects::WebdavFollowRedirectsError, send::WebdavSendError};

use crate::types::{BridgeError, PushOutcome};

/// A completed coroutine failure as the bridge error: the display
/// message, plus the HTTP status dug out of the failure itself.
pub(crate) fn coroutine_error(err: &(impl StdError + 'static)) -> BridgeError {
    BridgeError {
        message: err.to_string(),
        status: http_status(err),
    }
}

/// Walks the failure's source chain down to the transport leaf that
/// knows the HTTP status of the failed round, if the failure was one:
/// io-webdav and io-jmap carry it on their send errors, io-msgraph
/// and io-gpeople expose it as an accessor.
///
/// **Not every status is on a send error.** A few coroutines check the
/// status themselves and fail with a variant of their own that formats
/// it into the message and sources nothing, so the walk below runs
/// straight past it: the status has to be read off those variants by
/// name. JMAP's session fetch is one, and missing it cost every JMAP
/// account its token refresh, since a session get is the first call of
/// every JMAP round and a 401 that reports no status is a 401 nothing
/// can retry.
fn http_status(err: &(dyn StdError + 'static)) -> Option<u16> {
    let mut cause = Some(err);

    while let Some(err) = cause {
        if let Some(WebdavSendError::HttpStatus { status, .. }) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(WebdavFollowRedirectsError::HttpStatus { status, .. }) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(JmapSendError::HttpStatus(status)) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(JmapSessionGetError::HttpStatus(status)) = err.downcast_ref() {
            return Some(*status);
        }
        if let Some(send) = err.downcast_ref::<MsgraphSendError>() {
            return send.status();
        }
        if let Some(send) = err.downcast_ref::<GpeopleSendError>() {
            return send.status();
        }
        cause = err.source();
    }

    None
}

/// A required field of a push change, named in the error when absent.
pub(crate) fn required<'a>(
    field: &'a Option<String>,
    op: &str,
    name: &str,
) -> Result<&'a str, BridgeError> {
    field
        .as_deref()
        .ok_or_else(|| format!("Push {op} change is missing its {name}").into())
}

/// One rejected push outcome carrying what the server objected.
pub(crate) fn rejected(reference: String, error: String) -> PushOutcome {
    PushOutcome {
        reference,
        accepted: false,
        error: Some(error),
        ..Default::default()
    }
}

#[cfg(test)]
mod tests {
    use io_jmap::rfc8620::{send::JmapSendError, session_get::JmapSessionGetError};

    use super::coroutine_error;

    #[test]
    fn a_status_the_failure_carries_itself_still_crosses() {
        // The regression this exists for: JmapSessionGetError formats
        // the status into its message and sources nothing, so a walk of
        // the source chain finds no status and Java sees a bare error.
        // A JMAP session get is the first call of every JMAP round, so
        // a 401 that reports no status is one no token refresh can be
        // triggered by, and the account simply stops syncing.
        let session = coroutine_error(&JmapSessionGetError::HttpStatus(401));
        assert_eq!(session.status, Some(401));
        assert!(session.message.contains("401"));

        // The ordinary path, where the status is on the send error.
        assert_eq!(
            coroutine_error(&JmapSendError::HttpStatus(412)).status,
            Some(412)
        );

        // A failure that was never an HTTP round carries no status, so
        // nothing downstream mistakes it for one.
        let parsed = coroutine_error(&JmapSessionGetError::NoPrimaryMailAccount);
        assert_eq!(parsed.status, None);
    }
}
