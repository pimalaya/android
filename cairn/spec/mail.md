---
cairn: spec
capability: mail
status: current
---

# Mail

Mail is a list of summaries: a pass connects to an account on a few sessions and lists its mailboxes on them side by side, storing every message as an item with its summary and sort key and no body, which is what pimdir's detail ladder calls the meta level. The merged list is one descending scan of the sort key across the mail collections the filter lets through, read a page at a time around the scroll position and sized by a count, so a listing never parses a date and never holds the whole store.

A message rises off that rung by being opened, or by a step of its account (about 50 messages brought whole, older than what it holds) and the body step behind the list, on Wi-Fi only: what was fetched is filed as the item's object, as the bytes the server sent, so the item reaches full and every read after it, offline included, is a read of the store.

Every mailbox but Gmail's and JMAP's runs io-pimdir's sync, on a session of the pass's pool, from its floor: its first pass lists its 50 newest messages, the floor being the `Date` of the 50th by `Date` on IMAP and the oldest `Date` among them elsewhere, and a background fill widens it a chunk of messages at a time toward the account's bound, all of its mail or the last N months on the `Date` header. One LIST names the mailboxes and a few are listed at once, each on its own session, the inbox begun first. A mailbox carrying a `(UIDVALIDITY, HIGHESTMODSEQ)` checkpoint on a QRESYNC server is selected with the QRESYNC parameter, the server streams what moved and what went, and the messages that moved are read for their header fields in the same pass. Anything else is a round: the UIDs `UID SEARCH` finds in the scope, newest first, read 500 at a time by `UID FETCH` for their markers, their size and the header fields pimdir STORAGE Annex A derives a summary from, `Content-Type` among them and no `BODYSTRUCTURE`. The connection ENABLEs CONDSTORE and QRESYNC once when it opens, which RFC 7162 section 3.1 requires before the parameter may be used at all. Every message a page lists arrives named, so it is listed the moment its page lands: there is no probe and no upgrade after the sync. Each page is one write with the round's resume cursor, so a pass cut off resumes below its last page, and only the round's last page retires what it found absent, within the bound; mail outside the bound is never deleted by a sync, only by narrowing the bound.

Four backends answer, told apart by the account's base URL: an IMAP session behind an `imaps://` URL, the RFC 8621 verbs behind the `jmap://` marker, Microsoft Graph behind the `msgraph://` one, the Gmail API behind `google://`. A Graph mailbox is a mail folder named by its path, its mail listed by band with `/messages` filtered on `sentDateTime`, 1,000 messages a page with the summary `$select`, and its changes followed by one message delta link made with no filter, whose first pass names the folder's messages by id once, made by the pass after the first chunk. A JMAP account is stepped through as one account: a step is one `Email/query` over its mailboxes but the trash and the junk, anchored where the last one stopped, each `Email` read once and placed in every mailbox its `mailboxIds` names, and every pass after replays `Email/changes` once from the state read before the first step, each mailbox projecting it. A Gmail mailbox is a label filing mail, its name already a path; the account is stepped through once, its messages read whole 10 to a batch under the account's pacing and placed in every label they carry, and every pass after replays the history from the `historyId` taken before the first step, reading again only the messages that moved. Graph, Gmail and JMAP address a mailbox by the id their roster names, and a session reads the roster the first time it addresses a mailbox it holds no id for, whatever opened it: a pass, a widening, a step of the fill, or a session reopened after its connection died.

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
Deleting a message outside the trash SHALL stage a move into the mailbox the account records as its trash: the row SHALL leave its mailbox and show in the trash at once. The next sync SHALL relocate it as staged: on IMAP with MOVE, or where the server implements no MOVE with COPY and `\Deleted`, then `UID EXPUNGE` where it announces `UIDPLUS`; on Graph into the folder Graph names `deleteditems`, recognised by id when the roster is read; on Gmail with `messages.trash`; on JMAP through `mailboxIds`.

Deleting a message in the trash SHALL stage a removal, pushed as a permanent delete: on IMAP `\Deleted` then `UID EXPUNGE` (RFC 4315); on Graph `permanentDelete`; on Gmail `messages.delete`; on JMAP `Email/set` destroy. Where an IMAP account records no trash, or the message sits in the trash of an account whose last session announced no `UIDPLUS` (an expunge without it is mailbox-wide), a delete SHALL stage `\Deleted` in place: the row stays, its line saying it is marked deleted, and the toast says so. An account no session recorded yet SHALL read as announcing it. A JMAP account recording none SHALL refuse the delete outright: RFC 8621 has no counterpart to `\Deleted`. Deleting a message still in the outbox SHALL cancel its submission and withdraw its staged sent copy, there being nothing on the server to tell.

Deleting SHALL ask first, from the reader and from a selection alike. Where the delete removes messages for good and the phone holds no body of some of them, the question SHALL say how many: Deleted items keeps only what was stored, so those cannot be restored, and it SHALL say that opening them or downloading the trash keeps them restorable.

#### Scenario: An account with a trash
- GIVEN an account recording a mailbox marked `\Trash`
- WHEN a message is deleted
- THEN the row leaves the list and shows in the trash at once
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

The row SHALL be removed by cancelling it once the message has been handed over, and never claimed before: a submission's effect is not a store mutation, so there is nothing to apply, and a claim that deleted the row before the server accepted the message would lose the message. A drain interrupted between the handover and the cancel SHALL send the message again; a failure after the handover SHALL NOT (A send that may have gone waits for the user), and an SMTP `QUIT` failing after the message was accepted SHALL NOT fail the send.

A submission the server refuses for good, which is a 5yz reply (RFC 5321 section 4.2.1), SHALL park the row with what the server said, counted as one attempt, shown as failed to send and never retried on its own; the drain SHALL carry on to the rows behind it. An uncertain one SHALL be parked as such and stop the drain. Any other failure SHALL count an attempt, leave the row pending and stop the drain, the usual cause being that there is no network and the next message would fail too. An action of a kind this app does not carry out SHALL be left pending and untouched, its attempts unbumped.

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
The bridge SHALL read the RFC 5322 source of messages into files (`fetchMessageSources`), and SHALL turn a stored source into what the reader draws with no network access (`parseMessage`, `parseMessageFile` from the stored file). A JMAP account SHALL read that source by downloading the message's blob (RFC 8620 section 6.2), an IMAP one by fetching it with `BODY.PEEK[]`, streamed. A Graph account SHALL read it as the MIME Graph serves at `messages/{id}/$value`, a Gmail one as the `raw` format of the message. No message SHALL cross the bridge as a string.

#### Scenario: A JMAP message
- GIVEN a JMAP account
- WHEN a message is opened
- THEN its `blobId` is read and the blob downloaded
- AND the reader renders the same shape it renders an IMAP message from

