---
cairn: change
id: body-run-loop
status: active
created: 2026-10-12
---

# The body step stops retrying a message gone

## Why

On a Gmail API account the sync strip showed the body step every few seconds, forever. The phone's log: the same single SENT message asked of Gmail, `{"fetched":0}`, "1 not landed" three times, the run done, a new run 4 to 10 seconds later.

The message (`19b76daa895d508d` in SENT, at meta, owed) was a message of the neverest live suite on the same account: imported with a `Date` of 1 January 2026, starred, picked up by the 10:04:35 history replay, then deleted for good by the suite's cleanup. Gmail answers its `minimal` pre-pass 404, which `read_sources` leaves out like any message gone, and `MailEngine.download` counts as not landed, a failure.

Three causes made it a loop:

1. **Gone reads as failed.** The bridge's `fetchMessageSources` answer had no way to tell a message gone from one not handed over, so the row stayed owed and was tried 3 times a run.
2. **A run plans what the last one gave up on.** `MailBodies.FAILED` was only read by the row's label: `planBodies` planned a failed message again in the next run, its attempts counted per run.
3. **Wi-Fi "came back" every few seconds.** `wifiWatch.onCapabilitiesChanged` posts `wifiBack` (and so `fillMail`, a new plan and a new run) on every capability change of an unmetered network, which Wi-Fi reports every few seconds (signal, bandwidth) while it stays up.

## What

- **The bridge tells gone apart.** `fetchMessageSources` answers `{items, gone}`: Gmail names an id its `minimal` or raw read answered 404, Graph one its batch or alone read answered 404, IMAP a UID with no `RFC822.SIZE` (expunged) or no UID at all, JMAP an id `Email/get` returned no email for. One not handed over stays out of both, a failure as before.
- **A message gone is owed no more.** `MailEngine.download` reads `gone` from `sources`: such a row is not a failure, it is settled from `MailOwed` and marked in `MailBodies.GONE`, left for the next listing of its mailbox (a Gmail history's `messagesDeleted`, an IMAP vanish) to take away. Its row says *Not downloaded*.
- **A message given up on is not planned again until something changes.** `MailBodies.Run.plan` leaves out what `FAILED` and `GONE` hold, and answers how many it planned; both are cleared per mailbox when it is listed anew (`MailEngine.list` and `land`, through `MailBodies.relisted`), and by the next app start. Opening a message still fetches it.
- **Only Wi-Fi coming back starts a run.** `wifiWatch` posts `wifiBack` once a network becomes unmetered (a flag reset on registration and on `onLost`), never on the capability changes of a network that stays so.
