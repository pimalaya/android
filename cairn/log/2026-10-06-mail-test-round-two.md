---
cairn: log
change: mail-test-round-two
landed: 2026-10-06
---

# Second round of mail fixes against Fastmail

**Dates.** Not a bug: the list sorts by the `Date` header, and the io-smtp test messages carry a fixed one well before their arrival. The open item of mail-test-round is closed with no change.

**Markers set on the phone.** Logcat on the device showed `unsupported mail push update` and `rejected:2`: io-pimdir derived an `Update`, because opening a message files its body against an item whose base holds none, and its content outcome withholds the flag push. `offline::sync` now takes a `content` right, `Native.offlineSync` carries it, and `PimalayaClient.offlineSyncImmutable` passes false for `MailEngine`; the engine then leaves the body untouched and derives `setFlags`. `MailEngineTest.aMarkerOnAnOpenedMessageIsPushedAsAMarker` pins it and fails with content pushes allowed.

**Submission rows.** `OnboardingFlow.submissionOptions` drops a methodless row for an endpoint another row offers with a method.

**Outbox.** Queued rows carry the mailbox `mail_outbox` (`MailStore.outboxName`), first on the filter's collection axis.

**Composer.** `RecipientField`, a wrapping chip group ending in its input, replaces the three address `EditText`s; `value()` joins the chips with commas, the shape the bridge already reads.

**Bars.** One `bar_more` overflow replaces `bar_filter`, `contacts_birthdays`, `contacts_duplicates` and `contacts_more_slot`; its menu is the filter, then `ContactsList.addMenuItems` on contacts. The contacts FAB uses `ic_add`. `ic_person_add`, `ic_filter_list` and `ic_duplicates` went unused and were deleted.

Capabilities moved: mail, offline-store, onboarding.
