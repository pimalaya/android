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
A task write SHALL patch the stored VTODO through its syntax tree, every property it does not set left byte for byte, and stage the result for the calendar's next sync. Setting a state SHALL add or remove only the three reserved values: the new one SHALL take the place of the first reserved value it replaces, else a `CATEGORIES` line of its own; every other category SHALL stay in order, and a line holding none of the three SHALL stay byte for byte; a task already in the state written SHALL be left untouched. Editing a task's categories SHALL keep its state. Clearing the parent SHALL remove only the `RELATED-TO` of type `PARENT`, and clearing the source only the `ATTACH` holding a `mid:` URI.

#### Scenario: Another client's relation
- GIVEN a VTODO relating a sibling and attaching a document
- WHEN its parent and its source are cleared
- THEN the sibling relation and the document attachment stay

#### Scenario: A person's category beside the state
- GIVEN a VTODO carrying `CATEGORIES:@home,inbox,Work` and `CATEGORIES:@phone`
- WHEN it is clarified into Waiting
- THEN it carries `CATEGORIES:@home,waiting,Work`, its `@phone` line byte for byte

### Requirement: A capture is on the phone at once
A capture SHALL be staged at once as a dateless VTODO carrying `CATEGORIES:inbox` in a writable CalDAV calendar, offline included, from the Tasks tab, a launcher shortcut, the capture widget, the share sheet, or a mail's Add to tasks. A capture from a mail SHALL link it by `mid:` and leave the mail where it is filed.

#### Scenario: Capturing a mail
- GIVEN a mail in INBOX
- WHEN Add to tasks is saved from the reader
- THEN the Inbox holds a task carrying `CATEGORIES:inbox`, linked to the mail's `Message-ID`, and the mail is still in INBOX

### Requirement: Only CalDAV calendars take tasks
A capture SHALL be offered only writable calendars served over CalDAV; a Microsoft, Google or JMAP calendar and the phone's local calendar SHALL NOT be offered.

#### Scenario: No CalDAV calendar
- GIVEN accounts whose calendars are all Graph or Google
- WHEN Capture is pressed
- THEN the capture sheet says no calendar takes tasks yet and offers no save

### Requirement: Clarifying never puts an item back
The clarify flow SHALL ask, one Inbox item at a time, whether it is actionable, under two minutes, someone else's, then its next action, and its outcome SHALL be an edit of the same item, written as one change: the title may be rephrased at any step; not actionable SHALL delete it (restorable from Deleted items) or move it to Someday (`someday`); under two minutes SHALL mark it done; someone else's SHALL move it to Waiting (`waiting`), the person named as `CONTACT` when given; otherwise it SHALL become a next action, its `inbox` removed, with its categories, the task it sits under, a due date and, when several take tasks, its calendar. A skipped item SHALL stay in the Inbox, first in line for the next pass.

#### Scenario: A capture that is a next action
- GIVEN an Inbox item
- WHEN it is clarified as actionable, longer than two minutes, mine, rephrased, with a category and a due date
- THEN the same item carries the new title, the category and the due date, and no `inbox`

### Requirement: A reply marks a Waiting For
A Waiting For item linked to a mail SHALL show a reply received when the store holds a mail whose `In-Reply-To` names that mail. That SHALL be computed at load, never written.

#### Scenario: Send and wait
- GIVEN a mail sent with Send and wait for a reply
- WHEN a reply to it is synced
- THEN its Waiting For item shows Reply received

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
The Tasks tab SHALL offer, under its lists, a filter over the categories the open tasks carry, the three reserved values aside; a category picked SHALL narrow Inbox, Next, Waiting and Someday alike, their counts included, and SHALL stay while the person switches lists. A task SHALL take part in the filter by any of its categories: contexts, projects, anything.

#### Scenario: A project is a filter
- GIVEN two next actions, one carrying `CATEGORIES:Garden` and one carrying none
- WHEN Garden is picked under the lists
- THEN Next lists the first alone, and Waiting and Someday count only Garden tasks

### Requirement: Filing a capture elsewhere never loses it
Clarifying an item into another task calendar SHALL stage its edited body as an add in the target, then a removal of the source item only once that add was accepted, rather than a move: a refused add SHALL leave the capture where it was. The `UID` SHALL stay.

#### Scenario: Another calendar
- GIVEN an Inbox item in Work and a second task calendar, Home
- WHEN it is clarified into a next action filed in Home
- THEN the store holds it once, in Home, under the same `UID`, and Work no more
