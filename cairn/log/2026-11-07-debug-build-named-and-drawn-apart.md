---
cairn: log
change: debug-build-named-and-drawn-apart
date: 2026-11-07
---

# The debug build is "Pimalaya Debug", its launcher icon the logo on black

- The debug build is named "Pimalaya Debug", matching its package `org.pimalaya.debug` and its Google project, renamed from "Pimalaya Dev" (owner); the spec, the build and the open plans say so, the earlier logs keep the old name.
- Its launcher icon is the release logo on black, where launchers set the release one on white: an adaptive icon (android/app/src/debug/res/drawable/ic_launcher_debug.xml, a black background under ic_launcher inset by 26.67% each side, the size Launcher3 draws a non-adaptive icon at, LEGACY_ICON_SCALE 0.4667 of the layer), which android/app/src/debug/AndroidManifest.xml sets as the application's icon. The bar logo, the widgets and the phone accounts keep the release logo. Tried first and dropped (owner): an inverted gradient (ugly), a 20% inset (the logo larger than the release one).
- Checked: both build types built, the merged manifests naming `ic_launcher_debug` on debug and `ic_launcher` on release; the debug APK labelled "Pimalaya Debug".

Capabilities moved: onboarding (A debug build installs beside a release build, modified).
