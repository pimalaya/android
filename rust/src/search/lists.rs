//! # Lists
//!
//! What the app's lists read off index.db rather than off every body
//! (cairn/changes/archive/lists-from-the-index): the summaries hold a row, the
//! index the questions. The contacts list asks what its Has filter and its
//! categories test of every card (SEARCH §6), as the index recorded it at
//! extraction; the agenda draws a window of calendar time from the
//! occurrences the index wrote (SEARCH §7), each item's row from its
//! summary.

use serde::Serialize;

use crate::{
    error::BridgeError,
    search::sql::{Batch, IndexSql, int, opt_text, text},
};

/// What the index recorded of one card's body (SEARCH §6), by placement.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
pub struct CardFacts {
    /// The collection.
    pub collection: String,
    /// The item's seq there.
    pub seq: i64,
    /// The vCard properties its body holds of `TEL`, `ADR`, `BDAY` and
    /// `PHOTO`, in that order.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub has: Vec<&'static str>,
    /// Its categories, folded as STORAGE Annex A.2 folds `fn`, each once.
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub categories: Vec<String>,
}

/// Every card of the store the index holds a body of, with what its body
/// holds and its categories, in one round, by collection then seq.
pub fn card_facts(sql: &mut impl IndexSql) -> Result<Vec<CardFacts>, BridgeError> {
    let mut batch = Batch::default();
    let holding = batch.add("CARD_FACTS", &[])?;
    let categorised = batch.add("CARD_CATEGORIES", &[])?;
    let answers = batch.run(sql)?;

    let mut cards = Vec::new();
    for row in answers.rows(holding) {
        let mut has = Vec::new();
        for (column, property) in [(2, "TEL"), (3, "ADR"), (4, "BDAY"), (5, "PHOTO")] {
            if int(row, column)? == 1 {
                has.push(property);
            }
        }
        cards.push(CardFacts {
            collection: text(row, 0)?,
            seq: int(row, 1)?,
            has,
            categories: Vec::new(),
        });
    }

    // NOTE: both reads run by collection then seq, so each category lands
    // on its card in one walk.
    let mut at = 0;
    for row in answers.rows(categorised) {
        let key = (text(row, 0)?, int(row, 1)?);
        while at < cards.len() && (cards[at].collection.as_str(), cards[at].seq) < (&key.0, key.1) {
            at += 1;
        }
        if let Some(card) = cards.get_mut(at)
            && (card.collection.as_str(), card.seq) == (key.0.as_str(), key.1)
        {
            card.categories.push(text(row, 2)?);
        }
    }
    Ok(cards)
}

/// One occurrence an agenda draws (SEARCH §7), with its item's row read
/// from the store's summaries and its source's binding.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct AgendaOccurrence {
    /// The collection.
    pub collection: String,
    /// The item's seq there.
    pub seq: i64,
    /// When it starts, an RFC 3339 instant in UTC.
    pub start: String,
    /// When it ends, its start for one of zero length.
    pub end: String,
    /// The item's key.
    pub link_id: String,
    /// The store's hash of its body.
    pub object: String,
    /// How its source addresses it, absent until one binds it.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub handle: Option<String>,
    /// The source's validator, absent when unknown.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub revision: Option<String>,
    /// `VEVENT`, `VTODO` or `VJOURNAL`, by the summary it holds.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub component: Option<String>,
    /// Its master's `SUMMARY`, empty with none.
    pub summary: String,
    /// Its master's `LOCATION`, empty with none.
    pub location: String,
    /// Whether its master starts on a date rather than a date-time, its
    /// occurrences then read in the floating zone.
    pub all_day: bool,
}

/// A window of calendar time as the index answers it (SEARCH §7, §8.2):
/// its occurrences and what it could not see.
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Agenda {
    /// The occurrences overlapping the window, by start, then seq, then
    /// collection.
    pub occurrences: Vec<AgendaOccurrence>,
    /// The horizon the occurrences are written within, `[start, end)`,
    /// RFC 3339; absent for an index no refresh has rolled one into yet.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub horizon: Option<Horizon>,
    /// Whether the window leaves the horizon (SEARCH §8.1): an occurrence
    /// outside it is not listed.
    pub horizon_left: bool,
    /// How many items had their occurrences cut before the window's end by
    /// a bound of SEARCH §7, so the window does not see all of theirs.
    pub cut: i64,
    /// The zone the index read floating times and dates in
    /// (`index_meta.floating_zone`).
    pub floating_zone: String,
}

/// The window occurrences are written within (SEARCH §7).
#[derive(Clone, Debug, Default, Eq, PartialEq, Serialize)]
pub struct Horizon {
    /// Its first instant.
    pub start: String,
    /// The instant after its last.
    pub end: String,
}

/// The occurrences of `[start, end)`, RFC 3339 instants in UTC, of every
/// live calendar item, the row each draws read from the store's summaries
/// and `source`'s binding, with the window's coverage; one round.
pub fn agenda(
    sql: &mut impl IndexSql,
    start: &str,
    end: &str,
    source: &str,
) -> Result<Agenda, BridgeError> {
    let mut batch = Batch::default();
    let meta = batch.add("LOAD_INDEX_META", &[])?;
    let rows = batch.add(
        "AGENDA_OCCURRENCES",
        &[
            (":start", start.into()),
            (":end", end.into()),
            (":source", source.into()),
        ],
    )?;
    let cut = batch.add("AGENDA_CUT", &[(":end", end.into())])?;
    let answers = batch.run(sql)?;

    // NOTE: load_index_meta's columns: version, tokenizer, store_change,
    // store_purges, horizon_start, horizon_end, tzdb_version, store_id,
    // store_incarnation, format_version, shape, floating_zone.
    let (horizon, floating_zone) = match answers.first(meta) {
        Some(row) => {
            let (first, last) = (text(row, 4)?, text(row, 5)?);
            let horizon = (!first.is_empty() && !last.is_empty()).then_some(Horizon {
                start: first,
                end: last,
            });
            (horizon, text(row, 11)?)
        }
        None => (None, String::new()),
    };
    let horizon_left = horizon
        .as_ref()
        .is_none_or(|horizon| *start < *horizon.start || *end > *horizon.end);

    let mut occurrences = Vec::new();
    for row in answers.rows(rows) {
        occurrences.push(AgendaOccurrence {
            collection: text(row, 0)?,
            seq: int(row, 1)?,
            start: text(row, 2)?,
            end: text(row, 3)?,
            link_id: text(row, 4)?,
            object: text(row, 5)?,
            handle: opt_text(row, 6)?,
            revision: opt_text(row, 7)?,
            component: opt_text(row, 8)?,
            summary: text(row, 9)?,
            location: text(row, 10)?,
            all_day: opt_text(row, 11)?.as_deref() == Some("date"),
        });
    }

    Ok(Agenda {
        occurrences,
        horizon,
        horizon_left,
        cut: answers
            .first(cut)
            .map(|row| int(row, 0))
            .transpose()?
            .unwrap_or(0),
        floating_zone,
    })
}
