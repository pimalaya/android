//! # FFI
//!
//! The JNI surface: every export the Java `:client` module calls, grouped
//! by domain in submodules, and the marshaling helpers they share.
//!
//! An export captures the [`EnvUnowned`](jni::EnvUnowned), upgrades it to
//! an [`Env`] in [`with_env`](jni::EnvUnowned::with_env), which also stops
//! unwinding at the JNI boundary, and resolves the outcome with
//! [`ErrorReply`]. Every reply is JSON, failures `{"error", "status"?}`.

mod account;
mod calendar;
mod contact;
mod host;
mod mail;
mod offline;

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
use serde::de::DeserializeOwned;
use serde_json::{from_str, to_string};
use url::Url;

use crate::{contact::dedup::DuplicateCard, error::BridgeError};

/// The longest panic message an error reply carries: a formatted panic
/// can quote the data it failed on, which has no business in a reply or
/// a log beyond a hint.
const PANIC_HINT: usize = 160;

/// The error policy of every export.
///
/// A JNI failure or a panic becomes `{"error": "Internal error: .."}`, so
/// Java reports a failure rather than dereferencing a null string. A call
/// answering no object logs and answers its default.
pub struct ErrorReply;

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
pub fn read_string(env: &Env, value: &JString) -> String {
    value.try_to_string(env).unwrap_or_default()
}

/// Reads and parses a store verb's JSON facts; the error side is the
/// ready-to-return error reply.
pub fn parse_facts<T: DeserializeOwned>(env: &Env, facts: &JString) -> Result<T, String> {
    let raw = read_string(env, facts);
    from_str(&raw).map_err(|err| error_json(format!("Invalid store facts: {err}")))
}

/// Parses a URL, the error naming the raw input.
pub fn parse_url(raw: &str) -> Result<Url, String> {
    Url::parse(raw).map_err(|err| format!("Invalid URL `{raw}`: {err}"))
}

/// Parses a JSON string array of book ids (empty or null means none).
pub fn parse_books(raw: &str) -> Result<Vec<String>, String> {
    if raw.is_empty() {
        return Ok(Vec::new());
    }
    from_str(raw).map_err(|err| format!("Invalid book id list `{raw}`: {err}"))
}

/// Parses a JSON string array of vCards.
pub fn parse_strings(raw: &str) -> Result<Vec<String>, String> {
    from_str(raw).map_err(|err| format!("Invalid vCard list: {err}"))
}

/// Parses a JSON array of `{"ref", "vcard"}` pairs.
pub fn parse_ref_cards(raw: &str) -> Result<Vec<DuplicateCard>, String> {
    from_str(raw).map_err(|err| format!("Invalid card list: {err}"))
}

/// Serializes a failure as the error reply.
///
/// `{"error": message}`, plus `status` when the failure was an HTTP round;
/// a plain string converts to a status-less error.
pub fn error_json(error: impl Into<BridgeError>) -> String {
    to_string(&error.into())
        .unwrap_or_else(|_| r#"{"error": "Unserializable bridge failure"}"#.to_string())
}

#[cfg(test)]
pub mod tests {
    use serde::Serialize;
    use serde_json::{to_string, to_value};

    use crate::ffi::*;

    /// Asserts the value serializes its keys in alphabetical order.
    ///
    /// The order a `json!` reply had: serde_json is built without
    /// `preserve_order`.
    pub fn assert_key_order(value: &impl Serialize) {
        let typed = to_string(value).unwrap();
        let sorted = to_string(&to_value(value).unwrap()).unwrap();
        assert_eq!(typed, sorted);
    }

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
