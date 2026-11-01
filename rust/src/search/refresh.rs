//! # Refresh
//!
//! io-pimdir's I/O-free [`PimdirIndexRefresh`] driven over the shape: each
//! yield answered by one round of steps on index.db, the store attached,
//! each [`Writes`](PimdirIndexRefreshYield::Writes) mapped to the shape's
//! statements in order. One run is one transaction, which the Java owner
//! opens before and commits after, so a refresh commits in chunks of at
//! most a batch of the feed and a query waits at most one of them.
//!
//! The writes key a body's text, categories and chunks on its hash and a
//! summary's text on its placement's key rather than on an id a statement
//! returns, so a run's writes go out in one round.

use std::{collections::BTreeMap, fs, path::Path};

use io_pimdir::{
    codec,
    coroutine::PimdirCoroutineState,
    hash,
    object::PimdirHash,
    placement::PimdirLinkId,
    search::refresh::{
        PimdirIndexFeedRow, PimdirIndexGone, PimdirIndexGonePlacement, PimdirIndexHorizon,
        PimdirIndexKey, PimdirIndexMessage, PimdirIndexMessageRow, PimdirIndexMeta, PimdirIndexOp,
        PimdirIndexPlacement, PimdirIndexPresence, PimdirIndexRefresh, PimdirIndexRefreshArg,
        PimdirIndexRefreshOutput, PimdirIndexRefreshYield, PimdirIndexSummary, PimdirIndexText,
        PimdirIndexThreadRow,
    },
    summary::{
        PimdirAddress, PimdirAddressRole, PimdirSummary,
        calendar::{PimdirEventSummary, PimdirJournalSummary, PimdirTaskSummary, PimdirTime},
        contact::PimdirContactSummary,
        file::PimdirFileSummary,
        mail::PimdirMailSummary,
    },
};
use serde::Serialize;

use crate::{
    error::BridgeError,
    search::sql::{Batch, Handle, IndexSql, Row, Value, int, opt_int, opt_text, query, text},
};

/// What one run did, as Java reads it.
#[derive(Clone, Copy, Debug, Default, Eq, PartialEq, Serialize)]
pub struct RunReport {
    /// The last stamp folded.
    pub cursor: i64,
    /// Bodies indexed for the first time.
    pub indexed: u64,
    /// Whether the feed is exhausted, another run folding nothing new.
    pub exhausted: bool,
    /// Feed rows folded.
    pub folded: u64,
    /// Placements dropped.
    pub removed: u64,
}

impl From<PimdirIndexRefreshOutput> for RunReport {
    fn from(output: PimdirIndexRefreshOutput) -> Self {
        Self {
            cursor: output.cursor,
            indexed: output.indexed,
            exhausted: output.exhausted,
            folded: output.folded,
            removed: output.removed,
        }
    }
}

/// One run: at most `batch` feed rows folded, its horizon around `now`
/// (Unix seconds), the bodies read from `blobs`, floating times and dates
/// read in `zone`, an IANA name (SEARCH §7), the device's, so an agenda
/// draws them at the wall time they state; `missing` folds the placements
/// an earlier pass could not read first, as a pass's first run does.
pub fn run(
    sql: &mut impl IndexSql,
    blobs: &Path,
    now: i64,
    batch: usize,
    missing: bool,
    zone: &str,
) -> Result<PimdirIndexRefreshOutput, BridgeError> {
    let mut coroutine = PimdirIndexRefresh::new(now, batch);
    coroutine.missing = missing;
    coroutine.floating_zone = zone.to_string();
    let mut arg = None;
    loop {
        match coroutine.resume(arg.take()) {
            PimdirCoroutineState::Yielded(want) => arg = Some(answer(sql, blobs, want)?),
            PimdirCoroutineState::Complete(output) => {
                return output.map_err(|err| err.to_string().into());
            }
        }
    }
}

