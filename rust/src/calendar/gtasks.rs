//! # Google task lists
//!
//! The [`Gtasks`] backend: a Google account's task lists as collections
//! taking tasks alone, their tasks as VTODO through io-gtasks' `ical`
//! projection.
//!
//! The Tasks API has no sync token: a round lists the tasks updated since
//! the greatest `updated` the last one saw, deleted and hidden ones
//! included (a task completed in Google's own apps is hidden), and a first
//! round lists them all. A deleted task stays listed only for a while, so
//! a round older than a day starts over in full, its absent tasks gone.
//!
//! A task holds a title, notes, a date-only due, a done flag and a parent:
//! everything else the phone keeps (its GTD state, categories, who it
//! waits on, the mail it came from) stays on the phone alone, laid back
//! over each read ([`overlay`]). Writes go `If-Match` on the task's ETag,
//! which Google honours (412, as on CalDAV).

use ical::tree::cst::{IcalCst, IcalItem};
use io_gtasks::{
    coroutine::*,
    v1::{
        rest::{
            tasklists::{
                GtasksTaskList,
                list::{GtasksTaskListsList, GtasksTaskListsListParams},
            },
            tasks::{
                GtasksTask,
                delete::GtasksTaskDelete,
                get::GtasksTaskGet,
                insert::GtasksTaskInsert,
                list::{GtasksTasksList, GtasksTasksListParams},
                patch::GtasksTaskPatch,
            },
        },
        send::{GTASKS_API_BASE, GtasksSendError, GtasksSendOutput},
    },
};
use io_http::rfc6750::bearer::HttpAuthBearer;
use jiff::Timestamp;

use crate::{
    calendar::{
        Calendar, Event, EventDelta, EventRef, child, child_mut, cst_of, edit::remove_named, line,
        scheduled, text,
    },
    client::{Client, Host},
    error::{BridgeError, coroutine_error},
    offline::default_role,
};

/// The page size asked for when listing, the API's ceiling.
const PAGE_SIZE: u32 = 100;

/// How long a round may go on from the last full listing: a deleted task
/// leaves the listing after a while Google does not state.
const FULL_EVERY_HOURS: i64 = 24;

/// The properties of a task Google keeps (io-gtasks' projection): every
/// other one of a phone's to-do is the phone's alone.
const KEPT: [&str; 11] = [
    "UID",
    "DTSTAMP",
    "LAST-MODIFIED",
    "SUMMARY",
    "DESCRIPTION",
    "STATUS",
    "COMPLETED",
    "DUE",
    "RELATED-TO",
    "X-GOOGLE-WEB-VIEW-LINK",
    "LINK",
];

/// A Google account's Tasks API, signed in to with an OAuth 2.0 token.
pub struct Gtasks<'c, H> {
    pub client: &'c mut Client<H>,
    pub token: &'c str,
}

