//! # Logging
//!
//! The bridge's `log` records, to logcat under the app's tag (liblog's
//! `__android_log_write`), or to standard error off Android.
//!
//! Installed when the JVM loads the library ([`JNI_OnLoad`]) at warnings:
//! the library is always built in release, so the build cannot tell, and
//! a release app's logcat is what a bug report carries.
//!
//! A debug app turns on debug for this crate and info for the libraries
//! ([`Java_org_pimalaya_client_Native_debugLogs`]), whose debug records
//! narrate every chunk a stream reads.

use std::{
    ffi::c_void,
    panic::catch_unwind,
    sync::atomic::{AtomicBool, Ordering},
};

use jni::{
    EnvUnowned,
    errors::Error,
    objects::JClass,
    sys::{JNI_VERSION_1_6, JavaVM, jboolean, jint},
};
use log::{Level, LevelFilter, Log, Metadata, Record};

use crate::ffi::ErrorReply;

/// The tag the app's own Java logs use, so one filter shows both.
const TAG: &str = "pimalaya";

/// The target prefix of this crate's own records.
const CRATE: &str = env!("CARGO_CRATE_NAME");

static LOGGER: Logcat = Logcat;

/// Whether debug logs are on ([`Java_org_pimalaya_client_Native_debugLogs`]).
static VERBOSE: AtomicBool = AtomicBool::new(false);

/// Installs the logger when the JVM loads the library, once.
///
/// A panic must not unwind into the JVM, so one leaves no logger.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    let _ = catch_unwind(|| {
        if log::set_logger(&LOGGER).is_ok() {
            log::set_max_level(LevelFilter::Warn);
        }
    });
    JNI_VERSION_1_6
}

/// `Native.debugLogs`: turns the debug logs on or off, for a debug app.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_debugLogs<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    on: jboolean,
) {
    env.with_env(|_| -> Result<(), Error> {
        VERBOSE.store(on, Ordering::Relaxed);
        log::set_max_level(match on {
            true => LevelFilter::Debug,
            false => LevelFilter::Warn,
        });
        Ok(())
    })
    .resolve::<ErrorReply>()
}

/// Writes every record to logcat, or to standard error off Android.
struct Logcat;

impl Log for Logcat {
    fn enabled(&self, metadata: &Metadata) -> bool {
        let own = metadata.target().split("::").next() == Some(CRATE);
        let floor = match (VERBOSE.load(Ordering::Relaxed), own) {
            (false, _) => Level::Warn,
            (true, true) => Level::Debug,
            (true, false) => Level::Info,
        };
        metadata.level() <= floor
    }

    fn log(&self, record: &Record) {
        if self.enabled(record.metadata()) {
            write(record.level(), &record.args().to_string());
        }
    }

    fn flush(&self) {}
}

#[cfg(target_os = "android")]
fn write(level: Level, text: &str) {
    use std::ffi::{CString, c_char, c_int};

    #[link(name = "log")]
    unsafe extern "C" {
        fn __android_log_write(priority: c_int, tag: *const c_char, text: *const c_char) -> c_int;
    }

    // NOTE: android/log.h's priorities.
    let priority = match level {
        Level::Error => 6,
        Level::Warn => 5,
        Level::Info => 4,
        Level::Debug => 3,
        Level::Trace => 2,
    };
    let (Ok(tag), Ok(text)) = (CString::new(TAG), CString::new(text.replace('\0', " "))) else {
        return;
    };
    unsafe {
        __android_log_write(priority, tag.as_ptr(), text.as_ptr());
    }
}

#[cfg(not(target_os = "android"))]
fn write(level: Level, text: &str) {
    eprintln!("{level} {TAG}: {text}");
}
