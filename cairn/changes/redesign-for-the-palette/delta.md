---
cairn: delta
change: redesign-for-the-palette
---

Folds into spec/offline-store.md, beside the chrome the lists share, and the sync wording into spec/mail.md, beside the sync dock; each MODIFIED requirement names its file.

## ADDED Requirements

### Requirement: An avatar takes the palette's tones
A row's avatar SHALL be a soft fill of one of eight hues at one lightness, its letter in a deep tone of the same hue, in light and dark, the hue still taken from the row's seed. A picked row's check disc SHALL be the avatar's size.

#### Scenario: Two senders
- GIVEN messages from two addresses whose seeds land on different hues
- WHEN the mail list shows
- THEN both discs read at one weight, each letter in its disc's hue

### Requirement: The top-right actions share one shape
A screen's actions at the top end of the bar SHALL sit in one surface pill of icon buttons, the reader's toolbox, whatever the screen.

#### Scenario: The contacts list
- GIVEN the contacts list
- WHEN it shows
- THEN Next birthday, Find duplicates and Import or export sit in one pill

### Requirement: A sync step reads the same on every backend
The sync dock and its log SHALL name each step of a pass with one phrase whatever the backend, a verb first: Connecting; Fetching changes; Downloading envelopes, then " · N of M mailboxes"; Downloading N messages, or " · N of M" for older mail downloading behind; Downloading contacts or events, " · N of M" address books or calendars, then Downloading N contacts or events; Sending N changes; Updating the phone's contacts or calendar, then Updating N contacts or events on the phone; Resolving N conflicts; Loading older mail, then its step; Waiting for Wi-Fi. The sheet's other accounts SHALL read Up next or Up to date. A backend without a step SHALL skip it rather than name it otherwise.

#### Scenario: IMAP and Gmail
- GIVEN an IMAP account and a Gmail account syncing their mail headers
- WHEN the dock shows each one's step
- THEN both read "Downloading envelopes" and their mailboxes counted, as "3 of 12 mailboxes"

### Requirement: An unread message is marked on its avatar
A mail row SHALL mark an unread message with an accent badge on its avatar's top-right edge, ringed in the card's tone, so a read row and an unread one keep their avatars and their times in one column. The time SHALL keep the secondary ink, unread or not.

#### Scenario: Two rows, one unread
- GIVEN a list holding a read and an unread message
- WHEN it shows
- THEN the unread row's avatar carries the badge, and both avatars and both times line up

