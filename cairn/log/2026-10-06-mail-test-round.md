---
cairn: log
change: mail-test-round
landed: 2026-10-06
---

# Fixes from a round of mail testing against Fastmail

**Sending.** `OnboardingFlow.finishOnboarding` persisted every domain through `SecureStore.connect(email, domain, baseUrl, credential)`, which dropped the submit endpoint the flow had built, so every mail account connected with SMTP was stored without one and the composer refused it. `connect` now takes `submitUrl`. Accounts connected before this are still missing it: set it in the account's settings, or reconnect mail.

**Flags.** The drawer's sync (`MainActivity.syncAll`) ran the contacts pass alone, so mail synced only on a pull of the mail list. It now runs contacts, mail and calendars in turn, through `mailPass()` and `calendarPass()`, which `syncMail` and `syncCalendars` share. The engine was checked first against a scripted remote, full and QRESYNC-shaped rounds, every direction of `\Seen` and `\Flagged`: it moves every change, so the gap was the trigger. Not verified against Fastmail itself.

**Sync dialog.** The title is the domain (`mail_title`, `contacts_title`, `calendar_title`); the detail line is unchanged. `SyncRunner.Observer.bookStarted` titled the dialog by book and nothing else, and is removed.

**Filter.** A mail pass skips a mailbox the filter hides, by account or name; the roster is still listed. The account axis lists only accounts covering the domain on screen, and never `local://on-this-device`, whose book is already on the collection axis.

**Deleting an account** now drops its mail collections, outbox and calendars (`MailStore.forget`, `EventStore.forget`), which stayed listed before.

**Open: message dates.** The report reads as newest-labelled rows holding the oldest messages. `MailDate` parses both the RFC 5322 IMAP and RFC 3339 JMAP shapes correctly, and the label is the sort key read back, so label and order cannot disagree; the cause needs one concrete row (its true date and its label) to pin down.

Capabilities moved: offline-store, mail, onboarding.
