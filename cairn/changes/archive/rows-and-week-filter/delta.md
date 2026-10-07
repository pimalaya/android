---
cairn: delta
change: rows-and-week-filter
---

## ADDED Requirements

### Requirement: The three lists share one row
A contact row and an agenda row SHALL take the mail row's shape: a disc, then three lines, a hairline parting the rows of a card. A contact row SHALL lead with the name, then the phone or else the email, then its addressbook and account, naming a card an account holds before one on the device. An agenda row SHALL lead with the entry ended by when it starts, then how long it runs ended by the kind of entry, then its calendar and account.

#### Scenario: A contact on two cards
- GIVEN a contact held in the on-device book and in an account's addressbook
- WHEN the contacts list is shown
- THEN its row names that addressbook and that account

## MODIFIED Requirements

### Requirement: A row's trailing marks sit on the line they describe
A message row SHALL end the sender's line with the time and, while unread, a dot; the subject's line with its marks read from the edge inwards (a star shown only while the message is important, a paperclip while it carries an attachment); and the mailbox-and-account line with the replied mark, each aligned with the line it belongs to rather than stacked in a column beside all three. The star SHALL be a mark, filled in yellow whatever the theme's accent, not a button. A hairline SHALL part the rows of one card.

#### Scenario: A row with a long subject
- GIVEN a subject wider than the row
- WHEN it is drawn
- THEN it ellipsizes and its marks keep their place at the end of that same line

#### Scenario: A row with markers
- GIVEN an unread message that was replied to, marked important and carrying an attachment
- WHEN it is drawn
- THEN the sender's line ends with the time and the dot, the subject's line with the paperclip and the yellow star, and the replied mark ends the mailbox and account line

### Requirement: The agenda offers the week
The agenda's header SHALL carry the month and year over one card of this week's seven days, today in the accent. Pressing a day SHALL narrow the agenda to that day, the day on a filled disc, and pressing it again SHALL widen the agenda back to every entry from today on. A day of this week already gone SHALL be pickable like the others.

#### Scenario: Only Friday
- GIVEN entries on Wednesday, Friday and Saturday, today being Tuesday
- WHEN Friday is pressed in the week
- THEN the agenda shows Friday's entries alone
- AND pressing Friday again shows all three days

## REMOVED Requirements
