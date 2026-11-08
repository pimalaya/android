//! # Find
//!
//! A query answered from index.db (SEARCH §8): parsed by io-pimdir, its
//! limits and refusals included, compiled to the shape's SQL
//! ([`compile`](crate::search::compile)), run in one round on the owner's
//! handle, and its hits described with their placements and a snippet cut
//! from the body in Rust, never from FTS4's `snippet()`.

use std::path::Path;

use io_pimdir::{
    object::PimdirHash,
    search::{
        extract::PimdirExtract,
        fold,
        query::{PimdirQuery, PimdirQueryError, PimdirQueryKind, PimdirQueryPredicate},
    },
};
use jiff::tz::TimeZone;
use serde::Serialize;

use crate::{
    error::BridgeError,
    search::{
        compile::{self, Categories, Compiled, Ends, Sort},
        refresh,
        sql::{Batch, IndexSql, Value, int, opt_text, query, text},
    },
};

/// About how many characters a snippet holds.
const SNIPPET_LEN: usize = 200;

/// A query refused (SEARCH §8.4), as the app shows it.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct Refusal {
    /// The code, `limit-exceeded` and the others.
    pub code: &'static str,
    /// The fault, as a sentence for a person.
    pub message: String,
    /// The first offset at fault, in Unicode scalar values.
    pub start: usize,
    /// The offset after the last at fault.
    pub end: usize,
    /// What would have been valid at `start`.
    pub expected: Vec<String>,
    /// Every field name, for a field refused.
    pub fields: Vec<String>,
}

impl From<PimdirQueryError> for Refusal {
    fn from(err: PimdirQueryError) -> Self {
        Self {
            code: err.code.as_str(),
            message: err.message,
            start: err.start,
            end: err.end,
            expected: err.expected,
            fields: err.fields,
        }
    }
}

/// What a query answers: its hits, or its refusal.
pub enum Answer<T> {
    /// The query ran.
    Found(T),
    /// The query was refused before it ran.
    Refused(Refusal),
}

/// One hit, `(account, seq)` (SEARCH §8.2).
#[derive(Clone, Debug, Eq, Ord, PartialEq, PartialOrd, Serialize)]
pub struct Hit {
    /// The account, absent for an account-less collection.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub account: Option<String>,
    /// The item's public id.
    pub seq: i64,
}

/// A hit described: its kind, title, sort key, placements and snippet.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Described {
    /// The account, absent for an account-less collection.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub account: Option<String>,
    /// The item's public id.
    pub seq: i64,
    /// `mail`, `contact`, `event`, `task`, `journal`, `file`, or the media
    /// type of another.
    pub kind: String,
    /// Its title, empty with none.
    pub title: String,
    /// Its latest sort key.
    pub sort_key: String,
    /// Its live placements.
    pub placements: Vec<Placement>,
    /// A passage of its body naming a word of the query, absent for an
    /// item holding no body.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub snippet: Option<String>,
}

/// One live placement of a hit.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Placement {
    /// The collection.
    pub collection: String,
    /// The item's key there.
    pub link_id: String,
    /// The body it holds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub object: Option<String>,
}

/// A page of described hits.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
pub struct Page {
    /// The hits, in the query's order.
    pub hits: Vec<Described>,
    /// Whether hits follow the page.
    pub more: bool,
}

/// Every hit of `query` in `sort` order, `page` holding how many after
/// how many (`None` for every one), relative dates read at `now`.
pub fn hits(
    sql: &mut impl IndexSql,
    query: &str,
    sort: Sort,
    page: Option<(usize, usize)>,
    now: i64,
) -> Result<Answer<Vec<Hit>>, BridgeError> {
    let parsed = match PimdirQuery::parse(query) {
        Ok(parsed) => parsed,
        Err(err) => return Ok(Answer::Refused(err.into())),
    };
    let (found, _) = run(sql, &parsed, sort, page, now)?;
    Ok(Answer::Found(found))
}

/// One page of `query`'s hits described, `limit` after `offset`, each with
/// a snippet of its body read off `blobs`.
pub fn search(
    sql: &mut impl IndexSql,
    blobs: &Path,
    query: &str,
    sort: Sort,
    limit: usize,
    offset: usize,
    now: i64,
) -> Result<Answer<Page>, BridgeError> {
    let parsed = match PimdirQuery::parse(query) {
        Ok(parsed) => parsed,
        Err(err) => return Ok(Answer::Refused(err.into())),
    };
    let probe = limit.saturating_add(1);
    let (mut found, words) = run(sql, &parsed, sort, Some((probe, offset)), now)?;
    let more = found.len() > limit;
    found.truncate(limit);
    let hits = describe(sql, blobs, found, &words)?;
    Ok(Answer::Found(Page { hits, more }))
}

