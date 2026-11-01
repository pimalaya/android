//! # Search exports
//!
//! The search index (SEARCH.md) over JNI: index.db is the Java
//! `PimdirIndexDb`'s, its one handle and its transactions, and each export
//! sends it the steps of SQL it runs through its `serve` upcall
//! ([`HostSql`]): what index.db holds, its schema, one run of the refresh,
//! and a query's hits.

use std::path::Path;

use jni::{
    EnvUnowned,
    errors::Error,
    objects::{JClass, JObject, JString},
    sys::{jboolean, jint, jlong},
};
use serde::Serialize;
use serde_json::to_string;

use crate::{
    error::BridgeError,
    ffi::{ErrorReply, error_json, host::JniHost, read_string},
    search::{
        compile::Sort,
        find::{self, Answer, Hit, Refusal},
        held,
        lists::{self, CardFacts},
        refresh,
        refresh::RunReport,
        shape,
        sql::HostSql,
    },
};

/// A reply as JSON, a failure as `{"error"}`.
fn reply<T: Serialize>(result: Result<T, BridgeError>) -> String {
    match result {
        Ok(value) => to_string(&value).unwrap_or_else(|err| error_json(err.to_string())),
        Err(err) => error_json(err),
    }
}

/// `Native.pimdirIndexStatement`: one statement of the index's own that
/// Java runs itself, its parameters numbered: `ATTACH_STORE` (the store's
/// path its one parameter), `BEGIN_RUN`, `COMMIT_RUN`; empty for another.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIndexStatement<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    name: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let name = read_string(env, &name);
        let text = match name.as_str() {
            "ATTACH_STORE" | "BEGIN_RUN" | "COMMIT_RUN" => shape::text(&name).unwrap_or_default(),
            _ => "",
        };
        let sql = shape::steps(text)
            .into_iter()
            .next()
            .map(|step| step.sql)
            .unwrap_or_default();
        Ok(env.new_string(sql)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirIndexHeld`: what index.db holds (SEARCH §2), read through
/// `index`, the store attached: `{"held": "empty" | "current"}`,
/// `{"held": "rebuild", "reason"}` or `{"held": "refused", "detail"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIndexHeld<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    index: JObject<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = reply(held::held(&mut HostSql(JniHost::offline(env, &index))));
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirIndexInit`: builds the schema on an empty index.db and
/// writes its `index_meta`, inside the caller's transaction. Answers `{}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIndexInit<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    index: JObject<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        #[derive(Serialize)]
        struct Built {}

        let built = held::init(&mut HostSql(JniHost::offline(env, &index))).map(|()| Built {});
        Ok(env.new_string(reply(built))?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirIndexRefresh`: one run of the refresh (SEARCH §4), inside
/// the caller's transaction: at most `batch` rows of the store's feed
/// folded, the bodies read off `blobs`, the horizon around `now` (Unix
/// seconds), `missing` folding first what an earlier pass could not read,
/// floating times and dates read in `zone`, an IANA name (`UTC` when
/// empty). Answers `{"cursor", "exhausted", "folded", "indexed", "removed"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIndexRefresh<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    index: JObject<'local>,
    blobs: JString<'local>,
    now: jlong,
    batch: jint,
    missing: jboolean,
    zone: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let blobs = read_string(env, &blobs);
        let zone = read_string(env, &zone);
        let zone = if zone.is_empty() {
            "UTC"
        } else {
            zone.as_str()
        };
        let mut sql = HostSql(JniHost::offline(env, &index));
        let ran = refresh::run(
            &mut sql,
            Path::new(&blobs),
            now,
            batch.max(1) as usize,
            missing,
            zone,
        )
        .map(RunReport::from);
        Ok(env.new_string(reply(ran))?.into())
    })
    .resolve::<ErrorReply>()
}

/// What a query answers over JNI: its hits or its refusal.
#[derive(Serialize)]
#[serde(untagged)]
enum Answered<T> {
    Found(T),
    Refused { refused: Refusal },
}

impl<T> From<Answer<T>> for Answered<T> {
    fn from(answer: Answer<T>) -> Self {
        match answer {
            Answer::Found(found) => Self::Found(found),
            Answer::Refused(refused) => Self::Refused { refused },
        }
    }
}

#[derive(Serialize)]
struct HitsJson {
    hits: Vec<Hit>,
}

/// The order a query names, `date` unless `name`.
fn sort(name: &str) -> Sort {
    match name {
        "name" => Sort::Name,
        _ => Sort::Date,
    }
}

/// `Native.pimdirIndexHits`: every hit of `query` (SEARCH §8), sorted
/// `date` or `name`, relative dates read at `now` (Unix seconds), inside
/// the caller's transaction: `{"hits": [{"account"?, "seq"}]}`, or
/// `{"refused": {"code", "message", "start", "end", "expected",
/// "fields"}}` for a query the language refuses (SEARCH §8.4).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIndexHits<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    index: JObject<'local>,
    query: JString<'local>,
    order: JString<'local>,
    now: jlong,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let query = read_string(env, &query);
        let order = read_string(env, &order);
        let mut sql = HostSql(JniHost::offline(env, &index));
        let found = find::hits(&mut sql, &query, sort(&order), None, now)
            .map(|answer| Answered::from(answer).map_found(|hits| HitsJson { hits }));
        Ok(env.new_string(reply(found))?.into())
    })
    .resolve::<ErrorReply>()
}

