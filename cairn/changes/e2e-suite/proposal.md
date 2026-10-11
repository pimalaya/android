---
cairn: change
id: e2e-suite
status: active
created: 2026-10-10
---

# A headless end-to-end suite against real servers, the release gate for logic

## Why

The app's behaviour lives in the Rust bridge and the Java engines (`MailEngine`, `OfflineEngine`, `CalendarEngine`, `PimdirEngine`, `RemotePass`, `SyncRunner`, the pimdir store over JNI). The unit suite already runs them under Robolectric on the real `libpimalaya.so`, but against recorded or faked servers: a provider's own behaviour (Gmail's labels and its rewritten `Message-ID`, Exchange's folders, Fastmail's JMAP, a server's MOVE, UIDPLUS or SMTP refusal, Google's CardDAV rewriting what it is sent) is only ever met on the device, by hand. Every scenario of `cairn/spec/` that reaches a server is a test case nobody runs before a release.

This change builds a suite that drives the engines headlessly, with no UI, against the Pimalaya test accounts: real sync passes, steps and staged mutations, then assertions on what the store and the server hold. Phase 1 is the harness and mail; phase 2 is contacts and calendars on the same harness.

Out of scope, staying on the human device checklist: the OAuth browser consent, permission prompts, the phone mirror as other apps see it, notifications, reminders, the body step and fill the open app runs behind the list (`MainActivity.fillMail`), the look of the UI.

## What

### Where it lives

A source directory of its own, `android/app/src/e2eTest/java`, compiled with the unit tests (it needs the package-private engines, so its classes sit in `org.pimalaya`, every one named `E2e*`) and run by a task of its own, `:app:e2eTest`. The unit tasks exclude `org/pimalaya/E2e*`, so `:app:testDebugUnitTest` and CI never run it; `e2eTest` runs nothing else. A test whose target was not asked for, or whose credentials are missing, is skipped (JUnit assumption), so a stray run does no network at all.

```sh
nix develop --command gradle -p android :app:e2eTest -Pe2e.targets=google,microsoft,fastmail --console=plain
```

`e2e.targets` takes providers (`google`, `microsoft`, `fastmail`), single targets (`google-imap`) or `all`; `e2e.only` narrows to test classes by name; `e2e.patience` sets the eventual-consistency bound in seconds (default 120); `e2e.log` streams the app's log into the test output.

### Targets

A target is a provider account reached over one protocol for one domain: the address, how its credential is obtained, which fixture prepares and cleans its server, and what it supports (keywords the app does not model, a separate SMTP submission). A test class runs over its domain's targets.

| Target | Domain | Read over | Credential | Fixture |
|---|---|---|---|---|
| `google-gmail` | mail | Gmail API (`google://`), sends with `messages.send` | service-account token delegated to google@pimalaya.org | Gmail API |
| `google-imap` | mail | imap.gmail.com, smtp.gmail.com, XOAUTH2 | same | Gmail API |
| `microsoft-imap` | mail | outlook.office365.com, smtp.office365.com STARTTLS, XOAUTH2 | app-only client-credentials token for microsoft@pimalaya.onmicrosoft.com | IMAP |
| `fastmail-jmap` | mail | JMAP (`jmap://`) | API token of pimalaya@fastmail.org | JMAP |
| `fastmail-imap` | mail | imap.fastmail.com, smtp.fastmail.com | app password | IMAP |
| `google-carddav` | contacts | Google CardDAV | service-account token | People API |
| `google-gcal` | calendars | Calendar API (`google://`) | service-account token | Calendar API |
| `google-caldav` | calendars | Google CalDAV | service-account token | Calendar API |
| `fastmail-carddav`, `fastmail-caldav` | contacts, calendars | Fastmail CardDAV, CalDAV | DAV app password | WebDAV (extended MKCOL, MKCALENDAR, PUT) |
| `fastmail-jmap-contacts`, `fastmail-jmap-calendar` | contacts, calendars | JMAP | API token | none: the token's JMAP session offers mail and submission alone |

Microsoft Graph (`msgraph://`) is not a target yet: the bridge addresses `/me`, which an app-only token cannot, and the consent flow that mints a user token is out of scope. It drops in once a delegated refresh token is supplied (`MICROSOFT_GRAPH_REFRESH_TOKEN`), as a Stalwart container will. The Fastmail JMAP contacts and calendar targets skip, saying so: the test token's session lists no contacts or calendars capability.

An account is built the way the connection flow builds it: discovery (`PimalayaClient.searchAll`) for the address, the domain's endpoint and mail's submission derived by the flow's own functions, the user put in a mail URL for a token credential, stored in an in-memory `SecureStore` (the Android keystore is a device's). Google's CalDAV home lists the primary calendar alone, so `google-caldav` works in the primary calendar, writing only events carrying the run's tag.

### Credentials

Read at run time from the environment, never printed, never written to the repository or the report:

| Variable | For |
|---|---|
| `GOOGLE_SERVICE_ACCOUNT_KEY_FILE` (or `GOOGLE_SERVICE_ACCOUNT_KEY`, inline) | Google targets: the key of the service account with domain-wide delegation |
| `GOOGLE_SERVICE_ACCOUNT_SUBJECT` | the delegated user, google@pimalaya.org by default |
| `MICROSOFT_TENANT_ID`, `MICROSOFT_CLIENT_ID`, `MICROSOFT_CLIENT_SECRET` | Microsoft targets: the test app's client-credentials grant (IMAP.AccessAsApp, SMTP.SendAsApp); the secret is `pass show pimalaya/test/microsoft` |
| `MICROSOFT_USER` | the mailbox, microsoft@pimalaya.onmicrosoft.com by default |
| `FASTMAIL_JMAP_TOKEN` | `fastmail-jmap` |
| `FASTMAIL_APP_PASSWORD`, `FASTMAIL_USER` | `fastmail-imap`, pimalaya@fastmail.org by default |
| `FASTMAIL_DAV_PASSWORD` | `fastmail-carddav`, `fastmail-caldav`: an app password with the calendars and contacts scope (`pass show pimalaya/fastmail-webdav`) |

The tokens the suite mints (RFC 7523 JWT bearer for Google, with the scope its domain needs; RFC 6749 client credentials for Microsoft) are not renewable, so an account carries no refresh token; a token is minted again when the cached one has less than ten minutes left.

### A test

Each test runs on a fresh store per simulated device: an isolated `Context` (its own files directory, databases and preferences) holding the pimdir store, the mail, event and card stores, an in-memory account roster and a headless `RemotePass` and `SyncRunner`, wired as `BackgroundJob` wires them. A device drives them as the open app does: connecting a mail account runs its first sync, one step (`RemotePass.stepMail`); Load more is a step, of one mailbox for a view filtered to it; a sync is `RemotePass.mailPass` listing only on Wi-Fi; contacts and calendars pass as their tabs' pulls do. Every gate reads the app's own `MainActivity.wifi(Context)`, never bypassed: the suite puts the devices on a network through Robolectric's connectivity (Wi-Fi by default, mobile data or none where a scenario asks). A test stages what the reader, the composer, the contact form or the event page stages, through the same calls they make, then runs a pass. What reached the server is read back by an *observer* device, a second fresh store syncing the same account, and through the fixture where the app cannot observe it (a keyword it does not model, the trash a step leaves out, a deleted item).

### Isolation and cleanup

Every test works in scratch collections it creates, named `pimalaya-e2e-<run>-<n>` (a mailbox, a Gmail label, a calendar), with items whose subject or name carries the run's tag and messages whose `Message-ID` is under `pimalaya-e2e.invalid`. Where the provider has one fixed collection (Google's contacts, Google's CalDAV primary calendar), only tagged items are written there. Whatever happens, the test then deletes its scratch collections and sweeps every item carrying its tag (Gmail through the API, since a removed label leaves its mail in All Mail; IMAP through `UID EXPUNGE` alone, never a mailbox-wide `EXPUNGE`; JMAP by `Email/set` destroy; contacts and events by the provider's API). The first test of a run on a target also sweeps what a run older than two hours left. Nothing without the prefix or the tag is ever written, and mail is sent only to the account itself.

### Timing

Providers settle late: Gmail files a sent copy and delivers to oneself after a few seconds, Exchange delivers in seconds to a minute, Google's contact list lags its writes, a Google calendar's delete completes asynchronously. A server-side assertion is therefore *eventual*: it is checked again, syncing again where a pass is owed, until it holds or the patience runs out, backing off from 2 s to 15 s, and the failure names the state it saw last.

### Report

Every test names the spec scenarios it covers (`@E2eScenario`). The run writes `android/app/build/reports/e2e/matrix.md`: one row per scenario and one per test, one column per target asked, each cell passed, failed (with the first line of the failure) or skipped (with the reason: not asked for, no credential, not supported by the target).

### Seams added to the app

Driving the engines headlessly needs the logic the reader and the composer run to be callable without a view. Each is a move of existing code out of a UI class into a plain one, with the UI calling it, not a test hook:

- `MailSubmit`: composing a draft, queueing it and staging its sent copy, out of `MessageCompose.send`, which keeps reading the fields and showing the outcome.
- `MailReader`: reading a message's body, the store first and the server otherwise (`fetch`, which also serves an attachment of a message not on the phone), filing what it fetched, restating its summary and recording its attachments, out of `MessageView.load` and `fetch`; the view keeps the offline message (`openOnline`).
- `MailEngine.stageFlag`: staging one marker on one message, out of `MessageView.stageFlag`'s loop.
- `MainActivity.wifi(Context)`: the Wi-Fi gate the activity reads, static.
- `OnboardingFlow.endpointUrl`, `submissionUrl` and `asUser` made static, so the suite derives an account's endpoints from discovery with the flow's own rules.

## Spec

No requirement changes: the suite tests the spec as it stands and the seams move code without changing what it does. Where a run finds the app diverging from the spec, the finding is reported, not encoded as the expected behaviour.