/// Answers one yield, writing a [`Writes`] yield's ops in order.
///
/// [`Writes`]: PimdirIndexRefreshYield::Writes
fn answer(
    sql: &mut impl IndexSql,
    blobs: &Path,
    want: PimdirIndexRefreshYield,
) -> Result<PimdirIndexRefreshArg, BridgeError> {
    let arg = match want {
        PimdirIndexRefreshYield::WantsIndexState => PimdirIndexRefreshArg::IndexState(meta(sql)?),
        PimdirIndexRefreshYield::WantsReexpand {
            old,
            new,
            tzdb_version,
            floating_zone,
        } => {
            let rows = query(
                sql,
                "ITEMS_TO_REEXPAND",
                &[
                    (":old_start", old.start.into()),
                    (":old_end", old.end.into()),
                    (":new_start", new.start.into()),
                    (":new_end", new.end.into()),
                    (":tzdb_version", tzdb_version.into()),
                    (":floating_zone", floating_zone.into()),
                ],
            )?;
            PimdirIndexRefreshArg::Reexpand(rows.iter().map(key).collect::<Result<_, _>>()?)
        }
        PimdirIndexRefreshYield::WantsFeed { since, limit } => {
            let values = [
                (":since", Value::from(since)),
                (":limit", Value::from(limit as i64)),
            ];
            PimdirIndexRefreshArg::Feed(feed(sql, "LOAD_CHANGED_ITEMS", &values)?)
        }
        PimdirIndexRefreshYield::WantsMissing => {
            PimdirIndexRefreshArg::Feed(feed(sql, "LOAD_MISSING_ITEMS", &[])?)
        }
        PimdirIndexRefreshYield::WantsHeld {
            placements,
            objects,
        } => {
            let mut batch = Batch::default();
            let mut asked_placements = Vec::new();
            for placement in &placements {
                let values = [
                    (":collection", Value::from(&placement.collection)),
                    (":seq", Value::from(placement.seq)),
                ];
                asked_placements.push((placement, batch.add("LOAD_PLACEMENT", &values)?));
            }
            let mut asked_objects = Vec::new();
            for hash in &objects {
                let values = [(":hash", Value::from(&hash.0))];
                asked_objects.push((hash, batch.add("OBJECT_INDEXED", &values)?));
            }
            let answers = batch.run(sql)?;
            let mut held = Vec::new();
            for (placement, handle) in asked_placements {
                if let Some(row) = answers.first(handle) {
                    held.push(PimdirIndexPlacement {
                        collection: placement.collection.clone(),
                        seq: placement.seq,
                        link_id: PimdirLinkId(text(row, 1)?),
                        hash: opt_text(row, 2)?.map(PimdirHash),
                    });
                }
            }
            let indexed = asked_objects
                .into_iter()
                .filter(|(_, handle)| answers.first(*handle).is_some())
                .map(|(hash, _)| hash.clone())
                .collect();
            PimdirIndexRefreshArg::Held {
                placements: held,
                objects: indexed,
            }
        }
        PimdirIndexRefreshYield::WantsBodies { hashes } => {
            let mut bodies = BTreeMap::new();
            for hash in hashes {
                if let Some(body) = body(blobs, &hash) {
                    bodies.insert(hash, body);
                }
            }
            PimdirIndexRefreshArg::Bodies(bodies)
        }
        PimdirIndexRefreshYield::WantsSummaries { placements } => {
            let mut batch = Batch::default();
            let mut kinds = Vec::new();
            for key in &placements {
                kinds.push(batch.add("LOAD_KIND", &[(":collection", (&key.collection).into())])?);
            }
            let answers = batch.run(sql)?;
            let mut keyed = Vec::new();
            for (key, handle) in placements.into_iter().zip(kinds) {
                let kind = match answers.first(handle) {
                    Some(row) => opt_text(row, 0)?.unwrap_or_default(),
                    None => String::new(),
                };
                keyed.push((key, kind));
            }
            PimdirIndexRefreshArg::Summaries(summaries(sql, keyed)?)
        }
        PimdirIndexRefreshYield::WantsMessages { link_ids } => {
            PimdirIndexRefreshArg::Messages(messages(sql, link_ids)?)
        }
        PimdirIndexRefreshYield::WantsThreadRows { threads } => {
            let mut batch = Batch::default();
            let handles = threads
                .iter()
                .map(|thread| batch.add("THREAD_ROWS", &[(":thread", thread.into())]))
                .collect::<Result<Vec<_>, _>>()?;
            let answers = batch.run(sql)?;
            let mut read = Vec::new();
            for handle in handles {
                for row in answers.rows(handle) {
                    read.push(PimdirIndexThreadRow {
                        link_id: text(row, 0)?,
                        parent: opt_text(row, 1)?,
                        sort_key: opt_text(row, 2)?,
                    });
                }
            }
            PimdirIndexRefreshArg::ThreadRows(read)
        }
        PimdirIndexRefreshYield::WantsGone => {
            let mut batch = Batch::default();
            let placements = batch.add("PLACEMENTS_GONE", &[])?;
            let objects = batch.add("OBJECTS_GONE", &[])?;
            let answers = batch.run(sql)?;
            PimdirIndexRefreshArg::Gone(PimdirIndexGone {
                placements: answers
                    .rows(placements)
                    .iter()
                    .map(|row| {
                        Ok(PimdirIndexGonePlacement {
                            collection: text(row, 0)?,
                            seq: int(row, 1)?,
                            link_id: PimdirLinkId(text(row, 2)?),
                        })
                    })
                    .collect::<Result<_, BridgeError>>()?,
                objects: answers
                    .rows(objects)
                    .iter()
                    .map(|row| Ok(PimdirHash(text(row, 0)?)))
                    .collect::<Result<_, BridgeError>>()?,
            })
        }
        PimdirIndexRefreshYield::Writes(ops) => {
            let mut batch = Batch::default();
            for op in ops {
                write(&mut batch, op)?;
            }
            batch.run(sql)?;
            PimdirIndexRefreshArg::Written
        }
    };
    Ok(arg)
}

