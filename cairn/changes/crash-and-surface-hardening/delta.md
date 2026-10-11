---
cairn: delta
change: crash-and-surface-hardening
---

Folds into `spec/mail.md` (mail, the bridge, the reader, the cache), `spec/calendar.md` (expansion, Graph, the phone mirror) and `spec/onboarding.md` (accounts, backup, the sync adapters).

## ADDED Requirements

### Requirement: No message or calendar object can overflow the bridge's stack
The bridge SHALL walk a message's MIME tree with a stack of its own, at most 64 levels, parts nested deeper left unnumbered, and SHALL derive a message nesting deeper than that from its header alone. It SHALL refuse a calendar object whose components nest deeper than 16 levels, a folded `BEGIN` counted. Every one of these SHALL hold on a 1 MiB thread, the stack of an ordinary Java thread. (spec/mail.md)

#### Scenario: Ten thousand nested parts
- GIVEN a message nesting 10 000 `multipart/mixed` parts
- WHEN a body pass stores it and the reader opens it
- THEN it is stored and read, its subject shown, and the app keeps running

### Requirement: A body whose reading kills the app is given up
A read of a body SHALL be marked on disk before it starts and cleared once it ends. A body whose read the process did not survive 3 times SHALL never be fetched or read again: its row SHALL say *Download failed*, it SHALL be owed no more, and opening it SHALL say it could not be read. (spec/mail.md)

#### Scenario: A body that crashes the app
- GIVEN a body whose reading kills the app on every attempt
- WHEN three runs try it
- THEN the fourth run skips it, its row says *Download failed*, and the app no longer dies

### Requirement: A link in a message opens only when tapped
The reader SHALL open a link only when the reader tapped it in the message itself (a gesture, in the main frame), only for `http`, `https` and `mailto`, as a browsable intent with no component, and SHALL not log it. A meta refresh, a frame or any other scheme SHALL open nothing. (spec/mail.md)

#### Scenario: A meta refresh to a tracker
- GIVEN a message carrying `<meta http-equiv="refresh" content="0;url=https://tracker.example/">`
- WHEN it is opened
- THEN no browser opens

### Requirement: A failed native call answers an error
A native call that panics or meets a JNI failure SHALL answer `{"error": "Internal error: .."}`, its panic message cut to 160 characters, never a null string; one answering a primitive SHALL log and answer its default; no native entry point SHALL let a panic unwind into the JVM. (spec/mail.md)

#### Scenario: A panic in the bridge
- GIVEN a bridge call that panics
- WHEN the Java side reads its answer
- THEN it throws a PimalayaException naming an internal error

### Requirement: A killed run leaves nothing in the cache
On every start, the app SHALL remove the folders a body download writes into and the copies an import stages that are older than an hour. An attachment's file name SHALL drop control and format characters and take at most 200 bytes in UTF-8, cut between characters, its extension kept. (spec/mail.md)

#### Scenario: A body download killed midway
- GIVEN a body download killed with messages in its cache folder
- WHEN the app starts more than an hour later
- THEN the folder is gone

### Requirement: Expansion is bounded whatever the rule
An expansion SHALL walk at most 200 000 occurrences of a series, placed or not, and stop after 2 s for the whole object; a this-and-following split SHALL count at most 200 000 occurrences of a `COUNT`. A Graph series SHALL be read for exceptions within five years either side of today, bounded or not. (spec/calendar.md)

#### Scenario: A secondly series from 1970
- GIVEN an event `RRULE:FREQ=SECONDLY` starting in 1970
- WHEN the agenda shows January 2026
- THEN it answers within the bound, placing nothing

### Requirement: The phone's calendars take bounded data
The phone mirror SHALL list a secondly or minutely series instance by instance rather than hand its rule to the provider, and SHALL carry at most 5 reminders an event, 1024 characters of title and of location and 16 384 of description, cut between characters, the store keeping the whole text. (spec/calendar.md)

#### Scenario: Fifty alarms
- GIVEN an event with 50 `VALARM`s
- WHEN it is mirrored to the phone
- THEN the phone's event carries 5 reminders

### Requirement: Nothing leaves the phone by backup or transfer
The app SHALL exclude every file from cloud backup and from a device transfer. Accounts the Keystore key cannot open for good (a failed tag, a lost key) SHALL be dropped once and the user asked to add them again; a Keystore failing for now SHALL leave the app starting with no account rather than failing. (spec/onboarding.md)

#### Scenario: A phone migrated by the maker's tool
- GIVEN accounts sealed on another phone
- WHEN the app starts
- THEN it opens, asks to add the accounts again, and starts every time after

### Requirement: The sync adapters serve their own accounts alone
The contacts sync adapter SHALL run only for one of the app's accounts and the contacts authority, the calendar one only for one of the app's accounts and the calendar authority; any other request, a missing account included, SHALL do nothing. (spec/onboarding.md)

#### Scenario: Another app asks for a sync
- GIVEN an app calling the calendar adapter for a book's account and the contacts authority
- WHEN the adapter runs
- THEN the book's contacts sync is left as it was

## MODIFIED Requirements

### Requirement: The bridge logs to logcat
The Rust bridge SHALL log to logcat under the app's tag, warnings and above only until a debug app turns its debug logs on, then debug and above for its own records, info and above for the libraries under it. Every Gmail request SHALL be logged at debug with its call, its quota units, its pacing wait and the time its answer took, and every widening of a mailbox with its floor and the time it took. A warning the app logs SHALL name an account by its address's domain alone.

#### Scenario: A throttled request
- GIVEN a debug app and a Gmail request answered 429
- WHEN the bridge waits to send it again
- THEN logcat names the status, the reason and the wait

## REMOVED Requirements

None.
