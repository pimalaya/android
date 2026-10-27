---
cairn: change
id: one-fastmail-consent
status: active
created: 2026-10-18
---

# One consent for a Fastmail account

## Why

A Fastmail account set up over OAuth asks the user to consent more than once (audit carry-over, 2026-10-11, still so on 2026-10-18). SetupPlan.sameSignIn (android/app/src/main/java/org/pimalaya/setup/SetupPlan.java:230) shares a grant only when both options have the same authorization server and the same audience, and the audience (`audienceOf`, line 259, over Oauth.audience, account/Oauth.java:165) is the RFC 8707 resource before anything else. The resource differs per domain (`resourceOf`, line 284): a JMAP or DAV option takes its discovery URL, an IMAP one its authorization endpoint's origin (Fastmail answers `invalid_target` without one). So mail over IMAP and contacts or calendars over JMAP or DAV, all on one issuer, run as separate grants, each its own browser consent. The onboarding spec's overview says one browser grant covers every domain that chose the same authorization server for the same API, which this does not break, but the user sees one account and several consents.

## What

Decide how one grant can cover the domains of one issuer, then group them:

- RFC 8707 allows several `resource` parameters in one authorization request, the token then asked per resource (or with all of them) on exchange and refresh; or
- RFC 9728 protected-resource metadata (Fastmail serves it behind the 401's `resource_metadata` pointer) naming one authorization server for the resources, which lets them share one grant.

Whichever holds live on Fastmail, `sameSignIn` groups options by issuer and the set of resources, and the token is presented per resource as the server requires. Google and Microsoft are untouched: provider rules send no resource, and Entra refuses one token for two APIs (A provider grant is the app's own).

## Out of scope

- The RFC 8707 resource on exchange and refresh for every generic grant (network-trust-hardening, Open, L3), which this may land with.
