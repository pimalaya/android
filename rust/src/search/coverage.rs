//! # Coverage
//!
//! What a query's answer could not see (SEARCH §8.2), as io-pimdir's
//! [`PimdirSearchCoverage`], filled from index.db with the store attached
//! the way io-pimdir's own index fills it, the canonical statements run
//! through the owner ([`coverage`]). The mail list asks it per account over
//! the mailboxes it shows, beside its hits, and reads one roll-up of it
//! ([`mail`]): which account falls shortest, from when, and why.

use std::collections::BTreeMap;

use io_pimdir::{
    collection::PimdirScope,
    search::{
        coverage::{PimdirSearchCoverage, PimdirSearchTemporal},
        query::{PimdirQuery, PimdirQueryPredicate},
    },
};
use serde::{Deserialize, Serialize};

use crate::{
    error::BridgeError,
    search::{
        compile,
        find::{self, Answer, Hit},
        sql::{Answers, Batch, Handle, IndexSql, Row, Value, int, opt_text, text},
    },
};

/// The media type of a mail collection.
const MAIL: &str = "message/rfc822";

/// The media type of a calendar collection.
const CALENDAR: &str = "text/calendar";

/// The collections a query's conjuncts leave, and the date range
/// `[since, until)` they narrow to.
struct Scoped {
    collections: Vec<Collection>,
    since: Option<String>,
    until: Option<String>,
}

/// One collection of the store, as a query's conjuncts narrow them.
#[derive(Clone, Debug)]
struct Collection {
    id: String,
    account: Option<String>,
    kind: String,
}

/// What the mail list searches: the mailboxes it shows, as the app knows
/// them beside the store.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct MailScope {
    /// The source whose rounds list the mailboxes: a mailbox it never
    /// listed is not known to hold anything whole.
    pub source: String,
    /// The mailboxes shown.
    pub collections: Vec<MailCollection>,
}

/// One mailbox the mail list shows.
#[derive(Clone, Debug, Default, Deserialize)]
pub struct MailCollection {
    /// The collection id.
    pub id: String,
    /// Where its account's steps reached, for a mailbox its account's steps
    /// bring (Gmail, JMAP), whose rounds name what the store holds and so
    /// cover the whole bound: the store is not known to hold what lies
    /// below. Absent for all mail and for a mailbox listed by rounds.
    #[serde(default)]
    pub reach: Option<String>,
    /// Its account's bound, absent for all mail or a mailbox kept whole:
    /// what lies below it the phone never keeps.
    #[serde(default)]
    pub bound: Option<String>,
}

/// One shown account's coverage, as the mail list reads it.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AccountCoverage {
    /// The account, absent for an account-less collection.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub account: Option<String>,
    /// Whether nothing its coverage reports could have changed the answer
    /// (`PimdirSearchCoverage::is_complete`).
    pub complete: bool,
    /// Whether the store is not known to hold every message of the range
    /// searched (the temporal axis is not full).
    pub short: bool,
    /// The instant below which the store is not known to hold every
    /// message, absent when the floor is not the cause.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub since: Option<String>,
    /// Whether a mailbox's source never closed a round: a first sync still
    /// filling in.
    pub filling: bool,
    /// Whether `since` is the account's own bound: what lies below it the
    /// phone does not keep, rather than not yet.
    pub bounded: bool,
    /// The messages in scope searched without their body (bodiless, not
    /// readable or unparseable, and encrypted under a text term), counted
    /// only where a term reads one, as `is_complete` counts them.
    pub unread: u64,
    /// The messages in scope the index has not folded in yet.
    pub unindexed: u64,
}

/// The roll-up the mail list's end line reads.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MailCoverage {
    /// Whether the answer is complete for every shown account.
    pub complete: bool,
    /// How many shown accounts fall short on the range searched.
    pub short: usize,
    /// The account falling shortest: the one whose floor is the latest, or
    /// the first one short with none; absent when none is short.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub worst: Option<String>,
    /// The latest floor of the accounts short, from which every one of them
    /// is known whole: the worst account's.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub since: Option<String>,
    /// Whether that floor is the worst account's own bound.
    pub bounded: bool,
    /// Whether a shown account's mailbox is still filling in.
    pub filling: bool,
    /// The messages searched without their body, every account summed.
    pub unread: u64,
    /// The messages the index has not folded in yet, every account summed.
    pub unindexed: u64,
    /// How far the whole index is behind the store, in change stamps.
    pub behind: u64,
    /// Every shown account searched, in the order the scope names them.
    pub accounts: Vec<AccountCoverage>,
}

