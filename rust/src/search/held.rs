//! # Held
//!
//! What an index.db holds, read before the refresh folds into it or a
//! query answers from it (SEARCH §2), and its building: an index of
//! another shape, or of a later version, is refused and left as it is; an
//! older one of this shape, or one built under another tokenizer, schema
//! text or store, is rebuilt, never migrated.

use serde::Serialize;

use crate::{
    error::BridgeError,
    search::{
        shape::{self, SHAPE, TOKENIZER, VERSION},
        sql::{Batch, IndexSql, Value, int, opt_text, query, text},
    },
};

/// The draft of SEARCH.md this shape follows (SEARCH §2).
const FORMAT_DRAFT: u32 = io_pimdir::sql::search::FORMAT_DRAFT;

/// What index.db holds.
#[derive(Clone, Debug, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase", tag = "held")]
pub enum Held {
    /// No schema: the owner builds it ([`init`]).
    Empty,
    /// This shape at an earlier version, built under another schema,
    /// tokenizer or store, or folded past the store's last stamp: the
    /// owner deletes index.db and builds it again.
    Rebuild {
        /// Why, for the log.
        reason: String,
    },
    /// Another shape, a later version, or a schema with no `index_meta`:
    /// never written nor deleted, and no search answers from it.
    Refused {
        /// Its version and shape, and this shape's, for the log.
        detail: String,
    },
    /// What this shape builds, from this store.
    Current,
}

/// What index.db holds, the store attached as `store`.
pub fn held(sql: &mut impl IndexSql) -> Result<Held, BridgeError> {
    let declared = query(sql, "INDEX_OBJECTS", &[])?;
    if declared.is_empty() {
        return Ok(Held::Empty);
    }

    let columns: Vec<String> = query(sql, "INDEX_META_COLUMNS", &[])?
        .iter()
        .map(|row| text(row, 0))
        .collect::<Result<_, _>>()?;
    let has = |name: &str| columns.iter().any(|column| column == name);
    if columns.is_empty() {
        return Ok(refused(None, None));
    }
    if !has("format_version") {
        return Ok(Held::Rebuild {
            reason: "an index of a draft before search-draft-08".into(),
        });
    }
    if !has("shape") {
        return Ok(refused(None, None));
    }
    let Some(named) = query(sql, "INDEX_META_NAMED", &[])?.into_iter().next() else {
        return Ok(refused(None, None));
    };
    let version = opt_text(&named, 0)?;
    let named_shape = opt_text(&named, 1)?;
    let Some(found) = version.clone() else {
        return Ok(Held::Rebuild {
            reason: "an index naming no version".into(),
        });
    };
    if named_shape.as_deref() != Some(SHAPE) {
        return Ok(refused(version, named_shape));
    }
    match draft(&found) {
        Some(number) if number < FORMAT_DRAFT => {
            return Ok(Held::Rebuild {
                reason: format!("an index of {found}"),
            });
        }
        Some(number) if number == FORMAT_DRAFT => (),
        _ => return Ok(refused(version, named_shape)),
    }

    let mut batch = Batch::default();
    let user_version = batch.add("INDEX_USER_VERSION", &[])?;
    let meta = batch.add("LOAD_INDEX_META", &[])?;
    let cursor = batch.add("LOAD_CHANGE_CURSOR", &[])?;
    let answers = batch.run(sql)?;
    let user_version = answers.first(user_version).map(|row| int(row, 0));
    let Some(meta) = answers.first(meta) else {
        return Ok(refused(version, named_shape));
    };
    if user_version.transpose()? != Some(VERSION) || int(meta, 0)? != VERSION {
        return Ok(Held::Rebuild {
            reason: "an index at another schema version".into(),
        });
    }
    if text(meta, 1)? != TOKENIZER {
        return Ok(Held::Rebuild {
            reason: "an index under another tokenizer".into(),
        });
    }

    let declared: Vec<(String, String)> = declared
        .iter()
        .map(|row| Ok((text(row, 0)?, opt_text(row, 1)?.unwrap_or_default())))
        .collect::<Result<_, BridgeError>>()?;
    for (name, wanted) in shape::objects() {
        let same = declared
            .iter()
            .any(|(held, sql)| *held == name && shape::normalised(sql) == wanted);
        if !same {
            return Ok(Held::Rebuild {
                reason: format!("an index whose {name} is another"),
            });
        }
    }

    let Some(cursor) = answers.first(cursor) else {
        return Err("The store holds no store_meta row".into());
    };
    let (head, store_id, incarnation) = (int(cursor, 0)?, text(cursor, 2)?, int(cursor, 3)?);
    if text(meta, 7)? != store_id || int(meta, 8)? != incarnation || int(meta, 2)? > head {
        return Ok(Held::Rebuild {
            reason: "an index built from another store or another copy of it".into(),
        });
    }
    Ok(Held::Current)
}

/// The refusal of an index naming `version` and `shape`.
fn refused(version: Option<String>, named: Option<String>) -> Held {
    Held::Refused {
        detail: format!(
            "index of version {} and shape {}, this app's search-draft-{FORMAT_DRAFT} {SHAPE}",
            version.as_deref().unwrap_or("none"),
            named.as_deref().unwrap_or("none"),
        ),
    }
}

/// The number of a draft version, `search-draft-N`; `None` for a release
/// or a name of no known form, which no draft implements.
fn draft(version: &str) -> Option<u32> {
    let number = version.strip_prefix("search-draft-")?;
    match number.bytes().all(|b| b.is_ascii_digit()) {
        true => number.parse().ok(),
        false => None,
    }
}

/// Builds the schema on an empty index.db and writes `index_meta` from the
/// attached store, inside the owner's transaction (SEARCH §2).
pub fn init(sql: &mut impl IndexSql) -> Result<(), BridgeError> {
    let mut batch = Batch::default();
    for migration in shape::migrations() {
        batch.add_text(migration, &[]);
    }
    batch.add("SET_INDEX_USER_VERSION", &[])?;
    batch.add(
        "INIT_INDEX_META",
        &[
            (":version", Value::from(VERSION)),
            (":tokenizer", Value::from(TOKENIZER)),
        ],
    )?;
    batch.run(sql)?;
    Ok(())
}
