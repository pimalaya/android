---
cairn: change
id: java-domain-layout
status: landed
created: 2026-10-14
---

# The Java side laid out by domain, typed, and thin

## Why

The `:app` module is 121 files in one flat package, so nothing can be package-private and every class sees every other. MainActivity (4,784 lines) and OnboardingFlow (3,392) mix rendering with sync orchestration and setup decisions that no test reaches. Dependencies point both ways (stores call screens, storage builds engines, engines call the orchestrator), UI and services share mutable static flags, and six lock registries guard overlapping state. JSON is the internal model: about 1,400 JSONObject/JSONArray tokens, card and event models handled as JSON end to end, and `:client` returning raw JSON where the Rust side now replies with typed shapes. 137 methods take five or more parameters, and MailEngine carries protected forwards that exist only for tests.

## What

Behaviour-preserving restructuring mirroring rust-domain-layout, in phases, each leaving `:app:assembleDebug :app:testDebugUnitTest` green with no fewer tests, `cargo test` untouched and the exported JNI symbols unchanged.

1. Moves only: packages `account`, `mail`, `contact`, `calendar`, `file`, `pimdir`, `sync`, `phone`, `ui`, with screens inside their domain; manifest-registered components stay in `org.pimalaya` as thin delegates; tests mirror the packages; OfflineEngine renamed ContactEngine.
2. Dependency direction: screens and `ui` → domain → `sync`/`pimdir` → `:client`, never back, enforced by a JVM test; one state owner instead of static flags; one lock registry.
3. Engines: stager and remote pass split (no null-selected roles), one worker pool, one SyncCoordinator out of MainActivity and RemotePass, one phone-spoke contract.
4. Logic out of the UI: setup planning, body planning, contact editing, event save, account deletion and filters extracted and tested; MainActivity ends as navigation, lifecycle and composition root.
5. Typed data: Java records for every Rust reply, five domain clients replacing PimalayaClient, JSON only at the `:client` boundary and in persistence formats, params records for wide signatures.
6. Tests fake only the `:client` domain clients; MailEngine becomes final.
7. Docs: package-info.java per package, the root one the Java twin of rust/src/lib.rs.

Guiding direction: the Java side holds as little logic as possible, it is the UI and the platform. Logic that has to stay in Java is extracted out of screens and tested; logic that duplicates the Rust side is listed for the boundary change rather than grown in Java.

Out of scope, a separate later change: the JNI boundary itself (one account argument instead of the positional tuple, backend questions answered by Rust, PimdirStorage and calendar recurrence logic moving to Rust). Robolectric stays where tests need Android types.
