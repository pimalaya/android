---
cairn: change
id: crash-and-surface-hardening
status: active
created: 2026-10-11
---

# Crash and surface hardening

## Why

The 2026-10-11 audit (S1, S3, S4) found ways for a sender, a server or another app to crash the app or reach past it:

- A message nesting multipart parts a few thousand levels deep overflows a Java thread's 1 MiB stack in the bridge (`mail::number`) and in io-pimdir's derivation: a SIGSEGV no panic guard catches, every body pass dying again on the body still owed.
- A `FREQ=SECONDLY` series from 1970 walks billions of occurrences before the agenda's window (`calendar::expand` counted placed ones only); a huge `COUNT` does the same on a this-and-following edit; a Graph series from 1601 to 9000 is read in 1481 instances requests.
- Server calendar data reaches the phone's provider unbounded: a sub-hourly rule for it to expand, hundreds of alarms, megabyte descriptions over one binder transaction.
- A panic or a JNI failure answers a null string the Java side dereferences, and four exports run outside any panic guard.
- The message reader opens any navigation a message starts on its own (a meta refresh as a read receipt, another app's deep link from an iframe), with no browsable category, and logs the link.
- A device transfer copies the store in clear and the accounts sealed under a Keystore key it cannot carry, and the app then fails every start.
- On unpatched Android 8 to 10, any app can ask the exported sync adapters for a sync of any account and authority, crashing the app or turning a book's contacts sync off.
- A killed run leaves message bodies and imported documents in the cache; an attachment name can spoof its extension (U+202E) or exceed the file system's 255 bytes.
- A release build forwards the bridge's debug logs and names addresses in warnings.

## What

- Bridge: the MIME walk is iterative, at most 64 levels; a mail nesting deeper is derived from its header alone (`mail::derivable`) wherever the bridge calls io-pimdir. A read that kills the app (any native crash) is marked before it starts (`MailPoison`); after 3 such deaths the body is never fetched or read again and its row says *Download failed*.
- Calendar: every walked occurrence counts against 200 000 and an expansion stops after 2 s; a split counts at most 200 000 of a `COUNT`; a Graph series is read within five years either side of today, bounded or not; the phone mirror lists a secondly or minutely series rather than handing its rule to the provider, and carries at most 5 reminders, 1024 characters of title and location and 16384 of description.
- JNI: an `ErrorReply` policy answers `{"error": "Internal error: .."}` for a panic or a JNI failure; every export goes through it.
- Reader: only a link tapped in the message itself opens, `http`, `https` or `mailto`, browsable, never logged.
- Backup and transfer: data extraction rules exclude everything from cloud backup and device transfer; accounts the Keystore key cannot open (a failed tag) are dropped once and the user asked to add them again; a Keystore failing for now leaves the app usable.
- Sync adapters: each serves its own account type and authority alone.
- Cache: a start removes `mail-sources` folders and import copies older than an hour; attachment names are cut by UTF-8 bytes (200) between characters, control and format characters dropped.
- Logs: the bridge logs warnings and above until a debug app turns debug logs on (`Native.debugLogs`); warnings name an address by its domain.

## Edits outside this change's files

- rust/src/ffi/oauth.rs: the policy swap only (one import line, the `resolve` calls), beside the network-trust change.
- rust/src/client/gmail.rs: `derivable` before Gmail's derivation.
- RemotePass, SyncRunner, AccountSettings, MessageCompose, MailBodies: one log line each, the address redacted.
- PimalayaClient: a null reply read as an internal error.

## Out of scope

- io-pimdir's own recursion, ical-rs's component depth, io-imap and io-webdav: fixed in their repositories; the bridge guard covers io-pimdir until it is bumped.
- The OAuth loopback listener (S1 L2): OauthFlow belongs to the network-trust change.