/// A placement key off a `(collection, seq)` row.
fn key(row: &Row) -> Result<PimdirIndexKey, BridgeError> {
    Ok(PimdirIndexKey {
        collection: text(row, 0)?,
        seq: int(row, 1)?,
    })
}

/// The index's `index_meta` with the store's change cursor.
fn meta(sql: &mut impl IndexSql) -> Result<PimdirIndexMeta, BridgeError> {
    let mut batch = Batch::default();
    let meta = batch.add("LOAD_INDEX_META", &[])?;
    let cursor = batch.add("LOAD_CHANGE_CURSOR", &[])?;
    let answers = batch.run(sql)?;
    let meta = answers
        .first(meta)
        .ok_or("The index holds no index_meta row")?;
    let cursor = answers
        .first(cursor)
        .ok_or("The store holds no store_meta row")?;
    let (start, end) = (text(meta, 4)?, text(meta, 5)?);
    Ok(PimdirIndexMeta {
        store_change: int(meta, 2)?,
        store_purges: int(meta, 3)?,
        horizon: (!start.is_empty() && !end.is_empty())
            .then_some(PimdirIndexHorizon { start, end }),
        tzdb_version: text(meta, 6)?,
        floating_zone: text(meta, 11)?,
        head: int(cursor, 0)?,
        purges: int(cursor, 1)?,
    })
}

/// Feed rows through a statement answering `load_changed_items`' columns,
/// each row the fold reads the summary of carrying it.
fn feed(
    sql: &mut impl IndexSql,
    name: &str,
    values: &[(&str, Value)],
) -> Result<Vec<PimdirIndexFeedRow>, BridgeError> {
    let mut feed = Vec::new();
    for row in query(sql, name, values)? {
        feed.push(PimdirIndexFeedRow {
            collection: text(&row, 0)?,
            link_id: PimdirLinkId(text(&row, 1)?),
            seq: int(&row, 2)?,
            object: opt_text(&row, 3)?.map(PimdirHash),
            flags: codec::flags_from_column(opt_text(&row, 4)?.as_deref()),
            deleted: int(&row, 5)? != 0,
            retained: opt_text(&row, 6)?.is_some(),
            kind: text(&row, 7)?,
            account: opt_text(&row, 8)?,
            changed: int(&row, 9)?,
            summary: None,
        });
    }
    let wanted: Vec<(PimdirIndexKey, String)> = feed
        .iter()
        .filter(|row| row.needs_summary())
        .map(|row| (row.key(), row.kind.clone()))
        .collect();
    if wanted.is_empty() {
        return Ok(feed);
    }
    let mut found = summaries(sql, wanted)?;
    for row in &mut feed {
        if let Some(summary) = found.remove(&row.key()) {
            row.summary = Some(summary);
        }
    }
    Ok(feed)
}

