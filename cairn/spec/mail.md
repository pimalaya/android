---
cairn: spec
capability: mail
status: current
---

# Mail

Mail is a list of summaries: a pass connects to an account on a few sessions and lists its mailboxes on them side by side, storing every message as an item with its summary and sort key and no body, which is what pimdir's detail ladder calls the meta level. The merged list is one descending scan of the sort key across the mail collections the filter lets through, read a page at a time around the scroll position and sized by a count, so a listing never parses a date and never holds the whole store.

A message rises off that rung by being opened, or by the body step its account's offline setting runs behind the list: what was fetched is filed as the item's object, as the bytes the server sent, so the item reaches full and every read after it, offline included, is a read of the store.

Every mailbox runs io-pimdir's sync, on a session of the pass's pool, from its floor: its first pass lists its 50 newest messages, the floor being the oldest `Date` among them, and the end of the list and a background fill widen it a chunk of messages at a time toward the account's bound, all of its mail or the last N months on the `Date` header. One LIST names the mailboxes and a few are listed at once, each on its own session, the inbox begun first. A mailbox carrying a `(UIDVALIDITY, HIGHESTMODSEQ)` checkpoint on a QRESYNC server is selected with the QRESYNC parameter, the server streams what moved and what went, and the messages that moved are read for their header fields in the same pass. Anything else is a round: the UIDs `UID SEARCH` finds in the scope, newest first, read 500 at a time by `UID FETCH` for their markers, their size and the header fields pimdir STORAGE Annex A derives a summary from, `Content-Type` among them and no `BODYSTRUCTURE`. The connection ENABLEs CONDSTORE and QRESYNC once when it opens, which RFC 7162 section 3.1 requires before the parameter may be used at all. Every message a page lists arrives named, so it is listed the moment its page lands: there is no probe and no upgrade after the sync. Each page is one write with the round's resume cursor, so a pass cut off resumes below its last page, and only the round's last page retires what it found absent, within the bound; mail outside the bound is never deleted by a sync, only by narrowing the bound.

Four backends answer, told apart by the account's base URL: an IMAP session behind an `imaps://` URL, the RFC 8621 verbs behind the `jmap://` marker, Microsoft Graph behind the `msgraph://` one, the Gmail API behind `google://`. A Graph mailbox is a mail folder named by its path, its mail listed by band with `/messages` filtered on `sentDateTime`, 1,000 messages a page with the summary `$select`, and its changes followed by one message delta link made with no filter, whose first pass names the folder's messages by id once, made by the pass after the first chunk. A JMAP mailbox is an `Email/query` sorted by `receivedAt`, 500 a page capped by the server's `maxObjectsInGet`, then `Email/changes` from the state read before its first page. A Gmail mailbox is a label filing mail, its name already a path, listed 100 ids a page narrowed by `after:`, each read for its metadata under the account's pacing, and every round after replays the history from the `historyId` taken before its first page, reading again only the messages that moved. Graph, Gmail and JMAP address a mailbox by the id their roster names, and a session reads the roster the first time it addresses a mailbox it holds no id for, whatever opened it: a pass, a widening, a step of the fill, or a session reopened after its connection died.

The reader can write three things back, all of them into the store: the markers, whether the message has been read, and where it is filed. A fourth thing, a message of their own, does not go into the store at all: it is an action on the store's queue, pimdir's write door for what a process wants done somewhere else, with a mail submission as the standard's own worked example. The outbox is that queue read back.

### Requirement: A row's trailing marks sit on the line they describe
A message row SHALL lead the sender's line with the replied mark and end it with the time and, while unread, a dot; end the subject's line with its marks read from the edge inwards (a paperclip while it carries an attachment, a star shown only while it is important), the dot above centred on the paperclip's column; and name the mailbox and account on the third line. The star SHALL be a mark, filled in yellow whatever the theme's accent, not a button. A hairline SHALL part the rows of one card.

#### Scenario: A row with a long subject
- GIVEN a subject wider than the row
- WHEN it is drawn
- THEN it ellipsizes and its marks keep their place at the end of that same line

#### Scenario: A row with markers
- GIVEN an unread message that was replied to, marked important and carrying an attachment
- WHEN it is drawn
- THEN the sender's line opens with the replied mark and ends with the time and the dot, and the subject's line ends with the yellow star and the paperclip under that dot

