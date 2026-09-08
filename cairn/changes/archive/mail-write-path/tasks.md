---
cairn: tasks
change: mail-write-path
---

- [x] Read the server's capabilities at authentication and keep them on the session
- [x] IMAP: `UID STORE` one flag on one message
- [x] IMAP: resolve the `\Trash` mailbox from LIST, `UID MOVE` into it
- [x] IMAP: mark `\Deleted` where the server names no trash
- [x] JMAP: `Email/set` patching one keyword
- [x] JMAP: `Email/set` moving one message into the trash mailbox
- [x] JNI: `setMessageFlag` and `deleteMessage`
- [x] Store: apply the flag locally, and retire a deleted message
- [x] Reader: the action row, and marking read on open
- [x] Tests: the flag round trip and the trash resolution
- [x] Fold the delta and log
