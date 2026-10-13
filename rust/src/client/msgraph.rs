//! # Microsoft Graph
//!
//! The [`Msgraph`] backend: the runners and the JSON batching that the
//! contact, calendar and mail Graph verbs share.

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::{
    coroutine::*,
    v1::{
        rest::batch::{
            MSGRAPH_BATCH_MAX_REQUESTS, MsgraphBatch, MsgraphBatchRequest, MsgraphBatchResponse,
        },
        send::{MSGRAPH_API_BASE, MsgraphSendError, MsgraphSendOutput},
    },
};
use url::Url;

use crate::{
    client::{Client, Host},
    error::{BridgeError, coroutine_error},
};

/// A Microsoft account's Graph API, signed in to with an OAuth 2.0 token.
pub struct Msgraph<'c, H> {
    pub client: &'c mut Client<H>,
    pub token: &'c str,
}

/// Graph coroutine runners, routing every yield to the Graph origin.
impl<H: Host> Msgraph<'_, H> {
    /// Runs a Microsoft Graph coroutine to completion.
    pub fn run<C, T>(&mut self, coroutine: C) -> Result<T, BridgeError>
    where
        C: MsgraphCoroutine<
                Yield = MsgraphYield,
                Return = Result<MsgraphSendOutput<T>, MsgraphSendError>,
            >,
    {
        self.try_run(coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// [`Self::run`] keeping Graph's own error inside the transport's.
    ///
    /// A caller can then tell its status apart (an expired delta link).
    pub fn try_run<C, T>(
        &mut self,
        mut coroutine: C,
    ) -> Result<Result<T, MsgraphSendError>, BridgeError>
    where
        C: MsgraphCoroutine<
                Yield = MsgraphYield,
                Return = Result<MsgraphSendOutput<T>, MsgraphSendError>,
            >,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                MsgraphCoroutineState::Complete(Ok(output)) => return Ok(Ok(output.response)),
                MsgraphCoroutineState::Complete(Err(err)) => return Ok(Err(err)),
                MsgraphCoroutineState::Yielded(MsgraphYield::WantsRead) => {
                    arg = Some(self.client.http_read(MSGRAPH_API_BASE)?);
                }
                MsgraphCoroutineState::Yielded(MsgraphYield::WantsWrite(bytes)) => {
                    self.client.http_write(MSGRAPH_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// Graph JSON batching, shared by the batched contact and event reads.
impl<H: Host> Msgraph<'_, H> {
    /// Sends one `$batch` of at most [`MSGRAPH_BATCH_MAX_REQUESTS`].
    pub fn batch(
        &mut self,
        requests: &[MsgraphBatchRequest],
    ) -> Result<Vec<MsgraphBatchResponse>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = MsgraphBatch::new(&auth, requests).map_err(|err| err.to_string())?;
        Ok(self.run(coroutine)?.responses)
    }
}

pub fn parse_graph_url(raw: &str) -> Result<Url, BridgeError> {
    Url::parse(raw).map_err(|err| format!("Invalid Graph page URL `{raw}`: {err}").into())
}

/// An address under the Graph API version.
pub fn graph_url(path: &str) -> Result<Url, BridgeError> {
    Url::parse(MSGRAPH_API_BASE)
        .and_then(|base| base.join(path))
        .map_err(|err| err.to_string().into())
}

/// An address as a batch names it, relative to the API version.
pub fn relative(url: &Url) -> String {
    let base = MSGRAPH_API_BASE.trim_end_matches('/');
    url.as_str()
        .strip_prefix(base)
        .unwrap_or(url.as_str())
        .to_owned()
}

/// Sends `urls` as GETs, 20 to a `$batch`, answering each reply in order.
///
/// A reply is its body, or [`None`] when the batch could not serve it, for
/// the caller to send again on its own.
pub fn batched<F>(
    mut send: F,
    urls: &[Url],
) -> Result<Vec<Option<MsgraphBatchResponse>>, BridgeError>
where
    F: FnMut(&[MsgraphBatchRequest]) -> Result<Vec<MsgraphBatchResponse>, BridgeError>,
{
    let mut replies: Vec<Option<MsgraphBatchResponse>> = Vec::with_capacity(urls.len());
    replies.resize_with(urls.len(), || None);

    for (chunk, urls) in urls.chunks(MSGRAPH_BATCH_MAX_REQUESTS).enumerate() {
        let requests: Vec<MsgraphBatchRequest> = urls
            .iter()
            .enumerate()
            .map(|(index, url)| MsgraphBatchRequest {
                id: index.to_string(),
                method: String::from("GET"),
                url: relative(url),
                ..Default::default()
            })
            .collect();
        for reply in send(&requests)? {
            let Some(index) = reply.id.parse::<usize>().ok().filter(|i| *i < urls.len()) else {
                continue;
            };
            replies[chunk * MSGRAPH_BATCH_MAX_REQUESTS + index] = Some(reply);
        }
    }

    Ok(replies)
}

/// A read sent on its own, a 404 (what Graph no longer holds) [`None`].
pub fn alone<T>(read: Result<T, BridgeError>) -> Result<Option<T>, BridgeError> {
    match read {
        Ok(value) => Ok(Some(value)),
        Err(err) if err.status == Some(404) => Ok(None),
        Err(err) => Err(err),
    }
}
