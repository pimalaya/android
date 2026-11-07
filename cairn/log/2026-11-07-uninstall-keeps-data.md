---
cairn: log
change: uninstall-keeps-data
date: 2026-11-07
---

# An uninstall offers to keep the app's data

On Android 10 and later the uninstall dialog now offers to keep Pimalaya's data, with its size (`android:hasFragileUserData` on the application, asked by the owner), so a reinstall signed by the same key finds the store, the accounts and what was not yet synced: the outbox, staged edits, the task details Google Tasks does not keep.

- The refused-store screen (store_refused_message) now asks to uninstall without keeping the data, since a reinstall keeping it would find the same store and refuse again.
- The Google Tasks limits note no longer says a reinstall loses the phone-only task details: it keeps them when the data is kept.
- Credentials: the Keystore key is believed kept with the data (not checked on a device); if it is not, SecureStore drops the accounts it cannot open and asks to add them again, as after a device transfer.
- Reinstalling over kept data needs the same signing key: Play and CI builds share one, and debug builds are their own package.
- Checked: both build types built, both merged manifests carrying the attribute; no test reads the changed messages.

Capabilities moved: onboarding (An uninstall offers to keep the app's data, added), offline-store (An incompatible store blocks start-up, modified).