/// A mail search's answer: its hits and what it could not see.
#[derive(Clone, Debug, Serialize)]
pub struct MailHits {
    /// Every hit, in date order.
    pub hits: Vec<Hit>,
    /// What the answer could not see over the mailboxes shown.
    pub coverage: MailCoverage,
}

/// What `query` could not see over its collections, those its conjuncts
/// leave and the date range they narrow to (SEARCH §8.2), as io-pimdir's
/// index answers it: relative dates read at `now`, in the zone the index
/// reads floating times in, which is the one the app asks in, so the zone
/// never differs. What io-pimdir's vectors check (SEARCH §11); the app reads
/// [`mail`].
#[cfg(test)]
pub fn coverage(
    sql: &mut impl IndexSql,
    parsed: &PimdirQuery,
    now: i64,
) -> Result<PimdirSearchCoverage, BridgeError> {
    let Scoped {
        collections,
        since,
        until,
    } = scoped(sql, parsed, now)?;
    let group = Group {
        collections: collections.iter().map(|c| (c, None)).collect(),
    };
    let mut filled = fill(sql, parsed, &[group], None, since, until)?;
    Ok(filled.parts.pop().unwrap_or_default())
}

/// Every hit of `query` in date order, relative dates read at `now`, with
/// what it could not see per account over the mailboxes of `scope`, rolled
/// up for the mail list's end line.
pub fn mail(
    sql: &mut impl IndexSql,
    query: &str,
    scope: &MailScope,
    now: i64,
) -> Result<Answer<MailHits>, BridgeError> {
    let parsed = match PimdirQuery::parse(query) {
        Ok(parsed) => parsed,
        Err(err) => return Ok(Answer::Refused(err.into())),
    };
    let hits = match find::hits(sql, query, compile::Sort::Date, None, now)? {
        Answer::Found(hits) => hits,
        Answer::Refused(refused) => return Ok(Answer::Refused(refused)),
    };

    let Scoped {
        collections,
        since,
        until,
    } = scoped(sql, &parsed, now)?;
    let by_id: BTreeMap<&str, &Collection> =
        collections.iter().map(|c| (c.id.as_str(), c)).collect();
    let mut accounts: Vec<Option<String>> = Vec::new();
    let mut groups: Vec<Group> = Vec::new();
    let mut bounds: Vec<Vec<&str>> = Vec::new();
    for shown in &scope.collections {
        let Some(collection) = by_id.get(shown.id.as_str()) else {
            continue;
        };
        let at = match accounts.iter().position(|a| *a == collection.account) {
            Some(at) => at,
            None => {
                accounts.push(collection.account.clone());
                groups.push(Group::default());
                bounds.push(Vec::new());
                accounts.len() - 1
            }
        };
        groups[at]
            .collections
            .push((collection, Some(shown.reach.as_deref())));
        bounds[at].extend(shown.bound.as_deref());
    }

    let filled = fill(sql, &parsed, &groups, Some(&scope.source), since, until)?;
    let accounts = accounts
        .into_iter()
        .zip(filled.parts)
        .zip(bounds)
        .map(|((account, part), bounds)| account_coverage(account, &part, &bounds))
        .collect();
    Ok(Answer::Found(MailHits {
        hits,
        coverage: roll_up(accounts, filled.behind),
    }))
}

/// One account's coverage as the mail list reads it.
fn account_coverage(
    account: Option<String>,
    coverage: &PimdirSearchCoverage,
    bounds: &[&str],
) -> AccountCoverage {
    let (short, since, filling) = match &coverage.temporal {
        PimdirSearchTemporal::Full => (false, None, false),
        PimdirSearchTemporal::Bounded { since, filling, .. } => (true, since.clone(), *filling),
    };
    let bounded = since
        .as_deref()
        .is_some_and(|since| bounds.contains(&since));
    let reads = &coverage.local.reads;
    let unread = coverage.local.kinds.get(MAIL).map_or(0, |counts| {
        let mut unread = 0;
        if reads.counts_bodiless(MAIL) {
            unread += counts.bodiless;
        }
        if reads.kinds.contains(MAIL) {
            unread += counts.missing + counts.unparseable;
        }
        if reads.content {
            unread += counts.encrypted;
        }
        unread
    });
    AccountCoverage {
        account,
        complete: coverage.is_complete(),
        short,
        since,
        filling,
        bounded,
        unread,
        unindexed: coverage.local.unindexed,
    }
}

