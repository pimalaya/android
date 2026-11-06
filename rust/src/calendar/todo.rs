//! # Microsoft To Do task lists
//!
//! The task verbs of the [`Msgraph`] backend: the user's To Do lists as
//! collections taking tasks alone, their tasks as VTODO through
//! io-msgraph's `ical` projection, every line Graph has no field for
//! stashed server-side and restored on read.
//!
//! A first round lists a list's tasks by delta and keeps its delta link; a
//! later round asks what changed since. Delta answers ids and revisions
//! (`@odata.etag`) but cannot expand the stash, so the bodies are read by
//! `$batch`, 20 to a request, as Graph events are.
//!
//! A task's state lives in reserved `CATEGORIES` on the phone (pimdir GUIDE
//! §17), where To Do keeps Waiting and Someday as statuses
//! (`waitingOnOthers`, `deferred`): read, such a status becomes its
//! category; written, the category becomes the status while the task is
//! open and states no other, so To Do's own apps show it. Inbox and the
//! person's own categories stay categories, which To Do takes verbatim.
//!
//! Dates read and write in the device's zone: To Do stores a due day as a
//! midnight there.

use ical::{
    prop::{IcalProp, IcalPropName},
    value::{IcalValue, text::IcalText},
};
use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::v1::rest::users::todo::{
    lists::{
        MsgraphTodoList, MsgraphTodoWellknownListName as Wellknown,
        list::{MsgraphTodoListsList, MsgraphTodoListsListParams},
    },
    tasks::{
        MsgraphTodoTask, create::MsgraphTodoTaskCreate, delete::MsgraphTodoTaskDelete,
        delta::MsgraphTodoTasksDelta, get::MsgraphTodoTaskGet,
        ical::MSGRAPH_TODO_TASK_STASH_EXPAND, update::MsgraphTodoTaskUpdate,
    },
};
use url::Url;

use crate::{
    calendar::{
        Calendar, Event, EventDelta, EventRef, child, child_mut, cst_of,
        edit::{give_state, remove_named, take_state},
        line, scheduled, text,
    },
    client::{
        Host,
        msgraph::{Msgraph, alone, batched, graph_url},
    },
    error::{BridgeError, coroutine_error},
    offline::default_role,
};

/// The states To Do keeps as statuses, by the reserved category each
/// stands for on the phone.
const STATUSES: [(&str, &str); 2] = [("waiting", "waitingOnOthers"), ("someday", "deferred")];

/// The property io-msgraph's projection carries those statuses in.
const GRAPH_STATUS: &str = "X-MSGRAPH-STATUS";

/// The page size asked for when listing lists and tasks.
const PAGE_SIZE: u32 = 100;

