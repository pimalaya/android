---
cairn: spec
capability: mail
status: current
---

# Mail

Mail is a spine: a pass connects to an account once and reconciles each of its mailboxes on that session, storing what changed as items with a summary and no body, which is what pimdir's detail ladder calls the meta level. The merged list is one descending scan of the sort key across every mail collection, so a listing never parses a date.

A message rises off that rung by being opened: what the open fetched is filed as the item's object, so the item reaches full and every read after it, offline included, is a read of the store.

Every mailbox runs io-pimdir's sync, on the session the pass opened. One LIST names the mailboxes and each is enumerated in turn: a mailbox carrying a `(UIDVALIDITY, HIGHESTMODSEQ)` cursor on a QRESYNC server is selected with the QRESYNC parameter and the server streams what moved and what went, and anything else falls back to a select and a windowed `UID FLAGS` spine. The connection ENABLEs CONDSTORE and QRESYNC once when it opens, which RFC 7162 section 3.1 requires before the parameter may be used at all. Envelopes are fetched for the UIDs the merge names and no others, so a mailbox nothing touched costs one select. The sync files a new message as a probe, an unnamed handle no listing shows, so every mailbox sync is followed by a meta upgrade of its probes: their envelopes are fetched, each is named by its handle and given its summary, and no body is read.

A full round still takes a window off the end of a mailbox rather than all of it: this store holds a window and not a mailbox, which is the difference between a phone and a desktop replica. A delta round reports changes across the whole mailbox, so the window drifts a little older as flags move outside it; a later full round prunes it back.

Four backends answer, told apart by the account's base URL: an IMAP session behind an `imaps://` URL, the RFC 8621 verbs behind the `jmap://` marker, Microsoft Graph behind the `msgraph://` one, the Gmail API behind `google://`. A Graph mailbox is a mail folder named by its path, and like a JMAP one it answers its newest messages whole every pass. A Gmail mailbox is a label filing mail, its name already a path; a first round reads its newest messages one metadata read each, and every round after replays the history from the `historyId` it stopped at, reading again only the messages that moved.

The reader can write three things back, all of them into the store: the markers, whether the message has been read, and where it is filed. A fourth thing, a message of their own, does not go into the store at all: it is an action on the store's queue, pimdir's write door for what a process wants done somewhere else, with a mail submission as the standard's own worked example. The outbox is that queue read back.

### Requirement: A row's trailing marks sit on the line they describe
A message row SHALL end the subject's line with the date and the mailbox-and-account line with the markers, each aligned with the line it belongs to rather than stacked in a column beside all three. Text pairs SHALL align on their baselines, two sizes reading as one line only that way.

#### Scenario: A row with a long subject
- GIVEN a subject wider than the row
- WHEN it is drawn
- THEN it ellipsizes and the date keeps its place at the end of that same line

#### Scenario: A row with markers
- GIVEN a message that was replied to and marked important
- WHEN it is drawn
- THEN the two icons end the mailbox and account line, not the sender's

### Requirement: An extension is enabled before it is used
A connection SHALL ENABLE CONDSTORE and QRESYNC when it opens, where the server advertises them, RFC 7162 section 3.1 requiring it before a SELECT may carry the QRESYNC parameter. A refused ENABLE SHALL forget the capability rather than fail the connection, and a QRESYNC select the server refuses anyway SHALL fall back to a full round.

#### Scenario: A second pass over a mailbox
- GIVEN a mailbox synced once against a QRESYNC server
- WHEN it is synced again
- THEN the select carries the parameter and the server accepts it

#### Scenario: A server that advertises it and refuses it
- GIVEN a server that answers the parameter with a protocol error
- WHEN a mailbox is enumerated
- THEN the mailbox is enumerated whole and the pass carries on

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

### Requirement: A sync skips what the filter hides
A mail pass SHALL list every account's mailboxes and SHALL NOT reconcile a mailbox the filter hides, by its account or by its name.

#### Scenario: A hidden mailbox
- GIVEN a mailbox unchecked in the filter
- WHEN mail is synced
- THEN the mailbox is still offered by the filter, and nothing in it is read or pushed

### Requirement: A mail sync pushes no body
A mail sync SHALL NOT push content: a message is immutable once sent, and a body the reader stored SHALL NOT be read as an edit. A marker staged on an opened message SHALL be pushed as a marker.

#### Scenario: Flagging an opened message
- GIVEN a message whose body was stored by opening it
- WHEN it is flagged and mail is synced
- THEN the push is a `setFlags`, and the server is flagged

