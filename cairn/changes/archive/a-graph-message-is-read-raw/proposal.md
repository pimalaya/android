---
cairn: change
id: a-graph-message-is-read-raw
status: landed
created: 2026-11-13
---

# A Graph message is read raw

## Why

pimdir STORAGE Annex A.1 ("A server-side summary", "The fields §9 compares are read raw") has every writer derive a message's `Message-ID`, `Subject`, `Date` and `From` raw wherever the source serves them raw, each from the first occurrence of its name. Graph serves `internetMessageHeaders` to a `$select` naming it, on a listing, a delta page and a single read, for received and sent messages alike (probed on the test tenant on 2026-11-13, io-msgraph cairn/log/2026-11-13-message-internet-headers.md). The app named every Graph member from the values Graph decodes: `internetMessageId` through a parser of its own, which strips one pair of angle brackets, `subject` through `decode`, `sentDateTime` and `from`. neverest reads the header fields raw since 55358e3, so one message could be keyed one way by the app and another by neverest, a key never being derived again (pimdir audit of 2026-11-12, line D2, closed for neverest only).

## What

- io-msgraph by git at 92c28d5 (pushed, unreleased), which carries `MsgraphMessage::internet_message_headers` and reads a `null` collection as none; the app adapted to what else moved since 01105a5, its behaviour otherwise unchanged.
- `internetMessageHeaders` in the summary `$select` (`MESSAGE_SELECT`), so every read that names a mail member asks for it: a band's `/messages` page, the `$batch` of reads by id that names a delta's unbound members, and the read by id alone that a batch could not serve.
- A row listing header fields is named off them as neverest does: the nine fields a summary reads laid out as a header block, each from its first entry of that name compared without regard to case, through io-pimdir's `derive_meta`, the size and the attachment mark staying Graph's own.
- A row listing none (a draft, perhaps) keeps the decoded reading, its `internetMessageId` read by io-pimdir's `message_id`; the app's own parser goes.
- The page sizes stay: a row is larger, unmeasured.

## Not in this change

A Graph delta, a step and the folder enumeration select no member and name none, so they are unchanged. The `decode` over Graph's `subject` on a row listing no header field, and the addresses read off Graph's `from` and recipients, stay as they were: neverest takes Graph's subject as it comes, so on such a row the two can still differ (see the log).

## What a user meets

Nothing to see: a Graph message's subject, sender and date read the same in the list. A message whose `Message-ID` carries a comment, or whose fields are stated twice, is now keyed as the other apps of the family key it.