### Requirement: A mailbox with a role is shown under the role's name
Wherever a mailbox is named to the reader (a row, the reader, the filter page, deleted items), a mailbox whose source states a role a chip names (inbox, sent, drafts, trash, junk, archive) SHALL be shown under that chip's name in the reader's language, and any other under its own name. Servers name those mailboxes in their own words, IMAP's `INBOX` and Gmail's system labels `SENT`, `DRAFT`, `TRASH` and `SPAM` among them. The name the server knows the mailbox by stays its address.

#### Scenario: Gmail's sent mail
- GIVEN a Gmail account, whose sent mail is the system label `SENT`
- WHEN one of its messages is listed on a phone set to French
- THEN its row names the mailbox *Envoyés*

### Requirement: The mail list narrows what it shows
The mail list SHALL list every stored message of every mailbox the filter lets through, and SHALL offer a search over the sender and the subject, the role chips, an unread chip and an attachments chip, all answered by the store over every stored message. None of these SHALL change what syncs, which the filter alone decides.

#### Scenario: Unread only
- GIVEN read and unread mail in two accounts
- WHEN the unread chip is on
- THEN the list shows both accounts' unread messages and nothing else

### Requirement: The mail list selects
A long press on a message SHALL start a selection holding it, and while one runs a tap SHALL add or remove a message rather than open it. The bar SHALL then carry the count, read or unread, star or unstar, delete after asking, and select-all, each over the whole selection, a toggle going the way that changes something. The selection SHALL be keyed by store id, and select-all SHALL select what the list's query lets through, read whole only when the bar acts on it. Back SHALL clear the selection.

#### Scenario: Starring three messages
- GIVEN three messages, one of them starred
- WHEN they are selected and the bar's star is pressed
- THEN all three are starred

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

### Requirement: A sync skips what the filter hides
A mail pull SHALL list the shown accounts' mailboxes and SHALL NOT reconcile a mailbox the filter hides, by its account or by its collection, nor drain an outbox it hides. The drawer's sync SHALL reconcile every mailbox of every account that is on.

#### Scenario: A hidden mailbox
- GIVEN a mailbox unchecked in the filter
- WHEN the mail list is pulled down
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
Deleting a message outside the trash SHALL stage a move into the mailbox the account records as its trash: the row SHALL leave its mailbox and show in the trash at once, marked pending. The next sync SHALL relocate it as staged: on IMAP with MOVE, or where the server implements no MOVE with COPY and `\Deleted`, then `UID EXPUNGE` where it announces `UIDPLUS`; on Graph into the folder Graph names `deleteditems`, recognised by id when the roster is read; on Gmail with `messages.trash`; on JMAP through `mailboxIds`.

Deleting a message in the trash SHALL stage a removal, pushed as a permanent delete: on IMAP `\Deleted` then `UID EXPUNGE` (RFC 4315); on Graph `permanentDelete`; on Gmail `messages.delete`; on JMAP `Email/set` destroy. Where an IMAP account records no trash, or the message sits in the trash of an account whose last session announced no `UIDPLUS` (an expunge without it is mailbox-wide), a delete SHALL stage `\Deleted` in place: the row stays, its line saying it is marked deleted, and the toast says so. An account no session recorded yet SHALL read as announcing it. A JMAP account recording none SHALL refuse the delete outright: RFC 8621 has no counterpart to `\Deleted`. Deleting a message still in the outbox SHALL cancel its submission and withdraw its staged sent copy, there being nothing on the server to tell.

Deleting SHALL ask first, from the reader and from a selection alike. Where the delete removes messages for good and the phone holds no body of some of them, the question SHALL say how many: Deleted items keeps only what was stored, so those cannot be restored, and it SHALL say that opening them or downloading the trash keeps them restorable.

#### Scenario: An account with a trash
- GIVEN an account recording a mailbox marked `\Trash`
- WHEN a message is deleted
- THEN the row leaves the list and shows in the trash at once, marked pending
- AND the next sync moves it there

#### Scenario: Emptying a trash never opened
- GIVEN a selection of 12 messages in the trash, 9 of them never opened
- WHEN delete is pressed
- THEN the question says 9 of them are not on this phone and cannot be restored once deleted

#### Scenario: A message already in the trash
- GIVEN a message in the trash of a server announcing `UIDPLUS`
- WHEN it is deleted
- THEN the next sync expunges that one message, and no other