### Requirement: A recipient is a chip
Each address field of the composer SHALL hold one removable chip per recipient. A separator (comma, semicolon, space), the keyboard's next action or leaving the field SHALL turn what was typed into chips, and backspace on an empty input SHALL take the last chip back into it.

#### Scenario: A pasted list
- GIVEN `a@x.org, b@y.org` pasted into the To field
- THEN two chips are shown, and the message is addressed to both

### Requirement: A message's markers can be written
The app SHALL write the `\Seen`, `\Answered` and `\Flagged` markers of one message in the store, and the next sync SHALL push the difference between the staged set and the set the source last agreed on, so a keyword the app does not model is never replaced. The markers are named the IMAP way whichever backend answers, JMAP's keywords mapping onto the same three (RFC 8621 section 4.1.1). On Graph `\Seen` is `isRead` and `\Flagged` the follow-up flag; Graph keeps no answered marker, so a Graph message never carries `\Answered` and writing one is refused. On Gmail `\Seen` is the absence of `UNREAD` and `\Flagged` is `STARRED`, with no answered marker either.

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

Where the account records no trash, or the message already sits in it, `\Deleted` SHALL be staged in place and the row SHALL stay in the list, which says so. A JMAP account recording none SHALL refuse the delete outright: RFC 8621 has no counterpart to `\Deleted`, so there is nothing to mark it with, and staging a change nothing could carry out would fail once per sync forever. A Graph account always records one: its trash is the folder Graph names `deleteditems`, recognised by id when the roster is read, and a delete moves the message there. A Gmail account's trash is the `TRASH` label, and a delete trashes the message.

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
A mail connection SHALL carry the endpoint mail is submitted through, beside the one it is read from, chosen in the connection flow from what discovery turned up or from what was entered by hand, and stored with it. Implicit TLS only: this client has no STARTTLS step, and driving a `starttls` endpoint as if it were implicit would hand a message over in the clear rather than fail. Every other domain carries none, and so does a mail account reading over JMAP, or whose sending was switched off. The sending question, headed Submission, SHALL be asked only once mail is read over IMAP, discovered or typed, or over Graph, and its rows SHALL follow that choice: IMAP reading is offered the SMTP servers, one row per server and sign-in method as the reading rows are, those reading the same collapsed and only those signing in the way mail does offered, since sending reuses its credential, and manual entry, Graph reading Graph alone, Gmail reading Gmail alone, and both the row for not sending. A Graph or Gmail account sending SHALL carry its own `msgraph://` or `google://` base as its submit endpoint.

The account SHALL also record the mailbox the server marks `\Trash` (RFC 6154), refreshed by every mail sync from the roster the LIST builds, so a delete decides between a move and a marker with no round trip.

#### Scenario: An address that publishes both
- GIVEN an address whose discovery turns up IMAP and SMTP
- WHEN the mail domain is connected over IMAP with sending on
- THEN the account stores both endpoints

#### Scenario: An account stored before submission existed
- GIVEN an account connected by an earlier version
- WHEN it is read back
- THEN it carries no submit endpoint, and is not offered as a sender until one is entered in its settings

#### Scenario: A roster that found a trash
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
Submitting SHALL compose the message on the device and stage it as one action on the store's queue: the app's own `submit` kind, a versioned payload naming the sender and what a listing draws, and the composed message written to the blob directory and pinned by the enqueue, all in the one transaction the standard prescribes for a producer. The payload carries the subject and the date because a waiting message is not an item and has no summary row beside it, and parsing a message to draw a list is what the sort key exists to avoid.

The queued actions of an account SHALL be shown as its outbox, above everything the store synced, and discarding one SHALL cancel its row and release its pin, there being nothing anywhere to tell. A parked one SHALL be shown too, saying it was refused rather than that it is waiting: a message the sender wrote is not something to drop quietly.

The waiting messages SHALL be filed under a mailbox named *Outbox*, which the filter's collection axis SHALL offer first, so they can be shown alone or hidden like any mailbox.

#### Scenario: Composed with no network
- GIVEN no network
- WHEN a message is sent
- THEN its action is queued, the message shows as pending, and the composer closes

#### Scenario: A refresh over it
- GIVEN a message waiting to be sent
- WHEN the account's mailboxes are re-listed
- THEN it is still there, a queue row being no collection's to replace

#### Scenario: Discarding one
- GIVEN a queued message
- WHEN it is discarded
- THEN its row is cancelled and the body it pinned is released

