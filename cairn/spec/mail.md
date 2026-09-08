---
cairn: spec
capability: mail
status: current
---

# Mail

Mail is a spine mirror: a sync walks every mailbox of every mail account in one authentication and stores the newest messages of each as items with a summary and no body, which is what pimdir's detail ladder calls the meta level. The merged list is one descending scan of the sort key across every mail collection, so a listing never parses a date.

Two backends answer, told apart by the account's base URL: an IMAP session behind an `imaps://` URL, the RFC 8621 verbs behind the `jmap://` marker. A message body is fetched each time it is opened and cached nowhere.

The reader can write three things back: the markers, whether the message has been read, and where it is filed.

### Requirement: A message's markers can be written
The app SHALL write the `\Seen`, `\Answered` and `\Flagged` markers of one message on its server, and SHALL apply the same change to the stored envelope in the same pass so the list reflects it before the next sync. The markers are named the IMAP way whichever backend answers, JMAP's keywords mapping onto the same three (RFC 8621 section 4.1.1).

#### Scenario: Marking a message read
- GIVEN a message the store holds without `\Seen`
- WHEN the reader marks it read
- THEN the server is told, over `UID STORE` or `Email/set`
- AND the stored envelope carries `\Seen`

#### Scenario: The server refuses
- GIVEN a server rejecting the write
- WHEN the reader marks a message read
- THEN the stored envelope is left as it was, and the failure is shown

#### Scenario: A marker JMAP has no keyword for
- GIVEN a JMAP account
- WHEN a write names `\Deleted`, which RFC 8621 gives no keyword
- THEN the write fails saying so, rather than inventing one

### Requirement: Opening a message marks it read
Opening a message SHALL mark it read, once its body has arrived. The fetch itself SHALL NOT: it asks for `BODY.PEEK[]`, so a read that failed leaves the message unread.

#### Scenario: Opening an unread message
- GIVEN an unread message
- WHEN the reader opens it and the body arrives
- THEN it is marked read on the server and in the store

#### Scenario: The body never arrives
- GIVEN an unread message whose fetch fails
- WHEN the reader closes it
- THEN it is still unread

### Requirement: A message is deleted into the account's trash
Deleting a message SHALL move it into the mailbox the account names as its trash: the one carrying the RFC 6154 `\Trash` attribute over IMAP, the one whose role is `trash` over JMAP. Nothing SHALL be expunged, an expunge without `UIDPLUS` being mailbox-wide and taking every message another client had marked.

Where an IMAP account names no trash, the message SHALL be marked `\Deleted` in place and SHALL stay in the list, which says so. A JMAP account naming none SHALL fail: RFC 8621 has no counterpart to `\Deleted`, so there is nothing to mark it with.

#### Scenario: An account with a trash
- GIVEN a server marking a mailbox `\Trash`
- WHEN a message is deleted
- THEN it is moved there, with MOVE or with a COPY where the server implements no MOVE
- AND it leaves the list

#### Scenario: An IMAP account with no trash
- GIVEN a server marking no mailbox
- WHEN a message is deleted
- THEN it is marked `\Deleted` where it is, the reader is told so, and the row stays

#### Scenario: A message already in the trash
- GIVEN a message whose mailbox is the trash
- WHEN it is deleted
- THEN it is marked `\Deleted` where it is rather than moved into the mailbox it already sits in

### Requirement: A mail account carries where it submits
A mail connection SHALL carry the endpoint mail is submitted through, beside the one it is read from, discovered in the same run and stored with it. Implicit TLS only: this client has no STARTTLS step, and driving a `starttls` endpoint as if it were implicit would hand a message over in the clear rather than fail. Every other domain carries none, and so does a mail account whose backend submits through the endpoint it reads from.

#### Scenario: An address that publishes both
- GIVEN an address whose discovery turns up IMAP and SMTP
- WHEN the mail domain is connected over IMAP
- THEN the account stores both endpoints

#### Scenario: An account stored before submission existed
- GIVEN an account connected by an earlier version
- WHEN it is read back
- THEN it carries no submit endpoint, and is not offered as a sender

### Requirement: A message is composed to RFC 5322
The app SHALL compose a message from the composer's fields: `Date`, `Message-ID`, `From`, `To`, `Cc`, `Subject`, and a `text/plain; charset=utf-8` body. Header lines SHALL fold at 78 columns, a header value carrying anything outside US-ASCII SHALL be encoded as RFC 2047 words, and the body SHALL be quoted-printable with no line past 76.

#### Scenario: A subject in two scripts
- GIVEN a subject mixing ASCII words and accented ones
- WHEN it is composed
- THEN the accented run is one or more encoded words and the ASCII words stay readable
- AND a run split across two encoded words carries its own spaces, RFC 2047 section 6.2 having a decoder drop the whitespace between them

#### Scenario: A blind copy
- GIVEN a draft naming a blind copy
- WHEN it is composed
- THEN that address is in the envelope and in no header

### Requirement: A message is submitted and a copy is kept
Submitting SHALL hand the composed bytes to the account's submit endpoint with the envelope the composition names, then `APPEND` a copy into the mailbox the server marks `\Sent` (RFC 6154), already `\Seen`. The copy SHALL be filed after the submission and never instead of it. An account with no sent mailbox SHALL send anyway and say no copy was kept.

#### Scenario: A message that is sent
- GIVEN an account with a submit endpoint and a `\Sent` mailbox
- WHEN a message is sent
- THEN it is handed over, a copy is filed, and the composer says where

#### Scenario: A submission the server refuses
- GIVEN a server rejecting the envelope
- WHEN a message is sent
- THEN nothing is filed anywhere and the failure is shown