impl<T> Answered<T> {
    /// The hits mapped, a refusal kept.
    fn map_found<U>(self, map: impl FnOnce(T) -> U) -> Answered<U> {
        match self {
            Self::Found(found) => Answered::Found(map(found)),
            Self::Refused { refused } => Answered::Refused { refused },
        }
    }
}

/// `Native.pimdirIndexSearch`: one page of `query`'s hits described,
/// `limit` after `offset`, each with its placements and a snippet of its
/// body read off `blobs`: `{"hits": [{"account"?, "seq", "kind", "title",
/// "sortKey", "placements", "snippet"?}], "more"}`, or `{"refused"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIndexSearch<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    index: JObject<'local>,
    blobs: JString<'local>,
    query: JString<'local>,
    order: JString<'local>,
    limit: jint,
    offset: jint,
    now: jlong,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let blobs = read_string(env, &blobs);
        let query = read_string(env, &query);
        let order = read_string(env, &order);
        let mut sql = HostSql(JniHost::offline(env, &index));
        let found = find::search(
            &mut sql,
            Path::new(&blobs),
            &query,
            sort(&order),
            limit.max(0) as usize,
            offset.max(0) as usize,
            now,
        )
        .map(Answered::from);
        Ok(env.new_string(reply(found))?.into())
    })
    .resolve::<ErrorReply>()
}

#[derive(Serialize)]
struct CardsJson {
    cards: Vec<CardFacts>,
}

/// `Native.pimdirIndexCardFacts`: what the index recorded of every card's
/// body (SEARCH §6), inside the caller's transaction: `{"cards":
/// [{"collection", "seq", "has"?, "categories"?}]}`, `has` naming `TEL`,
/// `ADR`, `BDAY` and `PHOTO` as the body holds them.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIndexCardFacts<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    index: JObject<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let mut sql = HostSql(JniHost::offline(env, &index));
        let cards = lists::card_facts(&mut sql).map(|cards| CardsJson { cards });
        Ok(env.new_string(reply(cards))?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.pimdirIndexAgenda`: the occurrences of `[start, end)`, RFC 3339
/// instants in UTC, of every live calendar item (SEARCH §7), each with the
/// row it draws read from the store's summaries and `source`'s binding,
/// inside the caller's transaction: `{"occurrences": [{"collection",
/// "seq", "start", "end", "linkId", "object", "handle"?, "revision"?,
/// "component"?, "summary", "location", "allDay"}], "horizon"?: {"start",
/// "end"}, "horizonLeft", "cut", "floatingZone"}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_pimdirIndexAgenda<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    index: JObject<'local>,
    start: JString<'local>,
    end: JString<'local>,
    source: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let start = read_string(env, &start);
        let end = read_string(env, &end);
        let source = read_string(env, &source);
        let mut sql = HostSql(JniHost::offline(env, &index));
        let agenda = lists::agenda(&mut sql, &start, &end, &source);
        Ok(env.new_string(reply(agenda))?.into())
    })
    .resolve::<ErrorReply>()
}