#### Scenario: A message of 100 MB
- GIVEN an IMAP message of 100 MB not on the phone
- WHEN it is opened
- THEN it is streamed into a file, filed in the store and rendered from it

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
Every HTTP backend (Graph, Gmail, Google Calendar and People, CalDAV, CardDAV, JMAP) SHALL send a request again when the server answers 429, 503, or 403 with Google's `rateLimitExceeded` or `userRateLimitExceeded`, after the `Retry-After` the server named or the instant Google's message names (`Retry after …`), or else after an exponential back-off with jitter from about a second. A 503 SHALL be retried only for an idempotent method (GET, HEAD, OPTIONS, TRACE, PUT, DELETE, PROPFIND, REPORT): a gateway may answer it after the server behind did the work, so any other request SHALL take the 503 as its answer. Only an answer naming a per-minute quota (`Quota exceeded for quota metric … per minute per user`) SHALL wait longer: until the next minute, except on Gmail, whose quota refills by the second, where it SHALL wait 2 s, then a second more each time up to 5 s, never to the next minute and without holding the account's pacing; a rate or concurrency refusal (`Too many concurrent requests for user`, `User-rate limit exceeded`) SHALL NOT. An answer naming a daily quota SHALL NOT be retried. The waiting SHALL be bounded per request and per native call, a `Retry-After` past the bound SHALL NOT be waited, and past the bound the throttled answer SHALL fail the request as any error does. An answer whose end cannot be told SHALL NOT be retried. Every wait SHALL be logged at debug with the answer's status and reason.

#### Scenario: Graph asks for a pause
- GIVEN a Graph request answered 429 with `Retry-After: 2`
- WHEN the bridge reads the answer
- THEN it waits two seconds and sends the request again, and the sync goes on with the answer to that

#### Scenario: Gmail's concurrency limit
- GIVEN a Gmail read answered 429 `Too many concurrent requests for user`
- WHEN the bridge reads the answer
- THEN it sends the read again within a second or two, never waiting for the next minute

#### Scenario: Gmail's minute quota
- GIVEN a Gmail request refused twice for its per-minute quota
- WHEN the bridge reads the answers
- THEN it waits 2 s, then 3 s, and sends it again

#### Scenario: A server that keeps throttling
- GIVEN a server answering 503 to every request
- WHEN a sync pass reaches it
- THEN the request fails with the 503 within the bound, and the pass reports it as it reports any error

#### Scenario: A gateway's 503 to a send
- GIVEN a Graph `sendMail` answered 503
- WHEN the bridge reads the answer
- THEN it does not send the message again, and the send is uncertain

### Requirement: Gmail is paced below its quota
Gmail API requests SHALL be paced per account, every worker, the body step and the reader sharing the account's pacing, each request costing its documented units (a `messages.get` 20) and a batch the units of every call it carries: near 1,000 units a second with a burst of one batch, within a per-minute ceiling of four fifths of the documented 6,000 units per user, the units and rates kept in one place beside the 5 units against 15,000 a minute measured on 2026-10-10. Messages SHALL be read 10 to a batch, one batch of an account at a time: the calls of a batch run side by side at Gmail, whose per-user concurrency limit is what binds. Within a batch, a read refused for concurrency or left unanswered SHALL be put back into the next batch, sent after 1 to 2 s with jitter; one refused a per-minute quota SHALL be put back after 2 to 5 s, more each time; a daily quota SHALL stop the read. A request someone waits on, a message being opened, SHALL take no slot behind the background requests, which wait for it, and SHALL draw on the fifth of the minute's quota the ceiling leaves. Every pacing wait SHALL be logged at debug.

#### Scenario: A step's reads
- GIVEN a step reading 50 Gmail messages
- WHEN it runs
- THEN they go out in batches of 10, one at a time, and a read refused for concurrency rides the next batch