/// The summary tables a collection's kind holds rows in, in the order a
/// read tries them (`get_mail` and its siblings).
fn tables_of(kind: &str) -> &'static [Table] {
    match kind.split(';').next().unwrap_or_default().trim() {
        "message/rfc822" => &[Table::Mail],
        "text/vcard" => &[Table::Contact],
        io_pimdir::summary::file::KIND => &[Table::File],
        "text/calendar" => &[Table::Event, Table::Task, Table::Journal],
        _ => &[
            Table::Mail,
            Table::Contact,
            Table::Event,
            Table::Task,
            Table::Journal,
        ],
    }
}

/// One of the six summary tables (STORAGE §4.3).
#[derive(Clone, Copy)]
enum Table {
    Mail,
    Contact,
    Event,
    Task,
    Journal,
    File,
}

impl Table {
    /// The read of one live item with its row of this table, by its key.
    fn statement(self) -> &'static str {
        match self {
            Self::Mail => "GET_MAIL",
            Self::Contact => "GET_CONTACT",
            Self::Event => "GET_EVENT",
            Self::Task => "GET_TASK",
            Self::Journal => "GET_JOURNAL",
            Self::File => "GET_FILE",
        }
    }

    /// The summary a row of [`statement`](Self::statement) carries from
    /// column `at`, `None` when the item has none in this table.
    fn read(self, row: &Row, at: usize) -> Result<Option<PimdirSummary>, BridgeError> {
        let text = |offset: usize| opt_text(row, at + offset);
        let flag = |offset: usize| Ok::<_, BridgeError>(opt_int(row, at + offset)?.map(|v| v != 0));
        let time = |value: usize, tzid: usize, kind: usize| {
            Ok::<_, BridgeError>(text(value)?.map(|value| PimdirTime {
                value,
                tzid: text(tzid).ok().flatten(),
                date: text(kind).ok().flatten().as_deref() == Some("date"),
            }))
        };
        let summary = match self {
            Self::Mail => {
                let Some(subject) = text(1)? else {
                    return Ok(None);
                };
                PimdirSummary::Mail(PimdirMailSummary {
                    message_id: text(0)?,
                    subject,
                    sender: text(2)?,
                    sender_name: text(3)?,
                    date: text(4)?,
                    size: opt_int(row, at + 5)?.map(|size| size.max(0) as u64),
                    attachment: flag(6)?,
                    ..Default::default()
                })
            }
            Self::Contact => {
                let Some(full_name) = text(1)? else {
                    return Ok(None);
                };
                PimdirSummary::Contact(PimdirContactSummary {
                    uid: text(0)?,
                    full_name,
                    kind: text(2)?,
                    org: text(3)?,
                    emails: Vec::new(),
                })
            }
            Self::Event => {
                let Some(summary) = text(1)? else {
                    return Ok(None);
                };
                PimdirSummary::Event(PimdirEventSummary {
                    uid: text(0)?,
                    summary,
                    location: text(2)?,
                    dtstart: time(3, 4, 5)?,
                    dtend: text(6)?,
                    recurring: flag(7)?,
                    until: text(8)?,
                    organizer: None,
                    attendees: Vec::new(),
                })
            }
            Self::Task => {
                let Some(summary) = text(1)? else {
                    return Ok(None);
                };
                PimdirSummary::Task(PimdirTaskSummary {
                    uid: text(0)?,
                    summary,
                    dtstart: time(2, 3, 4)?,
                    due: time(5, 6, 7)?,
                    status: text(8)?,
                    completed: text(9)?,
                    percent: opt_int(row, at + 10)?.map(|percent| percent as _),
                    recurring: flag(11)?,
                    until: text(12)?,
                    priority: opt_int(row, at + 13)?.map(|priority| priority as _),
                    categories: Vec::new(),
                    parent: text(14)?,
                    mid: text(15)?,
                    created: text(16)?,
                    organizer: None,
                    attendees: Vec::new(),
                    contacts: Vec::new(),
                })
            }
            Self::Journal => {
                let Some(summary) = text(1)? else {
                    return Ok(None);
                };
                PimdirSummary::Journal(PimdirJournalSummary {
                    uid: text(0)?,
                    summary,
                    dtstart: time(2, 3, 4)?,
                    organizer: None,
                    attendees: Vec::new(),
                })
            }
            Self::File => {
                let Some(name) = text(0)? else {
                    return Ok(None);
                };
                PimdirSummary::File(PimdirFileSummary {
                    name,
                    media_type: text(1)?,
                    size: opt_int(row, at + 2)?.map(|size| size.max(0) as u64),
                    part: text(3)?,
                })
            }
        };
        Ok(Some(summary))
    }
}

