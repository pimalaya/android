# Contributing guide

Thank you for investing your time in contributing to Pimalaya for Android.

Whether you are a human or an AI agent, read these in order before touching the code:

1. the [Pimalaya README](https://github.com/pimalaya) for what the project is and how its repositories stack;
2. the [Pimalaya CONTRIBUTING](https://github.com/pimalaya/.github/blob/master/CONTRIBUTING.md) guide, which chains to the shared architecture and guidelines;
3. the Architecture section below and [docs/design.md](./docs/design.md): the settled design of this app;
4. the [cairn/](./cairn) folder: the living spec of what the app does, the changes in flight and the log of what landed (see [AGENTS.md](./AGENTS.md));
5. the [docs/](./docs) folder for the design notes and plans.

Everything below documents only what differs from the Pimalaya standards.

## Development environment

The development environment is managed by [Nix flakes](https://nixos.wiki/wiki/Flakes). Running `nix develop` spawns a shell with everything pinned: a stable Rust toolchain carrying the four Android ABI targets, `cargo-ndk`, the Android SDK and NDK, JDK 17, Gradle and `jdt-language-server`.

If you do not want to use Nix, provide these yourself: the Android SDK (platform 35, build-tools 36.1.0) and NDK 29, JDK 17, Gradle, plus a Rust toolchain (>= `v1.88`) with the `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` and `i686-linux-android` targets and `cargo-ndk`.

## Architecture

Three layers, each knowing only the one below:

- `:app` (Java, framework Views): the screens and the app's policy (sync passes, steps, filters, the phone mirrors). Talks only to the five domain clients of `:client` (account, contact, calendar, mail, offline); never sees sockets or JNI.
- `:client` (Android library, Java): the public API exposed to the app. Owns the JNI boundary, the sockets and TLS (the platform trust store, at no APK cost), and the parsing of the bridge's replies into typed records.
- `libpimalaya.so` (Rust): the Pimalaya I/O-free libraries exposed over JNI: io-http, io-imap, io-smtp, io-jmap, io-webdav, io-gmail, io-gpeople, io-gcal, io-msgraph, io-oauth, io-pim-discovery, vcard-rs and ical-rs, and io-pimdir, the offline store and sync engine. Socket I/O is delegated back to Java on each read and write.

TLS and TCP live in Java on purpose: the `.so` stays a small set of state machines that cross-compiles trivially, and certificate validation is handled by Android. The app is plain Java and XML framework Views, no Kotlin and no Jetpack beyond two AndroidX widgets (the drawer and the pull to refresh), to keep the toolchain small and the APK minimal. The store is SQLite, bundled so every Android version gets the same one.

`:app` is laid out by domain packages (`account`, `contact`, `calendar`, `mail`, `sync`, `setup`...), each referencing only the packages below it; android/app/src/main/java/org/pimalaya/package-info.java documents the layout and `LayeringTest` enforces it.

## Build

```sh
nix develop --command gradle -p android assembleRelease
```

`gradle` first cross-compiles the Rust bridge for the four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) via cargo-ndk into client/src/main/jniLibs, then assembles the APKs. The release build emits one APK per ABI plus a universal one under android/app/build/outputs/apk/release/, signed when `PIMALAYA_KEYSTORE` and `PIMALAYA_KEYSTORE_PASSWORD` are set and unsigned otherwise. For a quick install on your own device, `assembleDebug` produces a ready-to-sideload android/app/build/outputs/apk/debug/app-debug.apk.

To iterate on the Rust bridge alone: `nix develop --command bash -c 'cd rust && cargo build'`.

## Lint, test

The Rust bridge is checked with:

```sh
cd rust
cargo fmt
cargo clippy --all-targets -- -D warnings
cargo test
```

The app's logic (the store, the sync steps and passes, the mappings onto the phone's Contacts and Calendar, the filter) is covered by JVM unit tests mirroring the packages, under Robolectric where they need Android types. They load the host build of libpimalaya.so, so a bridge change runs them again, and fake only the `:client` domain clients:

```sh
nix develop --command gradle -p android :app:assembleDebug :app:testDebugUnitTest :client:testDebugUnitTest --console=plain
```

Both run in CI on every push to master. The build is the bar: the app is not verified by driving its interface.

## Commit style

Pimalaya for Android follows the [conventional commits specification](https://www.conventionalcommits.org/en/v1.0.0/#summary).
