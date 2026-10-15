---
cairn: log
change: jni-boundary
landed: 2026-10-15
---

# The JNI boundary: one account, backend facts from Rust, less JSON

Capabilities moved: none in requirements. Recorded behaviour changes: deleting a message where the account keeps no `\Deleted` and lists no trash is refused up front on Graph and Gmail as on JMAP (the bridge refused it already, unreachable today); a server host shows lowercased.

Every network native takes the account as one JSON argument, decoded once in Rust with its secret in a `SecretString`; the calendar writes' address and the submit URL travel in it, and the positional decodes go. `accountInfo` answers what each backend does (appends mail, keeps `\Answered`, keeps `\Deleted`, sessions, step kind, card fetch and push strategy, protocol, host), read once per account: Java decides by these facts, and `isGraph`, `isGoogle`, `isJmap`, the static memo and the three `writes_*` predicates (true for every remote backend) are gone. The contact type spinners are built from Rust's type order instead of restating it in string-arrays.

The phone event view (`projectEvent`, `applyEvent`) and the mail pages (`enumerateMailbox`, `nameMessages`) cross as records mirroring the Rust types, pinned by a calendar corpus of 7,057 outcomes and mail captures on four backends taken on the old code. The calendar text verbs answer `{ical}` / `{etag}` and the standard error object, ending Java's brace sniffing; the deleted-items retention instant is read with its row. Seven dead natives went (the advanced editor's five, `discover`, `listAccountCards`) with rust/src/contact/props.rs and the dead meta fetch.

Mail naming stays in Java (D7 revised): moved to Rust it would have traded little Java for new store yields and taken naming out of the JVM tests. Storage in Rust, contact fetch and push strategy, setup planning and VTIMEZONE from ical-rs remain later changes; analysis.md in the archived change holds the evidence. JVM tests 729 to 730, cargo 490 to 491.
