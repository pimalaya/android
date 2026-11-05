//! # Compile
//!
//! A [`PimdirQuery`] compiled to the shape's SQL over index.db with the
//! store attached (SEARCH §8), as io-pimdir's canonical compile does for
//! FTS5 (src/client/search/compile.rs) and on its terms: every text term
//! one FTS4 `MATCH` of its own, read once through the shape's
//! `match_objects` and `match_summaries` into `temp.term_match` before the
//! query runs; every structured term a set of placements through the
//! served statements; the boolean logic in SQL, so FTS4's basic query
//! syntax (no parentheses, no `NOT`) is never asked for more than one term.
//! A hit is `(account, seq)`, each term lifted to the hits any live
//! placement of which it holds for, and `not` the hits none does.
//!
//! No relevance (SEARCH §8.2): FTS4 ranks nothing, so the shape orders by
//! `date`, the default, or `name`.

use io_pimdir::search::{
    fold,
    query::{
        PimdirQuery, PimdirQueryDate, PimdirQueryDateRange, PimdirQueryDateUnit, PimdirQueryId,
        PimdirQueryKind, PimdirQueryPredicate, PimdirQueryPriority, PimdirQueryTerm,
        PimdirQueryText, PimdirQueryTextField,
    },
};

use crate::search::{
    shape::{self, Prepared},
    sql::Value,
};

/// The order of a search's hits (SEARCH §8.2).
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq)]
pub enum Sort {
    /// `sort_key` descending, the undated last in their own ascending.
    #[default]
    Date,
    /// The title folded as STORAGE Annex A.2 folds `fn`, ascending.
    Name,
}

/// The items each `linked:` term names, as `(kind, link id)` pairs per
/// named id (STORAGE §14.2).
pub type Ends = Vec<(PimdirQueryId, Vec<(String, String)>)>;

/// The task categories each `category:` term names: its value folded, and
/// every value a task states that folds to it, as stated.
pub type Categories = Vec<(String, Vec<String>)>;

/// A statement filling `temp.term_match`, run before the query.
pub struct Matches {
    /// The statement, its parameters numbered.
    pub sql: String,
    /// Its values, the first bound to `?1`.
    pub params: Vec<Value>,
}

/// A compiled query: the statements filling `temp.term_match` with its
/// text terms' matches, the condition a live hit meets, and its values.
pub struct Compiled {
    /// The statements reading each text term's matches, in order.
    pub matches: Vec<Matches>,
    /// The text terms read so far, each numbered by its position.
    terms: usize,
    /// The condition over `live(akey, account, seq)`.
    condition: String,
    /// The values, bound by number.
    pub params: Vec<Value>,
    /// The words a snippet looks for: the positive text terms'.
    pub words: Vec<String>,
    ends: Ends,
    categories: Categories,
    /// The instant relative dates resolve against, Unix seconds.
    now: i64,
}

impl Compiled {
    /// Compiles a query, `ends` holding the items each `linked:` term
    /// names and `categories` the task categories each `category:` term
    /// names, its relative dates resolved against `now` (Unix seconds).
    pub fn new(query: &PimdirQuery, ends: Ends, categories: Categories, now: i64) -> Self {
        let undated: Vec<&str> = query
            .terms()
            .into_iter()
            .filter_map(|(term, _)| match &term.predicate {
                PimdirQueryPredicate::Kind(kind) if !kind.is_dated() => Some(media_type(*kind)),
                _ => None,
            })
            .collect();

        let mut compiled = Self {
            matches: Vec::new(),
            terms: 0,
            condition: String::new(),
            params: Vec::new(),
            words: Vec::new(),
            ends,
            categories,
            now,
        };
        compiled.condition = compiled.node(query, false, &undated);
        compiled
    }