impl<H: Host> Msgraph<'_, H> {
    /// Lists the user's To Do lists. Collection URLs are left empty: the
    /// caller composes them, only it knowing the account they belong to.
    pub fn list_task_lists(&mut self) -> Result<Vec<Calendar>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let params = MsgraphTodoListsListParams {
            top: Some(PAGE_SIZE),
            ..Default::default()
        };

        let coroutine =
            MsgraphTodoListsList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
        let mut page = self.run(coroutine)?;

        let mut lists = Vec::new();
        loop {
            lists.extend(page.value.into_iter().filter_map(task_list));
            let Some(next) = page.next_link else {
                break;
            };
            let coroutine =
                MsgraphTodoListsList::from_link(&auth, &next).map_err(|err| err.to_string())?;
            page = self.run(coroutine)?;
        }

        Ok(lists)
    }

    /// One round over a list's tasks: what changed since `cursor` (the
    /// last round's delta link), else every task. [`None`] when Graph
    /// refuses the link, for the caller to start over in full.
    pub fn sync_tasks(
        &mut self,
        list: &str,
        cursor: Option<&str>,
    ) -> Result<Option<EventDelta>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = match cursor {
            Some(link) => MsgraphTodoTasksDelta::from_link(&auth, link),
            None => MsgraphTodoTasksDelta::new(&auth, "me", list, None),
        }
        .map_err(|err| err.to_string())?
        .max_page_size(PAGE_SIZE);
        let mut page = match self.try_run(coroutine)? {
            Ok(page) => page,
            // NOTE: an expired or unknown sync state; a list gone answers
            // 404 on the full round too, which then errors.
            Err(err) if cursor.is_some() && matches!(err.status(), Some(400 | 404 | 410)) => {
                return Ok(None);
            }
            Err(err) => return Err(coroutine_error(&err)),
        };

        let mut changed = Vec::new();
        let mut vanished = Vec::new();
        loop {
            for row in page.value {
                if row.task.id.is_empty() {
                    continue;
                }
                if row.removed.is_some() {
                    vanished.push(row.task.id);
                } else {
                    changed.push(EventRef {
                        id: row.task.id,
                        etag: row.task.etag,
                    });
                }
            }
            if let Some(link) = page.delta_link {
                return Ok(Some(EventDelta {
                    changed,
                    bodies: Vec::new(),
                    vanished,
                    token: Some(link),
                    complete: cursor.is_none(),
                }));
            }
            let Some(next) = page.next_link else {
                return Err(format!("The To Do round of list {list} ended with no link").into());
            };
            let coroutine = MsgraphTodoTasksDelta::from_link(&auth, &next)
                .map_err(|err| err.to_string())?
                .max_page_size(PAGE_SIZE);
            page = self.run(coroutine)?;
        }
    }

    /// The iCalendar objects of the named tasks, each at its revision, read
    /// by `$batch` with their stash; a task gone is left out.
    pub fn read_tasks(
        &mut self,
        list: &str,
        ids: &[&str],
        zone: &str,
    ) -> Result<Vec<Event>, BridgeError> {
        let urls = ids
            .iter()
            .map(|id| task_url(list, id))
            .collect::<Result<Vec<_>, _>>()?;

        let mut events = Vec::with_capacity(ids.len());
        for (index, reply) in batched(|requests| self.batch(requests), &urls)?
            .into_iter()
            .enumerate()
        {
            let task = match reply {
                Some(reply) if reply.status == 404 => None,
                Some(reply) if (200..300).contains(&reply.status) => {
                    match reply.parse::<MsgraphTodoTask>() {
                        Ok(task) => Some(task),
                        Err(_) => alone(self.fetch_task(list, ids[index]))?,
                    }
                }
                _ => alone(self.fetch_task(list, ids[index]))?,
            };
            if let Some(task) = task {
                events.push(Event {
                    id: task.id.clone(),
                    etag: task.etag.clone(),
                    ical: to_app(&task.to_ical(zone))?,
                });
            }
        }

        Ok(events)
    }

    /// Files an iCalendar object as a task, answering its id and revision.
    ///
    /// Read back with its stash: one whose UID was lost would read as
    /// another task, so it is deleted and the create refused.
    pub fn create_task(
        &mut self,
        list: &str,
        ical: &str,
        zone: &str,
    ) -> Result<EventRef, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let task = MsgraphTodoTask::create_from_ical(to_graph(ical)?.as_bytes(), zone)
            .map_err(|err| err.to_string())?;
        let uid = task.stashed_uid();

        let coroutine =
            MsgraphTodoTaskCreate::new(&auth, "me", list, &task).map_err(|err| err.to_string())?;
        let created = self.run(coroutine)?;
        let stored = self.fetch_task(list, &created.id)?;

        if uid.is_some() && stored.stashed_uid() != uid {
            let coroutine = MsgraphTodoTaskDelete::new(&auth, "me", list, &created.id)
                .map_err(|err| err.to_string())?;
            if let Err(err) = self.run(coroutine) {
                log::warn!("cannot delete task {} that lost its UID: {err}", created.id);
            }
            return Err(format!(
                "To Do did not keep the UID of the task created in list {list}, so it would \
                 read back as another task"
            )
            .into());
        }

        Ok(EventRef {
            id: stored.id,
            etag: stored.etag,
        })
    }

    /// Writes a task from an iCalendar object: the fields that differ from
    /// what Graph holds, once its revision checks (a moved task answers 412,
    /// as on CalDAV). Answers the new revision.
    pub fn update_task(
        &mut self,
        list: &str,
        id: &str,
        ical: &str,
        if_match: Option<&str>,
        zone: &str,
    ) -> Result<Option<String>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let current = self.fetch_task(list, id)?;
        check_revision(id, current.etag.as_deref(), if_match)?;

        let base = current.to_ical(zone);
        let task =
            MsgraphTodoTask::update_from_ical(to_graph(ical)?.as_bytes(), base.as_bytes(), zone)
                .map_err(|err| err.to_string())?;
        let coroutine = MsgraphTodoTaskUpdate::new(&auth, "me", list, id, &task)
            .map_err(|err| err.to_string())?;
        let updated = self.run(coroutine)?;

        Ok(updated.etag)
    }

    /// Deletes a task, conditionally on `if_match`.
    pub fn delete_task(
        &mut self,
        list: &str,
        id: &str,
        if_match: Option<&str>,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        if if_match.is_some() {
            let current = self.fetch_task(list, id)?;
            check_revision(id, current.etag.as_deref(), if_match)?;
        }

        let coroutine =
            MsgraphTodoTaskDelete::new(&auth, "me", list, id).map_err(|err| err.to_string())?;
        self.run(coroutine)?;

        Ok(())
    }

    /// Reads one task with its stash.
    fn fetch_task(&mut self, list: &str, id: &str) -> Result<MsgraphTodoTask, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = MsgraphTodoTaskGet::new(
            &auth,
            "me",
            list,
            id,
            None,
            Some(MSGRAPH_TODO_TASK_STASH_EXPAND),
        )
        .map_err(|err| err.to_string())?;

        self.run(coroutine)
    }
}

