---
cairn: log
change: envelope-decoding
landed: 2026-09-08
---

# An envelope's subject and sender are decoded before the store holds them

A message list showed `=?UTF-8?B?...?=` where the reader one tap below it showed the words.

The two read the same message by different routes. Opening one parses the RFC 5322 bytes, and the parser decodes the headers on the way through. The list is drawn from the spine the walk stores, and an IMAP `ENVELOPE` is not parsed headers: it is the header text, handed over as it was written on the wire. Nothing decoded it, so a subject or a sender's name written in anything but ASCII reached the store as its encoded words and was rendered as such. The type had claimed the field was decoded since it was written, which is how it went unnoticed.

`mail::decode_header` is the counterpart of the `header` encoder that was already there, and the two now round-trip in a test. It decodes by parsing a synthetic header rather than by hand, so the charsets, the two spellings of an encoded word and section 6.2's rule about the whitespace between two of them are the reader's, not a second implementation of the same RFC that agrees with it until it does not. A value carrying no `=?` skips the parse and comes back byte for byte, which is most of them and is also what keeps a subject that only looks encoded from being emptied. A line break in a value folds into a space rather than ending the synthetic header: an `ENVELOPE` value is unfolded already, so it never fires, and it costs nothing to be wrong about that.

JMAP carries none of this: RFC 8621 hands the decoded subject and name over, so only the IMAP walk needed it.

Capabilities moved: mail.