    /// The hits in `sort` order, `limit` of them after `offset`, `None`
    /// for every one: account, seq, sort key, whether undated, title. The
    /// `name` order folds titles as SQL cannot, so its caller sorts the
    /// whole set itself; the date order leaves the title empty.
    pub fn select(&mut self, sort: Sort, page: Option<(usize, usize)>) -> String {
        let order = match sort {
            Sort::Date => {
                "undated ASC, CASE WHEN undated = 0 THEN sort_key END DESC, \
                 CASE WHEN undated = 1 THEN sort_key END ASC, h.seq ASC, h.akey ASC"
            }
            Sort::Name => "h.seq ASC, h.akey ASC",
        };
        let (limit, offset) = page.unwrap_or((i64::MAX as usize, 0));
        let limit = self.bind(Value::Integer(limit.min(i64::MAX as usize) as i64));
        let offset = self.bind(Value::Integer(offset as i64));
        let placements = "FROM store.items i JOIN store.collections c ON c.id = i.collection \
            WHERE i.seq = h.seq AND ifnull(c.account, '') = h.akey \
            AND i.deleted = 0 AND i.retained_at IS NULL";
        let title = match sort {
            Sort::Name => format!(
                "(SELECT coalesce(ms.subject, cs.fn, es.summary, ts.summary, js.summary, fs.name, '') \
                 {TITLES} WHERE i.seq = h.seq AND ifnull(c.account, '') = h.akey AND i.deleted = 0 \
                 AND i.retained_at IS NULL ORDER BY i.collection LIMIT 1)"
            ),
            Sort::Date => String::from("''"),
        };

        format!(
            "{}
SELECT h.account, h.seq,
  (SELECT max(i.sort_key) {placements}) AS sort_key,
  (SELECT max(c.kind IN ('text/vcard', 'application/octet-stream')) {placements}) AS undated,
  {title} AS title
FROM hits h ORDER BY {order} LIMIT {limit} OFFSET {offset}",
            self.with(),
        )
    }

    /// The common table expressions every statement starts with: the live
    /// hits and the hits meeting the condition.
    fn with(&self) -> String {
        format!(
            "WITH live(akey, account, seq) AS (SELECT DISTINCT ifnull(c.account, ''), c.account, \
             i.seq FROM store.items i JOIN store.collections c ON c.id = i.collection \
             WHERE i.deleted = 0 AND i.retained_at IS NULL),\n\
             hits(akey, account, seq) AS (SELECT akey, account, seq FROM live WHERE {})",
            self.condition
        )
    }

    /// Binds one value, answering its number.
    fn bind(&mut self, value: Value) -> String {
        self.params.push(value);
        format!("?{}", self.params.len())
    }

    /// Embeds a served statement as a subquery of the query.
    fn embed(&mut self, name: &str, binds: Vec<(&str, Value)>) -> String {
        embed(name, binds, &mut self.params)
    }

    /// Reads a text term's matches into `temp.term_match` before the query
    /// runs, through `statement` (`MATCH_OBJECTS` or `MATCH_SUMMARIES`),
    /// what matched named `src`, in `column` alone when one is named,
    /// those of collections of another kind than `kind` left out.
    ///
    /// FTS4 filters a column by the `MATCH`'s left operand, never by a
    /// `column:` in its expression, which its query syntax reads before one
    /// token and never before a phrase: the statement's `table MATCH`
    /// becomes `table."column" MATCH`, the column one of the shape's.
    fn matches(
        &mut self,
        term: usize,
        statement: &str,
        src: &str,
        column: Option<&str>,
        expression: String,
        kind: Option<&str>,
    ) {
        let mut params = Vec::new();
        let mut matched = embed(statement, vec![(":match", expression.into())], &mut params);
        if let Some(column) = column {
            let table = match statement {
                "MATCH_SUMMARIES" => "summary_text",
                _ => "object_text",
            };
            let whole = format!("{table} MATCH");
            assert!(matched.contains(&whole), "{statement} matches {table}");
            matched = matched.replacen(&whole, &format!("{table}.\"{column}\" MATCH"), 1);
        }
        let kind = match kind {
            Some(kind) => format!(" WHERE c.kind = '{kind}'"),
            None => String::new(),
        };
        let sql = format!(
            "INSERT INTO temp.term_match(term, collection, akey, seq, src, rank) \
             SELECT {term}, m.collection, ifnull(c.account, ''), m.seq, '{src}', m.rank \
             FROM ({matched}) m JOIN store.collections c ON c.id = m.collection{kind}"
        );
        self.matches.push(Matches { sql, params });
    }

