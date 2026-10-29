---
cairn: delta
change: gtd-hardening
---

Folds into spec/tasks.md, spec/calendar.md and spec/offline-store.md.

## ADDED Requirements

### Requirement: Each list has one order
Next SHALL list the soonest `DUE` first, as an instant, a task stating none after every one that does, then by `PRIORITY`, 1 first and 9 last, 0 or none after 9, then the oldest captured first; Inbox, Waiting and Someday SHALL list the oldest captured first (pimdir GUIDE §17). Captured is `CREATED`, else `DTSTAMP`. A subtask SHALL still show under its parent in the same list.

#### Scenario: Two next actions due alike
- GIVEN two next actions due the same day at the same time, one of `PRIORITY:1` captured last week and one of no priority captured last month
- WHEN Next opens
- THEN the one of `PRIORITY:1` comes first

#### Scenario: Two equal next actions
- GIVEN two next actions with no due date and no priority, one captured last month and one yesterday
- WHEN Next opens
- THEN last month's comes first

### Requirement: The Tasks tab reads its to-dos from the summaries
The Tasks tab SHALL find its to-dos as the items holding a `task_summary` row, through the canonical `list_tasks_page_asc`, and SHALL read the body of those alone; a calendar item the store holds with no summary row SHALL have one derived through io-pimdir, once, before the tab reads.

#### Scenario: An agenda of events
- GIVEN a calendar holding a thousand events and one to-do
- WHEN the Tasks tab loads
- THEN it reads one body

#### Scenario: A store from before summaries
- GIVEN a store whose calendar items were written with no summary
- WHEN the Tasks tab first loads
- THEN `task_summary` holds a row per to-do and the tab lists them all

## MODIFIED Requirements

### Requirement: A task write patches the object
A task write SHALL patch the stored VTODO through its syntax tree as pimdir GUIDE §17's writing table says, which pimdir's vectors/gtd/writers.json checks byte for byte, and stage the result for the calendar's next sync. Setting a state SHALL write nothing when the task already carries exactly that state among the three, its spelling kept; otherwise only the `CATEGORIES` lines holding a reserved value SHALL change: the first reserved value SHALL become the new state's literal, the others go, the person's values stay in order, a line left empty goes, and with no reserved value to take the place of, the state SHALL be a line of its own after the last `CATEGORIES` line, else after the task's last property. Editing the person's categories SHALL keep the state where it stands and write nothing when the person's values, in document order, already are the new ones; otherwise the new list, trimmed, empty and folded repeats dropped, SHALL take the place of the first person's value, the others go, a line left empty goes, and with none to take the place of, it SHALL be a line placed as a new state is; a reserved value among them SHALL be refused. Setting the contact SHALL replace every `CONTACT` where the first stood, else after the last property, written `CONTACT;ALTREP="mailto:<address>":<name>`, the address as the text when no name is given. An edit that changes a line SHALL restate `DTSTAMP` where it stands, after the last property when absent, and `LAST-MODIFIED` only where the task carries one, `SEQUENCE` and `CREATED` untouched; a line it does not change SHALL keep its bytes; an edit whose result the task already holds SHALL write nothing and stage nothing. Clearing the parent SHALL remove only the `RELATED-TO` of type `PARENT`, and clearing the source only the `ATTACH` holding a `mid:` URI. A staged task SHALL carry the summary io-pimdir derives from its body.

#### Scenario: Another client's relation
- GIVEN a VTODO relating a sibling and attaching a document
- WHEN its parent and its source are cleared
- THEN the sibling relation and the document attachment stay

#### Scenario: A person's category beside the state
- GIVEN a VTODO carrying `CATEGORIES:@home,inbox,Work` and `CATEGORIES:@phone`
- WHEN it is clarified into Waiting
- THEN it carries `CATEGORIES:@home,waiting,Work`, its `@phone` line byte for byte

#### Scenario: A category edit across lines
- GIVEN a VTODO carrying `CATEGORIES:work` and `CATEGORIES:Someday`
- WHEN its categories are set to `Home, errands, home`
- THEN it carries `CATEGORIES:Home,errands` where `work` stood, its `CATEGORIES:Someday` line byte for byte

#### Scenario: Nothing to write
- GIVEN a VTODO carrying `CATEGORIES:Waiting`
- WHEN it is clarified into Waiting
- THEN nothing is staged and its bytes are unchanged

### Requirement: A capture is on the phone at once
A capture SHALL be staged at once as a dateless VTODO carrying `CATEGORIES:inbox`, `CREATED` and `DTSTAMP` both the instant of capture in UTC, in a calendar that takes tasks, offline included, from the Tasks tab, a launcher shortcut, the capture widget, the share sheet, or a mail's Add to tasks. A capture from a mail SHALL link it by `mid:` and leave the mail where it is filed.

