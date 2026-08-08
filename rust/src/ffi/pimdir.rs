//! The canonical pimdir SQL, handed to the Java side.
//!
//! Android ships SQLite and the app's storage seam already drives
//! `android.database.sqlite`, so this app takes io-pimdir **without** its
//! `client` feature: no rusqlite, no second SQLite engine compiled into every
//! ABI. What the crate contributes is the schema and the statements, which
//! cross the boundary here rather than being transcribed into Java where they
//! would drift from the spec in silence.

use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject},
};
use serde_json::{Map, Value, to_string};

use crate::ffi::error_json;

/// `Native.pimdirSql`: every canonical statement, keyed by its constant name,
/// as a JSON object. `MIGRATION_0001` is one of the entries, since creating the
/// database is as much the caller's job as querying it.
///
/// Pure computation over compiled-in constants: no transport, no store, no
/// failure mode beyond serialization.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirSql<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mut statements = Map::with_capacity(io_pimdir::sql::ALL.len());
        for (name, sql) in io_pimdir::sql::ALL {
            statements.insert((*name).to_string(), Value::String((*sql).to_string()));
        }

        let json = to_string(&Value::Object(statements))
            .unwrap_or_else(|err| error_json(err.to_string()));

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.pimdirVersion`: the schema version the compiled-in SQL is, which the
/// caller stamps into `PRAGMA user_version` and checks on open.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirVersion<'local>(
    _env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> i32 {
    io_pimdir::sql::VERSION as i32
}
