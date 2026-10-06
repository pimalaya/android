---
cairn: delta
change: mail-test-round-two
---

## ADDED Requirements

### Requirement: A mail sync pushes no body
A mail sync SHALL NOT push content: a message is immutable once sent, and a body the reader stored SHALL NOT be read as an edit. A marker staged on an opened message SHALL be pushed as a marker.

#### Scenario: Flagging an opened message
- GIVEN a message whose body was stored by opening it
- WHEN it is flagged and mail is synced
- THEN the push is a `setFlags`, and the server is flagged

### Requirement: A recipient is a chip
Each address field of the composer SHALL hold one removable chip per recipient. A separator (comma, semicolon, space), the keyboard's next action or leaving the field SHALL turn what was typed into chips, and backspace on an empty input SHALL take the last chip back into it.

#### Scenario: A pasted list
- GIVEN `a@x.org, b@y.org` pasted into the To field
- THEN two chips are shown, and the message is addressed to both

### Requirement: A list bar keeps search out and the rest in an overflow
Every list screen SHALL show the same add glyph, and SHALL carry its secondary actions behind one ⋮ overflow, accented while the filter hides anything: the filter on every list, then on contacts the birthdays, the duplicate remover, the address books, import and export. Search SHALL stay a bar button, and an open search SHALL take the bar up to its clear cross, beside the overflow.

#### Scenario: The contacts bar
- WHEN the contacts list is shown
- THEN its bar carries search and the overflow, and nothing else after the navigation

## MODIFIED Requirements

### Requirement: The setup names sending in the words of the setup it is in
(Paragraph added.) The advanced setup SHALL list one row per discovered submission endpoint and sign-in method, as it lists reading rows, and SHALL drop a row naming no method when the same endpoint is offered with one.

#### Scenario: One server found twice
- GIVEN an SMTP endpoint discovered once with a password method and once with none
- WHEN the advanced setup switches mail on
- THEN it is listed once, with its method

### Requirement: A message is sent through an outbox
(Paragraph added.) The waiting messages SHALL be filed under a mailbox named *Outbox*, which the filter's collection axis SHALL offer first, so they can be shown alone or hidden like any mailbox.