#### Scenario: Capturing a mail
- GIVEN a mail in INBOX
- WHEN Add to tasks is saved from the reader
- THEN the Inbox holds a task carrying `CATEGORIES:inbox` and `CREATED`, linked to the mail's `Message-ID`, and the mail is still in INBOX

### Requirement: Only CalDAV calendars take tasks
A capture SHALL be offered only writable calendars served over CalDAV for which no source syncing them states `x-vtodo` `none` (pimdir STORAGE §15.6); a Microsoft, Google or JMAP calendar and the phone's local calendar SHALL NOT be offered. A CalDAV listing SHALL declare `x-vtodo` `none` for a calendar whose `supported-calendar-component-set` names components but not `VTODO`, and `full` otherwise, and SHALL record the server source as syncing each calendar it lists, a calendar's sync state kept as it stands (none before its first round), so the declaration counts from the listing on, before the calendar's first sync. A create or a move of a task into a calendar not taking tasks SHALL be refused before anything is staged.

#### Scenario: No CalDAV calendar
- GIVEN accounts whose calendars are all Graph or Google
- WHEN Capture is pressed
- THEN the capture sheet says no calendar takes tasks yet and offers no save

#### Scenario: An events-only CalDAV calendar
- GIVEN a CalDAV calendar whose `supported-calendar-component-set` names `VEVENT` alone, listed and never synced
- WHEN Capture is pressed
- THEN the capture sheet does not offer it

### Requirement: A category filter narrows every list
The Tasks tab SHALL offer, under its lists, a filter over the categories the open tasks carry, the three reserved values aside, one entry for spellings that fold alike (Unicode lowercase, locale-independent, trimmed); a category picked SHALL narrow Inbox, Next, Waiting and Someday alike, their counts included, compared folded, and SHALL stay while the person switches lists. A task SHALL take part in the filter by any of its categories: contexts, projects, anything.

#### Scenario: A project is a filter
- GIVEN two next actions, one carrying `CATEGORIES:Garden` and one carrying none
- WHEN Garden is picked under the lists
- THEN Next lists the first alone, and Waiting and Someday count only Garden tasks

#### Scenario: Two spellings
- GIVEN a next action carrying `CATEGORIES:Work` and another carrying `CATEGORIES:work`
- WHEN Work is picked under the lists
- THEN Next lists both, and the filter offers Work once

### Requirement: Filing a capture elsewhere never loses it
Clarifying an item into another task calendar SHALL stage, in one transaction, its edited body as an add in the target superseding the source item (pimdir STORAGE §15.3) and the source's tombstone, rather than a move: the source's delete SHALL NOT be pushed while the add is a pending create, so a refused create SHALL leave the capture on its server. The `UID` and the link id SHALL stay. When the server syncing the target holds the source item in a calendar of the same account, the next sync SHALL deliver the add by a WebDAV `MOVE` of that item into the target, `If-Match` its last-synced ETag, then a write of the edited body over it, so the `UID` never lives in two calendars (pimdir SYNC §4); a move the server answers as one it cannot do (405, 501, 404) SHALL be an upload instead, and a move refused for the `UID` or the ETag SHALL refuse the add as a refused upload does, uploading nothing. The source's delete that follows SHALL count as done when the item is already gone.

#### Scenario: Another calendar
- GIVEN an Inbox item in Work and a second task calendar, Home
- WHEN it is clarified into a next action filed in Home
- THEN the store holds it once, in Home, under the same `UID`, and Work no more

#### Scenario: A server keeping one UID per account
- GIVEN an Inbox item in Work clarified into Home, on a server refusing a create of a `UID` another of its calendars holds, as Fastmail does
- WHEN the calendars sync
- THEN Work's item is moved into Home and carries the clarification there, nothing is uploaded, and Work no longer holds it

#### Scenario: A server that cannot move
- GIVEN an Inbox item in Work clarified into Home, on a server answering `MOVE` with 404, as Google does
- WHEN the calendars sync
- THEN Home receives the item by an upload, and Work's copy is deleted once Home holds it

#### Scenario: The server refuses the create
- GIVEN an Inbox item in Work clarified into Home, and a server refusing the move and the create there with 403 for the `UID`
- WHEN the calendars sync
- THEN both passes succeed, the add stays staged, and Work still holds the item on the server

