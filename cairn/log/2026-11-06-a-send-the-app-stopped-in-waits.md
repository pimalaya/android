---
cairn: log
change: a-send-the-app-stopped-in-waits
date: 2026-11-06
---

# A send the app stopped in waits for the user

The first of the duplicate writes left after network-trust-hardening M6 (production-release section 1), folded without a proposal.

Capabilities moved: mail (modified: a submission's row is cancelled once handed over, a drain interrupted between the handover and the cancel now parking the row as uncertain instead of sending it again; added the scenario The app stops while sending).

The outbox is pimdir's queue, which records no attempt in flight, so a drain the process died in sent the message again on the next run. `Outbox.handing` notes the row in the app's preferences, written synchronously, right before `SyncPasses.drainOutbox` hands it over; acknowledging, parking or counting an attempt clears it. `Outbox.outgoing` parks a row still noted as *May have been sent*. Requeueing it instead would have broken the queue's order. Tests: SubmissionTest `aHandOverTheAppDiedInIsParkedUncertain`, `aHandOverWithAVerdictIsNotParked`.
