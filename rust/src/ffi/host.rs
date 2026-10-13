//! # Host
//!
//! The JNI [`Host`]: every upcall the bridge makes to the app, its Java
//! `Transport` for socket I/O and its `OfflineDriver` for the store.

use jni::{
    Env, JValue, JValueOwned,
    errors::Error,
    jni_sig, jni_str,
    objects::{JByteArray, JObject, JString},
    signature::MethodSignature,
    strings::JNIStr,
};

use crate::{client::Host, error::BridgeError};

/// The app as one native call reaches it.
///
/// A mutable `Env`, the Java transport it upcalls for socket I/O and the
/// store's driver.
pub struct JniHost<'a, 'local> {
    env: &'a mut Env<'local>,
    /// The Java `Transport`; [`None`] for the offline engine.
    transport: Option<&'a JObject<'local>>,
    /// The store's `OfflineDriver`, serving the offline engine's yields or
    /// a mail step asking what it holds; [`None`] outside both.
    driver: Option<&'a JObject<'local>>,
    /// What a failed serve upcall is told as: the store's answer to a
    /// mail step, or the offline engine's yield.
    serving: &'static str,
}

impl<'a, 'local> JniHost<'a, 'local> {
    /// The host of a call talking to a server, with the store's driver
    /// when a mail step asks what it holds.
    pub fn new(
        env: &'a mut Env<'local>,
        transport: &'a JObject<'local>,
        driver: Option<&'a JObject<'local>>,
    ) -> Self {
        Self {
            env,
            transport: Some(transport),
            driver,
            serving: "store held",
        }
    }

    /// The host of the offline engine, serving its yields through the
    /// store's driver alone.
    pub fn offline(env: &'a mut Env<'local>, driver: &'a JObject<'local>) -> Self {
        Self {
            env,
            transport: None,
            driver: Some(driver),
            serving: "offline driver serve",
        }
    }

    /// The Java transport, failing for a host without one.
    fn transport(&self) -> Result<&'a JObject<'local>, BridgeError> {
        self.transport
            .ok_or_else(|| "No transport to reach the server through".into())
    }

    /// One bookkeeping upcall taking the URL alone, none of whose
    /// failures is the caller's: logged, its exception cleared.
    fn upcall<'sig, 'args, N, S>(
        &mut self,
        url: &str,
        name: N,
        sig: S,
    ) -> Option<JValueOwned<'local>>
    where
        N: AsRef<JNIStr>,
        S: AsRef<MethodSignature<'sig, 'args>>,
    {
        let transport = match self.transport() {
            Ok(transport) => transport,
            Err(err) => {
                log::warn!("{}", err.message);
                return None;
            }
        };
        let called = self.env.new_string(url).and_then(|url| {
            self.env
                .call_method(transport, name, sig, &[JValue::Object(&url)])
        });
        match called {
            Ok(value) => Some(value),
            Err(err) => {
                let failed = clear_and_fail(self.env, "transport upcall", err);
                log::warn!("{failed}");
                None
            }
        }
    }
}

impl Host for JniHost<'_, '_> {
    fn read(&mut self, url: &str) -> Result<Vec<u8>, BridgeError> {
        let transport = self.transport()?;
        // NOTE: an empty slice signals EOF to the coroutine.
        let url = self.env.new_string(url).map_err(|err| err.to_string())?;
        let value = self
            .env
            .call_method(
                transport,
                jni_str!("read"),
                jni_sig!("(Ljava/lang/String;)[B"),
                &[JValue::Object(&url)],
            )
            .map_err(|err| clear_and_fail(self.env, "transport read", err))?;
        let object = value.l().map_err(|err| err.to_string())?;
        let array = unsafe { JByteArray::from_raw(self.env, object.into_raw()) };

        self.env
            .convert_byte_array(&array)
            .map_err(|err| err.to_string().into())
    }

    fn write(&mut self, url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
        let transport = self.transport()?;
        let url = self.env.new_string(url).map_err(|err| err.to_string())?;
        let array = self
            .env
            .byte_array_from_slice(bytes)
            .map_err(|err| err.to_string())?;

        self.env
            .call_method(
                transport,
                jni_str!("write"),
                jni_sig!("(Ljava/lang/String;[B)V"),
                &[JValue::Object(&url), JValue::Object(&array)],
            )
            .map_err(|err| clear_and_fail(self.env, "transport write", err))?;

        Ok(())
    }

    fn begin(&mut self, url: &str) -> bool {
        self.upcall(url, jni_str!("begin"), jni_sig!("(Ljava/lang/String;)Z"))
            .and_then(|value| value.z().ok())
            .unwrap_or(false)
    }

    fn settle(&mut self, url: &str) {
        self.upcall(url, jni_str!("settle"), jni_sig!("(Ljava/lang/String;)V"));
    }

    fn reset(&mut self, url: &str) {
        self.upcall(url, jni_str!("reset"), jni_sig!("(Ljava/lang/String;)V"));
    }

    fn starttls(&mut self, url: &str) -> Result<(), BridgeError> {
        let transport = self.transport()?;
        let jurl = self.env.new_string(url).map_err(|err| err.to_string())?;

        self.env
            .call_method(
                transport,
                jni_str!("starttls"),
                jni_sig!("(Ljava/lang/String;)V"),
                &[JValue::Object(&jurl)],
            )
            .map_err(|err| clear_and_fail(self.env, "transport starttls", err))?;

        Ok(())
    }

    fn serve(&mut self, request: &str) -> Result<String, BridgeError> {
        let driver = self.driver.ok_or("No store to ask what it holds")?;
        let request = self
            .env
            .new_string(request)
            .map_err(|err| err.to_string())?;
        let value = self
            .env
            .call_method(
                driver,
                jni_str!("serve"),
                jni_sig!("(Ljava/lang/String;)Ljava/lang/String;"),
                &[JValue::Object(&request)],
            )
            .map_err(|err| clear_and_fail(self.env, self.serving, err))?;
        let object = value.l().map_err(|err| err.to_string())?;
        let reply = unsafe { JString::from_raw(self.env, object.into_raw()) };

        reply
            .try_to_string(self.env)
            .map_err(|err| err.to_string().into())
    }
}

/// Clears any pending Java exception, surfacing its class and message.
///
/// A bare JNI error only says "Java exception was thrown". The op is
/// capitalized so the user-facing message reads as a sentence.
fn clear_and_fail(env: &mut Env, op: &str, err: Error) -> String {
    let mut chars = op.chars();
    let op = match chars.next() {
        Some(first) => first.to_uppercase().collect::<String>() + chars.as_str(),
        None => String::new(),
    };

    match env.exception_catch() {
        Err(Error::CaughtJavaException { name, msg, .. }) => {
            format!("{op} failed: {name}: {msg}")
        }
        _ => format!("{op} failed: {err}"),
    }
}
