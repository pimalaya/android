---
cairn: change
id: debug-app-alongside
status: landed
created: 2026-11-07
---

# Debug builds install beside release builds

## Why

Debug and release builds share the package `org.pimalaya` but not the signing key, so a phone holds one or the other: trying a CI release APK means uninstalling the debug build (its accounts and its store with it), and the reverse. Each build type already signs in to Google with its own client (onboarding, "Each build type signs in to Google with its own client"), so the two already speak to different OAuth projects; only the package and what is tied to it keep them from living side by side.

## What

- Debug builds become the package `org.pimalaya.debug` (`applicationIdSuffix = ".debug"` in android/app/build.gradle.kts), named "Pimalaya Dev" on the launcher (a per-build-type `app_name`), so the two read apart.
- Everything two installs would fight over follows the build type:
  - The phone account types `org.pimalaya` and `org.pimalaya.calendar` (Accounts.java, authenticator.xml, authenticator_calendar.xml, syncadapter.xml, syncadapter_calendar.xml): a BuildConfig field for Java and a generated string resource for the XML, `.debug` variants on debug. Two apps declaring one account type collide in the account manager.
  - The Microsoft redirect `pimalaya://oauth2redirect` (Oauth.java, the manifest): a per-build-type scheme like Google's, the debug one added to the Entra registration's mobile redirects.
  - The dynamic-registration redirect `org.pimalaya:/oauth2redirect` (Oauth.java, the manifest): built from the application ID, so `org.pimalaya.debug:/oauth2redirect` on debug; a reverse-DNS scheme with dots, which Fastmail accepts, and registered per grant, so no console step.
  - The launcher shortcut (shortcuts.xml), whose `targetPackage` is literal: a debug copy under android/app/src/debug/res/xml.
- Already per package, nothing to do: the FileProvider authority (`${applicationId}.files`), the store, the keystore keys, notification channels, widgets, the Google redirect scheme.
- The "Pimalaya Dev" project's Android client takes the package `org.pimalaya.debug`. The browser flow likely never checks it, but the registration then says what it serves.

## Decisions

- Release keeps every identifier it has: `org.pimalaya`, its account types, `pimalaya://` and `org.pimalaya:/`, so no installed release build, no Entra or Google registration of the production side and no tester's grant changes.
- Debug installs start fresh: `org.pimalaya.debug` is a new app to Android. The current debug install, `org.pimalaya` signed with the debug key, is uninstalled once.
- Release channels need nothing: Play's app signing key is the CI's own key (owner, 2026-11-07), so a Play build and a CI APK are one app with one signature and update each other in place, within the versionCode order.
- Side effect, said rather than handled: both apps mirroring the same account into the phone's Contacts or Calendar shows its contacts and entries twice; turn the mirror on in one of them.