#### Scenario: An IMAP account with no trash
- GIVEN an account recording none
- WHEN a message is deleted
- THEN `\Deleted` is staged where it is, the reader is told so, and the row stays, marked deleted

#### Scenario: A JMAP account with no trash
- GIVEN a JMAP account recording none
- WHEN a message outside a trash is deleted
- THEN the delete is refused saying so, and nothing is staged

### Requirement: A mail account carries where it submits and where its trash is
A mail connection SHALL carry the endpoint mail is submitted through, beside the one it is read from, chosen in the connection flow from what discovery turned up or from what was entered by hand, and stored with it. Implicit TLS only: this client has no STARTTLS step, and driving a `starttls` endpoint as if it were implicit would hand a message over in the clear rather than fail. Every other domain carries none, and so does a mail account reading over JMAP, or whose sending was switched off. The sending question, headed Submission, SHALL be asked only once mail is read over IMAP, discovered or typed, or over Graph, and its rows SHALL follow that choice: IMAP reading is offered the SMTP servers, one row per server and sign-in method as the reading rows are, those reading the same collapsed and only those signing in the way mail does offered, since sending reuses its credential, and manual entry, Graph reading Graph alone, Gmail reading Gmail alone, and both the row for not sending. A Graph or Gmail account sending SHALL carry its own `msgraph://` or `google://` base as its submit endpoint.

The account SHALL also record the mailbox the server marks `\Trash` (RFC 6154), refreshed by every mail sync from the roster the LIST builds, and whether its last session can expunge one message alone (`UIDPLUS` on IMAP, always elsewhere), so a delete decides between a move, a delete for good and a marker with no round trip.

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
The app SHALL compose a message from the composer's fields: `Date`, `Message-ID`, `From`, `To`, `Cc`, `Bcc`, `Subject`, a reply's `In-Reply-To` and `References`, and a `text/plain; charset=utf-8` body. Header lines SHALL fold at 78 columns, `References` one msg-id per line, a header value carrying anything outside US-ASCII SHALL be encoded as RFC 2047 words, and the body SHALL be quoted-printable with no line past 76. Composing SHALL reach no server: it is a function of the fields alone.

#### Scenario: A subject in two scripts
- GIVEN a subject mixing ASCII words and accented ones
- WHEN it is composed
- THEN the accented run is one or more encoded words and the ASCII words stay readable
- AND a run split across two encoded words carries its own spaces, RFC 2047 section 6.2 having a decoder drop the whitespace between them

#### Scenario: A blind copy
- GIVEN a draft naming a blind copy
- WHEN it is composed
- THEN the message carries it in a `Bcc` header, which RFC 5322 section 3.6.3 provides for a message prepared for sending

### Requirement: The reader replies and forwards
The reader SHALL offer Reply and Forward under the message. Reply SHALL answer everyone on it: the sender, and every other address in its `To` and `Cc`, the account's own left out; a message the account sent itself answers its recipients. The reply SHALL be sent from the message's account, carry the subject behind one `Re:`, quote the text under a line naming who wrote it and when, and carry `In-Reply-To` and `References` built from the `Message-ID` and `In-Reply-To` the store keeps for the message. Once queued, it SHALL mark the parent `\Answered` where the backend keeps that marker. Forward SHALL open on no recipient, the subject behind one `Fwd:`, and the message's headers over its text; the attachments stay behind.

#### Scenario: Replying to a message with a copy
- GIVEN a message from Ada to the account and Bob, copying Carol
- WHEN Reply is pressed
- THEN the composer goes to Ada and Bob, copies Carol, and the account's own address is on neither line

### Requirement: A message is sent through an outbox
Submitting SHALL compose the message on the device and stage it as one action on the store's queue: the app's own `submit` kind, a versioned payload naming the sender and what a listing draws, and the composed message written to the blob directory and pinned by the enqueue, all in the one transaction the standard prescribes for a producer. The payload carries the subject and the date because a waiting message is not an item and has no summary row beside it, and parsing a message to draw a list is what the sort key exists to avoid.

The queued actions of an account SHALL be shown as its outbox, above everything the store synced, and discarding one SHALL cancel its row and release its pin, there being nothing anywhere to tell. A parked one SHALL be shown too, saying it was refused rather than that it is waiting: a message the sender wrote is not something to drop quietly.

