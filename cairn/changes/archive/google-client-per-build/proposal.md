---
cairn: change
id: google-client-per-build
status: landed
created: 2026-11-07
---

# Each build type signs in to Google with its own client

## Why

Google's verification covers one project, "Pimalaya", created for production on 2026-11-06; its policy asks for separate projects per deployment tier, and its review wants every client of the verified project shown and ready for production. Every build so far carried the client of the former project, now "Pimalaya Dev". The release builds, which every channel and every tester runs, move to the production client; debug builds stay on the development one, where scope experiments cost no review (oauth-verification, production-release §4).

## What

- android/app/build.gradle.kts names both clients and gives each build type its own: release takes `803561575535-v7nav2ev8dvv995g3j3mhnoq5423abco` ("Pimalaya"), debug `991810147220-4f2s8id0pksdj5vtj1ivgpadnoibl4g1` ("Pimalaya Dev"), as BuildConfig fields (the client ID and its reversed scheme) and a manifest placeholder.
- Oauth.java reads `GOOGLE_CLIENT_ID` and `GOOGLE_REDIRECT_URI` from BuildConfig; the manifest's Google intent-filter takes `${googleRedirectScheme}`.
- No migration: each account stores the client it was granted by beside its refresh token and refreshes with it (SecureStore, SyncPasses), so testers' accounts on the former client keep working as long as that client lives.