### Requirement: A message is submitted and a copy is kept
A sync draining the queue SHALL take an account's pending actions in append order and hand each message's bytes to the account's submit endpoint with the envelope its own address headers name, the `Bcc` among them, that header leaving the bytes on the way out so no copy a recipient receives names a blind one. It SHALL then `APPEND` the stripped copy into the mailbox the server marks `\Sent` (RFC 6154), already `\Seen`. The copy SHALL be filed after the submission and never instead of it, and a copy that could not be filed SHALL NOT be a reason to run the action again: the message has gone, and sending it a second time to file a record of it is worse than the missing record. An account with no sent mailbox SHALL send anyway and say no copy was kept.

A Graph or Gmail account SHALL hand the bytes, `Bcc` still in them, to `sendMail` or `messages.send` on the session it reads from and file no copy: the API reads the recipients off the headers, keeps the blind ones from the recipients, and files the sent copy itself, in `Sent Items` or under `SENT`. A refusal for good is a 400, 403, 404, 413 or 422 there.

The row SHALL be removed by cancelling it once the message has been handed over, and never claimed before: a submission's effect is not a store mutation, so there is nothing to apply, and a claim that deleted the row before the server accepted the message would lose the message. Submission is therefore at-least-once, and a drain interrupted between the handover and the cancel SHALL send the message again.

A submission the server refuses for good, which is a 5yz reply (RFC 5321 section 4.2.1), SHALL park the row with what the server said, counted as one attempt, shown as failed to send and never retried on its own; the drain SHALL carry on to the rows behind it. Any other failure SHALL count an attempt, leave the row pending and stop the drain, the usual cause being that there is no network and the next message would fail too. An action of a kind this app does not carry out SHALL be left pending and untouched, its attempts unbumped.

#### Scenario: The next sync
- GIVEN a queued message and an account with a submit endpoint and a `\Sent` mailbox
- WHEN the sync drains the queue
- THEN it is handed over, a copy carrying no `Bcc` is filed, and the row is cancelled

#### Scenario: A submission the server refuses
- GIVEN a server answering the envelope with a 5yz reply
- WHEN the sync drains the queue
- THEN nothing is filed anywhere, the row is parked carrying the error, and the message shows as failed to send

#### Scenario: One refusal among several
- GIVEN two queued messages, the first of which is refused
- WHEN the sync drains the queue
- THEN the second is still handed over

#### Scenario: No network
- GIVEN a queued message and no network
- WHEN the sync drains the queue
- THEN the attempt is counted, the message stays queued, and the drain stops there

#### Scenario: The copy cannot be filed
- GIVEN a submission the server accepted and a `\Sent` mailbox that refuses the `APPEND`
- WHEN the drain finishes the message
- THEN the row is cancelled anyway and nothing offers the message again

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
The bridge SHALL answer the RFC 5322 source of one message (`fetchMessageSource`), and SHALL turn a source into what the reader draws with no network access (`parseMessage`). A JMAP account SHALL read that source by downloading the message's blob (RFC 8620 section 6.2), an IMAP one by fetching it whole with `BODY.PEEK[]`. A Graph account SHALL read it as the MIME Graph serves at `messages/{id}/$value`, a Gmail one as the `raw` format of the message.

#### Scenario: A JMAP message
- GIVEN a JMAP account
- WHEN a message is opened
- THEN its `blobId` is read and the blob downloaded
- AND the reader renders the same shape it renders an IMAP message from

### Requirement: Mail signs in with a token
An IMAP or SMTP connection whose credential is an OAuth token SHALL authenticate with SASL `XOAUTH2`, the user being the one its URL names. A mail connection signed in through a browser grant SHALL carry the address as that user, on the endpoint it reads from and on the one it submits to. A token IMAP refuses SHALL be renewed once, as an HTTP backend's 401 is.

#### Scenario: A Microsoft mailbox
- GIVEN mail connected through a Microsoft grant
- WHEN a pass opens the mailbox
- THEN the session authenticates with `XOAUTH2` as the address

### Requirement: Submission speaks STARTTLS
An `smtp://` submission endpoint SHALL be upgraded to TLS with `STARTTLS` before anything is authenticated or sent, and SHALL be refused when the server sends anything past its `220` reply.

#### Scenario: Port 587
- GIVEN a submission endpoint on port 587 with STARTTLS
- WHEN a message is submitted
- THEN the session is encrypted before `AUTH`, and nothing goes over the plain socket but `EHLO` and `STARTTLS`