/// What one item's summary round asked.
struct Asked {
    tables: Vec<(Table, Handle)>,
    in_reply_to: Handle,
    categories: Handle,
    addresses: Handle,
}

/// Live items' summaries as the fold reads them (SEARCH §6), by key, each
/// item of collection kind `kind`: its summary row, its lists filled and
/// its address rows, in one round.
pub fn summaries(
    sql: &mut impl IndexSql,
    keyed: Vec<(PimdirIndexKey, String)>,
) -> Result<BTreeMap<PimdirIndexKey, PimdirIndexSummary>, BridgeError> {
    let mut batch = Batch::default();
    let mut asked = Vec::new();
    for (key, kind) in &keyed {
        let values = [
            (":collection", Value::from(&key.collection)),
            (":seq", Value::from(key.seq)),
        ];
        let tables = tables_of(kind)
            .iter()
            .map(|table| Ok((*table, batch.add(table.statement(), &values)?)))
            .collect::<Result<_, BridgeError>>()?;
        asked.push(Asked {
            tables,
            in_reply_to: batch.add("ITEM_IN_REPLY_TO", &values)?,
            categories: batch.add("ITEM_CATEGORIES", &values)?,
            addresses: batch.add("ITEM_ADDRESSES", &values)?,
        });
    }
    let answers = batch.run(sql)?;

    let mut found = BTreeMap::new();
    for ((key, _), asked) in keyed.into_iter().zip(asked) {
        // NOTE: the first table holding the item's row with a summary, a
        // calendar item's component read from the one it is filed under.
        let mut held = false;
        let mut summary = None;
        for (table, handle) in asked.tables {
            let Some(row) = answers.first(handle) else {
                break;
            };
            held = true;
            if let Some(read) = table.read(row, 6)? {
                summary = Some(read);
                break;
            }
        }
        if !held {
            found.insert(key, PimdirIndexSummary::default());
            continue;
        }
        let values = |handle| -> Result<Vec<String>, BridgeError> {
            answers
                .rows(handle)
                .iter()
                .map(|row| text(row, 0))
                .collect()
        };
        match &mut summary {
            Some(PimdirSummary::Mail(mail)) => mail.in_reply_to = values(asked.in_reply_to)?,
            Some(PimdirSummary::Task(task)) => task.categories = values(asked.categories)?,
            _ => (),
        }
        let mut addresses = Vec::new();
        for row in answers.rows(asked.addresses) {
            if let Some(role) = PimdirAddressRole::parse(&text(row, 0)?) {
                addresses.push((
                    role,
                    PimdirAddress {
                        address: text(row, 2)?,
                        name: opt_text(row, 3)?,
                    },
                ));
            }
        }
        found.insert(key, PimdirIndexSummary { summary, addresses });
    }
    Ok(found)
}

/// What the index holds of message ids (SEARCH §9), in one round.
fn messages(
    sql: &mut impl IndexSql,
    link_ids: Vec<String>,
) -> Result<BTreeMap<String, PimdirIndexMessage>, BridgeError> {
    let mut batch = Batch::default();
    let mut asked = Vec::new();
    for link_id in &link_ids {
        let key = [(":link_id", Value::from(link_id))];
        asked.push([
            batch.add("LOAD_MESSAGE", &key)?,
            batch.add("MESSAGE_CHILDREN", &key)?,
            batch.add("MESSAGE_PRESENCE", &key)?,
            batch.add("MESSAGE_HASH", &key)?,
            batch.add("MESSAGE_IN_REPLY_TO", &key)?,
        ]);
    }
    let answers = batch.run(sql)?;

    let mut read = BTreeMap::new();
    for (link_id, [row, children, presence, hash, in_reply_to]) in link_ids.into_iter().zip(asked) {
        let row = match answers.first(row) {
            Some(row) => Some(PimdirIndexMessageRow {
                thread: text(row, 0)?,
                parent: opt_text(row, 1)?,
            }),
            None => None,
        };
        let children = answers
            .rows(children)
            .iter()
            .map(|row| text(row, 0))
            .collect::<Result<_, _>>()?;
        let presence = match answers.first(presence) {
            Some(row) if int(row, 0)? > 0 => Some(PimdirIndexPresence {
                sort_key: opt_text(row, 1)?.unwrap_or_default(),
                first: opt_text(row, 2)?,
                last: opt_text(row, 3)?,
            }),
            _ => None,
        };
        let hash = match answers.first(hash) {
            Some(row) => opt_text(row, 0)?.map(PimdirHash),
            None => None,
        };
        let in_reply_to = match answers.first(in_reply_to) {
            Some(row) => opt_text(row, 0)?,
            None => None,
        };
        read.insert(
            link_id,
            PimdirIndexMessage {
                row,
                children,
                presence,
                hash,
                in_reply_to,
            },
        );
    }
    Ok(read)
}