/// A To Do list as a collection taking tasks alone. The flagged email list
/// is Outlook's, filled by flagging a message: listed, as it holds tasks
/// the person reads, but not writable.
fn task_list(list: MsgraphTodoList) -> Option<Calendar> {
    if list.id.is_empty() {
        return None;
    }

    Some(Calendar {
        name: list
            .display_name
            .as_option()
            .cloned()
            .unwrap_or_else(|| list.id.clone()),
        id: list.id,
        url: String::new(),
        description: None,
        color: None,
        role: default_role(false),
        writable: list.wellknown_list_name != Some(Wellknown::FlaggedEmails),
        vtodo: Some(true),
    })
}

/// The address a task is read at, its stash expanded: what a batch names.
fn task_url(list: &str, id: &str) -> Result<Url, BridgeError> {
    let mut url = graph_url(&format!("me/todo/lists/{list}/tasks/{id}"))?;
    url.query_pairs_mut()
        .append_pair("$expand", MSGRAPH_TODO_TASK_STASH_EXPAND);
    Ok(url)
}

/// Refuses a write on a task that moved since it was read.
fn check_revision(
    id: &str,
    current: Option<&str>,
    if_match: Option<&str>,
) -> Result<(), BridgeError> {
    match if_match {
        Some(expected) if current != Some(expected) => Err(BridgeError {
            message: format!("Task {id} changed on To Do since it was read"),
            status: Some(412),
        }),
        _ => Ok(()),
    }
}

