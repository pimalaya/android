---
cairn: log
change: body-run-loop
landed: 2026-10-12
---

# The body step stops retrying a message gone

Capabilities moved: mail (new: a body gone is given up until a new listing; modified: a body that fails is tried again in the run, bodies download behind the list).

On a Gmail API account the body step ran every few seconds for one SENT message the neverest live suite had deleted for good after the history replay listed it: Gmail answered it 404, the bridge left it out like a failure, it stayed owed, and every Wi-Fi capability change (`wifiWatch.onCapabilitiesChanged`, every few seconds while Wi-Fi stays up) started a new run that planned it again, `MailBodies.FAILED` read by the row's label only. `fetchMessageSources` now answers `{items, gone}` on every backend (Gmail and Graph a 404, IMAP a UID with no size, JMAP no email for the id); `MailEngine.download` settles a gone row from `MailOwed` and marks it in `MailBodies.GONE` instead of failing it; `MailBodies.Run.plan` leaves out what `FAILED` and `GONE` hold until the mailbox is listed anew (`MailBodies.relisted` from `MailEngine.list` and `land`) or the app starts again; `wifiWatch` starts the step only when a network becomes unmetered.
