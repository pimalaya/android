//! # SQL
//!
//! What the bridge asks of index.db's owner: steps of SQL text with their
//! values, run in order on index.db with the store attached, inside the
//! transaction the owner holds, each read answering its rows. The text is
//! the shape's ([`shape`]), so the Java owner, `PimdirIndexDb`, runs
//! statements it never holds and knows nothing of the index's model.
//!
//! On the wire, one request is `{"steps": [{"sql", "args", "read"}]}` and
//! its reply `{"rows": [[row, ...], ...]}`, one entry per step, a write's
//! empty, or `{"error"}`.

use std::collections::BTreeMap;

use serde::{Deserialize, Serialize};
use serde_json::{Number, Value as Json, from_str, to_string};

use crate::{client::Host, error::BridgeError, search::shape};

/// A value bound to a step or read from a row.
#[derive(Clone, Debug, PartialEq)]
pub enum Value {
    /// SQL `NULL`.
    Null,
    /// An integer.
    Integer(i64),
    /// A floating-point number.
    Real(f64),
    /// Text.
    Text(String),
}

impl From<i64> for Value {
    fn from(value: i64) -> Self {
        Self::Integer(value)
    }
}

impl From<bool> for Value {
    fn from(value: bool) -> Self {
        Self::Integer(i64::from(value))
    }
}

impl From<&str> for Value {
    fn from(value: &str) -> Self {
        Self::Text(value.into())
    }
}

impl From<String> for Value {
    fn from(value: String) -> Self {
        Self::Text(value)
    }
}

impl From<&String> for Value {
    fn from(value: &String) -> Self {
        Self::Text(value.clone())
    }
}

impl<T: Into<Value>> From<Option<T>> for Value {
    fn from(value: Option<T>) -> Self {
        value.map_or(Self::Null, Into::into)
    }
}

impl Value {
    /// The value as JSON.
    fn json(&self) -> Json {
        match self {
            Self::Null => Json::Null,
            Self::Integer(value) => Json::from(*value),
            Self::Real(value) => Number::from_f64(*value).map_or(Json::Null, Json::Number),
            Self::Text(value) => Json::from(value.as_str()),
        }
    }

    /// A value read off JSON, a non-integral number as a real.
    fn of(json: &Json) -> Self {
        match json {
            Json::Number(number) => match number.as_i64() {
                Some(value) => Self::Integer(value),
                None => Self::Real(number.as_f64().unwrap_or_default()),
            },
            Json::String(value) => Self::Text(value.clone()),
            Json::Bool(value) => Self::Integer(i64::from(*value)),
            _ => Self::Null,
        }
    }

    /// The integer of a value, `None` for any other.
    pub fn int(&self) -> Option<i64> {
        match self {
            Self::Integer(value) => Some(*value),
            Self::Real(value) => Some(*value as i64),
            _ => None,
        }
    }
}

/// One row: its values by column.
pub type Row = Vec<Value>;

/// The text of a row's column, failing when it is not text.
pub fn text(row: &Row, column: usize) -> Result<String, BridgeError> {
    match row.get(column) {
        Some(Value::Text(value)) => Ok(value.clone()),
        other => Err(format!("Index column {column} is no text: {other:?}").into()),
    }
}

/// The text of a row's column, `None` when it is `NULL`.
pub fn opt_text(row: &Row, column: usize) -> Result<Option<String>, BridgeError> {
    match row.get(column) {
        Some(Value::Null) => Ok(None),
        _ => text(row, column).map(Some),
    }
}

/// The integer of a row's column, failing when it is not one.
pub fn int(row: &Row, column: usize) -> Result<i64, BridgeError> {
    row.get(column)
        .and_then(Value::int)
        .ok_or_else(|| format!("Index column {column} is no integer").into())
}

/// The integer of a row's column, `None` when it is `NULL`.
pub fn opt_int(row: &Row, column: usize) -> Result<Option<i64>, BridgeError> {
    match row.get(column) {
        Some(Value::Null) => Ok(None),
        _ => int(row, column).map(Some),
    }
}

/// One step: a statement's text and its values by number.
#[derive(Clone, Debug, PartialEq)]
pub struct Step {
    /// The text, its parameters numbered.
    pub sql: String,
    /// The values, the first bound to `?1`.
    pub args: Vec<Value>,
    /// Whether it answers rows.
    pub read: bool,
}

/// What runs steps on index.db: the Java owner over JNI, or a test's
/// SQLite.
pub trait IndexSql {
    /// Runs `steps` in order inside the owner's transaction, answering
    /// each step's rows, none for a write.
    fn run(&mut self, steps: Vec<Step>) -> Result<Vec<Vec<Row>>, BridgeError>;
}

/// The index's owner over a [`Host`]: each batch one `serve` upcall.
pub struct HostSql<H>(pub H);

