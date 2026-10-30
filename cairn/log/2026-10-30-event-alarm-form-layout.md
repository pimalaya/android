---
cairn: log
change: event-alarm-form-layout
date: 2026-10-30
---

# The alarm form: one label and one input per value

- **calendar**: the alarm form lays out Type, Trigger, When, Rings again and Every one per line, When and Every picked as an amount and a unit (or When as a day and a time), Every shown while the alarm rings again; no requirement moved.

Capabilities moved: none (calendar's form layout only).

Verified on 2026-10-30: `gradle :app:assembleDebug :app:testDebugUnitTest` green; cairn/verify.sh conformant. Device check pending.
