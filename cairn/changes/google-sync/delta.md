---
cairn: delta
change: google-sync
---

## ADDED Requirements

### Requirement: A Google account signs in once
Every domain an address connects through Google's authorization server SHALL be signed in to by one authorization request carrying the union of their scopes, one browser round and one refresh token held once for the account. Every Google request SHALL carry `include_granted_scopes=true`. A domain connected later onto an account already holding a Google grant SHALL ask for the union of that grant's scopes and its own, and the new grant SHALL replace the old one on every domain whose scopes it covers.

#### Scenario: Mail, calendars and contacts at once
- GIVEN a Google address with mail, calendars and contacts ticked
- WHEN the sign-in sequence runs
- THEN the browser opens once, asking for the mail, calendar and contacts scopes together
- AND the three domains name one credential

#### Scenario: Contacts added later
- GIVEN a Google account connected for mail alone
- WHEN contacts are connected onto it
- THEN the grant asks for the mail and contacts scopes, with `include_granted_scopes=true`
- AND mail and contacts then name the new credential, the old one dropped

### Requirement: Gmail is one account listing
A Gmail account SHALL be listed once per pass with `messages.list` without `labelIds`, `includeSpamTrash=true`, 500 ids a page, newest first, its cursor the page token. Each mail SHALL be read once, its metadata (`format=metadata`, the summary headers, `Content-Type`) naming its `labelIds`, through one envelope cache per account that every session shares and that each pass empties. The account SHALL hold one floor, its first sync taking the newest 50 of the account and the newest 50 of the inbox when the inbox is behind, scroll and the fill widening the one listing by count.

#### Scenario: A mail under three labels
- GIVEN a mail labelled `INBOX`, `IMPORTANT` and `Seed/Clients`
- WHEN the account syncs
- THEN its metadata is read once and the mail is placed in the three mailboxes

### Requirement: Gmail labels are rebuilt locally
Each Gmail label SHALL stay a mailbox, a mail being placed in every mailbox its `labelIds` name with no request per label. A label leaving a mail's set SHALL take it out of that mailbox (archiving is `INBOX` leaving), a label joining SHALL place it there, and a mail in `TRASH` or `SPAM` SHALL leave every other mailbox.

#### Scenario: A mail trashed on the web
- GIVEN a mail in the inbox and under a user label
- WHEN it is trashed in Gmail and the account syncs
- THEN it is in the trash alone

### Requirement: Gmail history is read once per account
A Gmail pass after the first SHALL read `history.list` unfiltered, `maxResults=500`, once for the account from its `historyId`; `messagesDeleted` SHALL be vanished with no read, and every id the history touches SHALL be read once.

#### Scenario: A quiet pass
- GIVEN an account with no change since its last pass
- WHEN it syncs
- THEN one `history.list` request is sent for the whole account

### Requirement: Gmail metadata is read 50 to a batch
Gmail metadata reads SHALL go 50 to a `POST /batch/gmail/v1` (multipart/mixed), each inner request the one a single read sends. An inner answer that is throttled SHALL be sent again on its own through the throttled path, and an inner 404 SHALL be a mail gone since the listing.

#### Scenario: A first sync of 120 inbox mails
- GIVEN 120 inbox mails never read
- WHEN their metadata is read
- THEN three batch requests carry the 120 reads

### Requirement: An expired People sync token restarts the round
A People round whose sync token Google refuses as expired (a 410, or a 400 whose message says the sync token expired) SHALL be run again as a full round without a token, rather than failing every pass.

#### Scenario: A token older than seven days
- GIVEN a Google address book last synced weeks ago
- WHEN it syncs
- THEN the expired token is dropped and the book is listed in full

### Requirement: People is read once per account per pass
People connections SHALL be listed 1,000 a page, and the account-wide delta SHALL be read once per pass and projected onto each contact group rather than relisted per group.

#### Scenario: Three contact groups
- GIVEN a Google account with three contact groups
- WHEN its contacts sync
- THEN `people.connections.list` is walked once

## MODIFIED Requirements