/// Runs a parsed query: its categories and ends read, its text terms'
/// matches and its select in one round. Answers the hits and the words a
/// snippet looks for.
fn run(
    sql: &mut impl IndexSql,
    parsed: &PimdirQuery,
    sort: Sort,
    page: Option<(usize, usize)>,
    now: i64,
) -> Result<(Vec<Hit>, Vec<String>), BridgeError> {
    let ends = ends(sql, parsed)?;
    let categories = categories(sql, parsed)?;
    let zone = zone(sql, parsed)?;
    let mut compiled = Compiled::new(parsed, ends, categories, now, zone);
    // NOTE: the `name` order folds titles in Rust, so it reads every hit
    // and pages here.
    let select = compiled.select(sort, page.filter(|_| sort == Sort::Date));

    let mut batch = Batch::default();
    batch.add("CREATE_TERM_MATCH", &[])?;
    batch.add("CLEAR_TERM_MATCH", &[])?;
    for matches in std::mem::take(&mut compiled.matches) {
        batch.add_numbered(matches.sql, matches.params, false);
    }
    let rows = batch.add_numbered(select, compiled.params.clone(), true);
    let answers = batch.run(sql)?;

    let mut found = answers
        .rows(rows)
        .iter()
        .map(|row| Ok((opt_text(row, 0)?, int(row, 1)?, text(row, 4)?)))
        .collect::<Result<Vec<_>, BridgeError>>()?;
    if sort == Sort::Name {
        // NOTE: ties to the seq then the account, as the SQL order gave.
        found.sort_by_cached_key(|(_, _, title)| fold::value(title));
        if let Some((limit, offset)) = page {
            found = found.into_iter().skip(offset).take(limit).collect();
        }
    }
    let hits = found
        .into_iter()
        .map(|(account, seq, _)| Hit { account, seq })
        .collect();
    Ok((hits, compiled.words))
}

/// The zone a query's days are read in (SEARCH §8.1): the one the index
/// reads floating times in, read only for a query holding a date term.
fn zone(sql: &mut impl IndexSql, parsed: &PimdirQuery) -> Result<TimeZone, BridgeError> {
    let dated = parsed
        .terms()
        .iter()
        .any(|(term, _)| matches!(term.predicate, PimdirQueryPredicate::Date(_)));
    if !dated {
        return Ok(TimeZone::UTC);
    }
    // NOTE: floating_zone is load_index_meta's twelfth column.
    let zone = match query(sql, "LOAD_INDEX_META", &[])?.first() {
        Some(row) => text(row, 11)?,
        None => return Ok(TimeZone::UTC),
    };
    TimeZone::get(&zone).map_err(|err| format!("search cannot read days in {zone}: {err}").into())
}

/// The task categories each `category:` term names: every value a task
/// states whose fold is the term's, both folded in Rust.
fn categories(sql: &mut impl IndexSql, parsed: &PimdirQuery) -> Result<Categories, BridgeError> {
    let mut categories = Vec::new();
    let mut stored: Option<Vec<(String, String)>> = None;
    for (term, _) in parsed.terms() {
        let PimdirQueryPredicate::Category(category) = &term.predicate else {
            continue;
        };
        let stored = match &mut stored {
            Some(stored) => stored,
            None => {
                let values = query(sql, "TASK_CATEGORY_VALUES", &[])?
                    .iter()
                    .map(|row| text(row, 0))
                    .collect::<Result<Vec<_>, _>>()?;
                stored.insert(
                    values
                        .into_iter()
                        .map(|value| (fold::value(&value), value))
                        .collect(),
                )
            }
        };
        let folded = fold::value(category);
        let values = stored
            .iter()
            .filter(|(stored, _)| *stored == folded)
            .map(|(_, value)| value.clone())
            .collect();
        categories.push((folded, values));
    }
    Ok(categories)
}

/// The items each `linked:` term names, every kind each is held under.
fn ends(sql: &mut impl IndexSql, parsed: &PimdirQuery) -> Result<Ends, BridgeError> {
    let mut ends = Vec::new();
    for (term, _) in parsed.terms() {
        let PimdirQueryPredicate::Linked { id, .. } = &term.predicate else {
            continue;
        };
        let named = query(
            sql,
            "LOAD_ENDS",
            &[
                (":seq", Value::from(id.seq())),
                (":link_id", id.as_str().into()),
            ],
        )?
        .iter()
        .map(|row| Ok((text(row, 0)?, text(row, 1)?)))
        .collect::<Result<Vec<_>, BridgeError>>()?;
        ends.push((id.clone(), named));
    }
    Ok(ends)
}

