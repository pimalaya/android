---
cairn: log
change: debug-app-alongside
date: 2026-11-07
---

# Debug builds install beside release builds

Debug builds are now the package `org.pimalaya.debug`, named "Pimalaya Dev", so a phone holds a debug build and a release build (Play or CI, one signature) side by side, each with its own accounts.

- build.gradle.kts `installedAs` sets per build type the launcher name, the phone account types (`org.pimalaya` and `org.pimalaya.calendar` on release, `org.pimalaya.debug` and `org.pimalaya.debug.calendar` on debug) and the Microsoft redirect scheme (`pimalaya`, `pimalaya.debug`), read by Accounts.java, the authenticator and sync-adapter XML, Oauth.java and the manifest.
- The dynamic-registration redirect follows the application ID (`org.pimalaya:/oauth2redirect`, `org.pimalaya.debug:/oauth2redirect`); the custom client page checks and names the build's own.
- The launcher shortcut has a debug copy (android/app/src/debug/res/xml/shortcuts.xml); the two are kept alike by hand.
- Release builds carry every identifier they did, checked in the built APK: no installed release, registration or grant changes.
- Owed by the owner: `pimalaya.debug://oauth2redirect` added to the Entra registration (a debug build's Microsoft sign-in fails until then), the "Pimalaya Dev" Android client's package set to `org.pimalaya.debug`, the old debug install removed once, then the two builds tried side by side.
- Checked: `:app:assembleDebug`, `:app:assembleRelease`, `:app:testDebugUnitTest`, and both APKs dumped with aapt2.

Capabilities moved: onboarding (A debug build installs beside a release build, added).
