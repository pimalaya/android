//! The bridge's `log` records, to logcat under the app's tag.
//!
//! Without a logger every `log` call in this crate and in the libraries
//! under it is dropped, a throttled request and its wait included. The
//! logger is installed once, when the JVM loads the library
//! ([`JNI_OnLoad`]): debug and above for this crate, info and above for
//! the libraries, whose debug records narrate every chunk a stream
//! reads. On Android it writes through liblog's `__android_log_write`;
//! anywhere else (the unit tests' host JVM) to standard error.

use std::ffi::c_void;

use jni::sys::{JNI_VERSION_1_6, JavaVM, jint};
use log::{Level, LevelFilter, Log, Metadata, Record};

/// The tag the app's own Java logs use, so one filter shows both.
const TAG: &str = "pimalaya";

/// The target prefix of this crate's own records.
const CRATE: &str = env!("CARGO_CRATE_NAME");

static LOGGER: Logcat = Logcat;

/// Called by the JVM when it loads the library: installs the logger, a
/// second load keeping the first.
#[unsafe(no_mangle)]
pub extern "system" fn JNI_OnLoad(_vm: *mut JavaVM, _reserved: *mut c_void) -> jint {
    if log::set_logger(&LOGGER).is_ok() {
        log::set_max_level(LevelFilter::Debug);
    }
    JNI_VERSION_1_6
}

/// Writes every record to logcat, or to standard error off Android.
struct Logcat;

impl Log for Logcat {
    fn enabled(&self, metadata: &Metadata) -> bool {
        let own = metadata.target().split("::").next() == Some(CRATE);
        metadata.level() <= if own { Level::Debug } else { Level::Info }
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