The waiting messages SHALL be filed under each account's own *Outbox*, which the filter page SHALL offer first among the account's mailboxes, so they can be shown alone or hidden like any mailbox.

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
A sync draining the queue SHALL take an account's pending actions in append order and hand each message's bytes to the account's submit endpoint with the envelope its own address headers name, the `Bcc` among them, that header leaving the bytes on the way out so no copy a recipient receives names a blind one. A Graph or Gmail account SHALL hand the bytes, `Bcc` still in them, to `sendMail` or `messages.send` on the session it reads from: the API reads the recipients off the headers, keeps the blind ones from the recipients, and files the sent copy itself, in `Sent Items` or under `SENT`. A refusal for good is a 400, 403, 404, 413 or 422 there.

The submission SHALL file no copy itself: the copy is the create staged in the sent mailbox beside it (A sent message is in Sent at once), carried out by a push of its own. On IMAP that push SHALL `APPEND` the message as composed, already `\Seen`, its `Bcc` kept as the sender's record of whom it went to, and SHALL wait while the submission is queued or parked: a copy filed for a message that was not sent is a lie the sender reads as a sent message. A copy that could not be filed SHALL stay pending for the next sync and SHALL NOT be a reason to submit again. An account marking no sent mailbox SHALL send anyway, with no copy staged.

The row SHALL be removed by cancelling it once the message has been handed over, and never claimed before: a submission's effect is not a store mutation, so there is nothing to apply, and a claim that deleted the row before the server accepted the message would lose the message. Submission is therefore at-least-once, and a drain interrupted between the handover and the cancel SHALL send the message again.

A submission the server refuses for good, which is a 5yz reply (RFC 5321 section 4.2.1), SHALL park the row with what the server said, counted as one attempt, shown as failed to send and never retried on its own; the drain SHALL carry on to the rows behind it. Any other failure SHALL count an attempt, leave the row pending and stop the drain, the usual cause being that there is no network and the next message would fail too. An action of a kind this app does not carry out SHALL be left pending and untouched, its attempts unbumped.

#### Scenario: The next sync
- GIVEN a queued message and an IMAP account with a submit endpoint and a `\Sent` mailbox
- WHEN the sync drains the queue and pushes
- THEN it is handed over, the row is cancelled, and the staged copy is appended to the sent mailbox

#### Scenario: A submission the server refuses
- GIVEN a server answering the envelope with a 5yz reply
- WHEN the sync drains the queue
- THEN nothing is appended anywhere, the row is parked carrying the error, and the message shows as failed to send

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
- WHEN the sync pushes the copy
- THEN the submission's row is cancelled anyway, nothing offers the message again, and the copy stays pending for the next sync

### Requirement: An opened message is stored and read back
The app SHALL store an opened message as its item's object, as the bytes the server sent, and SHALL render a later open from the store without reaching the network, whether the body was stored by an open or by the body step. Fetching SHALL happen only for a message the store does not hold.

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

#### Scenario: Downloaded in the background
- GIVEN a message whose body the body step stored
- WHEN it is opened with no network
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

### Requirement: A message's date is its Date header
A message's date SHALL be its `Date` header, which pimdir STORAGE Annex A.1 stores and sorts mail by, on every backend: the header itself on IMAP and Gmail (asked for among the metadata headers), `sentDateTime` on Graph, `sentAt` on JMAP. A message with no date SHALL be stored with none, never with its reception time in its place.

#### Scenario: A message received late
- GIVEN a message sent at 08:00 and received at 08:05
- WHEN its mailbox is synced
- THEN the message's date is 08:00

### Requirement: An HTTP sync rides out throttling
Every HTTP backend (Graph, Gmail, Google Calendar and People, CalDAV, CardDAV, JMAP) SHALL send a request again when the server answers 429, 503, or 403 with Google's `rateLimitExceeded` or `userRateLimitExceeded`, after the `Retry-After` the server named, or else after an exponential back-off with jitter. The waiting SHALL be bounded per request and per native call, a `Retry-After` past the bound SHALL NOT be waited, and past the bound the throttled answer SHALL fail the request as any error does. An answer whose end cannot be told SHALL NOT be retried.

#### Scenario: Graph asks for a pause
- GIVEN a Graph request answered 429 with `Retry-After: 2`
- WHEN the bridge reads the answer
- THEN it waits two seconds and sends the request again, and the sync goes on with the answer to that

