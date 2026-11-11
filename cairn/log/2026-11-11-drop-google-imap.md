---
cairn: log
change: drop-google-imap
date: 2026-11-11
---

# Google mail over the Gmail API alone, not IMAP and SMTP

Decided by the owner while recording the verification video (oauth-verification): Google's checklist asks every use the scope justification names to be filmed, and the `https://mail.google.com/` justification named IMAP and SMTP beside deleting for good. Once the client is verified, the Gmail API serves everything IMAP and SMTP did, so the app stops offering them for Google rather than film a path users gain nothing from. The scope stays: deleting for good takes it alone.

- SetupPlan.offered drops an IMAP or SMTP config from Google's provider rule or on Gmail's hosts (`imap.gmail.com`, `smtp.gmail.com` and their googlemail twins), whatever source names it: an autoconfig database lists them too. The setups, standard and advanced, offer Gmail over its API alone, sending included.
- `imap.gmail.com` and `smtp.gmail.com` left `Oauth.GOOGLE_HOSTS` and its twin in rust/src/account/identity.rs, so the app's Google client signs in to neither: a manual entry on those hosts takes a generic grant, which Google cannot serve without the user's own client.
- io-pim-discovery is unchanged; the generic IMAP and SMTP handling stays.
- No migration: a tester's Gmail account over IMAP keeps syncing while its grant holds, and is removed and added again to move onto the API.
- Tests failing first, against the old code: SetupPlanTest `gmailConnectsOverItsApiAlone` (Gmail's IMAP and SMTP offered), OauthTest `theShippedClientSignsInToItsProviderOnly` and EndpointsTest `everyEndpointMustBeTheProvidersOwn` (the client still signing in to Gmail's IMAP). GoogleNoticeTest, BrowserLeftTest and WrongGrantTest store their Gmail account on the Gmail API base rather than Gmail's IMAP, which the app no longer signs in to; identity.rs's test reads no identity for an IMAP-only Gmail base.
- oauth-verification follows: the justification keeps deleting for good alone (submission.md §2, to paste into the Data Access page), the video has no IMAP clips, the reply says why.
- Checked: `cargo test` (645 passed), `cargo fmt`, `:app:assembleDebug` and `:app:testDebugUnitTest`.

Capabilities moved: onboarding (Google connects over its APIs alone, modified).
