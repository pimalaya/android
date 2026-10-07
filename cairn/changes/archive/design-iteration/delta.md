---
cairn: delta
change: design-iteration
---

## ADDED Requirements

### Requirement: The mail list selects
A long press on a message SHALL start a selection holding it, and while one runs a tap SHALL add or remove a message rather than open it. The bar SHALL then carry the count, read or unread, star or unstar, delete after asking, and select-all, each over the whole selection, a toggle going the way that changes something. Back SHALL clear the selection.

#### Scenario: Starring three messages
- GIVEN three messages, one of them starred
- WHEN they are selected and the bar's star is pressed
- THEN all three are starred

### Requirement: An item's page groups its sections into cards
The contact editor and the entry page SHALL draw each section as a small label over one rounded card of its rows, the grouped lists' shape on a single item.

#### Scenario: A contact with two phones
- GIVEN a contact with two phone numbers
- WHEN its editor opens
- THEN both numbers sit in one card under the phones label

## MODIFIED Requirements

### Requirement: A row's trailing marks sit on the line they describe
A message row SHALL lead with a dot while unread, end the sender's line with the time, the subject's line with a star toggling the important marker, centred on that line, and the mailbox-and-account line with the replied mark, each aligned with the line it belongs to rather than stacked in a column beside all three. An attachment SHALL add a chip under the three lines. A star that is on SHALL be filled in yellow, in the list and the reader alike, whatever the theme's accent.

#### Scenario: A row with a long subject
- GIVEN a subject wider than the row
- WHEN it is drawn
- THEN it ellipsizes and the star keeps its place at the end of that same line

#### Scenario: A row with markers
- GIVEN a message that was replied to and marked important
- WHEN it is drawn
- THEN its star is filled yellow at the end of the subject's line, and the replied mark ends the mailbox and account line

### Requirement: The mail list narrows what it shows
The mail list SHALL be one page over every mailbox the filter lets through, and SHALL offer a search over the sender and the subject, an unread chip and an attachments chip. None of these SHALL change what syncs, which the filter alone decides.

#### Scenario: Unread only
- GIVEN read and unread mail in two accounts
- WHEN the unread chip is on
- THEN the list shows both accounts' unread messages and nothing else

### Requirement: The agenda offers the week
The agenda's header SHALL carry the month and year over one card of this week's seven days, today in the accent and the selected day on a filled disc. Pressing a day SHALL scroll the agenda to the first entry on or after it; the days already gone SHALL stay dimmed and inert, the agenda starting at today.

#### Scenario: Jumping to Friday
- GIVEN entries on Wednesday and Saturday, today being Tuesday
- WHEN Friday is pressed in the week
- THEN the agenda scrolls to Saturday's card

### Requirement: The bottom bar switches domains
A bottom navigation bar SHALL switch between mail, contacts and calendars, in that order, the one on screen on a neutral indicator, mail carrying the count of unread messages among those listed. It SHALL show on the three lists only. The drawer SHALL open on the app's name beside a closing cross, then one card per account naming its address and the domains it covers, with a pill saying when it last synced and a check in its corner while it takes part, then a line and the actions. Pressing a card SHALL open that account's settings. The drawer SHALL NOT list mailboxes. A list screen's bar SHALL carry the burger and SHALL NOT carry the domain buttons.

#### Scenario: Switching to the calendars
- GIVEN the mail list
- WHEN the calendars item of the bottom bar is pressed
- THEN the agenda is swapped in with no slide, the calendars item on the indicator

#### Scenario: An account the filter hides
- GIVEN an account hidden by the filter
- WHEN the drawer opens
- THEN its card carries no check

### Requirement: A list opens on a large title
Every list screen SHALL open on a large title naming what it shows over a supporting line counting it, scrolling with the rows. The bar SHALL carry the same title only once the large one has scrolled out of sight. The list's add button SHALL be an extended one, a pencil on mail, a person-plus on contacts and a plus on the agenda, shrinking to its square glyph while the list scrolls down and growing its label back when it scrolls up.

#### Scenario: Scrolling the mail list
- GIVEN the mail list at its top
- WHEN it is scrolled past its large title
- THEN the bar shows the title, and the add button shrinks to its glyph

## REMOVED Requirements

### Requirement: The agenda offers the coming days
Replaced by the agenda offering the week.
