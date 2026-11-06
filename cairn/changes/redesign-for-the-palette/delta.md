---
cairn: delta
change: redesign-for-the-palette
---

Folds into `spec/offline-store.md`, beside the chrome the lists share, and the sync wording into `spec/mail.md`, beside the sync dock.

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

### Requirement: An unread message is marked in its row's leading gutter
A mail row SHALL mark an unread message with an accent dot in the row's leading gutter, before its avatar and level with the sender's line, so a read row and an unread one keep their times in one column.

#### Scenario: Two rows, one unread
- GIVEN a list holding a read and an unread message
- WHEN it shows
- THEN the unread row's dot stands left of its avatar, and both times end at the same edge

## MODIFIED Requirements

## REMOVED Requirements