    /// Compiles one node to a condition over `live`.
    fn node(&mut self, node: &PimdirQuery, negated: bool, undated: &[&str]) -> String {
        match node {
            PimdirQuery::And(operands) | PimdirQuery::Or(operands) => {
                let operator = match node {
                    PimdirQuery::And(_) => " AND ",
                    _ => " OR ",
                };
                let mut conditions = Vec::new();
                for operand in operands {
                    conditions.push(self.node(operand, negated, undated));
                }
                format!("({})", conditions.join(operator))
            }
            PimdirQuery::Not(operand) => {
                format!("(NOT {})", self.node(operand, !negated, undated))
            }
            PimdirQuery::Term(term) => self.term(term, negated, undated),
        }
    }

    /// Compiles one term to a condition over `live`.
    fn term(&mut self, term: &PimdirQueryTerm, negated: bool, undated: &[&str]) -> String {
        let live = "FROM store.items i JOIN store.collections c ON c.id = i.collection \
                    WHERE i.deleted = 0 AND i.retained_at IS NULL";

        let placements = match &term.predicate {
            PimdirQueryPredicate::Text { field, text } => {
                let (column, summary) = match field {
                    PimdirQueryTextField::Any => (None, true),
                    PimdirQueryTextField::Title => (Some("title"), true),
                    PimdirQueryTextField::Body => (Some("body"), false),
                    PimdirQueryTextField::Attachment => (Some("attachment"), false),
                    PimdirQueryTextField::Place => (Some("place"), false),
                };
                return self.text(column, summary, text, negated);
            }
            PimdirQueryPredicate::Address { role, text } if text.text.contains('@') => {
                let address = text.text.to_lowercase();
                let role = Value::from(role.map(|role| role.as_str().to_string()));
                match address.strip_prefix('@') {
                    Some(domain) => self.embed(
                        "LIST_DOMAIN_PLACEMENTS",
                        vec![(":domain", domain.into()), (":role", role)],
                    ),
                    // NOTE: one page holding every placement, a `LIMIT` of
                    // -1 being SQLite's unbounded one: the term is a set,
                    // which the query's own order pages.
                    None => self.embed(
                        "LIST_ADDRESS_PLACEMENTS",
                        vec![
                            (":address", address.into()),
                            (":role", role),
                            (":limit", Value::Integer(-1)),
                        ],
                    ),
                }
            }
            PimdirQueryPredicate::Address { role, text } => {
                let column = role.map(|role| role.as_str()).unwrap_or("people");
                return self.text(Some(column), true, text, negated);
            }
            PimdirQueryPredicate::Person(text) => self.person(text),
            PimdirQueryPredicate::Thread(id) => {
                // NOTE: the thread any message of it names, so a thread id
                // still reaches its members after a join renamed it, the
                // old id being one of theirs (SEARCH §9).
                let thread = self.embed("THREAD_OF", vec![(":link_id", id.into())]);
                format!(
                    "SELECT p.collection, p.seq FROM message m \
                     JOIN placement p ON p.link_id = m.link_id \
                     JOIN store.collections c ON c.id = p.collection \
                     WHERE c.kind = 'message/rfc822' AND m.thread = ({thread})"
                )
            }
            PimdirQueryPredicate::Linked { role, id } => {
                let ends = self
                    .ends
                    .iter()
                    .find(|(named, _)| named == id)
                    .map(|(_, ends)| ends.clone())
                    .unwrap_or_default();
                let role = Value::from(role.as_ref().map(|role| role.as_str().to_string()));
                let mut linked = vec!["SELECT NULL AS collection, NULL AS seq WHERE 0".to_string()];
                for (kind, link_id) in ends {
                    linked.push(self.embed(
                        "LINKED",
                        vec![
                            (":kind", kind.into()),
                            (":link_id", link_id.into()),
                            (":role", role.clone()),
                        ],
                    ));
                }
                linked.join(" UNION ")
            }
            PimdirQueryPredicate::Flag(flag) => self.embed("FLAGGED", vec![(":flag", flag.into())]),
            PimdirQueryPredicate::Encrypted => {
                "SELECT p.collection, p.seq FROM placement p JOIN object o ON o.hash = p.hash \
                 WHERE o.status = 'encrypted'"
                    .into()
            }
            PimdirQueryPredicate::HasBody => {
                format!("SELECT i.collection, i.seq {live} AND i.object_hash IS NOT NULL")
            }
            PimdirQueryPredicate::HasAttachment => {
                // NOTE: a calendar item by the mark its body's extraction
                // recorded (SEARCH §6), the FTS rows keeping no text.
                let attached = self.embed("ATTACHED", Vec::new());
                format!(
                    "SELECT i.collection, i.seq {live} AND (EXISTS (SELECT 1 FROM store.mail_summary m \
                     WHERE m.item = i.id AND m.attachment = 1) \
                     OR (c.kind = 'text/calendar' AND (i.collection, i.seq) IN ({attached})))"
                )
            }
            PimdirQueryPredicate::HasProperty(property) => {
                let held = self.embed("HOLDING", vec![(":property", property.as_str().into())]);
                // NOTE: an EXISTS rather than a row value IN, which SQLite
                // 3.18 refuses over holding's CASE ("sub-select returns 2
                // columns - expected 1").
                format!(
                    "SELECT i.collection, i.seq {live} AND EXISTS (SELECT 1 FROM ({held}) h \
                     WHERE h.collection = i.collection AND h.seq = i.seq)"
                )
            }
            PimdirQueryPredicate::Kind(kind) => match kind {
                PimdirQueryKind::Event | PimdirQueryKind::Task | PimdirQueryKind::Journal => {
                    let table = match kind {
                        PimdirQueryKind::Event => "event_summary",
                        PimdirQueryKind::Task => "task_summary",
                        _ => "journal_summary",
                    };
                    format!(
                        "SELECT i.collection, i.seq {live} AND EXISTS (SELECT 1 FROM store.{table} s \
                         WHERE s.item = i.id)"
                    )
                }
                kind => {
                    let kind = self.bind(media_type(*kind).into());
                    format!("SELECT i.collection, i.seq {live} AND c.kind = {kind}")
                }
            },
            PimdirQueryPredicate::Status(status) => {
                let status = self.bind(status.into());
                format!(
                    "SELECT i.collection, i.seq {live} AND EXISTS (SELECT 1 FROM store.task_summary s \
                     WHERE s.item = i.id \
                     AND s.status = {status})"
                )
            }
            PimdirQueryPredicate::Priority(priority) => {
                let band = match priority {
                    PimdirQueryPriority::High => "s.priority BETWEEN 1 AND 4",
                    PimdirQueryPriority::Medium => "s.priority = 5",
                    PimdirQueryPriority::Low => "s.priority BETWEEN 6 AND 9",
                    PimdirQueryPriority::None => "ifnull(s.priority, 0) = 0",
                };
                format!(
                    "SELECT i.collection, i.seq {live} AND EXISTS (SELECT 1 FROM store.task_summary s \
                     WHERE s.item = i.id AND {band})"
                )
            }
            PimdirQueryPredicate::Category(category) => {
                // NOTE: both sides folded as STORAGE Annex A.2 folds `fn`,
                // in Rust: a task by the task_category values the caller
                // found folding to the term's, a card by the values its
                // body's extraction recorded (SEARCH §6), folded already.
                let folded = fold::value(category);
                let stated: Vec<String> = self
                    .categories
                    .iter()
                    .find(|(term, _)| *term == folded)
                    .map(|(_, values)| values.clone())
                    .unwrap_or_default();
                let carded = self.embed("CATEGORISED", vec![(":value", folded.into())]);
                let stated = match stated.is_empty() {
                    true => String::from("0"),
                    false => {
                        let values: Vec<String> = stated
                            .into_iter()
                            .map(|value| self.bind(value.into()))
                            .collect();
                        format!("s.value IN ({})", values.join(", "))
                    }
                };
                format!(
                    "SELECT i.collection, i.seq {live} AND (EXISTS (SELECT 1 FROM store.task_category s \
                     WHERE s.item = i.id \
                     AND {stated}) OR (i.collection, i.seq) IN ({carded}))"
                )
            }
            PimdirQueryPredicate::Account(account) => {
                return format!("(akey = {})", self.bind(account.into()));
            }
            PimdirQueryPredicate::Collection(collection) => {
                let collection = self.bind(collection.into());
                format!("SELECT i.collection, i.seq {live} AND i.collection = {collection}")
            }
            PimdirQueryPredicate::Date(range) => {
                let (since, until) = bounds(range, self.now);
                // NOTE: an open side is the earliest or the latest instant
                // an RFC 3339 string can spell.
                let occurring = self.embed(
                    "OCCURRING_BETWEEN",
                    vec![
                        (":start", since.clone().unwrap_or_default().into()),
                        (
                            ":end",
                            until
                                .clone()
                                .unwrap_or_else(|| "9999-12-31T23:59:59Z".into())
                                .into(),
                        ),
                    ],
                );
                let since = self.bind(since.into());
                let until = self.bind(until.into());
                let mut kinds = Vec::new();
                for kind in undated {
                    kinds.push(self.bind((*kind).into()));
                }
                let undated = match kinds.is_empty() {
                    true => String::from("NULL"),
                    false => kinds.join(", "),
                };
                format!(
                    "SELECT i.collection, i.seq {live} AND ((c.kind = 'message/rfc822' \
                     OR (c.kind = 'text/calendar' AND i.object_hash IS NULL)) \
                     AND i.sort_key != '' AND ({since} IS NULL OR i.sort_key >= {since}) \
                     AND ({until} IS NULL OR i.sort_key < {until})) \
                     UNION SELECT collection, seq FROM ({occurring}) \
                     UNION SELECT i.collection, i.seq {live} \
                     AND c.kind IN ({undated})"
                )
            }
            PimdirQueryPredicate::Changed { since, until } => {
                let since = self.bind((*since).into());
                let until = self.bind((*until).into());
                format!(
                    "SELECT i.collection, i.seq {live} AND ({since} IS NULL OR i.changed >= {since}) \
                     AND ({until} IS NULL OR i.changed <= {until})"
                )
            }
            PimdirQueryPredicate::Id(id) => {
                // NOTE: a value of digits names the item of that seq and
                // every item whose link id it is (SEARCH §8).
                let link = self.bind(id.as_str().into());
                let linked = member(&format!(
                    "SELECT ifnull(c.account, ''), i.seq {live} AND i.link_id = {link}"
                ));
                return match id.seq() {
                    Some(seq) => format!("((seq = {}) OR {linked})", self.bind(seq.into())),
                    None => linked,
                };
            }
        };

        member(&format!(
            "SELECT ifnull(c.account, ''), x.seq FROM ({placements}) x \
             JOIN store.collections c ON c.id = x.collection"
        ))
    }

