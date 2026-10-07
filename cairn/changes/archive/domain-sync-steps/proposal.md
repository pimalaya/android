---
cairn: change
id: domain-sync-steps
status: landed
created: 2026-10-07
---

# The sync dialog counts in the domain's own words

## Why

The owner's device read *Downloading 23 contact(s)* while the agenda synced. The detail line's counted steps (downloading, writing to the phone) were worded for contacts and shared by every engine, so a calendar or a mail pass reported its bodies as contacts.

## What

1. **The step carries its domain.** `PimdirEngine.Progress.step` takes the domain of the engine that stepped (`PimdirEngine.domain()`: mail, contacts, calendar), and the runner's observer passes it through; the detail line is chosen from the pair (`SyncSteps`).
2. **Domain nouns.** Downloading counts messages for mail, events for calendars, contacts for the books; sending changes and resolving conflicts stay neutral.
3. **Phone steps.** Reconciling with and writing to the phone are the contacts spoke's alone; another domain has no text for them, leaving the line as it was (no calendar is projected into the phone).
4. **Plurals.** Every counted step is an Android `<plurals>` resource, English and French, as the lists' supporting lines already are, rather than the "(s)" spelling.

## Out of scope

- The mailbox count of the first-sync dialog (*Mailboxes synced: n of m*), already the mail's own.
