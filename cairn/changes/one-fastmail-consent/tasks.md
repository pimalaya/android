---
cairn: tasks
change: one-fastmail-consent
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/.

- [ ] Live check on the Fastmail test account: whether one authorization with several `resource` parameters yields a token (or a refresh token) the IMAP, JMAP and DAV endpoints all accept, and how the token request must name them; record the answer in docs/providers.md
- [ ] setup/SetupPlan.java `sameSignIn` (line 230), `audienceOf` (259), `resourceOf` (284) and account/Oauth.java `audience` (165): group by issuer and resource set per the check
- [ ] OAuth flow: several resources on the authorization request; exchange and refresh per resource if the server needs one token each
- [ ] Tests: SetupPlanTest (IMAP mail plus JMAP or DAV contacts and calendars on one issuer are one sign-in; Google and Microsoft unchanged), OauthTest
- [ ] e2e: a Fastmail OAuth setup asks one consent (device check owed)
- [ ] delta.md once the rule is decided (spec/onboarding.md), fold, log, CHANGELOG