    /// The placements of `person:` (SEARCH §8): the contacts whose title
    /// matches, and every item naming one of their addresses in any role.
    fn person(&mut self, text: &PimdirQueryText) -> String {
        let at = self.terms;
        self.terms += 1;
        if let Some(expression) = fts(text) {
            let (card, title) = (Some("text/vcard"), Some("title"));
            self.matches(at, "MATCH_OBJECTS", "body", title, expression.clone(), card);
            self.matches(at, "MATCH_SUMMARIES", "summary", title, expression, card);
        }
        let contacts = format!("SELECT collection, seq FROM temp.term_match WHERE term = {at}");

        format!(
            "{contacts} UNION \
             SELECT i.collection, i.seq FROM store.item_address a \
             JOIN store.items i ON i.id = a.item \
             WHERE i.deleted = 0 AND i.retained_at IS NULL AND a.address IN ( \
               SELECT e.address FROM store.item_address e \
               JOIN store.items ei ON ei.id = e.item \
               WHERE e.role = 'email' AND (ei.collection, ei.seq) IN ({contacts}))"
        )
    }

    /// Compiles a text term to its matches over bodies and, when the field
    /// exists there, over summaries, read into `temp.term_match` under the
    /// term's number, answering the condition over `live`.
    fn text(
        &mut self,
        column: Option<&str>,
        summary: bool,
        text: &PimdirQueryText,
        negated: bool,
    ) -> String {
        let at = self.terms;
        self.terms += 1;
        if let Some(expression) = fts(text) {
            self.matches(
                at,
                "MATCH_OBJECTS",
                "body",
                column,
                expression.clone(),
                None,
            );
            if summary {
                self.matches(at, "MATCH_SUMMARIES", "summary", column, expression, None);
            }
        }
        if !negated {
            self.words.push(text.text.clone());
        }
        member(&format!(
            "SELECT akey, seq FROM temp.term_match WHERE term = {at}"
        ))
    }
}

