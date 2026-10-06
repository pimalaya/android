---
cairn: delta
change: design-refresh
---

## ADDED Requirements

### Requirement: The bottom bar switches domains
A bottom navigation bar SHALL switch between mail, contacts and calendars, in that order, the one on screen on an accent pill, mail carrying the count of unread messages among those listed. It SHALL show on the three lists only. The drawer SHALL open on a title bar, a closing cross beside the app's name, then the mailboxes, all of them first and the one the mail list shows on an accent pill, then the account rows and the footer of actions. A list screen's bar SHALL carry the burger and SHALL NOT carry the domain buttons.

#### Scenario: Switching to the calendars
- GIVEN the mail list
- WHEN the calendars item of the bottom bar is pressed
- THEN the agenda is swapped in with no slide, the calendars item on the accent pill

### Requirement: A list opens on a large title
Every list screen SHALL open on a large title naming what it shows over a supporting line counting it, scrolling with the rows. The bar SHALL carry the same title only once the large one has scrolled out of sight. The list's add button SHALL be an extended one, its label folding away while the list scrolls down and coming back when it scrolls up.

#### Scenario: Scrolling the mail list
- GIVEN the mail list at its top
- WHEN it is scrolled past its large title
- THEN the bar shows the title, and the add button folds to its glyph

### Requirement: A list groups its rows into cards
Every list screen SHALL group its rows into rounded cards under a section header: mail by the day a message arrived, contacts by their letter with conflicts first under a header of their own, the agenda by the day an entry starts. Pressing a contacts letter header SHALL select that section, or clear it when it is all selected.

#### Scenario: Two days of mail
- GIVEN messages from today and from yesterday
- WHEN the mail list is shown
- THEN today's sit in one card under Today and yesterday's in another under Yesterday

### Requirement: The mail list narrows what it shows
The mail list SHALL offer a search over the sender and the subject, an unread chip and an attachments chip, and SHALL narrow to one mailbox picked in the drawer. None of these SHALL change what syncs, which the filter alone decides.

#### Scenario: A mailbox from the drawer
- GIVEN mail in two accounts' INBOX and Archive
- WHEN Archive is picked in the drawer
- THEN the list shows both accounts' Archive under the title Archive

### Requirement: The agenda offers the coming days
The agenda's header SHALL carry a strip of the next fourteen days, and pressing one SHALL scroll the agenda to the first entry on or after it.

#### Scenario: Jumping to Friday
- GIVEN entries on Wednesday and Saturday
- WHEN Friday is pressed in the strip
- THEN the agenda scrolls to Saturday's card

## MODIFIED Requirements

### Requirement: A list bar carries its actions inline
Every list screen SHALL carry its actions as bar buttons with no overflow: on contacts the birthdays, the duplicate remover and one import/export button opening a menu of the two, then on every list the filter last, accented while it hides anything. A list's search SHALL sit in its header, under the large title, not in the bar.

#### Scenario: The contacts bar
- WHEN the contacts list is shown
- THEN its bar carries birthdays, duplicates, import/export and the filter after the burger, and its search field sits under the large title

### Requirement: A row's trailing marks sit on the line they describe
A message row SHALL lead with a dot while unread, end the sender's line with the time, the subject's line with a star toggling the important marker, and the mailbox-and-account line with the replied mark, each aligned with the line it belongs to rather than stacked in a column beside all three. An attachment SHALL add a chip under the three lines.

#### Scenario: A row with a long subject
- GIVEN a subject wider than the row
- WHEN it is drawn
- THEN it ellipsizes and the star keeps its place at the end of that same line

#### Scenario: A row with markers
- GIVEN a message that was replied to and marked important
- WHEN it is drawn
- THEN its star is filled in the accent at the end of the subject's line, and the replied mark ends the mailbox and account line

## REMOVED Requirements

### Requirement: The drawer switches domains
Replaced by the bottom bar switching domains.