/// Hits described: their placements, kind and title in one round, the
/// chunks of the bodies they hold in another, the snippets cut in Rust.
fn describe(
    sql: &mut impl IndexSql,
    blobs: &Path,
    found: Vec<Hit>,
    words: &[String],
) -> Result<Vec<Described>, BridgeError> {
    let mut batch = Batch::default();
    let mut asked = Vec::new();
    for hit in &found {
        asked.push(batch.add(
            "LOAD_HIT_PLACEMENTS",
            &[
                (":seq", hit.seq.into()),
                (":account", hit.account.clone().into()),
            ],
        )?);
    }
    let answers = batch.run(sql)?;

    let mut described = Vec::new();
    for (hit, handle) in found.into_iter().zip(asked) {
        let rows = answers.rows(handle);
        let mut placements = Vec::new();
        let mut kind = String::new();
        let mut title = String::new();
        let mut sort_key = String::new();
        for (at, row) in rows.iter().enumerate() {
            placements.push(Placement {
                collection: text(row, 0)?,
                link_id: text(row, 1)?,
                object: opt_text(row, 3)?,
            });
            let key = text(row, 4)?;
            if key > sort_key {
                sort_key = key;
            }
            if at == 0 {
                kind = match opt_text(row, 7)? {
                    Some(kind) => kind,
                    None => text(row, 6)?,
                };
                title = text(row, 8)?;
            }
        }
        described.push(Described {
            account: hit.account,
            seq: hit.seq,
            kind,
            title,
            sort_key,
            placements,
            snippet: None,
        });
    }

    let mut batch = Batch::default();
    let mut chunked = Vec::new();
    for (at, hit) in described.iter().enumerate() {
        let Some(hash) = hit.placements.iter().find_map(|p| p.object.clone()) else {
            continue;
        };
        let handle = batch.add("CHUNKS_OF", &[(":hash", (&hash).into())])?;
        chunked.push((at, hash, handle));
    }
    let answers = batch.run(sql)?;
    for (at, hash, handle) in chunked {
        let chunks = answers
            .rows(handle)
            .iter()
            .map(|row| Ok((text(row, 1)?, int(row, 2)? as usize, int(row, 3)? as usize)))
            .collect::<Result<Vec<_>, BridgeError>>()?;
        let hit = &mut described[at];
        hit.snippet = snippet(blobs, &hit.kind, &PimdirHash(hash), &chunks, words);
    }
    Ok(described)
}

/// A passage of a body: the first chunk naming one of `words`, else the
/// first chunk, trimmed to about [`SNIPPET_LEN`] characters around the
/// word, as io-pimdir's canonical query client cuts it.
fn snippet(
    blobs: &Path,
    kind: &str,
    hash: &PimdirHash,
    chunks: &[(String, usize, usize)],
    words: &[String],
) -> Option<String> {
    if chunks.is_empty() {
        return None;
    }
    let blob = refresh::body(blobs, hash)?;
    let media_type = match PimdirQueryKind::parse(kind) {
        Some(kind) => compile::media_type(kind),
        None => kind,
    };
    let extract = PimdirExtract::body(media_type, &blob)?;
    let words: Vec<String> = words.iter().map(|word| fold::text(word)).collect();
    let texts: Vec<&str> = chunks
        .iter()
        .filter_map(|(field, start, end)| extract.field(field)?.get(*start..*end))
        .collect();

    for text in &texts {
        let (folded, origin) = fold_chars(text);
        if let Some(at) = words
            .iter()
            .filter(|word| !word.is_empty())
            .filter_map(|word| folded.find(word.as_str()))
            .min()
        {
            return Some(trim(text, origin[at]));
        }
    }
    texts.first().map(|text| trim(text, 0))
}

/// A text folded for a snippet's word search, each character alone, with
/// the character of `text` each byte of the fold came from.
fn fold_chars(text: &str) -> (String, Vec<usize>) {
    let mut folded = String::with_capacity(text.len());
    let mut origin = Vec::with_capacity(text.len());
    for (at, c) in text.chars().enumerate() {
        let mut buf = [0; 4];
        let one = fold::text(c.encode_utf8(&mut buf));
        origin.extend(std::iter::repeat_n(at, one.len()));
        folded.push_str(&one);
    }
    (folded, origin)
}

/// About [`SNIPPET_LEN`] characters of a chunk around the character at
/// `at`, whitespace runs collapsed, an ellipsis marking each cut.
fn trim(text: &str, at: usize) -> String {
    let chars: Vec<char> = text.chars().collect();
    let start = at.saturating_sub(SNIPPET_LEN / 4);
    let start = match chars[..start].iter().rposition(|c| c.is_whitespace()) {
        Some(space) if start - space < 20 => space + 1,
        _ => start,
    };
    let end = (start + SNIPPET_LEN).min(chars.len());

    let mut snippet = String::new();
    if start > 0 {
        snippet.push('…');
    }
    let mut space = false;
    for c in &chars[start..end] {
        if c.is_whitespace() {
            space = true;
            continue;
        }
        if space && !snippet.is_empty() && !snippet.ends_with('…') {
            snippet.push(' ');
        }
        space = false;
        snippet.push(*c);
    }
    if end < chars.len() {
        snippet.push('…');
    }
    snippet
}
