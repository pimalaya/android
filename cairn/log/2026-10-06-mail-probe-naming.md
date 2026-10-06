---
cairn: log
change: mail-probe-naming
landed: 2026-10-06
---

# Synced mail shows again

A Fastmail account onboarded with contacts and calendars listed, and mail listed nothing although every mailbox sync reported messages pulled. io-pimdir's sync files a remote add as a probe, a handle with no link id and no summary, and only an upgrade names it. Contacts and calendars run a full upgrade after every sync; mail ran none, on the reading that a message rises by being opened, so every message stayed in `probes` and the list, which reads items and `mail_summary`, stayed empty.

`MailEngine.sync` now upgrades the mailbox's probes to the meta tier after the sync. `MailEngine.fetch` answers that tier from envelopes as before, so a message is named by its handle and summarised, and its body is still only read when opened. The bridge's `offlineUpgrade` takes the tier (`PimalayaClient.offlineUpgradeMeta`), and `PimdirStorage.probedHandles` lists a collection's probes. Probes already stored are named on the next refresh.

Capabilities moved: mail.