/// Embeds a served statement as a subquery: comments and its final `;`
/// gone, each named parameter numbered after those `params` holds, a name
/// absent from `binds` bound `NULL`.
fn embed(name: &str, binds: Vec<(&str, Value)>, params: &mut Vec<Value>) -> String {
    let text = shape::text(name).unwrap_or_else(|| panic!("{name} is served"));
    let Prepared { sql, names, .. } = shape::steps(text)
        .into_iter()
        .next()
        .unwrap_or_else(|| panic!("{name} holds a statement"));
    let mut numbers = Vec::new();
    for name in &names {
        let value = binds
            .iter()
            .find(|(bound, _)| bound == name)
            .map_or(Value::Null, |(_, value)| value.clone());
        params.push(value);
        numbers.push(params.len());
    }
    renumber(&sql, &numbers)
}

/// A statement's `?N` rewritten as `?numbers[N - 1]`, quotes kept whole.
fn renumber(sql: &str, numbers: &[usize]) -> String {
    let mut out = String::with_capacity(sql.len());
    let chars: Vec<char> = sql.chars().collect();
    let mut quote: Option<char> = None;
    let mut at = 0;
    while at < chars.len() {
        let c = chars[at];
        if let Some(open) = quote {
            out.push(c);
            quote = (c != open).then_some(open);
            at += 1;
            continue;
        }
        if c == '\'' || c == '"' {
            quote = Some(c);
        }
        if c != '?' || !chars.get(at + 1).is_some_and(char::is_ascii_digit) {
            out.push(c);
            at += 1;
            continue;
        }
        let start = at + 1;
        at = start;
        while at < chars.len() && chars[at].is_ascii_digit() {
            at += 1;
        }
        let number: usize = chars[start..at].iter().collect::<String>().parse().unwrap();
        out.push_str(&format!("?{}", numbers[number - 1]));
    }
    out
}