### Requirement: Every list draws its avatar with one slot
Mail, contacts, the agenda, files and tasks SHALL draw a row's avatar with one layout and one binding: the disc in the palette's tones, a calendar's disc taking its own colour's hue (a grey or an unreadable colour falling back to the seed's), the accent check disc at the avatar's size while the row is picked, and the unread badge. A task's box SHALL stand in the avatar's place at its size, a circle outlined in the secondary ink, empty while open, a check once done, a cross once cancelled, the Inbox glyph for a capture; a picked task SHALL show the same check disc in the box's place, its card untinted.

#### Scenario: A calendar coloured red
- GIVEN a calendar the server colours #D50000
- WHEN its entries show in the agenda
- THEN their discs are the palette's red slot, soft fill and deep letter, as a contact landing on that slot

### Requirement: A selection's actions take the navigation's place
On every list, a long press SHALL start a selection and, while one runs, a tap SHALL add or remove a row; the bar SHALL carry the cross, the count and select-all only, and the actions SHALL stand in the bottom navigation's place, each its icon on the navigation's pill over its label: mail Read or Unread, Important or Not important, Delete; contacts Merge (two cards or more) and Delete; tasks Category, Status, Due, Move, Copy, Delete; the agenda and files Delete. Clearing every row SHALL end the selection, and the navigation and the list's own buttons SHALL come back.

#### Scenario: Two entries of the agenda
- GIVEN the agenda
- WHEN one entry is long pressed and another tapped
- THEN both discs show the check, the bar reads 2 selected beside select-all, and Delete stands where the navigation was

#### Scenario: What cannot be selected
- GIVEN an entry in conflict, an entry of the phone's own calendar and a mail's attachment in Files
- WHEN each is long pressed
- THEN none starts a selection: the entry's page alone settles a conflict, the phone's calendar is not written, and an attachment goes with its last message

### Requirement: Deleting a selection of entries or files asks first
Delete on a selection of agenda entries SHALL ask first, saying a repeating entry goes whole, then stage each entry's removal, every occurrence of a series with it, for the next sync. Delete on a selection of files SHALL ask first, then delete each from its folder.

#### Scenario: A weekly entry selected
- GIVEN a selection holding one occurrence of a weekly entry
- WHEN Delete is confirmed
- THEN every occurrence leaves the agenda and the series' removal is staged

### Requirement: The week card marks today and each day's calendars
In spec/calendar.md. The week card SHALL show today's number on the accent's disc, a pressed day on the ink's and today pressed ringed, and under each day one dot per calendar holding an entry that day, up to three, in that calendar's disc ink, each ringed in the card's tone and overlapping the one before. A pressed day SHALL narrow the list, not the dots.

#### Scenario: A day of two calendars
- GIVEN Thursday holding a Work entry and a Personal one
- WHEN the week shows
- THEN two overlapping dots stand under Thursday, Work's blue and Personal's green

### Requirement: The week starts on the day picked
In spec/calendar.md. A Settings page, raised from the drawer, SHALL offer the week's first day, Monday or Sunday; until one is picked the phone's language SHALL decide. The week card, its days and its number SHALL follow it.

#### Scenario: Sunday picked on a French phone
- GIVEN a phone in French, whose weeks start on Monday
- WHEN Sunday is picked in Settings
- THEN the week card runs from Sunday to Saturday

### Requirement: A page arrives and leaves with its bar
Every page (the reader, the composer, an entry, a contact, a task, Clarify, the weekly review) SHALL carry its own bar and drop in whole, bar included, as the account settings do, and lift away whole on back, its entrance played backwards; a list SHALL swap in place under the lists' shared bar.

#### Scenario: Opening a message
- GIVEN the mail list
- WHEN a message is opened
- THEN the reader, its bar included, drops in over the list

## MODIFIED Requirements

### Requirement: The mail list selects
In spec/mail.md. A long press on a message SHALL start a selection holding it, and while one runs a tap SHALL add or remove a message rather than open it. The bar SHALL then carry the count and select-all, and the navigation's place Read or Unread, Important or Not important, and Delete after asking, each over the whole selection, a toggle going the way that changes something. The selection SHALL be keyed by store id, and select-all SHALL select what the list's query lets through, read whole only when an action reads it. Back SHALL clear the selection.

#### Scenario: Starring three messages
- GIVEN three messages, one of them starred
- WHEN they are selected and Important is pressed
- THEN all three are starred

### Requirement: The contacts list selects like mail
In spec/offline-store.md. A long press on a contact SHALL start a selection, its disc turning into a check, with no checkbox on the row; Merge and Delete SHALL stand in the navigation's place.

#### Scenario: Selecting a contact
- GIVEN the contacts list
- WHEN a contact is long pressed
- THEN its disc shows a check, the bar shows the count, and Delete stands where the navigation was

### Requirement: The reader's actions
In spec/mail.md. The reader's bar SHALL keep Add to tasks, Copy and the flags in its toolbox, and Reply (Send again for a message that may have gone), Forward, Move and Delete SHALL stand in a bottom bar on the selection bar's items; a message still in the outbox SHALL offer Delete alone there.

#### Scenario: A message waiting in the outbox
- GIVEN a message waiting to be sent
- WHEN it is opened
- THEN the bottom bar holds Delete alone

## REMOVED Requirements
