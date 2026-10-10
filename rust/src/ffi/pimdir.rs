//! The canonical pimdir SQL and the Annex A derivation, handed to the
//! Java side.
//!
//! The app's storage seam drives the SQLite it bundles on the Java side,
//! behind the platform's binding, so this app takes io-pimdir **without** its
//! `client` feature: no rusqlite, no second SQLite engine compiled into every
//! ABI. What the crate contributes is the schema and the statements, which
//! cross the boundary here rather than being transcribed into Java where they
//! would drift from the spec in silence.

use io_pimdir::summary::derive;
use jni::{
    EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JByteArray, JClass, JObject, JString},
};
use serde_json::{Map, Value, json, to_string};

use crate::{
    ffi::{error_json, read_string},
    summary::SummaryJson,
};

/// `Native.pimdirSql`: every statement, canonical then the crate's own, keyed
/// by its constant name, as a JSON object.
///
/// Pure computation over compiled-in constants: no transport, no store, no
/// failure mode beyond serialization.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirSql<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mut statements = Map::new();
        for (name, sql) in io_pimdir::sql::all() {
            statements.insert(name.to_string(), Value::String(sql.to_string()));
        }

        let json =
            to_string(&Value::Object(statements)).unwrap_or_else(|err| error_json(err.to_string()));

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.pimdirMigrations`: every canonical migration in order, as a JSON
/// array, the runner of STORAGE §6 applying each one above `user_version`.
///
/// Creating the database is as much the caller's job as querying it, and the
/// migrations are indexed apart from the statements the profiles run.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirMigrations<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let migrations: Vec<Value> = io_pimdir::sql::MIGRATIONS
            .iter()
            .map(|sql| Value::String((*sql).to_string()))
            .collect();

        let json =
            to_string(&Value::Array(migrations)).unwrap_or_else(|err| error_json(err.to_string()));

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

/// `Native.pimdirDerive`: what STORAGE Annex A derives from one body of
/// the given kind (its collection's media type), as
/// `{"linkId", "summary", "sortKey"}`, `summary` null where the body
/// yields none.
///
/// Pure computation: a body the store holds is named and summarised the
/// way any writer of the store would, with nothing transcribed in Java.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirDerive<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    body: JByteArray<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let body = env.convert_byte_array(&body).unwrap_or_default();

        let json = match derive(&kind, &body) {
            None => error_json(format!("No pimdir derivation for {kind}")),
            Some(derived) => json!({
                "linkId": derived.link_id.0,
                "summary": derived.summary.as_ref().map(SummaryJson::from),
                "sortKey": derived.sort_key.as_str(),
            })
            .to_string(),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}
