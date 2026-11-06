---
cairn: log
change: jmap-create-lookup-reverted
date: 2026-11-06
---

# The JMAP create lookup reverted

Reverts a-jmap-create-is-not-filed-twice (c0a7767), the same day.

Capabilities moved: offline-store (removed: A JMAP create is not filed twice).

The lookup answered a claim from the production review that a create whose answer was lost is pushed again on the next pass. It is not, wherever the server keeps the object's UID: the next pass lists the server's copy named by its identity and pimdir lands the pending create on it (CalendarMirrorTest `aCreateWhoseAnswerWasLostIsFiledOnce`). JMAP keeps the `uid`, so the `CalendarEvent/query` or `ContactCard/query` before each create cost a request for nothing. The card create is back on `Jmap`. Only Google Tasks, whose UID is Google's task id, files such a create twice (google-tasks-lost-create).
