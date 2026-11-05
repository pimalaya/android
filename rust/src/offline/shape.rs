//! # Shape
//!
//! The statements the app runs on pimdir.db: io-pimdir's canonical text
//! where SQLite 3.18 runs it, the app's own text where it does not, and the
//! app's own statements beside them, all served to Java by name.
//!
//! One on-disk format (STORAGE §4.1): the schema is the canonical
//! migrations alone, which load on 3.18, and pimdir.db holds nothing else
//! (§6). The canonical statements need 3.37 (`RETURNING`, upserts, `UPDATE
//! ... FROM`, the `STRICT` table of the list parameters); an implementation
//! may run text of its own keeping each statement's name, parameters,
//! columns and effect (§4.4). Those it rewrites are the files under
//! pimdir/queries/read/, queue/ and owner/, each naming the digest of the
//! canonical text it was forked from, so a pin moving that text fails the
//! tests until the fork is read again. A file may hold several statements,
//! run in order in one transaction under its one name: the write then the
//! read standing for a `RETURNING`, the update then the insert standing for
//! an upsert. pimdir/queries/app/ holds the statements of the app's own,
//! which the Java owner runs and no profile names; one holding `:inner` is
//! a read over another read, which Java's `PimdirSql.wrap` puts there and
//! its `-- Wraps:` line names.
//!
//! The text is the `pimalaya-android` shape. The store records no shape:
//! draft-09 has one on-disk format and no `store_meta.shape` (STORAGE
//! §4.1, §6), so a store the canonical text wrote is the same store, and
//! the app states its conformance as `storage-draft-09 owner, sync-draft-08
//! engine` (§17).

use std::collections::BTreeMap;

use io_pimdir::sql;

include!(concat!(env!("OUT_DIR"), "/shape.rs"));

/// The directory of the app's own statements under pimdir/queries/.
#[cfg(test)]
pub const APP: &str = "app";

/// Every statement the app serves, by name: the canonical text, each fork
/// in place of its canonical statement, then the app's own.
pub fn statements() -> BTreeMap<&'static str, &'static str> {
    let mut statements: BTreeMap<_, _> = sql::all().collect();
    for (name, _, text) in SHAPE {
        statements.insert(name, text);
    }
    statements
}

/// Every migration the app runs, in order: the canonical ones, the store's
/// one format.
pub fn migrations() -> &'static [&'static str] {
    sql::MIGRATIONS
}

/// Where a wrapping read of the app's own takes the read it wraps.
#[cfg(test)]
pub const INNER: &str = ":inner";

/// Every statement as SQLite prepares it, by name: a wrapping read once per
/// read its `-- Wraps:` line names, expanded as Java's `PimdirSql.wrap`
/// expands it, named `WRAPPER(INNER)`.
#[cfg(test)]
pub fn prepared() -> Vec<(String, String)> {
    let statements = statements();
    let mut prepared = Vec::new();
    for (name, text) in &statements {
        if !text.contains(INNER) {
            prepared.push((name.to_string(), text.to_string()));
            continue;
        }
        let inners = wraps(text).unwrap_or_else(|| panic!("{name} wraps no read it names"));
        for inner in inners {
            let read = expanded(&statements, inner);
            prepared.push((
                format!("{name}({inner})"),
                uncommented(text).replacen(INNER, &read, 1),
            ));
        }
    }
    prepared
}

/// One read a `-- Wraps:` line names, `READ` or `WRAPPER(READ)` to any
/// depth, as one statement: its comments and its closing `;` taken out.
#[cfg(test)]
fn expanded(statements: &BTreeMap<&str, &str>, read: &str) -> String {
    let (name, inner) = match read.split_once('(') {
        Some((name, rest)) => (name, rest.strip_suffix(')')),
        None => (read, None),
    };
    let text = statements
        .get(name)
        .unwrap_or_else(|| panic!("{read} names {name}, which is not served"));
    let text = uncommented(text);
    let text = text.trim().trim_end_matches(';');
    match inner {
        None => text.to_owned(),
        Some(inner) => text.replacen(INNER, &expanded(statements, inner), 1),
    }
}

/// The reads a wrapping read names on its `-- Wraps: A, B(C).` line.
#[cfg(test)]
fn wraps(text: &str) -> Option<Vec<&str>> {
    let line = text
        .lines()
        .find_map(|line| line.strip_prefix("-- Wraps: "))?;
    Some(
        line.trim_end_matches('.')
            .split(", ")
            .map(str::trim)
            .collect(),
    )
}

/// A text with its `--` comments taken out, string literals kept whole.
#[cfg(test)]
fn uncommented(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    let mut quoted = false;
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if c == '\'' {
            quoted = !quoted;
        } else if !quoted && c == '-' && chars.peek() == Some(&'-') {
            for skipped in chars.by_ref() {
                if skipped == '\n' {
                    out.push('\n');
                    break;
                }
            }
            continue;
        }
        out.push(c);
    }
    out
}

#[cfg(test)]
mod tests;