/// The roll-up over the shown accounts: complete when every one is, the
/// floor io-pimdir's merge keeps over theirs (the latest), and the account
/// holding it named.
fn roll_up(accounts: Vec<AccountCoverage>, behind: u64) -> MailCoverage {
    let mut temporal = PimdirSearchTemporal::Full;
    for account in accounts.iter().filter(|account| account.short) {
        temporal.merge(&PimdirSearchTemporal::Bounded {
            since: account.since.clone(),
            until: None,
            filling: account.filling,
        });
    }
    let since = match &temporal {
        PimdirSearchTemporal::Bounded { since, .. } => since.clone(),
        PimdirSearchTemporal::Full => None,
    };
    let worst = accounts
        .iter()
        .filter(|account| account.short)
        .find(|account| since.is_none() || account.since == since);

    MailCoverage {
        complete: accounts.iter().all(|account| account.complete),
        short: accounts.iter().filter(|account| account.short).count(),
        worst: worst.and_then(|account| account.account.clone()),
        bounded: worst.is_some_and(|account| account.bounded),
        since,
        filling: accounts.iter().any(|account| account.filling),
        unread: accounts.iter().map(|account| account.unread).sum(),
        unindexed: accounts.iter().map(|account| account.unindexed).sum(),
        behind,
        accounts,
    }
}

/// The collections a query's conjuncts leave and the date range they
/// narrow to, `[since, until)`, as io-pimdir's index reads them.
fn scoped(sql: &mut impl IndexSql, parsed: &PimdirQuery, now: i64) -> Result<Scoped, BridgeError> {
    let zone = find::zone(sql, parsed)?;
    let mut batch = Batch::default();
    let listed = batch.add("LIST_COLLECTIONS", &[])?;
    let answers = batch.run(sql)?;
    let mut collections = answers
        .rows(listed)
        .iter()
        .map(|row| {
            Ok(Collection {
                id: text(row, 0)?,
                account: opt_text(row, 1)?,
                kind: text(row, 2)?,
            })
        })
        .collect::<Result<Vec<_>, BridgeError>>()?;

    let mut since: Option<String> = None;
    let mut until: Option<String> = None;
    for term in parsed.conjuncts() {
        match &term.predicate {
            PimdirQueryPredicate::Account(account) => {
                collections.retain(|c| c.account.as_deref() == Some(account.as_str()))
            }
            PimdirQueryPredicate::Collection(id) => collections.retain(|c| &c.id == id),
            PimdirQueryPredicate::Kind(kind) => {
                collections.retain(|c| c.kind == compile::media_type(*kind))
            }
            PimdirQueryPredicate::Date(range) => {
                let (start, end) = compile::bounds(range, now, &zone);
                if start > since {
                    since = start;
                }
                if let Some(end) = end
                    && until.as_ref().is_none_or(|until| &end < until)
                {
                    until = Some(end);
                }
            }
            _ => (),
        }
    }
    Ok(Scoped {
        collections,
        since,
        until,
    })
}

/// A set of collections whose coverage is read as one value, each with,
/// for the mail list, the reach of its account's steps (`Some(None)` for
/// none); `None` outside it.
#[derive(Default)]
struct Group<'a> {
    collections: Vec<(&'a Collection, Option<Option<&'a str>>)>,
}

/// What [`fill`] answers: one coverage per group, and the index's lag.
struct Filled {
    parts: Vec<PimdirSearchCoverage>,
    behind: u64,
}

/// What a group's second round asks: each collection's coverage rows, the
/// bodies out of sight, the items not folded in, the series cut.
struct Asked {
    rows: Vec<Handle>,
    kinds: Handle,
    unindexed: Handle,
    cut: Option<Handle>,
}

/// The first integer of a statement's first row, 0 without one, a
/// negative one 0.
fn count(answers: &Answers, handle: Handle) -> Result<u64, BridgeError> {
    Ok(answers
        .first(handle)
        .map(|row| int(row, 0))
        .transpose()?
        .unwrap_or(0)
        .max(0) as u64)
}

/// One `list_coverage` row's closed coverage, `None` for a source that
/// never closed a round: its columns are the source, `covered_since`,
/// `covered_until`, `covered_at`, then the round under way.
fn covered(row: &Row) -> Result<Option<PimdirScope>, BridgeError> {
    if opt_text(row, 3)?.is_none() {
        return Ok(None);
    }
    Ok(Some(PimdirScope {
        since: opt_text(row, 1)?,
        until: opt_text(row, 2)?,
    }))
}