### Requirement: A mailbox is stored whole
A mail round SHALL list every message of a mailbox within its scope (its floor, within the account's bound), newest first in the source's own recency order, a page at a time (500 UIDs per IMAP `UID FETCH`, 1,000 per Graph `/messages` page, 500 ids per Gmail `messages.list`, 500 per JMAP `Email/query` capped by the server's `maxObjectsInGet`), each page landing in one write. Every message a page lists SHALL arrive named by the summary and sort key of pimdir STORAGE Annex A read in the listing itself, with no body: IMAP from `FLAGS`, `RFC822.SIZE` and the header fields Annex A reads, `Content-Type` among them and no `BODYSTRUCTURE`; Graph from the summary `$select`, or for a delta's member the store does not bind, from that `$select` read by id; JMAP from `Email/get`'s summary properties. A Gmail round, which a history Gmail no longer holds or a label new to the store opens, SHALL list the label's ids received since the oldest reception the account holds, named by their markers alone (the unread and starred labels' ids), the store keeping the summary it holds and reading by metadata only the members it holds in another mailbox or that arrived ahead of every message it holds, never one read per held message. An interrupted round SHALL resume from the cursor its last landed page left, and a cursor the source refuses SHALL restart the round. A round's last page SHALL retire only what it found absent within its scope; mail outside it SHALL never be deleted by a sync. The chunks and the fill (A mailbox is listed a chunk at a time, Older mail fills in behind) SHALL bring a mailbox whole within the account's bound, except on Gmail and JMAP, whose steps decide what it holds.

#### Scenario: A first pass over a large mailbox
- GIVEN an IMAP mailbox of 100k messages and an empty store
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

#### Scenario: An expired Gmail history
- GIVEN a Gmail account whose history Gmail no longer holds
- WHEN a pass opens a round over its inbox
- THEN the inbox's ids are listed with their markers, and no message the store holds is read again

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
A mailbox's first pass SHALL list its newest messages alone: the scope's floor SHALL be, on IMAP, the `Date` of its 50th newest message by `Date`, and elsewhere the oldest `Date` among the 50 newest the source names (Graph's `$top` ordered by `sentDateTime`), or no floor when it holds fewer, within the account's bound. On an IMAP server advertising `SORT` (RFC 5256), the floor SHALL be read from `UID SORT (REVERSE DATE) UTF-8 SENTBEFORE` a day above the ceiling bounded by `SENTSINCE` 90 days below it, widened to a year then to all mail until 50 dated messages are found; on one without, from a sample of the newest UIDs read for their header fields, 50 then doubled at most four times (800 at most), until the last part read holds no message dated or received at or above the 50th newest `Date` of the sample, the floor that 50th. A chunk SHALL be a number of messages, never a span of time, and the floor SHALL be all that is kept of it: the coverage of the round that listed it. A pass after the first SHALL list from that coverage, or from the round under way, so it is a delta, once a Graph mailbox's delta link is made (A Graph mailbox keeps one unfiltered delta link). Widening a mailbox SHALL take the next chunk below its floor, the same way below it, and list only the band it lacks, on every backend: no checkpoint is bound to a scope. A Gmail or JMAP mailbox SHALL be neither chunked nor widened: its account's steps bring what it holds.

#### Scenario: A first pass over a large mailbox
- GIVEN a mailbox of 3,000 messages never listed
- WHEN it is synced
- THEN its 50 newest are listed, and its floor is the `Date` of the 50th of them

#### Scenario: An imported message among the newest UIDs
- GIVEN an IMAP inbox whose newest UID is a message dated 2019, received today
- WHEN its floor is read
- THEN the floor is the `Date` of its 50th newest message by `Date`, not 2019

#### Scenario: An import above this autumn's mail, without SORT
- GIVEN a Gmail IMAP inbox whose 300 newest UIDs are imported mail of 2025, each received when dated, above 100 messages of this autumn
- WHEN its floor is read
- THEN the floor is the `Date` of the 50th newest of this autumn's, read from at most 800 headers

#### Scenario: A small mailbox
- GIVEN a mailbox of 20 messages
- WHEN it is synced the first time
- THEN all of them are listed, and nothing is left below its floor

#### Scenario: Widening
- GIVEN a mailbox listed down to its 50th newest message
- WHEN it is widened
- THEN the next 50 below the floor are listed, the band alone

### Requirement: A Graph mailbox keeps one unfiltered delta link
A Graph mailbox SHALL keep one message delta link made with no filter, selecting the id, `sentDateTime`, `isRead` and `flag`, every request asking 1,000 a page (`Prefer: odata.maxpagesize`), so its checkpoint is bound to no scope. Its mail SHALL be listed by band: a first chunk, a step, a widening and the fill SHALL list only the band they lack by `/messages` filtered on `sentDateTime` (from the floor, below the ceiling: the `Date` header, exactly), newest first, up to 1,000 a page with the summary `$select`, a page resuming below the oldest `Date` the pages before reached rather than by `$skip`, and below that second once a whole page of it was listed already. A first chunk SHALL land without waiting on the delta link: the round a later pass opens over the scope the store covers SHALL make it, walking the delta's first pass a page at a time, listing the members in scope by id and markers, and closing with the link, so what changed or went between the chunk and the link is read there. A delta SHALL apply a removal whatever the date, drop a change to a message dated out of the scope, and list one in scope by id and markers alone: a message the store binds keeps its summary, any other one is read by id with the summary `$select`, 20 to a `$batch`. An expired link (410), or one made under a filter by an earlier version, SHALL open the round that makes a new one, relisting no band.

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
The merged list SHALL show no message older than its floor (An account's frontier is where its steps reached), so no account's or mailbox's older mail is listed while another's of the same days is not on the phone yet; a search SHALL still cover every stored message. While a shown account has more to load, the list SHALL end on its footer (The mail list ends on Load more).

#### Scenario: Two mailboxes at different depths
- GIVEN an inbox listed down to Tuesday and a sent mailbox down to the week before, on an IMAP account whose frontier is earlier
- WHEN the list is scrolled to its end
- THEN it ends at Tuesday, on the footer

### Requirement: A pass takes the inbox first
Every mail pass SHALL take an account's mailboxes in the order of the role each source states: the inbox, the sent mail, the drafts, every other by name, the junk and the trash last, the workers of a pool beginning them in that order. The roles SHALL be stored as pimdir's collection roles (STORAGE section 14): RFC 6154 attributes and `INBOX` on IMAP, RFC 8621 roles, Gmail's system labels, Graph's well-known folders.

#### Scenario: A Graph account
- GIVEN Graph listing *Sent Items*, *Projets* and *Inbox* in that order
- WHEN the account is synced
- THEN *Inbox* is begun first and *Sent Items* second

### Requirement: Older mail fills in behind
After a mail tab's first sync, and on every return to the app or pass after it, every mailbox but Gmail's and JMAP's SHALL widen 500 messages at a time toward its account's bound, with no dialog, the inbox and the sent mail first, a mailbox never listed before one that only lacks older mail, and among them the one holding the most recent floor; a step SHALL widen the next mailboxes in that order side by side, as many of an account as its pool runs. A step SHALL list nothing while the body step has bodies left that it may download now. The fill SHALL run only while the app is in the foreground, on Wi-Fi, and while no other sync runs; it SHALL stop on an error, and SHALL resume from the floors the store covers. A Gmail or JMAP account SHALL NOT be filled: its search beyond what its steps brought is the server's.

#### Scenario: Leaving the app
- GIVEN a fill under way
- WHEN the app goes to the background
- THEN no further chunk is listed, and the next return resumes below the floors reached

#### Scenario: A metered network
- GIVEN a phone on mobile data
- WHEN the app is open
- THEN no chunk is listed until Wi-Fi is back

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
A mail pass and the background fill SHALL run an account's mailboxes concurrently on a pool of sessions, four for Graph and JMAP, three for IMAP, two for Gmail, each worker on a session of its own that no other worker uses while it runs, the workers taking the mailboxes in the pass's order. A step, the first sync's included, SHALL run on the account's one session. Every storage load, lookup and write SHALL be answered by one writer at a time, so only the network overlaps. A mailbox that fails SHALL leave the others running, the pass reporting the first failure, but for a mailbox the server refuses to open (A step and a pass skip a mailbox the server refuses to open).

#### Scenario: A pass over twelve mailboxes on Graph
- GIVEN a Graph account of twelve mailboxes
- WHEN a pass runs
- THEN four mailboxes are listed at once, the inbox begun first

#### Scenario: One mailbox failing
- GIVEN a pass over five mailboxes, one of which fails on a dropped connection
- WHEN the pass runs
- THEN the other four are stored, and the pass reports the failure

#### Scenario: One mailbox refused
- GIVEN a pass over five mailboxes, one of which the server refuses to open
- WHEN the pass runs
- THEN the other four are stored, and the pass reports nothing

#### Scenario: Two pages landing together
- GIVEN two mailboxes whose pages arrive at the same moment
- WHEN both are written
- THEN one write lands whole before the other begins

### Requirement: A sent message is in Sent at once
Sending SHALL stage the message as composed into the mailbox the account marks as its sent mail, beside its submission, so Sent shows it before any sync. Gmail and Graph file sent mail themselves: before a page reaches the engine, an arrival carrying the `Message-ID` of a pending create in that mailbox SHALL be named by the create's key, so the listing lands the staged copy and nothing is pushed there. Gmail stamps a `Message-ID` of its own on what it sends: the submission SHALL read Gmail's sent copy back for it, and an arrival carrying that one SHALL be named by the create staged under the composed one. On IMAP the staged copy's push SHALL be the append, made only once the submission went (A message is submitted and a copy is kept). A JMAP account, which sends nothing yet, and an account marking no sent mailbox SHALL stage none. While the submission is in the outbox, a list showing the outbox SHALL list its outbox row alone, not the staged copy; discarding the outbox row SHALL withdraw the copy with it.

#### Scenario: Sent offline
- GIVEN a message sent with no network
- WHEN the Sent mailbox is opened
- THEN the message is there

#### Scenario: A provider filing sent mail itself
- GIVEN a message sent from a Graph account
- WHEN the next sync lists `Sent Items`
- THEN the staged copy is landed on Graph's own copy, and Sent holds one

#### Scenario: Gmail renaming what it sends
- GIVEN a message sent from a Gmail account, which Gmail sends under a `Message-ID` of its own
- WHEN the next sync lists `SENT`
- THEN the staged copy is landed on Gmail's copy, and Sent holds one

### Requirement: A mailbox can be downloaded whole
A mailbox's row on the mail filter page SHALL offer "Download", which after a confirmation keeps that mailbox whole whatever its account's policy: its scope SHALL be all of its mail, past the account's bound, the fill SHALL widen it below its floor, narrowing the account's bound SHALL NOT collect it, and every listed body SHALL be downloaded. The row SHALL then say it is kept whole and offer "Stop", which returns it to the account's bound and policy, what is stored staying until the bound is narrowed again.

#### Scenario: An archive kept whole
- GIVEN an account bounded to the last year, with bodies on open
- WHEN its Archive is downloaded from the filter page
- THEN every message of the Archive is listed and its body stored, and the account's other mailboxes keep their year and fetch bodies on open

### Requirement: Bodies download behind the list
For each account that is on, the app SHALL raise to `Full`, through the engine's upgrade, every listed message dated on or after its mailbox's frontier in every mailbox but the junk and the trash, every listed message of a mailbox kept whole, and every owed message (Arrivals download whatever their date), after each pass, step and fill step while the app is open: newest first, the mailboxes the mail filter shows before the account's others, the owed ones last, at most 24 bodies an account a step. A step's own messages SHALL be stored from the files it read, never read again. A message with no date SHALL be downloaded only under a frontier of all mail or in a mailbox kept whole, and a pending create never. Whether a message is taken SHALL be read again against the current frontier as its chunk downloads. A body the store already holds under the same link id SHALL be linked rather than fetched where the link id names the message across mailboxes (A link id names a message across mailboxes only where the account lists them as one). The body SHALL be stored as the bytes the server sent, its base moved to it, its summary and sort key kept, its attachment mark restated from its parts. The step SHALL run only while the app is in the foreground, no other sync runs, and on Wi-Fi; it SHALL never be periodic work, and SHALL stop when the user starts a sync or leaves the app. A body that fails SHALL be tried again in the run (A body that fails is tried again in the run).

#### Scenario: Within the frontier only
- GIVEN an account whose frontier is 1 September, holding a header from yesterday and one from March
- WHEN the body step runs
- THEN yesterday's body is downloaded and stored, the March one stays a summary

#### Scenario: Junk and trash
- GIVEN a message from yesterday in the junk, its mailbox not kept whole
- WHEN the body step runs
- THEN its body is not downloaded

#### Scenario: A metered network
- GIVEN an account on mobile data
- WHEN a pass would end
- THEN no body is downloaded until Wi-Fi is back

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

### Requirement: The mail list shows its background download
While the background fill downloads older mail or bodies and no pass runs, the mail list's pinned strip SHALL read *Loading older mail* beside a download glyph pulsing gently in opacity, holding still when the system removes animations, a long press on the glyph naming it. While bodies download it SHALL show a bar of the bodies landed out of the run's plan, every account's summed, a body counting once its file is written and a replan adding to the plan; the header fill alone has no bar. The bar SHALL never move back while the fill runs. The strip SHALL show only once a step downloaded something, and SHALL go when the fill ends or pauses (the app left, no Wi-Fi).

#### Scenario: After the first mail sync
- GIVEN a first mail sync that landed the newest messages of an IMAP account
- WHEN the fill widens its mailboxes in the background
- THEN the strip pulses its glyph above the list, and goes once the fill stops

#### Scenario: Bodies scrolled past
- GIVEN 100 bodies left to download and the list scrolled down
- WHEN 48 have landed
- THEN the strip shows above the list, its bar near one half

### Requirement: Opening a message waits on no background work
Opening a message SHALL NOT queue behind a step of the background fill or of the body download: its read and its fetch SHALL run apart from them, and what it writes to the store SHALL be written under the store's one writer. A Gmail fetch for the reader SHALL go out ahead of the background requests the account's pacing holds (Gmail is paced below its quota).

#### Scenario: Opening during the fill
- GIVEN the background fill widening a Gmail account
- WHEN a message not on the phone is opened
- THEN its fetch goes out at once, and the reader renders it without waiting for the fill's step to end

### Requirement: The bridge logs to logcat
The Rust bridge SHALL log to logcat under the app's tag, warnings and above only until a debug app turns its debug logs on, then debug and above for its own records, info and above for the libraries under it. Every Gmail request SHALL be logged at debug with its call, its quota units, its pacing wait and the time its answer took, and every widening of a mailbox with its floor and the time it took. A warning the app logs SHALL name an account by its address's domain alone.

#### Scenario: A throttled request
- GIVEN a debug app and a Gmail request answered 429
- WHEN the bridge waits to send it again
- THEN logcat names the status, the reason and the wait

### Requirement: Mail syncs on Wi-Fi only
A mail pass SHALL list nothing, and no step, fill step or body download SHALL run, on a metered network; the outbox SHALL still go out there. The mail list's strip SHALL then say the sync waits for Wi-Fi (*Waiting for Wi-Fi*), and what was asked SHALL run once an unmetered network is back while the app is open. A background run on a metered network SHALL send the outbox and list nothing. Opening one message SHALL fetch it on any network.

#### Scenario: A first sync on mobile data
- GIVEN an account just connected, on mobile data
- WHEN the mail tab is opened
- THEN nothing is listed, the strip says it waits for Wi-Fi, and the first step runs once Wi-Fi is back

#### Scenario: Opening a message on mobile data
- GIVEN a message not on the phone, on mobile data
- WHEN it is opened
- THEN it is fetched and shown

### Requirement: An account's mail comes in steps
An account's mail SHALL come in steps: a step SHALL bring about 50 messages of the account whole (headers and body, plain `Full`), older than what the account holds, never counted by an offset, on the account's one session, its reads in small batches one after another. Each backend SHALL decide how a step finds them and batches them. The first sync SHALL be one step. A step SHALL leave out the junk and the trash. A view filtered to one mailbox of an account that has several SHALL step that mailbox alone. A step finding nothing older SHALL leave the account at all mail, or the one mailbox it stepped alone.

#### Scenario: A first sync
- GIVEN an account just connected, on Wi-Fi
- WHEN the mail tab's first sync runs
- THEN about 50 messages are listed and readable offline when it ends

#### Scenario: A small account
- GIVEN an account holding 30 messages
- WHEN its first step runs
- THEN all 30 are on the phone, and the list has nothing more to load

### Requirement: A mail step counts the bodies it brings
A mail step SHALL fill its account's part of the strip's bar with the bodies it brought out of the bodies it planned: about 50 for a step the bridge walks (Gmail, Graph, JMAP), what it brought once the walk answers; for IMAP, the bodies its listing left to read, known once its mailboxes are listed. A body SHALL count once its file is written, as the backend writes it (Gmail by batch of 10, Graph by `$batch`, IMAP as each streamed body starts, JMAP one blob at a time). The bar SHALL run indeterminate while the first account lists. A pass of steps over several accounts SHALL weigh each account alike.

#### Scenario: Load more on Gmail
- GIVEN a Gmail account with older mail
- WHEN *Load more* is tapped
- THEN the bar runs indeterminate while the step lists, then fills ten bodies at a time, and is full once the 50th is written

#### Scenario: Two accounts
- GIVEN accounts A and B shown together, both with older mail
- WHEN *Load more* is tapped and A's step ends
- THEN the bar stands at one half, and B's bodies carry it on

### Requirement: An account's frontier is where its steps reached
Each mail account SHALL keep a frontier, as app state beside the account: the `Date` of the oldest message its steps brought, leaving out a message dated more than a day before it was received (an import, a wrong clock), which is held and shown once the list's floor passes it; the oldest of them when every one is such. A step walking by reception (Gmail, JMAP without `sentAt`) SHALL never leave a frontier older than the oldest reception it brought, since a message it left out was received before that and may be dated after its oldest `Date`. A step over one mailbox alone SHALL move that mailbox's own frontier and never the account's, and a mailbox's frontier SHALL be the older of its own and its account's. A step SHALL only move a frontier back; only the account's settings move it later (A window moved later frees the bodies below it), a mailbox's own frontier older than the account's new one then going. The merged list's floor SHALL be the most recent of the shown mailboxes' frontiers and of their coverage floors, a Gmail or JMAP mailbox bringing no coverage floor.

#### Scenario: An imported message
- GIVEN a step bringing messages dated from 8 to 9 October and one dated March 2019 received on 9 October
- WHEN it ends
- THEN the frontier is 8 October, the March 2019 message held and not listed above it

#### Scenario: A late delivery on a step by reception
- GIVEN a Gmail step bringing messages received from 14:00 to 18:00, one of them dated 09:00 and received at 15:00
- WHEN it ends
- THEN the frontier is 14:00, so no message received before 14:00 but dated after 09:00 is missing above it, and the 09:00 one is held and shown once the frontier passes it

#### Scenario: A step over one label
- GIVEN a Gmail account whose frontier is 1 October, its view filtered to the label Clients
- WHEN *Load more* brings Clients down to 1 September
- THEN Clients lists down to 1 September, and the merged view still ends at 1 October

### Requirement: The mail list ends on Load more
While a shown account that is on has more to load, the mail list SHALL end on a footer: a button, *Load more*, over a line saying about how many messages a step brings each account (*About 50 more per account*). An account shown by one of its mailboxes alone has more to load while that mailbox's frontier is not all mail. A tap SHALL take one step of each such account, one after another under the sync strip, then read the list again and download the bodies left. While a step or any other pass holds the sync (a tap's, the first sync's, a pull's, the drawer's, a background run's), the button SHALL be disabled and read *Loading more*, so a second tap queues nothing, and SHALL read *Load more* again once every account's step ended. Off Wi-Fi the line SHALL add that it waits for Wi-Fi and the button SHALL be disabled. Junk, trash and mailboxes kept whole SHALL take no part. The footer SHALL hide under a search, and SHALL stand in for an empty list's empty state.

#### Scenario: Two accounts
- GIVEN accounts A and B shown together, both with older mail
- WHEN *Load more* is tapped
- THEN the button reads *Loading more* and is disabled while A then B take a step, and the list reaches down to the more recent of their new frontiers

#### Scenario: During the first sync
- GIVEN an account's first sync running
- WHEN the list's end is reached
- THEN the footer reads *Loading more*, disabled

#### Scenario: Off Wi-Fi
- GIVEN a list on mobile data
- WHEN its end is reached
- THEN the footer says it waits for Wi-Fi, and its button does nothing

### Requirement: A Gmail step is a set difference
A Gmail step SHALL walk the account's `messages.list` (no `labelIds`, `includeSpamTrash=false`, 500 ids a page) from the top, newest received first, keeping the ids the store does not hold anywhere in the account until it holds about 50, and SHALL never filter on a date (`before:` on `internalDate` or `Date`). A view filtered to one label SHALL walk that label's listing (`labelIds`). The account's `historyId` SHALL be taken before the walk. The ids kept SHALL be read with `format=minimal` for their sizes, then with `format=raw` in batches of at most 10 messages and about 8 MB, one batch at a time, a message over about 5 MB read alone. Each message SHALL be read once and placed in every mailbox its `labelIds` name, a message in the trash or the spam in that one alone, its body filed once and linked into the others. The first step SHALL open every label's mailbox, the junk and the trash included, as a round closed on that `historyId`, so the next pass is a history replay; a later one SHALL add to them.

#### Scenario: Two labels
- GIVEN a Gmail message filed under INBOX and Clients, not on the phone
- WHEN a step brings it
- THEN it is read once, and both mailboxes hold it whole

#### Scenario: A gap
- GIVEN a Gmail account holding its 50 newest messages but one a failure left out
- WHEN the next step runs
- THEN the missing one is among the messages it brings

### Requirement: Bodies cross the bridge as files
A message read whole for the store SHALL cross the bridge as a file: `fetchMessageSources(handle, collection, ids, dir)` SHALL write one file per message into `dir` and answer each one's id, path and size (with its labels and reception on Gmail), a message gone or not handed over left out, an expired token failing the call. It SHALL dispatch per backend, a backend without batches of its own reading one message after another. JMAP SHALL read the `blobId`s of every id in one `Email/get` (chunked by `maxObjectsInGet`) and download the blobs one after another, never one `Email/get` per message. On IMAP it SHALL read the sizes (`UID FETCH <set> (UID RFC822.SIZE)`), then one streamed `UID FETCH <set> (UID BODY.PEEK[])` per batch of at most 50 messages and about 12 MB, a message past it alone, each body written to its file as it arrives; a batch the server refuses SHALL be read one message after another. The bridge SHALL describe the file in one read (`describeMessage`: its content hash, its summary, its attachments), and the file SHALL be moved into the blob store under that hash, never read on the Java side nor copied, and handed to the engine as a body already persisted. Opening one message SHALL go the same way, and an attachment SHALL be written from the stored file to its own file.

#### Scenario: A batch of bodies
- GIVEN ten Gmail messages to download
- WHEN the body step reads them
- THEN one batch request answers them, each written to its own file, and no body crosses as a string

#### Scenario: An IMAP batch
- GIVEN 40 IMAP messages of an inbox to download
- WHEN the body step reads them
- THEN one `UID FETCH` of their sizes and one streamed `UID FETCH` of their bodies answer them, each written to its own file

#### Scenario: JMAP bodies
- GIVEN ten JMAP messages to download
- WHEN the body step reads them
- THEN one `Email/get` names their blobs, and each blob is downloaded into its own file

#### Scenario: Opening a message not on the phone
- GIVEN a message the store holds at meta
- WHEN it is opened
- THEN it is read into a file, moved into the blob store under the hash the bridge computed, and parsed from there

### Requirement: Arrivals download whatever their date
A message a delta lists new to the store (IMAP CONDSTORE or QRESYNC, Gmail history, Graph delta, JMAP changes), and a message a step brought whose body did not land, SHALL be owed: its body SHALL download on Wi-Fi whatever its `Date`, a delayed message or one filed from older mail included, its row not saying *Not downloaded* meanwhile. What is owed SHALL be kept beside the account and survive a restart; one in a mailbox that downloads nothing (junk, trash) SHALL be dropped.

#### Scenario: A delayed message
- GIVEN an account whose frontier is 1 October
- WHEN a pass lists a new message dated 20 September
- THEN its body downloads with the others

#### Scenario: Across a restart
- GIVEN an arrival dated below the frontier, its body not downloaded when the app was closed
- WHEN the app is opened again on Wi-Fi
- THEN its body downloads

### Requirement: A dead connection is dropped, never read again
The transport SHALL drop an origin's connection on any read or write failure, a read timeout included, and at the end of its stream, and the bridge SHALL tell it to on any transport error, on every backend. Before a new HTTP request on a held connection, the connection SHALL be dropped unless the last answer on it was read to its end by its framing (`Content-Length` or chunked) and did not say `Connection: close`, so no answer, late or cut short, is ever read as another request's. A chunk size that is not bare hex digits, or names more than 64 MiB, SHALL count as framing that cannot be told: the answer passes through untouched and its connection is not reused. A reused connection that ends or fails before the first byte of an answer to an idempotent request (GET, HEAD, OPTIONS, PROPFIND, REPORT, PUT, DELETE) SHALL be reopened and the request sent again once (RFC 9112 section 9.3.1); a read that timed out SHALL NOT, the server being perhaps still at work on it, and any other request SHALL fail. A verb on a held session that fails after its connection was lost SHALL be run once more on a reopened session, an IMAP one greeted and authenticated again; a verb a live server refuses SHALL NOT be run again.

#### Scenario: Graph after two idle minutes
- GIVEN a Graph session idle for two minutes, its connection closed by the server
- WHEN the body step reads the next message
- THEN the read goes out again on a new connection, and the body is stored

#### Scenario: An answer that came too late
- GIVEN a read that timed out before its answer arrived
- WHEN the next request goes out
- THEN it goes on a new connection, and the late answer is never read as its own

#### Scenario: A slow PUT
- GIVEN a CalDAV PUT whose answer takes longer than the read timeout
- WHEN the read times out
- THEN the PUT fails and is not sent again

#### Scenario: A dead IMAP connection
- GIVEN an IMAP session whose socket the server closed
- WHEN the next verb runs on it
- THEN the session is opened again and the verb answers

### Requirement: A body that fails is tried again in the run
A body the server does not hand over SHALL be put back at the end of its account's plan and tried again later in the same run, on a reopened session, at most 3 times a run. A message whose body failed every attempt SHALL say so in its row (*Download failed*) until a later run brings it. A download failing as a whole SHALL put its messages back the same way, an expired token renewed when the session reopens; an account whose download fails as a whole twice in a row SHALL leave the run.

#### Scenario: One body refused once
- GIVEN a body step whose server fails one message's read once
- WHEN the run goes on
- THEN that message is read again after the others and stored

#### Scenario: A token expiring mid-run
- GIVEN an account whose token expires while its bodies download
- WHEN the next chunk is read
- THEN the token is renewed and the run goes on

### Requirement: Junk and trash list when opened
The mail list showing an account's junk or trash alone SHALL list that mailbox once per opening, on Wi-Fi and while no other sync runs: a Gmail one by its label (`labelIds` `SPAM` or `TRASH` with `includeSpamTrash`), a step over that mailbox bringing about 50 messages it does not hold; any other by a pass over that mailbox.

#### Scenario: Gmail's spam
- GIVEN a Gmail account whose spam was never listed
- WHEN the mail list is filtered to its spam
- THEN its newest messages are listed

### Requirement: An IMAP step widens from the account floor
An IMAP account's first step SHALL read the 50 newest `Date`s of each mailbox it brings bodies to (A mailbox is listed a chunk at a time), take the 50th newest of them all as its floor, list every mailbox from that floor, the junk and the trash included, so each next pass is a delta, leave that floor as the frontier (all mail when those mailboxes hold 50 or fewer in all), then read whole every message it listed from that floor but the junk's and the trash's: about 50 bodies whatever the mailboxes. A later step SHALL widen the mailbox holding the most recent coverage floor by 50 until about 50 stored messages lie between it and the frontier, move the frontier to the `Date` of the 50th of them by `Date`, ties included, then read whole the messages between the new frontier and the old, one mailbox after another.

#### Scenario: A first IMAP step
- GIVEN an IMAP account whose inbox holds 3,000 messages, one an hour, and whose sent mail holds one every three hours
- WHEN its first step runs
- THEN the account's 50 newest by `Date`, about 37 of the inbox and 13 sent, are on the phone whole, and every mailbox is opened for the next pass's delta

#### Scenario: Load more on IMAP
- GIVEN an IMAP account after its first step
- WHEN *Load more* is tapped
- THEN about 50 older messages are listed and read whole, the frontier moved to the oldest of them

### Requirement: An IMAP search is kept while its mailbox stands
The UIDs a `UID SEARCH` or `UID SORT` found SHALL be kept per mailbox, UIDVALIDITY and scope for the session, and the header fields a floor read reused by the round after it, until the mailbox's EXAMINE reports another UIDNEXT, HIGHESTMODSEQ or message count; header fields SHALL be kept only on a server reporting a HIGHESTMODSEQ, and a write SHALL drop what was kept.

#### Scenario: A round after a floor
- GIVEN a CONDSTORE server whose inbox floor was just read, no message arriving meanwhile
- WHEN the inbox is listed from that floor
- THEN no header field is read twice, and a page after the first searches no more

### Requirement: A JMAP step is one anchored query
A JMAP step SHALL be one `Email/query` over the account with `inMailboxOtherThan` the trash and the junk (`inMailbox` for a view filtered to one mailbox), sorted by `sentAt` descending when the account's mail capability lists it in `emailQuerySortOptions`, else by `receivedAt`, with `calculateTotal` and `collapseThreads` false and a limit of about 50, its `Email/get` back-referenced for the summary properties, `receivedAt` and `blobId`. The account's `Email` state SHALL be read before it, as the checkpoint. A step SHALL answer the ids its query reached last, oldest first, and the next step SHALL query from the first of them with `anchorOffset` 1; a query refused with `anchorNotFound` SHALL be asked again from the next one, and once none is left SHALL page by `receivedAt` below the frontier, two days of margin above it. Each `Email` the store does not hold SHALL be downloaded by its blob, one after another on the account's session, and placed in every mailbox its `mailboxIds` names; one the store holds SHALL be passed over, a window held whole moving on to the next, a few queries at most. A body that fails SHALL leave the anchors before it, so the next step reads it again. The app SHALL keep the anchors beside the account's frontier, per account and per mailbox a view steps alone, and SHALL save them only once the step landed. A JMAP account SHALL be listed account-wide, as a Gmail one is.

#### Scenario: A second step
- GIVEN a JMAP account whose first step brought its 50 newest messages
- WHEN *Load more* is tapped
- THEN one query anchored on the oldest of them answers the next 50, read whole

#### Scenario: The anchor deleted
- GIVEN the oldest message of the last step deleted on the server since
- WHEN the next step runs
- THEN its query is asked again from the message before it, and no message is skipped

#### Scenario: Two mailboxes
- GIVEN a JMAP message filed in the inbox and in Clients, not on the phone
- WHEN a step brings it
- THEN it is downloaded once, and both mailboxes hold it whole

### Requirement: A JMAP delta is read once per pass
The mailboxes of a JMAP pass SHALL share one replay of `Email/changes` from a given state, its touched messages read once with the summary properties, each mailbox taking as members those whose `mailboxIds` name it and as gone the others and the destroyed ones. A state the server can no longer compute changes from SHALL be refused for the engine to open a round, as before.

#### Scenario: A pass over five mailboxes
- GIVEN a JMAP account of five mailboxes, one message moved since the last pass
- WHEN a pass runs
- THEN `Email/changes` is asked once, the message is read once, and it leaves one mailbox for the other

### Requirement: A Graph step lists the account by date
A Graph step SHALL list the account's messages (`/me/messages`) newest `Date` first (`$orderby=sentDateTime desc`), selecting the id, `sentDateTime`, `receivedDateTime`, `parentFolderId` and the MAPI size, filtered `sentDateTime le F` on the frontier (a view filtered to one folder: that folder's own) and from the account's bound, about 50 a request and some slack, keeping messages until it holds 50 and every one sharing the 50th one's `Date`. It SHALL leave out the messages the store holds dated `F` (the step before brought them), and those in the trash, the junk, a mailbox kept whole or a folder outside the roster. A request answering fewer than it asked for SHALL end the step with nothing older left; otherwise the next request SHALL list from the oldest `Date` reached, that second included and the ids already seen left out, never through `nextLink`, and below it once a whole page of that second was seen. An answer naming `InefficientFilter`, or a 504, SHALL list each folder the same way and merge them by `Date`. A view filtered to one folder SHALL list that folder alone. Every folder stepped SHALL then be listed by band down to the oldest `Date` the step brought, the folders holding its messages first, and the step's bodies stored from what it read.

#### Scenario: Three folders
- GIVEN a Graph account whose 50 newest messages lie in Inbox, Sent Items and Projets, and older ones in the trash
- WHEN a step runs
- THEN one listing names the 50, the trash's left out, and each of the three folders is listed by band down to the oldest of them

#### Scenario: A busy second
- GIVEN a Graph account whose 50th newest message shares its `Date` with four older ones
- WHEN a step runs
- THEN it brings 54, and the next step starts below them

### Requirement: Graph bodies come twenty to a batch
A Graph account SHALL read bodies through `$batch` calls of up to 20 `$value` GETs and about 4 MB of declared size, one call at a time on the account's session, a message over 1 MB, or whose size Graph does not state, read alone with a plain GET, as SHALL be one whose batched answer is not a base64 body. An item throttled inside a call (429) SHALL wait the `Retry-After` its own answer names, once, then go in the next call; an item failing again, or failing otherwise, SHALL be left out of the answer, and one gone (404) too. `fetchMessageSources` on Graph SHALL read the sizes first, 20 ids to a `$batch`.

#### Scenario: Fifty bodies
- GIVEN fifty messages of about 40 KB, one of 3 MB
- WHEN a step reads them
- THEN three batch calls read 49 of them and one GET the large one

#### Scenario: A throttled item
- GIVEN a batch answering one item 429 with `Retry-After: 2`
- WHEN the read goes on
- THEN it waits 2 s and reads that item again in the next call

### Requirement: A link id names a message across mailboxes only where the account lists them as one
A message's link id SHALL be its handle. Only on an account listed account-wide (Gmail, JMAP), whose id names one message across its mailboxes, SHALL a body be linked from another mailbox under the same link id, and only from the same account (pimdir STORAGE §14). On IMAP a link id is a UID, naming a message in its own mailbox alone, and on Graph a folder's copy alone: no body SHALL be linked there, every body being fetched. Once per such account, every body its mailboxes hold under a link id another collection holds with the same body SHALL be released back to its summary, both sides, unless the message still needs it (a conflict, a pending create, a local edit), so the body step reads it again.

#### Scenario: Two IMAP mailboxes holding the same UIDs
- GIVEN an IMAP account whose inbox and archive both hold UIDs 1 to 120
- WHEN a step reads the bodies of UID 120 in both
- THEN each holds its own message's body, both read from the server

#### Scenario: A store upgraded over wrongly linked bodies
- GIVEN an IMAP account whose sent UID 120 holds the inbox UID 120's body
- WHEN its next pass lists its mailboxes
- THEN both bodies are released, and the body step reads each message's own

### Requirement: No message or calendar object can overflow the bridge's stack
The bridge SHALL walk a message's MIME tree with a stack of its own, at most 64 levels, parts nested deeper left unnumbered, and SHALL derive a message nesting deeper than that from its header alone. It SHALL refuse a calendar object whose components nest deeper than 16 levels, a folded `BEGIN` counted. Every one of these SHALL hold on a 1 MiB thread, the stack of an ordinary Java thread.

#### Scenario: Ten thousand nested parts
- GIVEN a message nesting 10 000 `multipart/mixed` parts
- WHEN a body pass stores it and the reader opens it
- THEN it is stored and read, its subject shown, and the app keeps running

### Requirement: A body whose reading kills the app is given up
A read of a body SHALL be marked on disk before it starts and cleared once it ends. A body whose read the process did not survive 3 times SHALL never be fetched or read again: its row SHALL say *Download failed*, it SHALL be owed no more, and opening it SHALL say it could not be read.

#### Scenario: A body that crashes the app
- GIVEN a body whose reading kills the app on every attempt
- WHEN three runs try it
- THEN the fourth run skips it, its row says *Download failed*, and the app no longer dies

### Requirement: A link in a message opens only when tapped
The reader SHALL open a link only when the reader tapped it in the message itself (a gesture, in the main frame), only for `http`, `https` and `mailto`, as a browsable intent with no component, and SHALL not log it. A meta refresh, a frame or any other scheme SHALL open nothing.

#### Scenario: A meta refresh to a tracker
- GIVEN a message carrying `<meta http-equiv="refresh" content="0;url=https://tracker.example/">`
- WHEN it is opened
- THEN no browser opens

### Requirement: A failed native call answers an error
A native call that panics or meets a JNI failure SHALL answer `{"error": "Internal error: .."}`, its panic message cut to 160 characters, never a null string; one answering a primitive SHALL log and answer its default; no native entry point SHALL let a panic unwind into the JVM.

#### Scenario: A panic in the bridge
- GIVEN a bridge call that panics
- WHEN the Java side reads its answer
- THEN it throws a PimalayaException naming an internal error

### Requirement: A killed run leaves nothing in the cache
On every start, the app SHALL remove the folders a body download writes into and the copies an import stages that are older than an hour. An attachment's file name SHALL drop control and format characters and take at most 200 bytes in UTF-8, cut between characters, its extension kept.

#### Scenario: A body download killed midway
- GIVEN a body download killed with messages in its cache folder
- WHEN the app starts more than an hour later
- THEN the folder is gone

### Requirement: A send that may have gone waits for the user
A submission that fails after the whole message was handed over and before any verdict came back SHALL be uncertain: over SMTP once the data's final `.` was written, over JMAP, Graph or Gmail once the request was written whole and no byte of its answer came, or when a 500, 502, 503 or 504 answered. An uncertain submission SHALL park its row, shown as *May have been sent*, and SHALL never be handed over again on its own. The reader SHALL offer *Send again* on it, which queues the same message anew at the end of the queue and drops the parked row.

#### Scenario: The connection dies after the final dot
- GIVEN an SMTP server that closes the connection after the message's final `.`
- WHEN the sync drains the queue
- THEN the row is parked, reads *May have been sent*, and the next sync hands nothing over

#### Scenario: The user sends it again
- GIVEN a message parked as *May have been sent* that the recipient never got
- WHEN the user opens it and taps *Send again*
- THEN it is queued again and the next sync hands it over

### Requirement: An IMAP copy or append is not made twice
Where the server has no MOVE, a relocation SHALL search the target for the message's `Message-ID` (`UID SEARCH HEADER`) before its COPY and skip the COPY when it is there, so a relocation run again after a lost answer disposes of the original without filing a second copy. An `APPEND` of a message whose `Message-ID` the mailbox already holds SHALL be skipped and count as done. A message carrying no `Message-ID` SHALL be copied and appended as before.

#### Scenario: A COPY whose answer was lost
- GIVEN a server without MOVE whose connection dies after a COPY into Archive and before its answer
- WHEN the relocation runs again on a new session
- THEN Archive holds one copy and the original is disposed of

### Requirement: A step asks the store which of a page it holds
A mail step SHALL NOT carry the handles the store holds: each backend SHALL ask the store which of a page it lists the store holds, a page at a time, through the driver it was handed. Whether a mailbox holds anything SHALL be one indexed probe.

#### Scenario: A Gmail step over a large account
- GIVEN a Gmail account of 500k stored messages
- WHEN Load more runs a step
- THEN each listed page of 500 ids is asked of the store, and no list of 500k handles crosses the bridge

### Requirement: A step and a pass skip a mailbox the server refuses to open
The bridge SHALL answer a `NO` to an IMAP `EXAMINE` or `SELECT` (RFC 3501 6.3.1, 6.3.2: a mailbox that does not exist or cannot be accessed, whatever its text and whether it carries RFC 5530's `[NONEXISTENT]`) with status 404, as it answers a Graph folder or a Gmail label gone, and SHALL NOT answer a `BAD`, a `BYE`, a closed connection or a refused sign-in so. A mail step SHALL leave such a mailbox out: of an IMAP first step's account floor and listing, of a later step's widening, and of a first step's listings elsewhere, the others stepped as if it were not there, and a step that left one out SHALL NOT report the account held whole. A pass SHALL skip such a mailbox and report nothing for it. When every mailbox a step reads, or every mailbox of a pass, is refused, the first refusal SHALL be reported. The next pass's roster SHALL drop a mailbox the server no longer lists, with its messages.

#### Scenario: A folder deleted between the roster and the step
- GIVEN an IMAP account whose roster names a folder another client deleted since, and Exchange answering its `EXAMINE` `NO ... doesn't exist.`
- WHEN its first step runs
- THEN the account floor is taken across its other mailboxes, they are listed and their bodies read, and the step succeeds

#### Scenario: A label a new session does not see yet
- GIVEN a Gmail IMAP account whose label made seconds ago a fresh session refuses, `NO Unknown Mailbox`
- WHEN *Load more* runs a step
- THEN the other mailboxes widen, and the label is stepped by a later step once the server shows it

#### Scenario: A server refusing everything
- GIVEN an Exchange account whose backend is away, every `EXAMINE` answered `NO`
- WHEN a step runs
- THEN the step fails with the refusal, and no frontier moves

### Requirement: A push converges on a message gone
A write on one message that the server answers 404 (a message deleted for good, purged from the trash or the spam, or a Gmail draft saved under a new id since the listing) SHALL be taken as converged: a removal, a move and a marker accepted, a move's target create withdrawn, and a copy of it refused. The bridge SHALL answer such a write as done on Gmail and Graph, and a JMAP `notFound` likewise, a copy keeping its 404. A push SHALL NOT fail a pass with a 404, so only a mailbox's own reads are taken as the server refusing it.

#### Scenario: A message purged between the listing and the push
- GIVEN a deleted message staged for removal, purged from the trash on the server after the pass listed it
- WHEN the pass pushes the removal
- THEN it is accepted, and the page the pass listed lands

#### Scenario: A copy of a message gone
- GIVEN a copy staged of a message the server no longer holds
- WHEN it is pushed
- THEN it is refused
