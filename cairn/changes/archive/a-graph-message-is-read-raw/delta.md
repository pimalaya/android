---
cairn: delta
change: a-graph-message-is-read-raw
---

Folds into spec/mail.md.

## ADDED Requirements

### Requirement: A Graph message is named from its raw header fields
Every Graph read that names a mail member SHALL select `internetMessageHeaders` beside the summary `$select`: a band's `/messages` page, the `$batch` of reads by id that names a delta's members the store does not bind, and a read by id alone. A row listing header fields SHALL be named off them through io-pimdir's Annex A derivation (`derive_meta`), as an IMAP or Gmail message is: the `Message-ID`, `In-Reply-To`, `Subject`, `From`, `To`, `Cc`, `Bcc`, `Date` and `Content-Type` it lists, each from its first entry of that name, the name compared without regard to case, whatever Graph decodes beside them (pimdir STORAGE Annex A.1). The size SHALL stay the MAPI size Graph states and the attachment mark Graph's `hasAttachments`, the top-level `Content-Type`'s only where Graph states none. A row listing no header field, the member left out, empty or `null`, SHALL be named from what Graph decodes, its `internetMessageId` read as a `Message-ID` field is by io-pimdir's `message_id`.

#### Scenario: A commented Message-ID
- GIVEN a Microsoft 365 message whose `Message-ID` field reads `(via <relay@example.net>) <comment-1@example.org> (trailing)`
- WHEN its folder is listed
- THEN the message is keyed `comment-1@example.org`, as IMAP, Gmail and neverest key it

#### Scenario: Fields stated twice
- GIVEN a message stating `Subject`, `From` and `Date` twice
- WHEN its folder is listed
- THEN its subject, sender and date are the first of each

#### Scenario: A row Graph lists no header field for
- GIVEN a draft Graph answers no `internetMessageHeaders` for
- WHEN its folder is listed
- THEN it is named from Graph's `subject`, `sentDateTime` and `from`, and keyed by its `internetMessageId` with comments skipped

## MODIFIED Requirements

### Requirement: A message's date is its Date header
A message's date SHALL be its `Date` header, which pimdir STORAGE Annex A.1 stores and sorts mail by, on every backend: the header itself on IMAP and Gmail (asked for among the metadata headers) and on Graph (among `internetMessageHeaders`), `sentDateTime` on a Graph row listing no header field, `sentAt` on JMAP. A message with no date SHALL be stored with none, never with its reception time in its place.

#### Scenario: A message received late
- GIVEN a message sent at 08:00 and received at 08:05
- WHEN its mailbox is synced
- THEN the message's date is 08:00

### Requirement: A listing's text is decoded before it is stored
A subject and a sender's name a listing reads SHALL be stored with their RFC 2047 encoded words decoded, through io-pimdir's Annex A derivation of the header fields the listing read, Graph's `internetMessageHeaders` among them; JMAP hands the decoded value over already, as Graph does for a row listing no header field. A value carrying no encoded word SHALL be stored as it came.

#### Scenario: A subject in another script
- GIVEN a message whose subject the sender wrote as encoded words
- WHEN its mailbox is listed
- THEN the list row shows the subject, not `=?UTF-8?B?...?=`

## REMOVED Requirements

None.
