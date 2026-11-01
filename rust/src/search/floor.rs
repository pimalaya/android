//! # Floor
//!
//! A test's index.db on the SQLite of Android 8.0, the oldest the app runs
//! on: flake.nix builds 3.18.2 with the platform's flags
//! (`PIMALAYA_SQLITE_FLOOR`), loaded here with `dlopen` beside the bundled
//! SQLite rusqlite links into the test binary, its own symbols bound first,
//! so the steps the bridge sends run as the platform runs them.

use std::{
    env,
    ffi::{CStr, CString, c_char, c_int, c_void},
    path::Path,
    ptr,
    sync::OnceLock,
};

use crate::{
    error::BridgeError,
    search::sql::{IndexSql, Row, Step, Value},
};

/// The SQLite of Android 8.0 (flake.nix).
pub const FLOOR: &str = "3.18.2";

type Db = *mut c_void;
type Stmt = *mut c_void;

const SQLITE_OK: c_int = 0;
const SQLITE_ROW: c_int = 100;
const SQLITE_DONE: c_int = 101;
const SQLITE_INTEGER: c_int = 1;
const SQLITE_FLOAT: c_int = 2;
const SQLITE_TEXT: c_int = 3;
const SQLITE_NULL: c_int = 5;
const OPEN_READWRITE: c_int = 0x2;
const OPEN_CREATE: c_int = 0x4;

/// The C API of the floor's libsqlite3.so, the functions a step needs.
struct Api {
    version: unsafe extern "C" fn() -> *const c_char,
    open: unsafe extern "C" fn(*const c_char, *mut Db, c_int, *const c_char) -> c_int,
    close: unsafe extern "C" fn(Db) -> c_int,
    prepare: unsafe extern "C" fn(Db, *const c_char, c_int, *mut Stmt, *mut *const c_char) -> c_int,
    bind_int64: unsafe extern "C" fn(Stmt, c_int, i64) -> c_int,
    bind_double: unsafe extern "C" fn(Stmt, c_int, f64) -> c_int,
    bind_text: unsafe extern "C" fn(Stmt, c_int, *const c_char, c_int, isize) -> c_int,
    bind_null: unsafe extern "C" fn(Stmt, c_int) -> c_int,
    step: unsafe extern "C" fn(Stmt) -> c_int,
    column_count: unsafe extern "C" fn(Stmt) -> c_int,
    column_type: unsafe extern "C" fn(Stmt, c_int) -> c_int,
    column_int64: unsafe extern "C" fn(Stmt, c_int) -> i64,
    column_double: unsafe extern "C" fn(Stmt, c_int) -> f64,
    column_text: unsafe extern "C" fn(Stmt, c_int) -> *const u8,
    column_bytes: unsafe extern "C" fn(Stmt, c_int) -> c_int,
    finalize: unsafe extern "C" fn(Stmt) -> c_int,
    errmsg: unsafe extern "C" fn(Db) -> *const c_char,
}

// SAFETY: function pointers into a library loaded once and never unloaded.
unsafe impl Send for Api {}
unsafe impl Sync for Api {}

/// The floor's API, loaded once.
fn api() -> &'static Api {
    static API: OnceLock<Api> = OnceLock::new();
    API.get_or_init(|| {
        let floor = env::var("PIMALAYA_SQLITE_FLOOR")
            .expect("PIMALAYA_SQLITE_FLOOR is unset: run the tests through `nix develop`");
        let path = CString::new(format!("{floor}/lib/libsqlite3.so")).unwrap();
        // SAFETY: a library path; RTLD_DEEPBIND binds the library's own
        // symbols first, never the bundled SQLite's of the test binary.
        let lib = unsafe {
            libc::dlopen(
                path.as_ptr(),
                libc::RTLD_NOW | libc::RTLD_LOCAL | libc::RTLD_DEEPBIND,
            )
        };
        assert!(!lib.is_null(), "the floor's libsqlite3.so should load");
        let symbol = |name: &str| {
            let name = CString::new(name).unwrap();
            // SAFETY: a symbol of the library just loaded.
            let found = unsafe { libc::dlsym(lib, name.as_ptr()) };
            assert!(!found.is_null(), "{name:?} is in the floor's library");
            found
        };
        // SAFETY: each symbol is the C function of that signature.
        unsafe {
            Api {
                version: cast(symbol("sqlite3_libversion")),
                open: cast(symbol("sqlite3_open_v2")),
                close: cast(symbol("sqlite3_close_v2")),
                prepare: cast(symbol("sqlite3_prepare_v2")),
                bind_int64: cast(symbol("sqlite3_bind_int64")),
                bind_double: cast(symbol("sqlite3_bind_double")),
                bind_text: cast(symbol("sqlite3_bind_text")),
                bind_null: cast(symbol("sqlite3_bind_null")),
                step: cast(symbol("sqlite3_step")),
                column_count: cast(symbol("sqlite3_column_count")),
                column_type: cast(symbol("sqlite3_column_type")),
                column_int64: cast(symbol("sqlite3_column_int64")),
                column_double: cast(symbol("sqlite3_column_double")),
                column_text: cast(symbol("sqlite3_column_text")),
                column_bytes: cast(symbol("sqlite3_column_bytes")),
                finalize: cast(symbol("sqlite3_finalize")),
                errmsg: cast(symbol("sqlite3_errmsg")),
            }
        }
    })
}

