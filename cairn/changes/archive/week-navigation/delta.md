---
cairn: delta
change: week-navigation
---

## ADDED Requirements

### Requirement: An empty list says so below its header
A list with nothing to show SHALL centre its empty state in the space its header leaves below it, clear of the search, the chips and the week card.

#### Scenario: An empty day
- GIVEN a day with no entry picked in the week card
- WHEN the agenda renders
- THEN the empty state sits under the week card, not behind it

## MODIFIED Requirements

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

### Requirement: The agenda offers the week
The agenda's header SHALL carry the month and year of the shown week, its number and arrows to the weeks either side, over one card of that week's seven days, today in the accent. Pressing the number SHALL bring the card back to this week. Pressing a day SHALL narrow the agenda to that day, the day on a filled disc, and pressing it again SHALL widen it back: to every entry from today on while the card shows this week, and to the shown week otherwise.

#### Scenario: Only Friday
- GIVEN entries on Wednesday, Friday and Saturday, today being Tuesday
- WHEN Friday is pressed in the week
- THEN the agenda shows Friday's entries alone
- AND pressing Friday again shows all three days

#### Scenario: Next week
- GIVEN this week's card
- WHEN the next arrow is pressed
- THEN the card shows next week's days and number, and the agenda that week's entries alone

### Requirement: The three lists share one row
A contact row and an agenda row SHALL take the mail row's shape: a disc, then three lines, a hairline parting the rows of a card. A contact row SHALL lead with the name, then the phone or else the email, then its addressbook and account, naming a card an account holds before one on the device. An agenda row SHALL lead with the entry ended by when it starts, then the kind of entry by name and how long it runs, then its calendar and account.

#### Scenario: A contact on two cards
- GIVEN a contact held in the on-device book and in an account's addressbook
- WHEN the contacts list is shown
- THEN its row names that addressbook and that account

## REMOVED Requirements
