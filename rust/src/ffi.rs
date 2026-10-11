//! JNI entry points called from the Pimalaya `:client` module.
//!
//! Every method captures the FFI [`EnvUnowned`], upgrades it to a usable
//! [`Env`] inside [`EnvUnowned::with_env`] (which also guards against
//! unwinding across the JNI boundary), and resolves the outcome with
//! [`ErrorReply`], so an unexpected JNI failure or a panic answers the
//! error reply rather than a null string. Every reply is JSON; failures
//! are `{"error": ".."}`.
//!
//! The exported symbols are grouped by domain into per-domain
//! submodules (discovery, oauth, card, offline, project); this file
//! keeps only the marshaling helpers shared across more than one of
//! them.

mod calendar;
mod card;
mod discovery;
mod mail;
mod oauth;
mod offline;
mod pimdir;
mod project;
pub(crate) mod session;

use std::{
    any::Any,
    error::Error as StdError,
    mem,
    panic::{AssertUnwindSafe, catch_unwind},
};

use jni::{
    Env,
    errors::{ErrorPolicy, Result as JniResult},
    objects::{JObject, JString},
    refs::Reference,
};
use serde_json::{Value, from_str, to_string};
use url::Url;

use crate::types::BridgeError;

/// The longest panic message an error reply carries: a formatted panic
/// can quote the data it failed on, which has no business in a reply or
/// a log beyond a hint.
const PANIC_HINT: usize = 160;

/// Resolves what a native call could not answer: a JNI failure or a
/// panic becomes the error reply `{"error": "Internal error: .."}`, so
/// the Java side reads a failure it reports rather than a null string
/// it dereferences. A call answering no object logs and answers its
/// default.
pub(crate) struct ErrorReply;

impl<'any, E: StdError> ErrorPolicy<JObject<'any>, E> for ErrorReply {
    type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

    fn on_error<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut (),
        err: E,
    ) -> JniResult<JObject<'any>> {
        log::error!("native call failed: {err}");
        internal(env, err.to_string())
    }

    fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
        env: &mut Env<'unowned_env_local>,
        _captures: &mut (),
        payload: Box<dyn Any + Send + 'static>,
    ) -> JniResult<JObject<'any>> {
        log::error!("native call panicked");
        internal(env, panic_hint(payload))
    }
}

/// The policy of a call answering a primitive or nothing, which has no
/// reply to carry a failure: logged, the default answered.
macro_rules! log_and_default {
    ($($answer:ty),*) => {$(
        impl<E: StdError> ErrorPolicy<$answer, E> for ErrorReply {
            type Captures<'unowned_env_local: 'native_method, 'native_method> = ();

            fn on_error<'unowned_env_local: 'native_method, 'native_method>(
                _env: &mut Env<'unowned_env_local>,
                _captures: &mut (),
                err: E,
            ) -> JniResult<$answer> {
                log::error!("native call failed: {err}");
                Ok(<$answer>::default())
            }

            fn on_panic<'unowned_env_local: 'native_method, 'native_method>(
                _env: &mut Env<'unowned_env_local>,
                _captures: &mut (),
                payload: Box<dyn Any + Send + 'static>,
            ) -> JniResult<$answer> {
                log::error!("native call panicked: {}", panic_hint(payload));
                Ok(<$answer>::default())
            }
        }
    )*};
}

log_and_default!(i32, ());

/// The error reply of an internal failure, as a string of the native
/// call's own frame.
fn internal<'any>(env: &mut Env<'_>, message: String) -> JniResult<JObject<'any>> {
    // NOTE: a JNI call with an exception pending is undefined; the
    // failure is answered in the reply instead.
    if env.exception_check() {
        env.exception_clear();
    }
    let reply = env.new_string(error_json(format!("Internal error: {message}")))?;
    // SAFETY: a local reference created in this native call's frame,
    // owned by nothing else, which the call hands back as its answer: the
    // frame `'any` names in every export this policy resolves.
    Ok(unsafe { <JObject as Reference>::kind_from_raw(reply.into_raw()) })
}

/// What a panic said, cut to [`PANIC_HINT`] characters.
fn panic_hint(payload: Box<dyn Any + Send + 'static>) -> String {
    let message = match payload.downcast::<&'static str>() {
        Ok(message) => (*message).to_string(),
        Err(payload) => match payload.downcast::<String>() {
            Ok(message) => *message,
            Err(payload) => {
                // NOTE: dropping a payload may panic itself; leaked then.
                if let Err(again) = catch_unwind(AssertUnwindSafe(|| drop(payload))) {
                    mem::forget(again);
                }
                "a panic".to_string()
            }
        },
    };
    message.chars().take(PANIC_HINT).collect()
}

/// Reads a Java string, defaulting to empty on any conversion error.
pub(crate) fn read_string(env: &Env, value: &JString) -> String {
    value.try_to_string(env).unwrap_or_default()
}

/// Reads and parses a store verb's JSON facts; the error side is the
/// ready-to-return error reply.
pub(crate) fn parse_facts(env: &Env, facts: &JString) -> Result<Value, String> {
    let raw = read_string(env, facts);
    from_str(&raw).map_err(|err| error_json(format!("Invalid store facts: {err}")))
}

pub(crate) fn parse_url(raw: &str) -> Result<Url, String> {
    Url::parse(raw).map_err(|err| format!("Invalid URL `{raw}`: {err}"))
}

/// Parses a JSON string array of book ids (empty or null means none).
pub(crate) fn parse_books(raw: &str) -> Result<Vec<String>, String> {
    if raw.is_empty() {
        return Ok(Vec::new());
    }
    from_str(raw).map_err(|err| format!("Invalid book id list `{raw}`: {err}"))
}

/// Parses a JSON string array of vCards.
pub(crate) fn parse_strings(raw: &str) -> Result<Vec<String>, String> {
    from_str(raw).map_err(|err| format!("Invalid vCard list: {err}"))
}

/// Parses a JSON array of `{"ref", "vcard"}` pairs.
pub(crate) fn parse_ref_cards(raw: &str) -> Result<Vec<(String, String)>, String> {
    let value: Value = from_str(raw).map_err(|err| format!("Invalid card list: {err}"))?;
    let Some(entries) = value.as_array() else {
        return Err("Invalid card list: not an array".into());
    };

    let mut cards = Vec::with_capacity(entries.len());
    for entry in entries {
        let reference = entry
            .get("ref")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        let vcard = entry
            .get("vcard")
            .and_then(Value::as_str)
            .unwrap_or("")
            .to_string();
        cards.push((reference, vcard));
    }
    Ok(cards)
}

/// Serializes a failure as the error reply, `{"error": message}` plus
/// a `status` field when the failure was an HTTP round (the type is
/// the wire shape; plain strings convert to status-less errors).
pub(crate) fn error_json(error: impl Into<BridgeError>) -> String {
    to_string(&error.into())
        .unwrap_or_else(|_| r#"{"error": "Unserializable bridge failure"}"#.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn a_panic_is_told_by_its_message_cut_short() {
        assert_eq!(panic_hint(Box::new("boom")), "boom");
        assert_eq!(
            panic_hint(Box::new("é".repeat(500))).chars().count(),
            PANIC_HINT
        );
        assert_eq!(panic_hint(Box::new(42)), "a panic");
    }
}
