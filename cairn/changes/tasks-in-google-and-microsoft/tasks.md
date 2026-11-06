---
cairn: tasks
change: tasks-in-google-and-microsoft
---

# Tasks

- [x] io-gtasks pinned by rev (f4c4aed), io-msgraph patched to its To Do rev (01105a5)
- [x] Bridge: task lists listed beside the calendars at `<base>/tasks/<list>`, a missing scope (401, 403) listing the calendars alone; every calendar verb routed to To Do or Google Tasks on such a URL
- [x] To Do: delta rounds, `$batch` reads with the stash, writes checked by revision, Waiting and Someday as statuses (`calendar/todo.rs`, tests)
- [x] Google Tasks: rounds from the greatest `updated`, deleted and hidden included, a full round daily; `If-Match` writes; the phone-only overlay (`calendar/gtasks.rs`, `Native.overlayTask`, tests)
- [x] The device's zone on every network call (`Account.zone`)
- [x] Java: task lists take tasks, are never an event's target nor a phone calendar; the overlay on Google reads (CalendarEngine)
- [x] Task scopes beside the calendar's in the shipped grants
- [x] The task page, the capture sheet and the account settings say a Google list keeps states and categories on the phone; English and French
- [x] Build, unit tests (864 app, 618 bridge)
- [ ] Device check with the Microsoft and Google test accounts, added again (owner)
- [ ] Fold the delta into cairn/spec/tasks.md (after account-settings-page's) and log
