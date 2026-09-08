---
cairn: spec
capability: mail
status: current
---

# Mail

Mail is a spine: a sync walks every mailbox of every mail account in one authentication and reconciles the newest messages of each as items with a summary and no body, which is what pimdir's detail ladder calls the meta level. The merged list is one descending scan of the sort key across every mail collection, so a listing never parses a date.

A message rises off that rung by being opened: what the open fetched is filed as the item's object, so the item reaches full and every read after it, offline included, is a read of the store.

Every mailbox runs io-pimdir's sync, off the one account walk: the walk is what authenticates and what says which mailboxes there are, and each mailbox's reconcile reads its spine and its meta fetch out of that same cache. So the account is connected to once per pass, and what a pass does is reconcile rather than replace, which is what lets a staged write survive one.

Two backends answer, told apart by the account's base URL: an IMAP session behind an `imaps://` URL, the RFC 8621 verbs behind the `jmap://` marker.

The reader can write three things back, all of them into the store: the markers, whether the message has been read, and where it is filed. A fourth thing, a message of their own, goes into the outbox.

### Requirement: An envelope's text is decoded before it is stored
A subject and a sender's name read off an IMAP `ENVELOPE` SHALL have their RFC 2047 encoded words decoded before the store holds them, by the same decoder a message read whole goes through. A value carrying no encoded word SHALL be stored byte for byte. A JMAP account carries none: RFC 8621 hands the decoded value over already.

#### Scenario: A subject in another script
- GIVEN a message whose subject the sender wrote as encoded words
- WHEN the account is walked
- THEN the list row shows the subject, not `=?UTF-8?B?...?=`

#### Scenario: A sender's own name
- GIVEN a `From` display name written as encoded words
- WHEN the account is walked
- THEN the row and the reader's header show the same name

#### Scenario: A value that only looks encoded
- GIVEN a subject containing `=?` and nothing decodable behind it
- WHEN the account is walked
- THEN it is stored as it came, rather than emptied

### Requirement: A message's markers can be written
The app SHALL write the `\Seen`, `\Answered` and `\Flagged` markers of one message in the store, and the next sync SHALL push the difference between the staged set and the set the source last agreed on, so a keyword the app does not model is never replaced. The markers are named the IMAP way whichever backend answers, JMAP's keywords mapping onto the same three (RFC 8621 section 4.1.1).

#### Scenario: Marking a message read
- GIVEN a message the store holds without `\Seen`
- WHEN the reader marks it read
- THEN the stored placement carries `\Seen` and the list reflects it
- AND the next sync tells the server, over `UID STORE` or `Email/set`

#### Scenario: A keyword the app does not model
- GIVEN a message the server also marks `$junk`
- WHEN the reader flags it and the sync pushes
- THEN only `\Flagged` is added and `$junk` is left alone

#### Scenario: The server refuses
- GIVEN a server rejecting the write
- WHEN the sync pushes the marker
- THEN the failure is reported and the marker stays staged for the sync that follows

### Requirement: Opening a message marks it read
Opening a message SHALL mark it read in the store, once its body has arrived, and the next sync SHALL push it. The fetch itself SHALL NOT: it asks for `BODY.PEEK[]`, so a read that failed leaves the message unread.

#### Scenario: Opening an unread message
- GIVEN an unread message
- WHEN the reader opens it and the body arrives
- THEN it is marked read in the store, and on the server by the next sync

#### Scenario: The body never arrives
- GIVEN an unread message whose fetch fails
- WHEN the reader closes it
- THEN it is still unread

### Requirement: A message is deleted into the account's trash
Deleting a message SHALL stage a removal, and the row SHALL leave the list at once. The next sync SHALL move the message into the mailbox the account records as its trash, with MOVE or with a COPY where the server implements none. Nothing SHALL be expunged, an expunge without `UIDPLUS` being mailbox-wide and taking every message another client had marked.

Where the account records no trash, or the message already sits in it, `\Deleted` SHALL be staged in place and the row SHALL stay in the list, which says so. A JMAP account recording none SHALL refuse the delete outright: RFC 8621 has no counterpart to `\Deleted`, so there is nothing to mark it with, and staging a change nothing could carry out would fail once per sync forever.

#### Scenario: An account with a trash
- GIVEN an account recording a mailbox marked `\Trash`
- WHEN a message is deleted
- THEN the row leaves the list at once
- AND the next sync moves it there

#### Scenario: An IMAP account with no trash
- GIVEN an account recording none
- WHEN a message is deleted
- THEN `\Deleted` is staged where it is, the reader is told so, and the row stays

#### Scenario: A message already in the trash
- GIVEN a message whose mailbox is the trash
- WHEN it is deleted
- THEN `\Deleted` is staged where it is rather than a move into the mailbox it already sits in

#### Scenario: A JMAP account with no trash
- GIVEN a JMAP account recording none
- WHEN a message is deleted
- THEN the delete is refused saying so, and nothing is staged

