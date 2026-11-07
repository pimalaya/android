---
cairn: tasks
change: google-client-per-build
---

# Tasks

- [x] Both clients in build.gradle.kts, one per build type, BuildConfig fields and a manifest placeholder
- [x] Oauth.java and the manifest read the build type's client
- [x] Generated BuildConfig and merged manifest checked per variant: debug on 991810147220, release on 803561575535
- [x] `:app:assembleDebug`, `:app:compileReleaseJavaWithJavac`, `:app:testDebugUnitTest` green
- [x] Spec folded (onboarding), log written, CHANGELOG
- [ ] A release build signs in to Google through the new client: the custom URI scheme enabled on it, the consent screen naming Pimalaya (oauth-verification)
