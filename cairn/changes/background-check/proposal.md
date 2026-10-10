---
cairn: change
id: background-check
status: active
created: 2026-10-09
---

# Background check: sync at each account's interval, notify new mail

## Why

Sync is manual only: the app cancels every scheduled job on launch (`MainActivity.onCreate`). New mail is only seen by opening the app and pulling, and nothing is ready on the phone before the user asks. Push stays out of the first release (the relay is a beta), so there is no watch: the release syncs periodically, on the phone.

There are two kinds of sync. The in-app one (a pull, the drawer's sync, a first sync) never notifies. The background one syncs every account that is due, all its collections, and only new mail in an inbox notifies.

## What

### The job

- Each account picks its interval: off, every 15 minutes (the default), 30 minutes, hour or 2 hours. Fifteen is the floor Android allows, and notifications are only worth having if they come reasonably soon; the cost is mostly waking the radio, and an unchanged account is cheap to check (QRESYNC, JMAP state, Graph delta, sync tokens).
- One periodic JobScheduler job (`BackgroundJob`), every 15 minutes, on any network, persisted across reboots, scheduled while at least one account has an interval, cancelled otherwise (`BackgroundCheck.schedule`). A run takes the accounts whose interval has passed since they last synced, by any sync (`SyncStamps`), with 5 minutes of slack for the job's drift. Doze batches the job into its maintenance windows, so an idle phone checks less often; the interval's dialog says so.
- A run skips when the app is on screen (its own sync is the user's), and when an in-app sync holds the process-wide lock (`SyncLock`). An in-app sync started while a run holds it is turned down with "A background sync is running", and the lists' strip says "Syncing in the background" while a run goes on with the app open. A first sync owed meanwhile waits silently and starts when the run ends.
- A run syncs, for each account that is on, is due and owes no first sync of the domain: the contacts (remote, then the phone spoke when the contacts permission is granted), the mail (outbox drained, every mailbox reconciled, as the in-app pass) and the calendars. No body fill: that stays the open app's.
- To run without the activity, the mail and calendar passes move out of `MainActivity` into `RemotePass`, with a progress observer the activity feeds its strip from and a headless one.
- When the app comes back after a run, it reloads its accounts (a run may have renewed a token) and its lists.

### New mail notifications

- What is new is what the run added: the unread inbox messages present after the mail pass and not before it. Messages a fill, a widened bound or the in-app sync brought never notify, and neither does a first sync.
- One notification per message (sender as title, subject as text), grouped per account under a summary ("3 new messages"), at most 5 per account per run, the newest. One channel per account, named by its address, so Android's settings mute one account.
- A tap opens the app. Opening the app clears them.
- `POST_NOTIFICATIONS` (Android 13+) is asked once a mail account with notifications on exists, and again when the switch is turned on. Revised 2026-10-10 by `onboarding-options-ask-on-switch`: asked only when a switch is turned on, in the setups or in settings.

### Settings

A **Background** card on the account page, between Mail and Addressbooks:

- "Sync in the background", value the interval ("Every 15 minutes", "Off"): a choice dialog, "Roughly, as Android allows: an idle phone checks less often. A pull in the app syncs at once."
- "Notify new mail", a switch, on by default, mail accounts only, dimmed while background sync is off. Revised 2026-10-10 by `onboarding-options-ask-on-switch`: off by default, also offered in the setups, reading off while the permission is missing.

## Out of scope

- Event reminders (VALARM): their own change; they need this job to keep the calendars fresh.
- Opening the notified message itself: the tap lands on the app.
- Push (relay), and a watch of any kind (IMAP IDLE needs a foreground service with a standing notification).
