---
cairn: log
change: standard-setup-and-unrestorable-deletes
landed: 2026-10-09
---

# The standard setup takes the best sign-in; a delete says what cannot be restored

Capabilities moved: onboarding (modified: the standard setup connects with the best sign-in found, was a password alone), mail (added: deleting asks, and counts what cannot be restored), offline-store (modified: Free space carries its size).

**Standard setup.** Decided by the owner on 2026-10-09: the standard setup is the one a user picks domains in and nothing else. `OnboardingFlow.standardOption` replaces `passwordOption`: Google's and Microsoft's own APIs first (discovered `gmail`, `gcal`, `msgraph`, `msgraphCalendar`, and the People and Graph provider sign-ins), then the existing ranks, service (JMAP, the DAVs, the rest) before sign-in (OAuth, device grant, API token, password). The browser grants and the token prompt were already steps of the standard sequence. A server advertising an OAuth it then fails no longer falls back to its password in this setup: the advanced setup remains for it.

**Deletes.** The reader and a selection share `MessageView.confirmDelete`, which counts, off the main thread, the messages the delete erases (`MailStore.erases`, the rule `stageDelete` stages by) whose body the store does not hold (`MailStore.holdsBody`, `items.object_hash`), and adds that count to the question. The "Moved to" toast names the trash by its role's name.

**Free space.** The button reads *Free 1.2 MB* when retained bodies weigh anything.

**Drawer.** The drawer's title carries the app's logo (`ic_logo`) before its name.
