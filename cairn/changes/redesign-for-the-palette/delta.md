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
The sync dock and its log SHALL name each step of a pass with one phrase whatever the backend: Connecting, Fetching changes, Downloading envelopes, Downloading messages, Downloading contacts, Downloading events, Sending changes, Updating the phone's contacts or calendar, Resolving conflicts, Loading older mail, Waiting for Wi-Fi and Up to date, a count reading "N of M" after its step. A backend without a step SHALL skip it rather than name it otherwise.

#### Scenario: IMAP and Gmail
- GIVEN an IMAP account and a Gmail account syncing their mail headers
- WHEN the dock shows each one's step
- THEN both read "Downloading envelopes"

## MODIFIED Requirements

## REMOVED Requirements
