---
cairn: delta
change: reply-and-row-marks
---

## ADDED Requirements

### Requirement: The reader replies and forwards
The reader SHALL offer Reply and Forward under the message. Reply SHALL answer everyone on it: the sender, and every other address in its `To` and `Cc`, the account's own left out; a message the account sent itself answers its recipients. The reply SHALL be sent from the message's account, carry the subject behind one `Re:`, quote the text under a line naming who wrote it and when, and carry `In-Reply-To` and `References` built from the `Message-ID` and `In-Reply-To` the store keeps for the message. Once queued, it SHALL mark the parent `\Answered` where the backend keeps that marker. Forward SHALL open on no recipient, the subject behind one `Fwd:`, and the message's headers over its text; the attachments stay behind.

#### Scenario: Replying to a message with a copy
- GIVEN a message from Ada to the account and Bob, copying Carol
- WHEN Reply is pressed
- THEN the composer goes to Ada and Bob, copies Carol, and the account's own address is on neither line

### Requirement: The contacts list selects like mail
A long press on a contact SHALL start a selection, its disc turning into a check, with no checkbox on the row. Each contact row SHALL name the addressbook and account its card lives in.

#### Scenario: Selecting a contact
- GIVEN the contacts list
- WHEN a contact is long pressed
- THEN its disc shows a check and the bar shows the count

## MODIFIED Requirements

### Requirement: A row's trailing marks sit on the line they describe
A message row SHALL end the sender's line with the time, the subject's line with its marks read from the edge inwards (the unread dot, a star shown only while the message is important, a paperclip while it carries an attachment) and the mailbox-and-account line with the replied mark, each aligned with the line it belongs to rather than stacked in a column beside all three. The star SHALL be a mark, filled in yellow whatever the theme's accent, not a button. A hairline SHALL part the rows of one card.

#### Scenario: A row with a long subject
- GIVEN a subject wider than the row
- WHEN it is drawn
- THEN it ellipsizes and its marks keep their place at the end of that same line

#### Scenario: A row with markers
- GIVEN an unread message that was replied to, marked important and carrying an attachment
- WHEN it is drawn
- THEN the subject's line ends with the paperclip, the yellow star and the dot, and the replied mark ends the mailbox and account line

### Requirement: A message is composed to RFC 5322
The app SHALL compose a message from the composer's fields: `Date`, `Message-ID`, `From`, `To`, `Cc`, `Bcc`, `Subject`, a reply's `In-Reply-To` and `References`, and a `text/plain; charset=utf-8` body. Header lines SHALL fold at 78 columns, `References` one msg-id per line, a header value carrying anything outside US-ASCII SHALL be encoded as RFC 2047 words, and the body SHALL be quoted-printable with no line past 76. Composing SHALL reach no server: it is a function of the fields alone.

#### Scenario: A subject in two scripts
- GIVEN a subject mixing ASCII words and accented ones
- WHEN it is composed
- THEN the accented run is one or more encoded words and the ASCII words stay readable
- AND a run split across two encoded words carries its own spaces, RFC 2047 section 6.2 having a decoder drop the whitespace between them

#### Scenario: A blind copy
- GIVEN a draft naming a blind copy
- WHEN it is composed
- THEN the message carries it in a `Bcc` header, which RFC 5322 section 3.6.3 provides for a message prepared for sending

### Requirement: An item's page groups its sections into cards
The contact editor and the entry page SHALL draw each section as one rounded card holding its label, its add action and its rows.

#### Scenario: A contact with two phones
- GIVEN a contact with two phone numbers
- WHEN its editor opens
- THEN the phones label and both numbers sit in one card

## REMOVED Requirements
