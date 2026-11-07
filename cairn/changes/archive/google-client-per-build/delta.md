---
cairn: delta
change: google-client-per-build
---

# Delta

## ADDED Requirements

### Requirement: Each build type signs in to Google with its own client
Release builds, every channel alike (Google Play, the GitHub APKs, F-Droid), SHALL sign in to Google with the client of the verified "Pimalaya" project, and debug builds with the client of the "Pimalaya Dev" project, so the verified project holds only what ships. Each build SHALL carry one Google client ID and its reversed form as the redirect scheme, both set per build type in android/app/build.gradle.kts and read by Oauth.java and the manifest's intent-filter. An account SHALL keep refreshing its tokens with the client it was granted by, so a build changing its client strands no grant.

#### Scenario: An account granted through the former client
- GIVEN a Google account signed in by a release build carrying the "Pimalaya Dev" client
- WHEN a release build carrying the "Pimalaya" client syncs it
- THEN its tokens refresh through the "Pimalaya Dev" client, and the account keeps working without a new sign-in

## MODIFIED Requirements

## REMOVED Requirements