impl<H: Host> Gtasks<'_, H> {
    /// Lists the user's task lists, as collections taking tasks alone.
    /// Collection URLs are left empty: the caller composes them.
    pub fn list_task_lists(&mut self) -> Result<Vec<Calendar>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let mut lists = Vec::new();
        let mut page_token: Option<String> = None;

        loop {
            let params = GtasksTaskListsListParams {
                max_results: Some(PAGE_SIZE),
                page_token: page_token.as_deref(),
            };
            let coroutine =
                GtasksTaskListsList::new(&auth, &params).map_err(|err| err.to_string())?;
            let page = self.run(coroutine)?;
            lists.extend(page.items.into_iter().filter_map(task_list));
            match page.next_page_token {
                Some(next) if !next.is_empty() => page_token = Some(next),
                _ => break,
            }
        }

        Ok(lists)
    }

    /// One round over a list's tasks: those updated since `cursor`, else
    /// every one, each with its body. [`None`] when the cursor is unreadable
    /// or its full listing is over a day old, for the caller to list anew.
    ///
    /// The cursor is the greatest `updated` seen, then when the list was
    /// last listed in full, a space between.
    pub fn sync_tasks(
        &mut self,
        list: &str,
        cursor: Option<&str>,
    ) -> Result<Option<EventDelta>, BridgeError> {
        let now = self.client.host.now();
        let (since, listed) = match cursor {
            None => (None, now),
            Some(cursor) => {
                let mut parts = cursor.split(' ');
                let since = parts.next().filter(|part| !part.is_empty());
                let listed = parts.next().and_then(|part| part.parse::<Timestamp>().ok());
                match listed {
                    Some(listed) if since.is_some() && !stale(listed, now) => {
                        (since.map(str::to_string), listed)
                    }
                    _ => return Ok(None),
                }
            }
        };

        let auth = HttpAuthBearer::new(self.token);
        let mut changed = Vec::new();
        let mut bodies = Vec::new();
        let mut vanished = Vec::new();
        let mut watermark = since.clone();
        let mut page_token: Option<String> = None;

        loop {
            let params = GtasksTasksListParams {
                max_results: Some(PAGE_SIZE),
                page_token: page_token.as_deref(),
                show_completed: Some(true),
                show_deleted: true,
                show_hidden: true,
                updated_min: since.as_deref(),
                ..Default::default()
            };
            let coroutine =
                GtasksTasksList::new(&auth, list, &params).map_err(|err| err.to_string())?;
            let page = self.run(coroutine)?;

            for task in page.items {
                let Some(id) = task.id.clone() else { continue };
                if let Some(updated) = &task.updated
                    && watermark.as_deref().is_none_or(|mark| later(updated, mark))
                {
                    watermark = Some(updated.clone());
                }
                if task.deleted.as_option() == Some(&true) {
                    vanished.push(id);
                    continue;
                }
                changed.push(EventRef {
                    id: id.clone(),
                    etag: task.etag.clone(),
                });
                bodies.push(Event {
                    id,
                    etag: task.etag.clone(),
                    ical: task.to_ical(),
                });
            }

            match page.next_page_token {
                Some(next) if !next.is_empty() => page_token = Some(next),
                _ => break,
            }
        }

        let token = format!("{} {listed}", watermark.unwrap_or_default());
        Ok(Some(EventDelta {
            changed,
            bodies,
            vanished,
            token: Some(token),
            complete: cursor.is_none(),
        }))
    }

    /// The iCalendar objects of the named tasks; a task gone is left out.
    pub fn read_tasks(&mut self, list: &str, ids: &[&str]) -> Result<Vec<Event>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let mut events = Vec::with_capacity(ids.len());
        for id in ids {
            let coroutine = GtasksTaskGet::new(&auth, list, id).map_err(|err| err.to_string())?;
            match self.try_run(coroutine)? {
                Ok(task) if task.deleted.as_option() != Some(&true) => events.push(Event {
                    id: id.to_string(),
                    etag: task.etag.clone(),
                    ical: task.to_ical(),
                }),
                Ok(_) => {}
                Err(err) if err.status() == Some(404) => {}
                Err(err) => return Err(coroutine_error(&err)),
            }
        }
        Ok(events)
    }

    /// Files an iCalendar object as a task, answering the id Google gave it
    /// and its revision: an id the insert names is ignored.
    ///
    /// Nested under its parent where the object names one Google knows; a
    /// parent it does not know (a task the phone has not synced yet) files
    /// it at the top.
    pub fn create_task(&mut self, list: &str, ical: &str) -> Result<EventRef, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let task = GtasksTask::from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;
        let parent = task.parent.clone();

        let coroutine = GtasksTaskInsert::new(&auth, list, &task, parent.as_deref(), None)
            .map_err(|err| err.to_string())?;
        let created = match self.try_run(coroutine)? {
            Ok(created) => created,
            Err(err) if parent.is_some() && matches!(err.status(), Some(400 | 404)) => {
                let coroutine = GtasksTaskInsert::new(&auth, list, &task, None, None)
                    .map_err(|err| err.to_string())?;
                self.run(coroutine)?
            }
            Err(err) => return Err(coroutine_error(&err)),
        };

        Ok(EventRef {
            id: created.id.ok_or("Google answered a task with no id")?,
            etag: created.etag,
        })
    }

    /// Writes a task from an iCalendar object, `If-Match` on its ETag:
    /// title, notes, status, due and completion, what Google keeps. Answers
    /// the new revision.
    pub fn update_task(
        &mut self,
        list: &str,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
    ) -> Result<Option<String>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let task = GtasksTask::from_ical(ical.as_bytes()).map_err(|err| err.to_string())?;
        let coroutine = GtasksTaskPatch::new(&auth, list, id, &task, if_match)
            .map_err(|err| err.to_string())?;
        let updated = self.run(coroutine)?;

        Ok(updated.etag)
    }

    /// Deletes a task, `If-Match` on its ETag. Google deletes its subtasks
    /// with it.
    pub fn delete_task(
        &mut self,
        list: &str,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine =
            GtasksTaskDelete::new(&auth, list, id, if_match).map_err(|err| err.to_string())?;
        self.run(coroutine)?;
        Ok(())
    }

    /// Runs a Tasks coroutine to completion.
    fn run<C, T>(&mut self, coroutine: C) -> Result<T, BridgeError>
    where
        C: GtasksCoroutine<
                Yield = GtasksYield,
                Return = Result<GtasksSendOutput<T>, GtasksSendError>,
            >,
    {
        self.try_run(coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// [`Self::run`] keeping the API's own error, so a caller can tell its
    /// status apart; the outer error is the transport's.
    fn try_run<C, T>(&mut self, mut coroutine: C) -> Result<Result<T, GtasksSendError>, BridgeError>
    where
        C: GtasksCoroutine<
                Yield = GtasksYield,
                Return = Result<GtasksSendOutput<T>, GtasksSendError>,
            >,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                GtasksCoroutineState::Complete(Ok(output)) => return Ok(Ok(output.response)),
                GtasksCoroutineState::Complete(Err(err)) => return Ok(Err(err)),
                GtasksCoroutineState::Yielded(GtasksYield::WantsRead) => {
                    arg = Some(self.client.http_read(GTASKS_API_BASE)?);
                }
                GtasksCoroutineState::Yielded(GtasksYield::WantsWrite(bytes)) => {
                    self.client.http_write(GTASKS_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// A Google task list as a collection taking tasks alone.
fn task_list(list: GtasksTaskList) -> Option<Calendar> {
    let id = list.id.filter(|id| !id.is_empty())?;
    Some(Calendar {
        name: list
            .title
            .as_option()
            .cloned()
            .unwrap_or_else(|| id.clone()),
        id,
        url: String::new(),
        description: None,
        color: None,
        role: default_role(false),
        writable: true,
        vtodo: Some(true),
    })
}

/// Whether one RFC 3339 instant comes after another, a text compare when
/// either does not read.
fn later(left: &str, right: &str) -> bool {
    match (left.parse::<Timestamp>(), right.parse::<Timestamp>()) {
        (Ok(left), Ok(right)) => left > right,
        _ => left > right,
    }
}

/// A Google task as the phone holds it: `remote`, the task Google answered,
/// with the lines of `local`, the phone's copy, that Google has nowhere to
/// keep (its GTD state, categories, priority, who it waits on, the mail it
/// came from, when it was captured), so a read never takes them away.
///
/// A state Google folds is kept too: `IN-PROCESS` while Google says the
/// task is open, `CANCELLED` while it says done.
pub fn overlay(remote: &str, local: &str) -> Result<String, BridgeError> {
    let local_cst = cst_of(local)?.into_static();
    let Some(local_index) = scheduled(&local_cst).first().copied() else {
        return Ok(remote.to_string());
    };
    let local_todo = child(&local_cst, local_index);

    let mut cst = cst_of(remote)?.into_static();
    let Some(index) = scheduled(&cst).first().copied() else {
        return Ok(remote.to_string());
    };

    let status = |component: &IcalCst<'static>| {
        line(component, "STATUS").map(|line| line.raw_value_str().trim().to_ascii_uppercase())
    };
    let local_status = status(local_todo);
    let remote_status = status(child(&cst, index));
    let keep_status = matches!(
        (local_status.as_deref(), remote_status.as_deref()),
        (Some("IN-PROCESS"), None | Some("NEEDS-ACTION")) | (Some("CANCELLED"), Some("COMPLETED"))
    );

    let phone: Vec<IcalItem<'static>> = local_todo
        .items
        .iter()
        .filter(|item| match item {
            IcalItem::Prop(line) => {
                let name = line.name.get();
                !KEPT.iter().any(|kept| kept.eq_ignore_ascii_case(name))
                    || (keep_status && name.eq_ignore_ascii_case("STATUS"))
            }
            _ => false,
        })
        .cloned()
        .collect();
    if phone.is_empty() {
        return Ok(remote.to_string());
    }

    let component = child_mut(&mut cst, index);
    if keep_status {
        remove_named(component, "STATUS");
    }
    component.items.extend(phone);

    text(&cst)
}

/// Whether the last full listing is a day old or more.
fn stale(listed: Timestamp, now: Timestamp) -> bool {
    now.duration_since(listed).as_secs() >= FULL_EVERY_HOURS * 3600
}

#[cfg(test)]
mod tests {
    use jiff::{Timestamp, ToSpan};

    use super::{later, overlay, stale};

    fn todo(lines: &str) -> String {
        format!(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:g1\r\n{lines}END:VTODO\r\n\
             END:VCALENDAR\r\n"
        )
    }

    #[test]
    fn a_read_keeps_what_google_has_no_place_for() {
        let remote = todo("SUMMARY:Call the plumber\r\nSTATUS:NEEDS-ACTION\r\n");
        let local = todo(
            "SUMMARY:Call\r\nCATEGORIES:waiting,@phone\r\nCONTACT:Sam\r\n\
             ATTACH:mid:abc@example.net\r\nCREATED:20261101T080000Z\r\n",
        );

        let read = overlay(&remote, &local).unwrap();

        assert!(read.contains("SUMMARY:Call the plumber\r\n"), "{read}");
        assert!(!read.contains("SUMMARY:Call\r\n"), "{read}");
        assert!(read.contains("CATEGORIES:waiting,@phone\r\n"), "{read}");
        assert!(read.contains("CONTACT:Sam\r\n"), "{read}");
        assert!(read.contains("ATTACH:mid:abc@example.net\r\n"), "{read}");
        assert!(read.contains("CREATED:20261101T080000Z\r\n"), "{read}");
    }

    #[test]
    fn in_progress_stands_while_google_says_open() {
        let remote = todo("STATUS:NEEDS-ACTION\r\n");
        let local = todo("STATUS:IN-PROCESS\r\n");

        let read = overlay(&remote, &local).unwrap();

        assert!(read.contains("STATUS:IN-PROCESS\r\n"), "{read}");
        assert!(!read.contains("NEEDS-ACTION"), "{read}");
    }

    #[test]
    fn done_on_google_wins_over_in_progress() {
        let remote = todo("STATUS:COMPLETED\r\n");
        let local = todo("STATUS:IN-PROCESS\r\n");

        let read = overlay(&remote, &local).unwrap();

        assert!(read.contains("STATUS:COMPLETED\r\n"), "{read}");
        assert!(!read.contains("IN-PROCESS"), "{read}");
    }

    #[test]
    fn nothing_to_lay_back_leaves_googles_task() {
        let remote = todo("SUMMARY:Buy ink\r\n");

        assert_eq!(overlay(&remote, &todo("SUMMARY:Ink\r\n")).unwrap(), remote);
    }

    #[test]
    fn instants_compare_as_instants() {
        assert!(later(
            "2026-11-06T10:00:00.000Z",
            "2026-11-06T09:59:59.999Z"
        ));
        assert!(!later("2026-11-06T09:00:00Z", "2026-11-06T09:00:00Z"));
    }

    #[test]
    fn a_cursor_ages_out_after_a_day() {
        let listed: Timestamp = "2026-11-05T10:00:00Z".parse().unwrap();

        assert!(!stale(listed, listed + 23.hours()));
        assert!(stale(listed, listed + 24.hours()));
    }
}