/// A body's bytes off the blob directory, `None` when it holds none or
/// its bytes fail their name (SEARCH §4): the fold indexes the placement
/// bodiless and tries again on the next pass.
pub fn body(blobs: &Path, hash: &PimdirHash) -> Option<Vec<u8>> {
    let name = hash.as_str();
    if name.len() < 2 || !name.bytes().all(|b| b.is_ascii_alphanumeric()) {
        return None;
    }
    // NOTE: a link where a shard or a body belongs is no body (STORAGE
    // §7), never read through.
    let shard = blobs.join(&name[..2]);
    let path = shard.join(name);
    for part in [&shard, &path] {
        if fs::symlink_metadata(part).ok()?.file_type().is_symlink() {
            return None;
        }
    }
    let bytes = fs::read(path).ok()?;
    match hash::hash(&bytes) == *hash {
        true => Some(bytes),
        false => {
            log::warn!("index: body {name} fails its name, read as missing");
            None
        }
    }
}

/// The values of a placement key.
fn keyed(collection: &str, seq: i64) -> [(&'static str, Value); 2] {
    [(":collection", collection.into()), (":seq", seq.into())]
}

/// A text's fields as the FTS rows take them, the summary's or the body's.
fn fields(text: &PimdirIndexText) -> Vec<(&'static str, Value)> {
    vec![
        (":title", (&text.title).into()),
        (":people", (&text.people).into()),
        (":body", (&text.body).into()),
        (":attachment", (&text.attachment).into()),
        (":place", (&text.place).into()),
        (":org", (&text.org).into()),
        (":note", (&text.note).into()),
        (":from", (&text.from).into()),
        (":to", (&text.to).into()),
        (":cc", (&text.cc).into()),
        (":bcc", (&text.bcc).into()),
        (":email", (&text.email).into()),
        (":organizer", (&text.organizer).into()),
        (":attendee", (&text.attendee).into()),
    ]
}

/// Maps one op to the shape's statements, in order.
fn write(batch: &mut Batch, op: PimdirIndexOp) -> Result<(), BridgeError> {
    match op {
        PimdirIndexOp::PutObject(object) => {
            let hash = Value::from(&object.hash.0);
            batch.add(
                "INSERT_OBJECT",
                &[
                    (":hash", hash.clone()),
                    (":status", object.status.as_str().into()),
                    (":attachment", object.attachment.into()),
                    (":tel", object.tel.into()),
                    (":adr", object.adr.into()),
                    (":bday", object.bday.into()),
                    (":photo", object.photo.into()),
                ],
            )?;
            for value in &object.categories {
                batch.add(
                    "PUT_OBJECT_CATEGORY",
                    &[(":hash", hash.clone()), (":value", value.into())],
                )?;
            }
            let mut values = fields(&object.text);
            values.push((":hash", hash.clone()));
            batch.add("PUT_OBJECT_TEXT", &values)?;
            for (ord, chunk) in object.chunks.iter().enumerate() {
                batch.add(
                    "PUT_CHUNK",
                    &[
                        (":hash", hash.clone()),
                        (":ord", (ord as i64).into()),
                        (":field", chunk.field.into()),
                        (":start", (chunk.range.start as i64).into()),
                        (":end", (chunk.range.end as i64).into()),
                    ],
                )?;
            }
        }
        PimdirIndexOp::DropObject(hash) => {
            let values = [(":hash", Value::from(&hash.0))];
            batch.add("DROP_OBJECT_TEXT", &values)?;
            batch.add("DELETE_OBJECT_ROW", &values)?;
        }
        PimdirIndexOp::PutPlacement(placement) => {
            batch.add(
                "UPSERT_PLACEMENT",
                &[
                    (":collection", (&placement.collection).into()),
                    (":seq", placement.seq.into()),
                    (":link_id", (&placement.link_id.0).into()),
                    (":hash", placement.hash.map(|hash| hash.0).into()),
                ],
            )?;
        }
        PimdirIndexOp::DropPlacement { collection, seq } => {
            batch.add("DROP_PLACEMENT_TEXT", &keyed(&collection, seq))?;
            batch.add("DELETE_PLACEMENT", &keyed(&collection, seq))?;
        }
        PimdirIndexOp::PutSummaryText {
            collection,
            seq,
            text,
        } => {
            batch.add("DROP_PLACEMENT_TEXT", &keyed(&collection, seq))?;
            let mut values = fields(&text);
            values.extend(keyed(&collection, seq));
            batch.add("PUT_PLACEMENT_TEXT", &values)?;
        }
        PimdirIndexOp::DropSummaryText { collection, seq } => {
            batch.add("DROP_PLACEMENT_TEXT", &keyed(&collection, seq))?;
        }
        PimdirIndexOp::ReplaceFlags {
            collection,
            seq,
            flags,
        } => {
            batch.add("REPLACE_FLAGS", &keyed(&collection, seq))?;
            for flag in flags {
                let mut values = keyed(&collection, seq).to_vec();
                values.push((":flag", flag.into()));
                batch.add("INSERT_FLAG", &values)?;
            }
        }
        PimdirIndexOp::ReplaceOccurrences {
            collection,
            seq,
            occurrences,
            cut,
        } => {
            batch.add("REPLACE_OCCURRENCES", &keyed(&collection, seq))?;
            let mut values = keyed(&collection, seq).to_vec();
            values.push((":cut", cut.into()));
            batch.add("SET_CUT", &values)?;
            for occurrence in occurrences {
                let mut values = keyed(&collection, seq).to_vec();
                values.push((":start", occurrence.start.into()));
                values.push((":end", occurrence.end.into()));
                batch.add("INSERT_OCCURRENCE", &values)?;
            }
        }
        PimdirIndexOp::PutMessage {
            link_id,
            thread,
            parent,
        } => {
            batch.add(
                "UPSERT_MESSAGE",
                &[
                    (":link_id", link_id.into()),
                    (":thread", thread.into()),
                    (":parent", parent.into()),
                ],
            )?;
        }
        PimdirIndexOp::PutMissing(key) => {
            batch.add("INSERT_MISSING", &keyed(&key.collection, key.seq))?;
        }
        PimdirIndexOp::DropMissing(key) => {
            batch.add("DELETE_MISSING", &keyed(&key.collection, key.seq))?;
        }
        PimdirIndexOp::PruneThread(thread) => {
            let values = [(":thread", Value::from(thread))];
            batch.add("UNPARENT_MESSAGES", &values)?;
            batch.add("PRUNE_MESSAGES", &values)?;
        }
        PimdirIndexOp::PutThread {
            id,
            first,
            last,
            count,
        } => {
            batch.add(
                "UPSERT_THREAD",
                &[
                    (":id", id.into()),
                    (":first", first.into()),
                    (":last", last.into()),
                    (":count", (count as i64).into()),
                ],
            )?;
        }
        PimdirIndexOp::DropThread(id) => {
            batch.add("DELETE_THREAD", &[(":id", id.into())])?;
        }
        PimdirIndexOp::SetCursor {
            store_change,
            store_purges,
        } => {
            batch.add(
                "SET_INDEX_CURSOR",
                &[
                    (":store_change", store_change.into()),
                    (":store_purges", store_purges.into()),
                ],
            )?;
        }
        PimdirIndexOp::SetHorizon(horizon) => {
            batch.add(
                "SET_HORIZON",
                &[
                    (":horizon_start", horizon.start.into()),
                    (":horizon_end", horizon.end.into()),
                ],
            )?;
        }
        PimdirIndexOp::SetTzdbVersion(version) => {
            batch.add("SET_TZDB_VERSION", &[(":tzdb_version", version.into())])?;
        }
        PimdirIndexOp::SetFloatingZone(zone) => {
            batch.add("SET_FLOATING_ZONE", &[(":floating_zone", zone.into())])?;
        }
    }
    Ok(())
}
