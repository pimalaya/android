---
cairn: log
change: mail-composition
landed: 2026-09-03
---

# Let mail be written and sent

Mail writes now. The composer screen that had been a placeholder since the shell was built is a composer, and the SMTP endpoint the connection flow had been discovering and discarding is kept.

A mail connection carries two endpoints, because mail is the one domain whose server does not answer both ways: the base URL is where messages are read and the copy is filed, the submit URL is where one is handed over. It is stored beside the other and left out of every connection that has none, so an account written before this reads back with no submitter and is not offered as a sender rather than failing at the send.

The message itself is composed in the bridge: header folding at 78 columns, RFC 2047 encoded words for a subject or a display name outside US-ASCII, and a quoted-printable body. Two details are worth naming. A run of adjacent non-ASCII words encodes as one run rather than one word each, because RFC 2047 section 6.2 has a decoder drop the whitespace between two encoded words, so the spaces have to ride inside the encoding; and the envelope comes from the composition rather than from the headers, because a blind copy is a recipient the headers deliberately do not name.

Submission runs io-smtp's coroutines over the same Java transport every other protocol uses, and the copy the sender keeps is appended to the mailbox the server marks `\Sent`, already `\Seen`: it is not new mail. The copy is filed after the submission and never instead of it.

JMAP submission is not wired. `EmailSubmission/set` creates the message as an `Email` first and names it from the submission, which is a different shape from handing bytes over, so a JMAP account is not offered as a sender.

Spec updated: `mail` (ADDED: a mail account carries where it submits, a message is composed to RFC 5322, a message is submitted and a copy is kept).
