---
cairn: tasks
change: drop-google-imap
---

# Tasks

- [x] SetupPlan.offered drops Google's IMAP and SMTP configs (provider rule or Gmail's hosts)
- [x] `imap.gmail.com` and `smtp.gmail.com` out of Oauth.GOOGLE_HOSTS and identity.rs's twin
- [x] Tests: a failing-first SetupPlanTest case (Google's IMAP and SMTP not offered) and OauthTest case (the client does not sign in to Gmail's IMAP); fixtures storing a Gmail account over IMAP moved onto the Gmail API base
- [x] `cargo test`, `cargo fmt`, `:app:assembleDebug`, `:app:testDebugUnitTest`
- [x] CHANGELOG Removed entry
- [x] Spec folded, log written, change archived
- [x] oauth-verification: justification, shot list and reply without IMAP and SMTP