#### Scenario: A server that keeps throttling
- GIVEN a server answering 503 to every request
- WHEN a sync pass reaches it
- THEN the request fails with the 503 within the bound, and the pass reports it as it reports any error

### Requirement: Gmail is paced below its quota
Gmail API requests SHALL be paced near 40 a second across every worker of the process, below the 50 metadata reads a second Gmail's per-user quota allows, rather than sent until Gmail answers 429.

#### Scenario: A first round over a large label
- GIVEN several workers reading Gmail envelopes at once
- WHEN they run
- THEN their requests together go out at about 40 a second

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within its scope (its floor, within the account's bound), newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph `/messages` page, 100 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`, or for a delta's member the store does not bind, from that `$select` read by id; Gmail from its metadata read; JMAP from `Email/get`'s summary properties. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within its scope; mail outside it SHALL never be deleted by a sync. The chunks and the fill (A mailbox is listed a chunk at a time, Older mail fills in behind) SHALL bring a mailbox whole within the account's bound.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 100k messages and an empty store
- WHEN it is synced
- THEN its newest chunk is listed with its subject, sender and date before the first sync ends
- AND the rest is listed behind it, a chunk at a time

#### Scenario: An interrupted first pass
- GIVEN a round cut off after its first page
- WHEN the mailbox is synced again
- THEN it resumes below the last landed page rather than from the top

#### Scenario: A bounded account
- GIVEN an account bounded to the last 6 months
- WHEN it is synced and filled
- THEN older messages are neither fetched nor listed, and none already stored is deleted

### Requirement: An account bounds its mail
An account's settings SHALL offer to sync all of its mail or the last 1, 3, 6, 12 or 24 months, the floor being the first day of the month that many months back, on the `Date` header (a message with no usable date in every scope). A mailbox's floor SHALL never go below its bound: its account's, or none for a mailbox kept whole or an account set to whole mailbox. A chunk reaching past the bound stops at it, and a mailbox whose floor is the bound is whole. A provider's received-date filter SHALL only narrow a listing, two days below the floor (IMAP `SENTSINCE` one day below). Widening the bound SHALL have the fill carry on below the old floor, chunk by chunk. Narrowing it SHALL collect the stored messages dated below the new floor that owe nothing to the server, their mailboxes keeping them there, except in a mailbox kept whole.

#### Scenario: Narrowing to a year
- GIVEN an account syncing all of its mail
- WHEN its bound is set to the last year
- THEN the messages older than that leave the store, except one with a change not pushed yet or one in a mailbox kept whole
- AND nothing is deleted on the server

#### Scenario: Widening again
- GIVEN that account
- WHEN its bound is set back to all mail and the app stays open on an unmetered network
- THEN the older messages are listed again, a chunk at a time

### Requirement: The mail list loads lazily
The mail list SHALL hold only the rows near the scroll position, read a page at a time from the store and the far pages evicted, sized by a count of what the filter, the chips, the search and the list's floor let through, with a placeholder row while a page loads. It SHALL place its day headers from one count per day, without loading rows. The messages waiting to go out SHALL stay on top, outside the paged query. Search, the chips and the unread badge SHALL be conditions of the store's query, search and the badge covering every stored message. A list showing the store SHALL redraw as a pass's pages land.

#### Scenario: Scrolling to old mail
- GIVEN 100k stored messages in one mailbox listed whole
- WHEN the list is flung to its end
- THEN the oldest message is shown, and memory holds a bounded number of rows

#### Scenario: Searching old mail
- GIVEN a message from three years ago, stored
- WHEN its sender is searched with no network
- THEN it is found

### Requirement: A listing's text is decoded before it is stored
A subject and a sender's name a listing reads SHALL be stored with their RFC 2047 encoded words decoded, through io-pimdir's Annex A derivation of the header fields the listing read; JMAP and Graph hand the decoded value over already. A value carrying no encoded word SHALL be stored as it came.

#### Scenario: A subject in another script
- GIVEN a message whose subject the sender wrote as encoded words
- WHEN its mailbox is listed
- THEN the list row shows the subject, not `=?UTF-8?B?...?=`

### Requirement: The attachment mark is corrected by the body
A listing SHALL mark a message as carrying an attachment from the source's own flag where it states one (Graph `hasAttachments`, JMAP `hasAttachment`), else when its top-level `Content-Type` is `multipart/mixed`. Opening the message SHALL replace that mark with the one the walk of its parts gives.

#### Scenario: A list footer
- GIVEN a `multipart/mixed` message carrying no attachment, listed with a paperclip
- WHEN it is opened
- THEN its row loses the paperclip

### Requirement: A mailbox is listed a chunk at a time
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be the oldest `Date` among the 50 newest the source names (the last 50 UIDs `UID SEARCH` finds, Graph's `$top` ordered by `sentDateTime`, Gmail's `messages.list`, JMAP's `Email/query` by `receivedAt`), or no floor when it holds fewer, within the account's bound. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the oldest `Date` among the newest messages dated before it, and list only the band it lacks, on every backend: no checkpoint is bound to a scope.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 3,000 messages never listed
- WHEN it is synced
- THEN its 50 newest are listed, and its floor is the oldest `Date` among them

#### Scenario: A small mailbox
- GIVEN a mailbox of 20 messages
- WHEN it is synced the first time
- THEN all of them are listed, and nothing is left below its floor

#### Scenario: Widening
- GIVEN a mailbox listed down to its 50th newest message
- WHEN it is widened
- THEN the next 50 below the floor are listed, the band alone

### Requirement: A Graph mailbox keeps one unfiltered delta link
A Graph mailbox SHALL keep one message delta link made with no filter, selecting the id, `sentDateTime`, `isRead` and `flag`, every request asking 1,000 a page (`Prefer: odata.maxpagesize`), so its checkpoint is bound to no scope. Its mail SHALL be listed by band: a first chunk, a widening and the fill SHALL list only the band they lack by `/messages` filtered on `sentDateTime` (from the floor, below the ceiling: the `Date` header, exactly), newest first, up to 1,000 a page with the summary `$select`, a page resuming below the oldest `Date` the pages before reached rather than by `$skip`. A first chunk SHALL land without waiting on the delta link: the round a later pass opens over the scope the store covers SHALL make it, walking the delta's first pass a page at a time, listing the members in scope by id and markers, and closing with the link, so what changed or went between the chunk and the link is read there. A delta SHALL apply a removal whatever the date, drop a change to a message dated out of the scope, and list one in scope by id and markers alone: a message the store binds keeps its summary, any other one is read by id with the summary `$select`, 20 to a `$batch`. An expired link (410), or one made under a filter by an earlier version, SHALL open the round that makes a new one, relisting no band.

#### Scenario: The first chunk does not wait on the link
- GIVEN a Graph inbox of 50,000 messages never listed
- WHEN the first sync fetches it
- THEN its 50 newest are listed by `/messages` and no delta is asked for
- AND the next pass names the 50,000 by id once and keeps the link

#### Scenario: Widening relists nothing
- GIVEN a Graph inbox listed down to its 50th newest message, its delta link made
- WHEN it is widened three times
- THEN 150 more messages are listed, the band alone each time, and the link is kept

#### Scenario: A change out of scope
- GIVEN a Graph inbox listed down to September
- WHEN a message from March is marked read elsewhere
- THEN the next delta reports it, and nothing is stored

#### Scenario: A message gone before the link
- GIVEN a Graph inbox whose first chunk is listed, its link not made yet
- WHEN a message of the chunk is deleted elsewhere
- THEN the pass that makes the link removes it

### Requirement: The merged list reaches down to its mailboxes' floor
The merged list SHALL show no message older than the most recent floor among the mailboxes it shows, so no mailbox's older mail is listed while another's of the same days is not stored yet; a search SHALL still cover every stored message. While a floor holds mail back, the list SHALL end on a row that, once reached, widens by one chunk each shown mailbox holding that floor and reads the list again, or says older mail needs the network when there is none, and that it could not be loaded, with a tap to try again, when it failed with a network there.

#### Scenario: Two mailboxes at different depths
- GIVEN an inbox listed down to Tuesday and a sent mailbox down to the week before
- WHEN the list is scrolled to its end
- THEN it ends at Tuesday, and the inbox's next chunk is listed

#### Scenario: Offline
- GIVEN no network
- WHEN the list's end is reached while a floor holds mail back
- THEN the last row says older mail needs the network, and nothing is asked for

#### Scenario: A widening that fails online
- GIVEN a network, and a server refusing the widening
- WHEN the list's end is reached
- THEN the last row says older mail could not be loaded, not that it needs the network
- AND a tap on it asks again

### Requirement: A pass takes the inbox first
Every mail pass SHALL take an account's mailboxes in the order of the role each source states: the inbox, the sent mail, the drafts, every other by name, the junk and the trash last, the workers of a pool beginning them in that order. The roles SHALL be stored as pimdir's collection roles (STORAGE section 14): RFC 6154 attributes and `INBOX` on IMAP, RFC 8621 roles, Gmail's system labels, Graph's well-known folders.

#### Scenario: A Graph account
- GIVEN Graph listing *Sent Items*, *Projets* and *Inbox* in that order
- WHEN the account is synced
- THEN *Inbox* is begun first and *Sent Items* second

### Requirement: Older mail fills in behind
After a mail tab's first sync, and on every return to the app or pass after it, every mailbox SHALL widen 500 messages at a time toward its account's bound, with no dialog, the inbox and the sent mail first, a mailbox never listed before one that only lacks older mail, and among them the one holding the most recent floor; a step SHALL widen the next mailboxes in that order side by side, as many of an account as its pool runs. The fill SHALL run only while the app is in the foreground, on a network that is not metered, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers.

#### Scenario: Leaving the app
- GIVEN a fill under way
- WHEN the app goes to the background
- THEN no further chunk is listed, and the next return resumes below the floors reached

#### Scenario: A metered network
- GIVEN a phone on mobile data
- WHEN the first sync ends
- THEN only the first chunks are stored, until an unmetered network is back

### Requirement: Each page's time is logged
A mail pass SHALL log, page by page, how many messages a page listed and the time spent on the network, on the JSON this side reads and writes, in the engine, and in the store's loads and writes; and, for each account's run, its wall time against the network time summed over its mailboxes.

#### Scenario: A first round
- GIVEN a debug build
- WHEN a mailbox's page lands
- THEN the log names its count and the four times

#### Scenario: A pass's gain
- GIVEN a debug build
- WHEN an account's mailboxes have all landed
- THEN the log names how many ran on how many sessions, the wall time and the network time summed

### Requirement: An account's mailboxes sync side by side
A mail pass, the first sync, the scroll widening and the background fill SHALL run an account's mailboxes concurrently on a pool of sessions, four for Graph and JMAP, three for IMAP, two for Gmail, each worker on a session of its own that no other worker uses while it runs, the workers taking the mailboxes in the pass's order. Every storage load, lookup and write SHALL be answered by one writer at a time, so only the network overlaps. A mailbox that fails SHALL leave the others running, the pass reporting the first failure. The first sync SHALL end once every mailbox's first chunk has landed, its strip saying how many of the account's mailboxes have.

#### Scenario: A first sync of twelve mailboxes on Graph
- GIVEN a Graph account of twelve mailboxes never listed
- WHEN its first sync runs
- THEN four mailboxes are listed at once, the inbox begun first, and the first sync ends once all twelve chunks have landed

#### Scenario: One mailbox refused
- GIVEN a pass over five mailboxes, one of which the server refuses
- WHEN the pass runs
- THEN the other four are stored, and the pass reports the refusal

#### Scenario: Two pages landing together
- GIVEN two mailboxes whose pages arrive at the same moment
- WHEN both are written
- THEN one write lands whole before the other begins

### Requirement: A sent message is in Sent at once
Sending SHALL stage the message as composed into the mailbox the account marks as its sent mail, beside its submission, so Sent shows it before any sync, marked pending. Gmail and Graph file sent mail themselves: before a page reaches the engine, an arrival carrying the `Message-ID` of a pending create in that mailbox SHALL be named by the create's key, so the listing lands the staged copy and nothing is pushed there. Gmail stamps a `Message-ID` of its own on what it sends: the submission SHALL read Gmail's sent copy back for it, and an arrival carrying that one SHALL be named by the create staged under the composed one. On IMAP the staged copy's push SHALL be the append, made only once the submission went (A message is submitted and a copy is kept). A JMAP account, which sends nothing yet, and an account marking no sent mailbox SHALL stage none. While the submission is in the outbox, a list showing the outbox SHALL list its outbox row alone, not the staged copy; discarding the outbox row SHALL withdraw the copy with it.

#### Scenario: Sent offline
- GIVEN a message sent with no network
- WHEN the Sent mailbox is opened
- THEN the message is there, marked pending

#### Scenario: A provider filing sent mail itself
- GIVEN a message sent from a Graph account
- WHEN the next sync lists `Sent Items`
- THEN the staged copy is landed on Graph's own copy, and Sent holds one

#### Scenario: Gmail renaming what it sends
- GIVEN a message sent from a Gmail account, which Gmail sends under a `Message-ID` of its own
- WHEN the next sync lists `SENT`
- THEN the staged copy is landed on Gmail's copy, and Sent holds one

### Requirement: An account chooses which bodies it keeps offline
A mail account's settings SHALL offer three offline policies: bodies on open (the default), where a body is fetched when its message is opened; bodies in the background; and whole mailbox, which sets the account's bound to all mail and holds it there, the bound's choice dimmed, and downloads bodies in the background. The settings SHALL also offer a switch, off by default, to download bodies on a metered network. Both SHALL be committed when picked. The bound SHALL be the only limit on what is kept: there is no storage cap, space being freed from Deleted items and by narrowing the bound.

#### Scenario: The default
- GIVEN a mail account just added
- WHEN a pass lists its mailboxes
- THEN no body is downloaded until a message is opened

#### Scenario: Whole mailbox
- GIVEN an account bounded to the last 6 months
- WHEN its offline policy is set to whole mailbox
- THEN its bound reads all mail and cannot be changed, the fill lists below the old floor, and every listed body is downloaded

### Requirement: A mailbox can be downloaded whole
A mailbox's row on the mail filter page SHALL offer "Download", which after a confirmation keeps that mailbox whole whatever its account's policy: its scope SHALL be all of its mail, past the account's bound, the fill SHALL widen it below its floor, narrowing the account's bound SHALL NOT collect it, and every listed body SHALL be downloaded. The row SHALL then say it is kept whole and offer "Stop", which returns it to the account's bound and policy, what is stored staying until the bound is narrowed again.

#### Scenario: An archive kept whole
- GIVEN an account bounded to the last year, with bodies on open
- WHEN its Archive is downloaded from the filter page
- THEN every message of the Archive is listed and its body stored, and the account's other mailboxes keep their year and fetch bodies on open

### Requirement: Bodies download behind the list
For each account that is on, and whose policy downloads bodies or holds a mailbox kept whole, the app SHALL raise the listed messages lacking a body to `Full` through the engine's upgrade, after each pass and each fill step while the app is open: newest first, the mailboxes the mail filter shows before the others, at most 24 bodies an account a step, side by side on the account's pool. A message dated below its mailbox's bound SHALL NOT be downloaded, nor an undated one where a bound applies, nor a pending create. A body the store already holds under the same link id SHALL be linked rather than fetched. The body SHALL be stored as the bytes the server sent, its base moved to it, its summary and sort key kept, its attachment mark restated from its parts. The step SHALL run only while the app is in the foreground, no other sync runs, and a network is there that is unmetered unless the account allows a metered one; it SHALL never be periodic work, and SHALL stop when the user starts a sync or leaves the app. A body the server fails to hand over SHALL be left below `Full` and tried by a later run; an account whose download fails as a whole (no session, an expired token) SHALL leave the run. Gmail body reads SHALL count against the account's per-minute quota units like any other read, with no pacing of their own.

#### Scenario: Within the bound only
- GIVEN an account bounded to the last year with bodies in the background, holding a message from yesterday and one from three years back
- WHEN the body step runs
- THEN yesterday's body is downloaded and stored, the other stays a summary

#### Scenario: A metered network
- GIVEN an account with bodies in the background on mobile data
- WHEN a pass ends
- THEN no body is downloaded until an unmetered network is back, or the account allows metered networks

#### Scenario: The same message under two labels
- GIVEN a Gmail message under two labels, its body downloaded in one
- WHEN the body step reaches the other
- THEN the body is linked from the store and not read again

### Requirement: The drawer shows bodies left to download
While the body step runs for an account, the account's pill in the drawer SHALL say how many bodies it has left to download, in place of when it last synced, and SHALL go back to that once the step ends.

#### Scenario: A first whole download
- GIVEN an account set to whole mailbox
- WHEN the drawer is opened while its bodies download
- THEN its pill counts the messages left, falling as they land
