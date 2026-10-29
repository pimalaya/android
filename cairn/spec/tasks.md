---
cairn: spec
capability: tasks
status: current
---

# Tasks

Tasks are the VTODOs of the CalDAV calendars, organised the GTD way (Getting Things Done) in a Tasks tab: an Inbox for captures, Next, Waiting For and Someday across accounts, a category filter over all four, a guided clarify flow and a weekly review. The state is three reserved `CATEGORIES` values, `inbox`, `waiting` and `someday`, as pimdir GUIDE §17 states them, so another client reads and sets it too; every other category is the person's own, and nothing but the body is written.

### Requirement: A task is a VTODO read the GTD way
The Tasks tab SHALL list the VTODOs of every calendar the filter shows, across accounts, in four lists: Inbox, Next, Waiting and Someday. A task's state SHALL be three reserved `CATEGORIES` values, `inbox`, `waiting` and `someday` (pimdir GUIDE §17), compared folded (Unicode lowercase, locale-independent, trimmed) and shown under the app's labels in every locale; a task SHALL be in the list of each state it carries. An open task carrying none of the three SHALL be a next action, open being any `STATUS` but `COMPLETED` and `CANCELLED`, a task stating no `STATUS` included; a `COMPLETED` or `CANCELLED` task SHALL be out of every list. `X-PIMALAYA-GTD` SHALL be neither read nor written. Every other `CATEGORIES` value SHALL be the person's own. A task whose `RELATED-TO` of type `PARENT` names another one in the same list SHALL show under it, and that relation SHALL decide nothing else; who it waits on SHALL be its `CONTACT`, and the mail it came from an `ATTACH` or `URL` holding a `mid:` URI (RFC 2392), both shown and deciding no list.

#### Scenario: A task another client created
- GIVEN a CalDAV calendar holding a VTODO with no `CATEGORIES` and no `STATUS`
- WHEN the Tasks tab loads
- THEN it lists the VTODO under Next

#### Scenario: A state another client set
- GIVEN a VTODO carrying `CATEGORIES:@home,Waiting`
- WHEN the Tasks tab loads
- THEN it lists the VTODO under Waiting, with `@home` its one category

#### Scenario: The former property
- GIVEN a VTODO carrying `X-PIMALAYA-GTD:WAITING` and no reserved category
- WHEN the Tasks tab loads
- THEN it lists the VTODO under Next

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

### Requirement: Clarifying never puts an item back
The clarify flow SHALL ask, one Inbox item at a time, whether it is actionable, under two minutes, someone else's, then its next action, and its outcome SHALL be an edit of the same item, written as one change: the title may be rephrased at any step; not actionable SHALL delete it (restorable from Deleted items) or move it to Someday (`someday`); under two minutes SHALL mark it done; someone else's SHALL move it to Waiting (`waiting`), the person named as `CONTACT` when given; otherwise it SHALL become a next action, its `inbox` removed, with its categories, the task it sits under, a due date and, when several take tasks, its calendar. A skipped item SHALL stay in the Inbox, first in line for the next pass.

#### Scenario: A capture that is a next action
- GIVEN an Inbox item
- WHEN it is clarified as actionable, longer than two minutes, mine, rephrased, with a category and a due date
- THEN the same item carries the new title, the category and the due date, and no `inbox`

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

### Requirement: The weekly review counts, it does not guess
The weekly review SHALL list the Inbox left, the calendar, the open next actions, the Waiting For items with the replies received and those over two weeks, and Someday, each opening what it counts. Finishing it SHALL keep the day on the device, and the Tasks header SHALL say when one is due again, seven days after.

#### Scenario: Waiting too long
- GIVEN a Waiting For item captured three weeks ago
- WHEN the weekly review opens
- THEN its Waiting For step counts one item over two weeks

### Requirement: Captures go to the calendar last picked
The capture sheet SHALL offer the task calendars as a dropdown, each named with its account, the one the last capture went to preselected, else the first; the dropdown SHALL be hidden when one calendar takes tasks. There SHALL be no default calendar to set elsewhere.

#### Scenario: Picked in the sheet
- GIVEN two CalDAV calendars, Work and Home, and a capture saved into Home
- WHEN the capture sheet opens again
- THEN its dropdown has Home preselected

### Requirement: An account says whether it takes tasks
Account settings SHALL carry a Tasks card for an account holding calendars: each calendar taking tasks, and each read-only one as events only. An account none of whose calendars take tasks SHALL get the line saying so, naming Microsoft's or Google's own service when it is theirs. Onboarding SHALL say it in one line under a calendar option that is not CalDAV.

#### Scenario: A Microsoft account
- GIVEN an account whose calendars run over Graph
- WHEN its settings open
- THEN the Tasks card says tasks are not available there, Microsoft keeping them in its own service

### Requirement: The Next widget lists the next actions
The Next widget SHALL list the open next actions the Tasks tab last read, due ones first, a row opening its task page and the header the Next list. It SHALL read no store to draw.

#### Scenario: A row
- GIVEN the Next widget on the home screen
- WHEN a row is tapped
- THEN the app opens on that task's page

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
