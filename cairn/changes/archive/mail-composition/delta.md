---
cairn: delta
change: mail-composition
---

## ADDED Requirements

### Requirement: A mail account carries where it submits
A mail connection SHALL carry the endpoint mail is submitted through, beside the one it is read from, discovered in the same run and stored with it. Implicit TLS only: this client has no STARTTLS step, and driving a `starttls` endpoint as if it were implicit would hand a message over in the clear rather than fail. Every other domain carries none, and so does a mail account whose backend submits through the endpoint it reads from.

#### Scenario: An address that publishes both
- GIVEN an address whose discovery turns up IMAP and SMTP
- WHEN the mail domain is connected over IMAP
- THEN the account stores both endpoints

#### Scenario: An account stored before submission existed
- GIVEN an account connected by an earlier version
- WHEN it is read back
- THEN it carries no submit endpoint, and is not offered as a sender

### Requirement: A message is composed to RFC 5322
The app SHALL compose a message from the composer's fields: `Date`, `Message-ID`, `From`, `To`, `Cc`, `Subject`, and a `text/plain; charset=utf-8` body. Header lines SHALL fold at 78 columns, a header value carrying anything outside US-ASCII SHALL be encoded as RFC 2047 words, and the body SHALL be quoted-printable with no line past 76.

#### Scenario: A subject in two scripts
- GIVEN a subject mixing ASCII words and accented ones
- WHEN it is composed
- THEN the accented run is one or more encoded words and the ASCII words stay readable
- AND a run split across two encoded words carries its own spaces, RFC 2047 section 6.2 having a decoder drop the whitespace between them

#### Scenario: A blind copy
- GIVEN a draft naming a blind copy
- WHEN it is composed
- THEN that address is in the envelope and in no header

### Requirement: A message is submitted and a copy is kept
Submitting SHALL hand the composed bytes to the account's submit endpoint with the envelope the composition names, then `APPEND` a copy into the mailbox the server marks `\Sent` (RFC 6154), already `\Seen`. The copy SHALL be filed after the submission and never instead of it. An account with no sent mailbox SHALL send anyway and say no copy was kept.

#### Scenario: A message that is sent
- GIVEN an account with a submit endpoint and a `\Sent` mailbox
- WHEN a message is sent
- THEN it is handed over, a copy is filed, and the composer says where

#### Scenario: A submission the server refuses
- GIVEN a server rejecting the envelope
- WHEN a message is sent
- THEN nothing is filed anywhere and the failure is shown