### Requirement: A provider grant is the app's own
A browser grant against Google's or Microsoft's authorization server SHALL run with the app's registered client and SHALL NOT send an RFC 8707 resource. Domains SHALL share one grant only when their scopes address the same API; every Google scope SHALL count as one API, since Google issues one token for all of them.

#### Scenario: Microsoft mail and calendars
- GIVEN mail over IMAP and calendars over Graph, both connected through Microsoft
- WHEN the sign-in sequence runs
- THEN it asks for two consents, Outlook's and Graph's

#### Scenario: Google mail and calendars
- GIVEN mail over the Gmail API and calendars over the Calendar API
- WHEN the sign-in sequence runs
- THEN it asks for one consent

### Requirement: An HTTP sync rides out throttling
Every HTTP backend (Graph, Gmail, Google Calendar and People, CalDAV, CardDAV, JMAP) SHALL send a request again when the server answers 429, 503, or a 403 naming one of Google's rate limits (the reasons `rateLimitExceeded` or `userRateLimitExceeded`, or a message naming a per-minute quota: `Quota exceeded for quota metric`, `Units per minute per user`), after the `Retry-After` the server named, or else after an exponential back-off with jitter, a Google 429 or quota 403 waiting at least until the next minute. The waiting SHALL be bounded per request and per native call, a `Retry-After` past the bound SHALL NOT be waited, and past the bound the throttled answer SHALL fail the request as any error does. A 403 for anything else, a daily quota included, SHALL NOT be retried. An answer whose end cannot be told SHALL NOT be retried.

#### Scenario: Graph asks for a pause
- GIVEN a Graph request answered 429 with `Retry-After: 2`
- WHEN the bridge reads the answer
- THEN it waits two seconds and sends the request again, and the sync goes on with the answer to that

#### Scenario: Gmail's per-minute quota
- GIVEN a Gmail request answered 403 `Quota exceeded for quota metric 'Total Query Cost' and limit 'Units per minute per user'`, with no `Retry-After`
- WHEN the bridge reads the answer
- THEN it waits until the next minute and sends the request again

#### Scenario: A server that keeps throttling
- GIVEN a server answering 503 to every request
- WHEN a sync pass reaches it
- THEN the request fails with the 503 within the bound, and the pass reports it as it reports any error

### Requirement: Gmail is paced below its quota
Gmail API requests SHALL be paced near 40 a second across every worker of the process, below the 50 metadata reads a second Gmail's per-user quota allows, and SHALL spend at most a per-minute budget of quota units below the 15,000 units per minute per user Google documents, each request counting its method's units, rather than sent until Gmail answers 429 or a quota 403.

#### Scenario: A first round over a large label
- GIVEN several workers reading Gmail envelopes at once
- WHEN they run
- THEN their requests together go out at about 40 a second

#### Scenario: A long pass
- GIVEN a pass that would spend more than the minute's budget
- WHEN its requests go out
- THEN the request past the budget waits for the next minute rather than drawing a quota refusal

### Requirement: A Google calendar can run over the Calendar API
A calendar connection behind the `google://` marker SHALL list the user's calendar list and read events as iCalendar, a series with its changed and cancelled instances as one entry, those instances being the listed events naming the series as theirs. An entry's revision SHALL be the master's ETag folded with its instances', so an instance edited on Google moves the entry. A pass after the first SHALL list only what changed since the calendar's `syncToken` (the same `showDeleted` and `maxResults` 2,500 every time), a changed instance folded into its series; a token Google refuses with 410 SHALL fall back to listing the calendar in full, building the bodies from that listing. A write SHALL go to the master with Google's `If-Match`, after the folded revision is checked, and a created event SHALL be imported so it keeps its UID.

#### Scenario: An instance edited on Google
- GIVEN a series whose one occurrence was moved on Google since the last pass
- WHEN the calendar syncs
- THEN the entry carries the moved occurrence

#### Scenario: A quiet calendar
- GIVEN a Google calendar unchanged since its last pass
- WHEN it syncs
- THEN one listing with its sync token answers with nothing to read

## REMOVED Requirements
