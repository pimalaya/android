---
cairn: delta
change: audit-s1-residuals
---

Folds into spec/onboarding.md (the first two ADDED) and spec/carddav-sync.md (the last two).

## ADDED Requirements

### Requirement: A locked phone shows no message
A new-mail notification SHALL be private: on a secure lock screen it SHALL read only how many messages arrived, never a sender or a subject. Its channel SHALL be named by the account's label, not its address.

#### Scenario: Mail arriving on a locked phone
- GIVEN an account notifying new mail and a phone locked with a PIN
- WHEN three messages arrive
- THEN the lock screen reads "3 new messages", and the senders and subjects show once the phone is unlocked

### Requirement: The app can hide from recents
The app SHALL offer a setting "Hide in recents", off by default, which keeps the app's content out of the recents screen and its thumbnails.

#### Scenario: Hidden
- GIVEN "Hide in recents" on and a message open
- WHEN the user opens the recents screen
- THEN the app's card shows no content

### Requirement: A phone pass deleting too much asks first
A phone pass that would delete more than a share of a book's or a calendar's rows SHALL stop before pushing the deletions and ask the user, through the platform's too-many-deletions prompt, whether to carry them out or restore the rows.

#### Scenario: Another app wipes the contacts
- GIVEN a book of 500 contacts mirrored on the phone
- WHEN another app deletes all 500 raw rows
- THEN the next pass deletes nothing upstream until the user confirms

### Requirement: A book the user does not own stays apart
Raw contacts of a book the user does not own SHALL NOT be aggregated with the phone's other contacts.

#### Scenario: A planted card
- GIVEN a shared book holding a card with the address of one of the user's contacts
- WHEN the book is mirrored
- THEN the user's contact gains nothing from it