/// A task as the phone reads it: the status To Do keeps for Waiting or
/// Someday as its reserved category.
pub fn to_app(ical: &str) -> Result<String, BridgeError> {
    let mut cst = cst_of(ical)?.into_static();
    let Some(index) = scheduled(&cst).first().copied() else {
        return Ok(ical.to_string());
    };
    let Some(status) =
        line(child(&cst, index), GRAPH_STATUS).map(|line| line.raw_value_str().trim().to_string())
    else {
        return Ok(ical.to_string());
    };

    let component = child_mut(&mut cst, index);
    remove_named(component, GRAPH_STATUS);
    if let Some((state, _)) = STATUSES
        .iter()
        .find(|(_, graph)| graph.eq_ignore_ascii_case(&status))
    {
        give_state(component, state);
    }

    text(&cst)
}

/// A task as To Do takes it: its Waiting or Someday state as the status,
/// while the task is open and states no other (`STATUS` absent or
/// `NEEDS-ACTION`), the first of the two taken when it carries both.
pub fn to_graph(ical: &str) -> Result<String, BridgeError> {
    let mut cst = cst_of(ical)?.into_static();
    let Some(index) = scheduled(&cst).first().copied() else {
        return Ok(ical.to_string());
    };
    let open = line(child(&cst, index), "STATUS").is_none_or(|line| {
        line.raw_value_str()
            .trim()
            .eq_ignore_ascii_case("NEEDS-ACTION")
    });

    let component = child_mut(&mut cst, index);
    remove_named(component, GRAPH_STATUS);
    if open {
        for (state, graph) in STATUSES {
            if take_state(component, state) {
                component.push(IcalProp {
                    name: IcalPropName::Unknown(GRAPH_STATUS.into()),
                    params: Vec::new(),
                    value: IcalValue::Text(IcalText(graph.into())),
                });
                break;
            }
        }
    }

    text(&cst)
}

#[cfg(test)]
mod tests {
    use super::{to_app, to_graph};

    fn todo(lines: &str) -> String {
        format!(
            "BEGIN:VCALENDAR\r\nVERSION:2.0\r\nBEGIN:VTODO\r\nUID:1\r\n{lines}END:VTODO\r\nEND:VCALENDAR\r\n"
        )
    }

    #[test]
    fn a_waiting_task_goes_to_to_do_as_its_status() {
        let written = to_graph(&todo("SUMMARY:Call\r\nCATEGORIES:waiting,@phone\r\n")).unwrap();

        assert!(
            written.contains("X-MSGRAPH-STATUS:waitingOnOthers\r\n"),
            "{written}"
        );
        assert!(written.contains("CATEGORIES:@phone\r\n"), "{written}");
        assert!(!written.to_lowercase().contains(":waiting,"), "{written}");
    }

    #[test]
    fn a_deferred_task_reads_back_as_someday() {
        let read = to_app(&todo("SUMMARY:Learn Go\r\nX-MSGRAPH-STATUS:deferred\r\n")).unwrap();

        assert!(read.contains("CATEGORIES:someday\r\n"), "{read}");
        assert!(!read.contains("X-MSGRAPH-STATUS"), "{read}");
    }

    #[test]
    fn a_state_round_trips_through_to_do() {
        let task = todo("SUMMARY:Call\r\nCATEGORIES:@phone,waiting\r\n");

        let read = to_app(&to_graph(&task).unwrap()).unwrap();

        assert!(read.contains("CATEGORIES:@phone\r\n"), "{read}");
        assert!(read.contains("CATEGORIES:waiting\r\n"), "{read}");
    }

    #[test]
    fn a_task_in_progress_keeps_its_state_as_a_category() {
        let task = todo("STATUS:IN-PROCESS\r\nCATEGORIES:someday\r\n");

        let written = to_graph(&task).unwrap();

        assert!(!written.contains("X-MSGRAPH-STATUS"), "{written}");
        assert!(written.contains("CATEGORIES:someday\r\n"), "{written}");
    }

    #[test]
    fn inbox_stays_a_category() {
        let task = todo("CATEGORIES:inbox\r\n");

        assert_eq!(to_graph(&task).unwrap(), task);
    }
}