/// The condition that a hit of `live` is among `hits`, a statement
/// answering `(akey, seq)` pairs, as 1 or 0 and never `NULL`: read as the
/// test of a `CASE`, where `NULL` is false, it is one probe of the set
/// under `not` as well (io-pimdir's `member`).
fn member(hits: &str) -> String {
    format!("(CASE WHEN (akey, seq) IN ({hits}) THEN 1 ELSE 0 END)")
}

/// The titles a hit's placements carry, joined for the `name` order.
const TITLES: &str = "FROM store.items i JOIN store.collections c ON c.id = i.collection
   LEFT JOIN store.mail_summary ms ON ms.item = i.id
   LEFT JOIN store.contact_summary cs ON cs.item = i.id
   LEFT JOIN store.event_summary es ON es.item = i.id
   LEFT JOIN store.task_summary ts ON ts.item = i.id
   LEFT JOIN store.journal_summary js ON js.item = i.id
   LEFT JOIN store.file_summary fs ON fs.item = i.id";

/// Seconds in a day.
const DAY: i64 = 86_400;

/// The first instant of year 0, the earliest an RFC 3339 instant spells.
const YEAR_ZERO: i64 = -62_167_219_200;

/// The collection media type of a kind.
pub fn media_type(kind: PimdirQueryKind) -> &'static str {
    match kind {
        PimdirQueryKind::Mail => "message/rfc822",
        PimdirQueryKind::Contact => "text/vcard",
        PimdirQueryKind::Event | PimdirQueryKind::Task | PimdirQueryKind::Journal => {
            "text/calendar"
        }
        PimdirQueryKind::File => io_pimdir::summary::file::KIND,
    }
}

