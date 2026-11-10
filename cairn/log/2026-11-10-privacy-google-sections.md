---
cairn: log
change: privacy-google-sections
date: 2026-11-10
---

# PRIVACY.md's Google section follows Google's checklist

Part of oauth-verification (tasks §2): Google's review checklist of 2026-11-10 asks the privacy policy to state data access, use, transfer, protection, and retention and deletion, for raw and aggregated or anonymized data, and no AI or ML training or transfer.

- The Google user data section split under Data accessed, How the data is used, Sharing and transfer, Protection, Retention and deletion and Limited Use, adding: no aggregated or anonymized data derived; no AI or ML use and no transfer to an AI service; protection (no server, TLS to Google, sign-in on Google's page, private storage excluded from cloud backups and device transfers by data_extraction_rules.xml and backup_rules.xml, Keystore-encrypted tokens).
- Retention: uninstalling now says the Android 10+ dialog may keep the data, true since uninstall-keeps-data (`android:hasFragileUserData`, 2026-11-07), which left the old "removes all data" line wrong.
- Connections: the Google Tasks API listed beside the other Google APIs.
- Last updated 10 November 2026 (it still said 12 October though changed on 2026-11-07).
- The websites copy refreshed the same day (websites www-privacy-google-sections), not yet published.

Capabilities moved: none (the policy is not a spec capability; spec/onboarding.md's Google notice is unchanged).