### Requirement: A mail account carries where it submits and where its trash is
A mail connection SHALL carry the endpoint mail is submitted through, beside the one it is read from, discovered in the same run and stored with it. Implicit TLS only: this client has no STARTTLS step, and driving a `starttls` endpoint as if it were implicit would hand a message over in the clear rather than fail. Every other domain carries none, and so does a mail account whose backend submits through the endpoint it reads from.

The account SHALL also record the mailbox the server marks `\Trash` (RFC 6154), refreshed by every mail sync from the roster the walk builds, so a delete decides between a move and a marker with no round trip.

#### Scenario: An address that publishes both
- GIVEN an address whose discovery turns up IMAP and SMTP
- WHEN the mail domain is connected over IMAP
- THEN the account stores both endpoints

#### Scenario: An account stored before submission existed
- GIVEN an account connected by an earlier version
- WHEN it is read back
- THEN it carries no submit endpoint, and is not offered as a sender

#### Scenario: A walk that found a trash
- GIVEN a server marking one mailbox `\Trash`
- WHEN the account is synced
- THEN that mailbox is recorded, and read back with no network to ask

### Requirement: A message is composed to RFC 5322
The app SHALL compose a message from the composer's fields: `Date`, `Message-ID`, `From`, `To`, `Cc`, `Bcc`, `Subject`, and a `text/plain; charset=utf-8` body. Header lines SHALL fold at 78 columns, a header value carrying anything outside US-ASCII SHALL be encoded as RFC 2047 words, and the body SHALL be quoted-printable with no line past 76. Composing SHALL reach no server: it is a function of the fields alone.

#### Scenario: A subject in two scripts
- GIVEN a subject mixing ASCII words and accented ones
- WHEN it is composed
- THEN the accented run is one or more encoded words and the ASCII words stay readable
- AND a run split across two encoded words carries its own spaces, RFC 2047 section 6.2 having a decoder drop the whitespace between them

#### Scenario: A blind copy
- GIVEN a draft naming a blind copy
- WHEN it is composed
- THEN the message carries it in a `Bcc` header, which RFC 5322 section 3.6.3 provides for a message prepared for sending

### Requirement: A message is sent through an outbox
Submitting SHALL compose the message on the device and stage it in the account's outbox, a collection no server enumerates and no roster replace drops. It SHALL be shown as pending, and deleting it SHALL discard it outright, there being nothing anywhere to tell.

#### Scenario: Composed with no network
- GIVEN no network
- WHEN a message is sent
- THEN it is in the outbox, shown as pending, and the composer closes

#### Scenario: A refresh over it
- GIVEN a message waiting in the outbox
- WHEN the account's mailboxes are re-listed
- THEN it is still there, the outbox being the one collection nothing could hand back

### Requirement: A message is submitted and a copy is kept
A sync draining the outbox SHALL hand each message's bytes to the account's submit endpoint with the envelope its own address headers name, the `Bcc` among them, and that header SHALL leave the bytes on the way out so no copy a recipient receives names a blind one. It SHALL then `APPEND` the stripped copy into the mailbox the server marks `\Sent` (RFC 6154), already `\Seen`, and drop the outbox row. The copy SHALL be filed after the submission and never instead of it. An account with no sent mailbox SHALL send anyway and say no copy was kept.

A message the submission refuses SHALL stay in the outbox, and the drain SHALL stop rather than move on, the usual cause being that there is no network and the next one would fail too.

#### Scenario: The next sync
- GIVEN an outbox message and an account with a submit endpoint and a `\Sent` mailbox
- WHEN the sync drains the outbox
- THEN it is handed over, a copy carrying no `Bcc` is filed, and the outbox row goes

#### Scenario: A submission the server refuses
- GIVEN a server rejecting the envelope
- WHEN the sync drains the outbox
- THEN nothing is filed anywhere, the message stays in the outbox, and the failure is shown

### Requirement: An opened message is stored and read back
The app SHALL store an opened message as its item's object, as the bytes the server sent, and SHALL render a later open from the store without reaching the network. Fetching SHALL happen only for a message the store does not hold.

#### Scenario: The same message twice
- GIVEN a message opened once
- WHEN it is opened again
- THEN it renders from the store, with no request

#### Scenario: A message never opened
- GIVEN a message the store holds at meta
- WHEN it is opened
- THEN the message is fetched, stored, and rendered from what was stored

#### Scenario: Offline
- GIVEN a stored message and no network
- WHEN it is opened
- THEN it renders

### Requirement: A message body is read through the bridge
The bridge SHALL answer the RFC 5322 source of one message (`fetchMessageSource`), and SHALL turn a source into what the reader draws with no network access (`parseMessage`). A JMAP account SHALL read that source by downloading the message's blob (RFC 8620 section 6.2), an IMAP one by fetching it whole with `BODY.PEEK[]`.

#### Scenario: A JMAP message
- GIVEN a JMAP account
- WHEN a message is opened
- THEN its `blobId` is read and the blob downloaded
- AND the reader renders the same shape it renders an IMAP message from