/// The instants a range spans (SEARCH §8.1), `[since, until)` as RFC 3339
/// in UTC, either side `None` open, as io-pimdir's canonical compile reads
/// them: a calendar point its day, month or year in UTC, `today` and
/// `yesterday` around `now` (Unix seconds), a relative amount the instant
/// that long before `now`, its span alone running to `now`.
pub fn bounds(range: &PimdirQueryDateRange, now: i64) -> (Option<String>, Option<String>) {
    let single = range.start.is_some() && range.start == range.end;
    let since = range.start.as_ref().map(|point| span(point, now).0);
    let until = range.end.as_ref().map(|point| match point {
        PimdirQueryDate::Relative { .. } if single => now,
        point => span(point, now).1,
    });
    (since.map(rfc3339), until.map(rfc3339))
}

/// The span of one date point, `[start, end)` in Unix seconds.
fn span(point: &PimdirQueryDate, now: i64) -> (i64, i64) {
    let day = |year: u16, month: u8, day: u8| {
        days_from_civil(i32::from(year), u32::from(month), u32::from(day)) * DAY
    };
    let midnight = now.div_euclid(DAY) * DAY;

    match *point {
        PimdirQueryDate::Year(year) => (day(year, 1, 1), day(year + 1, 1, 1)),
        PimdirQueryDate::Month { year, month } => {
            let next = match month {
                12 => day(year + 1, 1, 1),
                month => day(year, month + 1, 1),
            };
            (day(year, month, 1), next)
        }
        PimdirQueryDate::Day {
            year,
            month,
            day: date,
        } => {
            let start = day(year, month, date);
            (start, start + DAY)
        }
        PimdirQueryDate::Today => (midnight, midnight + DAY),
        PimdirQueryDate::Yesterday => (midnight - DAY, midnight),
        PimdirQueryDate::Relative { amount, unit } => {
            let amount = i64::from(amount);
            let instant = match unit {
                PimdirQueryDateUnit::Day => now - amount * DAY,
                PimdirQueryDateUnit::Week => now - amount * 7 * DAY,
                PimdirQueryDateUnit::Year => {
                    let (year, month, date) = civil_from_days(now.div_euclid(DAY));
                    let year = i64::from(year) - amount;
                    match i32::try_from(year) {
                        Ok(year) if year >= 0 => {
                            let date = date.min(days_in_month(year, month));
                            days_from_civil(year, month, date) * DAY + now.rem_euclid(DAY)
                        }
                        _ => YEAR_ZERO,
                    }
                }
            };
            let instant = instant.max(YEAR_ZERO);
            (instant, instant)
        }
    }
}

/// Days from the Unix epoch to a civil date, proleptic Gregorian.
fn days_from_civil(year: i32, month: u32, day: u32) -> i64 {
    let year = year - i32::from(month <= 2);
    let era = if year >= 0 { year } else { year - 399 } / 400;
    let year_of_era = (year - era * 400) as i64;
    let month = month as i64;
    let day_of_year = (153 * (month + if month > 2 { -3 } else { 9 }) + 2) / 5 + day as i64 - 1;
    let day_of_era = year_of_era * 365 + year_of_era / 4 - year_of_era / 100 + day_of_year;
    era as i64 * 146_097 + day_of_era - 719_468
}

