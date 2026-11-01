//! # Shape
//!
//! The index's shape, `pimalaya-android` (SEARCH §2): its schema, FTS4 on
//! SQLite 3.18 in place of the canonical FTS5 one, and every statement the
//! bridge runs on index.db, by name. The canonical search statements where
//! 3.18 runs them, the app's own text where it does not (pimdir/queries/
//! search/, each naming the digest of the canonical text it forks), and the
//! app's own beside them (pimdir/queries/index/). A store statement the
//! fold reads (`get_mail` and its siblings, `load_change_cursor`) is the
//! store's shape's, run on index.db where the store is attached as `store`.
//!
//! The text is held here and sent with every step, named parameters
//! rewritten as numbered ones ([`steps`]), so Java runs SQL it never
//! holds.

use std::{collections::BTreeMap, sync::OnceLock};

use io_pimdir::sql::search;

use crate::offline::shape::{self as store, INDEX, INDEX_MIGRATIONS};

/// The shape's name, which `init_index_meta` writes in `index_meta.shape`.
pub const SHAPE: &str = "pimalaya-android";

/// The FTS4 `tokenize` argument in force (SEARCH §5), recorded in
/// `index_meta.tokenizer`: an index recording another is rebuilt.
pub const TOKENIZER: &str = "unicode61 \"remove_diacritics=0\"";

/// The index schema version (SEARCH §2), its `PRAGMA user_version`.
pub const VERSION: i64 = 1;

/// The canonical statements the shape leaves out: the relevance order,
/// which FTS4 cannot rank (SEARCH §8.2, `match_objects_ranked`).
pub const DROPPED: &[&str] = &["MATCH_OBJECTS_RANKED"];

/// The directory of the app's own index statements under pimdir/queries/.
#[cfg(test)]
pub const APP: &str = "index";

/// Every statement the shape serves, by name: the canonical search text,
/// each fork in place of its canonical statement, io-pimdir's own, then
/// the app's own.
pub fn statements() -> &'static BTreeMap<&'static str, &'static str> {
    static STATEMENTS: OnceLock<BTreeMap<&'static str, &'static str>> = OnceLock::new();
    STATEMENTS.get_or_init(|| {
        let mut statements: BTreeMap<_, _> = search::all()
            .filter(|(name, _)| !DROPPED.contains(name))
            .collect();
        statements.insert("CREATE_TERM_MATCH", search::CREATE_TERM_MATCH);
        statements.insert("CLEAR_TERM_MATCH", search::CLEAR_TERM_MATCH);
        for (name, _, text) in INDEX {
            statements.insert(name, text);
        }
        statements
    })
}

/// Every migration of the index, in order: the shape's one schema.
pub fn migrations() -> &'static [&'static str] {
    INDEX_MIGRATIONS
}

/// The text of one statement: the index's, else the store's shape's.
pub fn text(name: &str) -> Option<&'static str> {
    static STORE: OnceLock<BTreeMap<&'static str, &'static str>> = OnceLock::new();
    statements()
        .get(name)
        .or_else(|| STORE.get_or_init(store::statements).get(name))
        .copied()
}

/// One SQL statement as Java runs it: its text, named parameters rewritten
/// as numbered ones, and the names they stand for, in number order.
#[derive(Clone, Debug, Eq, PartialEq)]
pub struct Prepared {
    /// The text, comments removed, `?1` for the first name and so on.
    pub sql: String,
    /// The parameter names, `:name`, the first one `?1`.
    pub names: Vec<String>,
    /// Whether it answers rows: a `SELECT`, a `WITH`, a `VALUES` or a
    /// `PRAGMA` reading a value.
    pub read: bool,
}

/// The statements of a text, in order: split where a `;` ends one outside
/// a quote or a comment, comments removed, each named parameter numbered
/// in order of first use.
pub fn steps(text: &str) -> Vec<Prepared> {
    let mut steps = Vec::new();
    let mut current = String::new();
    let mut quote: Option<char> = None;
    let mut chars = text.chars().peekable();
    while let Some(c) = chars.next() {
        if let Some(open) = quote {
            current.push(c);
            if c == open {
                quote = None;
            }
            continue;
        }
        match c {
            '\'' | '"' => {
                quote = Some(c);
                current.push(c);
            }
            '-' if chars.peek() == Some(&'-') => {
                for skipped in chars.by_ref() {
                    if skipped == '\n' {
                        current.push('\n');
                        break;
                    }
                }
            }
            ';' => {
                if !current.trim().is_empty() {
                    steps.push(prepare(current.trim()));
                }
                current.clear();
            }
            c => current.push(c),
        }
    }
    if !current.trim().is_empty() {
        steps.push(prepare(current.trim()));
    }
    steps
}