/// Each group's coverage over `[since, until)`, in two rounds: the index's
/// cursor, horizon and lag, then every collection's coverage rows and each
/// group's bodies out of sight, items not folded in and, for a dated query
/// over a calendar, its series cut and whether the range leaves the
/// horizon (SEARCH §8.1: an open side always does). With `source`, a
/// collection that source holds no row for counts as one it never closed a
/// round over.
fn fill(
    sql: &mut impl IndexSql,
    parsed: &PimdirQuery,
    groups: &[Group],
    source: Option<&str>,
    since: Option<String>,
    until: Option<String>,
) -> Result<Filled, BridgeError> {
    let mut batch = Batch::default();
    let meta = batch.add("LOAD_INDEX_META", &[])?;
    let lag = batch.add("BEHIND", &[])?;
    let answers = batch.run(sql)?;
    let (cursor, horizon) = match answers.first(meta) {
        Some(row) => (int(row, 2)?, Some((text(row, 4)?, text(row, 5)?))),
        None => (0, None),
    };
    let behind = count(&answers, lag)?;

    let dated = parsed
        .terms()
        .into_iter()
        .any(|(term, _)| matches!(term.predicate, PimdirQueryPredicate::Date(_)));

    let mut batch = Batch::default();
    batch.add("CREATE_PARAM", &[])?;
    let mut asked = Vec::new();
    for group in groups {
        let mut rows = Vec::new();
        for (collection, _) in &group.collections {
            rows.push(batch.add(
                "LIST_COVERAGE",
                &[(":collection", collection.id.as_str().into())],
            )?);
        }
        batch.add("CLEAR_PARAM", &[])?;
        for (position, (collection, _)) in group.collections.iter().enumerate() {
            batch.add(
                "BIND_PARAM",
                &[
                    (":slot", "collections".into()),
                    (":position", (position as i64).into()),
                    (":value", collection.id.as_str().into()),
                ],
            )?;
        }
        let kinds = batch.add("COVERAGE", &[])?;
        let unindexed = batch.add("COUNT_UNINDEXED", &[(":since", cursor.into())])?;
        let calendar = group.collections.iter().any(|(c, _)| c.kind == CALENDAR);
        let cut = match dated && calendar {
            true => Some(batch.add("CUT_BEFORE", &[(":end", Value::from(until.clone()))])?),
            false => None,
        };
        asked.push(Asked {
            rows,
            kinds,
            unindexed,
            cut,
        });
    }
    let answers = batch.run(sql)?;

    let reads = parsed.reads();
    let mut parts = Vec::new();
    for (group, asked) in groups.iter().zip(asked) {
        let mut coverage = PimdirSearchCoverage::default();
        for ((_, reach), rows) in group.collections.iter().zip(asked.rows) {
            let mut scopes = Vec::new();
            let mut sourced = false;
            for row in answers.rows(rows) {
                sourced |= source.is_some_and(|source| text(row, 0).is_ok_and(|s| s == source));
                scopes.push(covered(row)?);
            }
            if source.is_some() && !sourced {
                scopes.push(None);
            }
            if let Some(Some(reach)) = reach {
                scopes.push(Some(PimdirScope {
                    since: Some((*reach).to_string()),
                    until: None,
                }));
            }
            coverage.temporal.merge(&PimdirSearchTemporal::over(
                scopes.iter().map(Option::as_ref),
                since.as_deref(),
                until.as_deref(),
            ));
        }

        for row in answers.rows(asked.kinds) {
            let count = |column| int(row, column).map(|count| count.max(0) as u64);
            let counts = coverage.local.kinds.entry(text(row, 0)?).or_default();
            counts.bodiless = count(1)?;
            counts.missing = count(2)?;
            counts.unparseable = count(3)?;
            counts.encrypted = count(4)?;
        }
        coverage.local.reads = reads.clone();
        coverage.local.unindexed = count(&answers, asked.unindexed)?;
        coverage.local.behind = behind;

        if let Some(cut) = asked.cut {
            coverage.cut = count(&answers, cut)?;
            coverage.horizon_left = match &horizon {
                Some((start, end)) => {
                    since.as_ref().is_none_or(|since| since < start)
                        || until.as_ref().is_none_or(|until| until > end)
                }
                None => true,
            };
        }
        parts.push(coverage);
    }
    Ok(Filled { parts, behind })
}

#[cfg(test)]
mod tests;