### Requirement: A calendar entry can be created
The app SHALL create a calendar entry from the entry page in the store alone: a fresh object carrying the `UID`, `DTSTAMP` and `DTSTART` RFC 5545 section 3.6.1 requires and nothing else, staged as a pending create. The next sync SHALL PUT it to a resource named after its `UID`, guarded by `If-None-Match: *`. A create the server refuses as such, with 403, 409, 412 or 422, SHALL stay staged for the sync that follows and the pass SHALL go on with the calendar's other changes, so one refused item never fails the calendar's pass; a 401, a throttle (a 403 naming a rate limit or a quota) and every other failure SHALL fail the pass as before. A refusal SHALL cross the bridge with its status whichever refusal io-webdav reads it as, a duplicate `UID` (`no-uid-conflict`, `unique-scheduling-object-resource`) included.

#### Scenario: A new entry
- GIVEN a writable calendar
- WHEN the agenda's add button is used and the page is saved
- THEN the entry appears in the agenda at once, with no network
- AND the next sync creates it on the server

#### Scenario: The resource already exists
- GIVEN a server already holding that resource
- WHEN the sync pushes the create
- THEN the server refuses it, nothing is overwritten, and the create stays staged

#### Scenario: One create refused among others
- GIVEN a calendar with two staged creates, the server answering the first with 403 "WebDAV collection already holds a resource with the same UID" or with 409
- WHEN the calendar syncs
- THEN the pass succeeds, the second is created, and the first stays staged

#### Scenario: A refused token
- GIVEN a staged create the server answers with 401
- WHEN the calendar syncs
- THEN the pass fails, so the token is renewed

#### Scenario: Several calendars
- GIVEN more than one writable calendar
- WHEN a new entry is started
- THEN the calendar it lands in is asked for, and taken silently when there is only one

### Requirement: A calendar entry can be deleted
The app SHALL stage a calendar entry's deletion, after asking, and the entry SHALL leave the agenda at once. The next sync SHALL delete it guarded by the ETag it was read at; a delete the server refuses SHALL leave the removal staged for the sync that follows. A delete finding the entry already gone SHALL count as done: a 404, or a 412 when the server, read back, holds no entry of that name (an `If-Match` on an object gone being false, RFC 9110 section 13.1.1).

#### Scenario: Deleting an entry
- GIVEN an entry open on its page
- WHEN it is deleted
- THEN it leaves the agenda at once, and the next sync tells the server

#### Scenario: The entry moved under it
- GIVEN an entry another client has edited since it was read
- WHEN the sync pushes the delete
- THEN the server refuses on the precondition and the removal stays staged

#### Scenario: The entry already gone
- GIVEN a deleted entry another client deleted on the server after the sync listed it, on a server answering 412 to the guarded delete of an object gone
- WHEN the sync pushes the delete
- THEN the removal is done and nothing stays staged

#### Scenario: An entry the server has never seen
- GIVEN a new entry that has not been saved
- WHEN it is deleted
- THEN the page closes and nothing is ever sent

### Requirement: A reply marks a Waiting For
A Waiting For item linked to a mail SHALL show a reply received when the store holds a live mail whose `In-Reply-To` names that mail, a mail filed under the `sent` or `drafts` role being the person's own and answering nothing, as io-pimdir's `PimdirReader::replied` reads it (the canonical `list_replied`, one statement for every item listed). That SHALL be computed at load, never written.

#### Scenario: Send and wait
- GIVEN a mail sent with Send and wait for a reply
- WHEN a reply to it is synced
- THEN its Waiting For item shows Reply received

#### Scenario: The person's own follow-up
- GIVEN a Waiting For item linked to a mail, and the person's follow-up to that mail in Sent
- WHEN the Tasks tab loads
- THEN the item shows no reply received

### Requirement: An item carries the standard's summary
The store SHALL record what a reader lists an item from in its kind's summary table and the people it names in `item_address`, as pimdir STORAGE Annex A defines them, written through the canonical statements. It SHALL NOT keep a summary convention of its own in the item row, and SHALL derive one through io-pimdir wherever the body is on hand: a calendar object listed from its server or the phone, or staged by the app, SHALL carry the summary and sort key its body derives.

#### Scenario: A card is written
- GIVEN a vCard with a `FN`, a `UID` and two `EMAIL` properties
- WHEN the app writes it
- THEN `contact_summary` holds its name, uid, kind and org, and `item_address` holds both emails under the `email` role in document order

#### Scenario: A message listed but not fetched
- GIVEN an IMAP envelope and no body
- WHEN the mail mirror writes the row
- THEN `mail_summary` holds the same fields the body's derivation would, built from the envelope

#### Scenario: A to-do listed
- GIVEN a CalDAV calendar holding a VTODO carrying `CATEGORIES:waiting,@home` and `PRIORITY:2`
- WHEN it syncs
- THEN `task_summary` holds its summary, its priority and both categories

#### Scenario: A summary the writer did not restate
- GIVEN an item whose summary is stored
- WHEN a write carries none
- THEN the stored summary is kept, on the same terms as the sort key

## REMOVED Requirements