/// One statement, comments already removed, its names numbered.
fn prepare(statement: &str) -> Prepared {
    let mut sql = String::with_capacity(statement.len());
    let mut names: Vec<String> = Vec::new();
    let mut quote: Option<char> = None;
    let chars: Vec<char> = statement.chars().collect();
    let mut at = 0;
    while at < chars.len() {
        let c = chars[at];
        if let Some(open) = quote {
            sql.push(c);
            if c == open {
                quote = None;
            }
            at += 1;
            continue;
        }
        let named = c == ':'
            && chars
                .get(at + 1)
                .is_some_and(|next| next.is_ascii_alphabetic() || *next == '_');
        if !named {
            if c == '\'' || c == '"' {
                quote = Some(c);
            }
            sql.push(c);
            at += 1;
            continue;
        }
        let start = at;
        at += 1;
        while at < chars.len() && (chars[at].is_ascii_alphanumeric() || chars[at] == '_') {
            at += 1;
        }
        let name: String = chars[start..at].iter().collect();
        let number = match names.iter().position(|known| *known == name) {
            Some(index) => index + 1,
            None => {
                names.push(name);
                names.len()
            }
        };
        sql.push_str(&format!("?{number}"));
    }

    let head: String = sql
        .trim_start()
        .chars()
        .take_while(char::is_ascii_alphabetic)
        .collect::<String>()
        .to_ascii_uppercase();
    let read = match head.as_str() {
        "SELECT" | "WITH" | "VALUES" => true,
        "PRAGMA" => !sql.contains('='),
        _ => false,
    };
    Prepared { sql, names, read }
}

/// A statement's text as SEARCH §2 compares schema text: comments
/// removed, whitespace collapsed.
pub fn normalised(text: &str) -> String {
    let uncommented: String = steps(text)
        .into_iter()
        .map(|step| step.sql)
        .collect::<Vec<_>>()
        .join("; ");
    uncommented.split_whitespace().collect::<Vec<_>>().join(" ")
}

/// The tables and indexes the shape's migrations create, by name, with
/// their text as SQLite records it in `sqlite_master`, normalised.
pub fn objects() -> Vec<(String, String)> {
    let mut objects = Vec::new();
    for migration in migrations() {
        for step in steps(migration) {
            let words: Vec<&str> = step.sql.split_whitespace().take(5).collect();
            let name = match words.as_slice() {
                ["CREATE", "TABLE", name, ..]
                | ["CREATE", "INDEX", name, ..]
                | ["CREATE", "UNIQUE", "INDEX", name, ..]
                | ["CREATE", "VIRTUAL", "TABLE", name, ..] => name,
                _ => continue,
            };
            let name = name.split('(').next().unwrap_or(name);
            objects.push((name.to_string(), normalised(&step.sql)));
        }
    }
    objects
}

#[cfg(test)]
mod tests {
    use crate::search::shape::*;

    #[test]
    fn a_name_used_twice_is_one_number() {
        let steps = steps(
            "-- a comment: with :colon\nSELECT :a, ':b', :a_b, :a FROM t; DELETE FROM t WHERE x = :c;",
        );
        assert_eq!(steps.len(), 2);
        assert_eq!(steps[0].sql, "SELECT ?1, ':b', ?2, ?1 FROM t");
        assert_eq!(steps[0].names, [":a", ":a_b"]);
        assert!(steps[0].read);
        assert_eq!(steps[1].names, [":c"]);
        assert!(!steps[1].read);
    }

    #[test]
    fn a_pragma_setting_a_value_reads_nothing() {
        assert!(steps("PRAGMA main.user_version;")[0].read);
        assert!(!steps("PRAGMA main.user_version = 1;")[0].read);
    }

    #[test]
    fn every_object_of_the_schema_is_named() {
        let names: Vec<String> = objects().into_iter().map(|(name, _)| name).collect();
        for name in [
            "index_meta",
            "object",
            "object_text",
            "summary_text",
            "message",
        ] {
            assert!(names.contains(&name.to_string()), "{name}");
        }
    }
}