/// The civil date a day count from the Unix epoch names.
fn civil_from_days(days: i64) -> (i32, u32, u32) {
    let days = days + 719_468;
    let era = if days >= 0 { days } else { days - 146_096 } / 146_097;
    let day_of_era = days - era * 146_097;
    let year_of_era =
        (day_of_era - day_of_era / 1460 + day_of_era / 36_524 - day_of_era / 146_096) / 365;
    let year = year_of_era + era * 400;
    let day_of_year = day_of_era - (365 * year_of_era + year_of_era / 4 - year_of_era / 100);
    let month_prime = (5 * day_of_year + 2) / 153;
    let day = (day_of_year - (153 * month_prime + 2) / 5 + 1) as u32;
    let month = (month_prime + if month_prime < 10 { 3 } else { -9 }) as u32;
    (year as i32 + i32::from(month <= 2), month, day)
}

/// The number of days in a month.
fn days_in_month(year: i32, month: u32) -> u32 {
    match month {
        1 | 3 | 5 | 7 | 8 | 10 | 12 => 31,
        4 | 6 | 9 | 11 => 30,
        2 if year.rem_euclid(4) == 0
            && (year.rem_euclid(100) != 0 || year.rem_euclid(400) == 0) =>
        {
            29
        }
        _ => 28,
    }
}

/// A Unix timestamp as the RFC 3339 instant in UTC, seconds precision.
fn rfc3339(timestamp: i64) -> String {
    let (year, month, day) = civil_from_days(timestamp.div_euclid(DAY));
    let seconds = timestamp.rem_euclid(DAY);
    format!(
        "{year:04}-{month:02}-{day:02}T{:02}:{:02}:{:02}Z",
        seconds / 3_600,
        seconds % 3_600 / 60,
        seconds % 60,
    )
}

/// The FTS4 expression of a text term (SEARCH §8.5), `None` for a text
/// holding no word, which matches nothing.
///
/// The text folded as the indexer folds it (SEARCH §5), every ASCII
/// character but a letter or a digit written as a space: `unicode61`
/// splits on each of them alike, so no token changes, and none is left to
/// read as FTS4 syntax (`"` closing the phrase, `*` after a token, `^`
/// before one, `-`, `:`, `(`). Then one quoted phrase, which FTS4 hands
/// whole to the tokenizer, its last token a prefix when the term ends with
/// the wildcard; a column is the `MATCH`'s own ([`Compiled::matches`]).
pub fn fts(text: &PimdirQueryText) -> Option<String> {
    if !text.text.chars().any(char::is_alphanumeric) {
        return None;
    }
    let folded: String = fold::text(&text.text)
        .chars()
        .map(|c| match c.is_ascii() && !c.is_ascii_alphanumeric() {
            true => ' ',
            false => c,
        })
        .collect();
    let mut words = folded.trim().to_string();
    if text.prefix {
        // NOTE: FTS4 reads a `*` as the wildcard only right after a token,
        // so a trailing character the tokenizer keeps in none goes first.
        while words.chars().last().is_some_and(|c| !c.is_alphanumeric()) {
            words.pop();
        }
    }
    if words.is_empty() {
        return None;
    }
    let star = if text.prefix { "*" } else { "" };
    Some(format!("\"{words}{star}\""))
}

#[cfg(test)]
mod tests {
    use io_pimdir::search::query::PimdirQueryText;

    use crate::search::compile::*;

    fn text(text: &str, prefix: bool) -> PimdirQueryText {
        PimdirQueryText {
            text: text.into(),
            phrase: false,
            prefix,
        }
    }

    #[test]
    fn no_character_of_a_term_is_fts4_syntax() {
        assert_eq!(fts(&text("^Report", false)).as_deref(), Some("\"report\""));
        assert_eq!(
            fts(&text("a\"b* c-d", false)).as_deref(),
            Some("\"a b  c d\"")
        );
        assert_eq!(fts(&text("Réun.", true)).as_deref(), Some("\"reun*\""));
        assert_eq!(fts(&text("--", false)), None);
    }

    #[test]
    fn a_number_is_renumbered_whole() {
        assert_eq!(
            renumber("?1 '?2' ?10 ?2", &[1, 4, 0, 0, 0, 0, 0, 0, 0, 7]),
            "?1 '?2' ?7 ?4"
        );
    }
}
