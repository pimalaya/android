---
cairn: log
change: domain-sync-steps
landed: 2026-10-07
---

# The sync dialog counts in the domain's own words

Capabilities moved: offline-store (modified: A sync says what it is working on, in every domain).

The owner's device read *Downloading 23 contact(s)* while the agenda synced: the counted steps were worded for contacts and shared by every engine. A step now carries the domain of the engine that took it (`PimdirEngine.domain()`, passed by `Progress.step` and the runner's observer), and `SyncSteps` picks the line from the pair: downloading counts messages, events or contacts; sending and resolving stay neutral; the two phone steps exist for contacts alone, another domain leaving the line as it was. Every counted step is a `<plurals>` resource in English and French. `SyncStepsTest` checks each domain's line, the phone steps' absence outside contacts, and French.
