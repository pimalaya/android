---
cairn: log
change: gmail-sent-copy-redated
date: 2026-11-07
---

# A Gmail sent copy lands on its staged copy whatever Gmail dates it

Seen by the owner on the release build while recording the verification video (2026-11-07): a message sent to oneself from a Gmail account showed in Sent twice after the next sync, beside its inbox copy.

- Cause, from the debug build's log and store: Gmail restamps the `Date` as well as the `Message-ID` of what `messages.send` sends (staged `Sat, 7 Nov 2026 19:50:59 +0100`, Gmail's `10:51:04 -0800`, five seconds later). The alias read back for the send named the arrival by the staged create's key, but io-pimdir lands a create only on an arrival of the same subject, date and sender (SYNC §6, `summary::same_message`), so it minted the arrival a `dup:` key and left the create pending, its push refused (`rejected=1`). The e2e test composed and sent within the same second, so the dates matched there. The inbox being listed first played no part.
- MailEngine.nameByMessageId: an arrival named through Gmail's stamped alias reaches the engine without its summary and sort key (`Named.bare`), which says nothing against, so the create lands; it keeps its own summary and sort key. An arrival matched by its own `Message-ID` keeps its summary and the engine's check.
- No "save to Sent" option: Gmail and Graph accounts file no copy themselves, the staged copy only landing on the provider's, and on IMAP the append is skipped when Sent already holds the `Message-ID`; the duplicate was the phone's alone, never on Gmail.
- Regression: MessageIdNamingTest.aGmailSentCopyRedatedLandsOnItsPendingCreate, failing against the old naming (the create pushed instead of landed). E2eMailSendTest sends three seconds after composing; google-gmail and google-imap send-to-self pass live (2026-11-07). google-imap's `onMobileDataTheOutboxGoesOutAndNothingIsListed` failed on its first step listing a scratch mailbox, unrelated, left to e2e-suite.

Capabilities moved: mail (requirement "A sent message is in Sent at once", modified: Gmail's `Date`, and its scenario "Gmail renaming what it sends").
