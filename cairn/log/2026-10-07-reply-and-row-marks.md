---
cairn: log
change: reply-and-row-marks
landed: 2026-10-07
---

# Reply, forward, and a quieter mail row

Another pass after a look on the device, and the reader learns to answer.

Capabilities moved: mail (modified: a row's trailing marks, a message is composed to RFC 5322; added: the reader replies and forwards), offline-store (modified: an item's page groups its sections into cards; added: the contacts list selects like mail).

**Mail row.** The subject's line ends, from the edge inwards, with the unread dot, a yellow star while the message is important and a paperclip while it carries an attachment. The star is a plain mark now; the selection's bar is where starring happens. A one-pixel hairline tops every row but the first of a card (`CardSections.opensCard`). The attachment chip is gone.

**Reader.** The mailbox and account read as a subtitle under the subject. The meta card sits in a `HorizontalScrollView`, so an address keeps to its line and the card scrolls sideways. Reply (to all) and Forward sit under the message, the reply an accent pill and the forward a tonal one. They build a `MessageCompose.Prefill`, which the composer opens on, sending from the message's account. Leaving the composer asks first only when a field moved from what it opened with.

**Threading.** `Draft` takes `inReplyTo` and `references` (rust/src/mail.rs), written as `In-Reply-To` and a folded `References`. The thread comes from `mail_summary` (`MailStore.threadOf`), the parent's `In-Reply-To` standing in for the `References` the summary does not keep. A queued reply stages `\Answered` on its parent, except on Graph and Gmail, which keep no such marker and would refuse it on every sync.

**Composer.** Recipient chips use `@color/faint`, the text colour at low alpha, which also tints the bottom bar's indicator: the surface tone they used was the card's own.

**Contacts and pages.** Contact selection turns the disc into a check, as mail does, and the checkbox is gone. Each row names its addressbook and account. `Sections` now puts a section's label, add action and rows together in one card.
