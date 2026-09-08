---
cairn: tasks
change: mail-composition
---

- [x] Carry a submit endpoint on the mail connection, stored and restored
- [x] Keep the discovered SMTP endpoint instead of discarding it
- [x] Teach the transport the SMTP schemes
- [x] Compose RFC 5322 bytes: folding, encoded words, quoted-printable
- [x] Submit over io-smtp, with the envelope the composition names
- [x] `APPEND` the sender's copy into the `\Sent` mailbox
- [x] JNI: `sendMessage`
- [x] The composer screen, with a send and a discard
- [x] Tests: the headers, the blind copy, the encodings, the folding
- [x] Fold the delta and log
