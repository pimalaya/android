---
cairn: log
change: google-client-per-build
date: 2026-11-07
---

# Each build type signs in to Google with its own client

Release builds, every channel alike, now sign in to Google through the client of the "Pimalaya" project, the one going through Google's verification (`803561575535-v7nav2ev8dvv995g3j3mhnoq5423abco`); debug builds keep the "Pimalaya Dev" client (`991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1`).

- android/app/build.gradle.kts sets each build type's client ID and reversed redirect scheme as BuildConfig fields and a manifest placeholder; Oauth.java and the manifest's Google intent-filter read them.
- Accounts granted through the former client keep refreshing with it, since each stores its client beside its refresh token: no tester signs in again, as long as the "Pimalaya Dev" client lives.
- Not yet tried: a release build's Google sign-in through the new client, which needs the custom URI scheme enabled on it.

Capabilities moved: onboarding (Each build type signs in to Google with its own client, added).