#[derive(Serialize)]
struct StepJson<'a> {
    args: Vec<Json>,
    read: bool,
    sql: &'a str,
}

#[derive(Serialize)]
struct RequestJson<'a> {
    steps: Vec<StepJson<'a>>,
}

#[derive(Deserialize)]
struct ReplyJson {
    error: Option<String>,
    #[serde(default)]
    rows: Vec<Vec<Vec<Json>>>,
}

impl<H: Host> IndexSql for HostSql<H> {
    fn run(&mut self, steps: Vec<Step>) -> Result<Vec<Vec<Row>>, BridgeError> {
        let request = RequestJson {
            steps: steps
                .iter()
                .map(|step| StepJson {
                    args: step.args.iter().map(Value::json).collect(),
                    read: step.read,
                    sql: &step.sql,
                })
                .collect(),
        };
        let request = to_string(&request).map_err(|err| err.to_string())?;
        let reply = self.0.serve(&request)?;
        let reply: ReplyJson =
            from_str(&reply).map_err(|err| format!("Unreadable index reply: {err}"))?;
        if let Some(error) = reply.error {
            return Err(error.into());
        }
        if reply.rows.len() != steps.len() {
            return Err(format!(
                "The index answered {} steps of {}",
                reply.rows.len(),
                steps.len()
            )
            .into());
        }
        Ok(reply
            .rows
            .into_iter()
            .map(|rows| {
                rows.into_iter()
                    .map(|row| row.iter().map(Value::of).collect())
                    .collect()
            })
            .collect())
    }
}

/// Statements by name gathered into one round, each answering the rows
/// of its read step.
#[derive(Default)]
pub struct Batch {
    steps: Vec<Step>,
    /// Per statement added, the index of its read step.
    reads: Vec<Option<usize>>,
}

/// A statement added to a [`Batch`], to read its rows by.
#[derive(Clone, Copy, Debug)]
pub struct Handle(usize);

impl Batch {
    /// Adds the statement of `name`, its parameters bound from `values`,
    /// a parameter absent there bound `NULL`.
    pub fn add(&mut self, name: &str, values: &[(&str, Value)]) -> Result<Handle, BridgeError> {
        let text = shape::text(name).ok_or_else(|| format!("No index statement {name}"))?;
        Ok(self.add_text(text, values))
    }

    /// Adds a statement's text, its parameters bound from `values`.
    pub fn add_text(&mut self, text: &str, values: &[(&str, Value)]) -> Handle {
        let values: BTreeMap<&str, &Value> =
            values.iter().map(|(name, value)| (*name, value)).collect();
        let mut read = None;
        for step in shape::steps(text) {
            if step.read {
                read = Some(self.steps.len());
            }
            let args = step
                .names
                .iter()
                .map(|name| {
                    values
                        .get(name.as_str())
                        .map_or(Value::Null, |v| (*v).clone())
                })
                .collect();
            self.steps.push(Step {
                sql: step.sql,
                args,
                read: step.read,
            });
        }
        self.reads.push(read);
        Handle(self.reads.len() - 1)
    }

    /// Adds a statement already numbered, with its values.
    pub fn add_numbered(&mut self, sql: String, args: Vec<Value>, read: bool) -> Handle {
        self.reads.push(read.then_some(self.steps.len()));
        self.steps.push(Step { sql, args, read });
        Handle(self.reads.len() - 1)
    }

    /// Runs every step in one round.
    pub fn run(self, sql: &mut impl IndexSql) -> Result<Answers, BridgeError> {
        if self.steps.is_empty() {
            return Ok(Answers {
                rows: Vec::new(),
                reads: self.reads,
            });
        }
        let rows = sql.run(self.steps)?;
        Ok(Answers {
            rows,
            reads: self.reads,
        })
    }
}

/// The rows a [`Batch`] answered.
pub struct Answers {
    rows: Vec<Vec<Row>>,
    reads: Vec<Option<usize>>,
}

impl Answers {
    /// The rows of a statement's read step, none for a write.
    pub fn rows(&self, handle: Handle) -> &[Row] {
        match self.reads.get(handle.0).copied().flatten() {
            Some(step) => self.rows.get(step).map_or(&[], Vec::as_slice),
            None => &[],
        }
    }

    /// The first row of a statement's read step, if any.
    pub fn first(&self, handle: Handle) -> Option<&Row> {
        self.rows(handle).first()
    }
}

/// Runs one statement by name, answering its read's rows.
pub fn query(
    sql: &mut impl IndexSql,
    name: &str,
    values: &[(&str, Value)],
) -> Result<Vec<Row>, BridgeError> {
    let mut batch = Batch::default();
    let handle = batch.add(name, values)?;
    let answers = batch.run(sql)?;
    Ok(answers.rows(handle).to_vec())
}
