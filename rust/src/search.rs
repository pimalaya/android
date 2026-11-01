//! # Search
//!
//! The search index of pimdir SEARCH.md on the platform's SQLite: index.db
//! in the app's own shape, `pimalaya-android` ([`shape`]), FTS4 on SQLite
//! 3.18 in place of the canonical FTS5. Java's `PimdirIndexDb` owns the
//! file, its one handle and its transactions, and runs the steps the bridge
//! sends ([`sql`]); everything else is here: what index.db holds
//! ([`held`]), io-pimdir's I/O-free refresh driven over the shape
//! ([`refresh`]), and a query compiled to the shape's SQL and answered
//! ([`compile`], [`find`]).

pub mod compile;
pub mod find;
pub mod held;
pub mod refresh;
pub mod shape;
pub mod sql;

#[cfg(test)]
mod floor;
#[cfg(test)]
mod tests;