/// A symbol as the function pointer `T` it is.
///
/// # Safety
///
/// `symbol` is a function of `T`'s signature.
unsafe fn cast<T: Copy>(symbol: *mut c_void) -> T {
    // SAFETY: the caller's symbol is such a function, a pointer the size
    // of `T`.
    unsafe { std::mem::transmute_copy(&symbol) }
}

/// SQLITE_TRANSIENT: SQLite copies the bound text.
const TRANSIENT: isize = -1;

/// One connection of the floor's SQLite.
pub struct Floor {
    db: Db,
}

impl Floor {
    /// Opens (creating) the database at `path`, foreign keys on.
    pub fn open(path: &Path) -> Self {
        let api = api();
        let path = CString::new(path.to_str().unwrap()).unwrap();
        let mut db = ptr::null_mut();
        // SAFETY: a valid path and an out pointer.
        let rc = unsafe {
            (api.open)(
                path.as_ptr(),
                &mut db,
                OPEN_READWRITE | OPEN_CREATE,
                ptr::null(),
            )
        };
        assert_eq!(rc, SQLITE_OK, "the floor opens the database");
        let mut floor = Self { db };
        floor.exec("PRAGMA foreign_keys = ON");
        floor
    }

    /// The SQLite version the library reports.
    pub fn version() -> String {
        // SAFETY: a static string.
        unsafe { CStr::from_ptr((api().version)()) }
            .to_string_lossy()
            .into_owned()
    }

    /// Runs one statement holding no parameter, failing loudly.
    pub fn exec(&mut self, sql: &str) {
        self.step(sql, &[], false)
            .unwrap_or_else(|err| panic!("{sql}: {err}"));
    }

    /// Runs one statement, answering its rows when `read`.
    fn step(&mut self, sql: &str, args: &[Value], read: bool) -> Result<Vec<Row>, String> {
        let api = api();
        let text = CString::new(sql).map_err(|err| err.to_string())?;
        let mut stmt = ptr::null_mut();
        // SAFETY: an open connection and a NUL-terminated statement.
        let rc = unsafe { (api.prepare)(self.db, text.as_ptr(), -1, &mut stmt, ptr::null_mut()) };
        if rc != SQLITE_OK {
            return Err(self.error());
        }
        let mut rows = Vec::new();
        // SAFETY: a prepared statement, finalized below on every path.
        let outcome = unsafe {
            for (index, value) in args.iter().enumerate() {
                let at = index as c_int + 1;
                match value {
                    Value::Null => (api.bind_null)(stmt, at),
                    Value::Integer(value) => (api.bind_int64)(stmt, at, *value),
                    Value::Real(value) => (api.bind_double)(stmt, at, *value),
                    Value::Text(value) => (api.bind_text)(
                        stmt,
                        at,
                        value.as_ptr().cast(),
                        value.len() as c_int,
                        TRANSIENT,
                    ),
                };
            }
            loop {
                match (api.step)(stmt) {
                    SQLITE_ROW => {
                        if read {
                            rows.push(self.row(stmt));
                        }
                    }
                    SQLITE_DONE => break Ok(()),
                    _ => break Err(self.error()),
                }
            }
        };
        // SAFETY: the statement prepared above.
        unsafe { (api.finalize)(stmt) };
        outcome.map(|()| rows)
    }

    /// The current row of a stepped statement.
    ///
    /// # Safety
    ///
    /// `stmt` stands on a row.
    unsafe fn row(&self, stmt: Stmt) -> Row {
        let api = api();
        // SAFETY: the caller's statement stands on a row.
        unsafe {
            (0..(api.column_count)(stmt))
                .map(|column| match (api.column_type)(stmt, column) {
                    SQLITE_INTEGER => Value::Integer((api.column_int64)(stmt, column)),
                    SQLITE_FLOAT => Value::Real((api.column_double)(stmt, column)),
                    SQLITE_NULL => Value::Null,
                    kind => {
                        let text = (api.column_text)(stmt, column);
                        let len = (api.column_bytes)(stmt, column) as usize;
                        let bytes = std::slice::from_raw_parts(text, len);
                        let text = String::from_utf8_lossy(bytes).into_owned();
                        debug_assert!(kind == SQLITE_TEXT, "blob read as text");
                        Value::Text(text)
                    }
                })
                .collect()
        }
    }

    /// The connection's last error.
    fn error(&self) -> String {
        // SAFETY: an open connection.
        unsafe { CStr::from_ptr((api().errmsg)(self.db)) }
            .to_string_lossy()
            .into_owned()
    }
}

impl IndexSql for Floor {
    fn run(&mut self, steps: Vec<Step>) -> Result<Vec<Vec<Row>>, BridgeError> {
        let mut answered = Vec::with_capacity(steps.len());
        for step in steps {
            let rows = self
                .step(&step.sql, &step.args, step.read)
                .map_err(|err| format!("{err} [{}]", step.sql))?;
            answered.push(rows);
        }
        Ok(answered)
    }
}

impl Drop for Floor {
    fn drop(&mut self) {
        // SAFETY: the connection opened in `open`, every statement
        // finalized.
        unsafe { (api().close)(self.db) };
    }
}
