# AGENTS.md: Cairn activation

This repository follows **Cairn**, a language- and tool-agnostic convention for keeping a living spec, reviewable change proposals, and an honest history next to the code. The normative format lives in [pimalaya/cairn](https://github.com/pimalaya/cairn) (CAIRN.md for the rules, GUIDE.md for the by-hand procedure). No tooling is required: you create and check the structure by reading and following the rules.

The Cairn root of this repository is [cairn/](./cairn):

- [cairn/spec/](./cairn/spec) current truth, one file per capability. Read it to learn what the app does today.
- [cairn/changes/](./cairn/changes) proposals for work in flight, one folder per change, archived under changes/archive/ once landed.
- [cairn/log/](./cairn/log) dated history, one file per landed change, immutable once written.

[docs/](./docs) keeps the plans and the design notes, which are prospective and personal where a spec is current and impersonal; [docs/pimalaya-android-plan.md](./docs/pimalaya-android-plan.md) stays the plan of record for what is not built yet.

If you are an agent working in this repository, do the following **by default, without being asked**.

## 1. Before non-trivial work, propose

For anything beyond a trivial fix, create cairn/changes/<change-id>/ with:

- proposal.md: *why* and *what* (frontmatter: `cairn: change`, `id`, `status: active`, `created`).
- tasks.md: the checklist.
- delta.md: what this changes in the spec, under the three literal headings `## ADDED Requirements`, `## MODIFIED Requirements`, `## REMOVED Requirements`.

Let the human review intent **before** you write code. Trivial fixes may skip this and go straight to landing.

## 2. After work lands, fold and log (never skip)

- Fold the change's delta into cairn/spec/<capability>.md so the spec always reflects current truth (append ADDED, replace MODIFIED, delete REMOVED).
- Append a dated entry cairn/log/YYYY-MM-DD-<change-id>.md describing what landed and which capabilities moved.
- Set the change `status: landed` and move its folder to cairn/changes/archive/.

> **The forcing rule:** a change that affects behaviour is not *done* until the spec is updated and the log entry is written.

## 3. Stay conformant

Check the structure yourself against the strict rules (CAIRN.md §8): a discoverable root, spec/ changes/ log/ present, every Cairn file carrying a valid `cairn:` type, each change having proposal.md and tasks.md, kebab-case ids, literal delta headings, and a log entry for every landed change. Everything else (prose, naming, ordering, extra files) is free. cairn/verify.sh runs the same nine checks when a command is wanted.

## 4. Repository conventions

- Run cargo through the nix devshell, from the repository root: `nix develop --command cargo <...>` in rust/.
- Run gradle through the same devshell, from the repository root: `nix develop --command gradle -p android <tasks> --console=plain`. The unit tests load the real libpimalaya.so, so a bridge change re-runs them.
- Run `cargo fmt` on the crate after finishing Rust changes.
- The build is the bar for Android work: `:app:assembleDebug` green, plus `:app:testDebugUnitTest`. Never verify by driving the UI with adb.
- Never adjust production code to fit a test: adjust the test to match correct behaviour.
- No em dashes in prose, per the Pimalaya guidelines.
- The pimdir schema is io-pimdir's, over JNI: never transcribe SQL or a column list into Java.
- A user-facing change also lands in CHANGELOG.md, which is a release-scoped roll-up over the Cairn log, not a replacement for it.
