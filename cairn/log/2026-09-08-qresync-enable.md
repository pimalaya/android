---
cairn: log
change: qresync-enable
landed: 2026-09-08
---

# QRESYNC is enabled before it is used, and a pass says what it is doing from the first round

Two faults the incremental enumeration turned up on its first real run.

**The second pass over a mailbox failed with `BAD invalid select modifier qresync`.** RFC 7162 section 3.1 requires an ENABLE before a SELECT may carry the QRESYNC parameter, and nothing sent one: the capability was read, the parameter was built, and the server refused a modifier it had never been told the client wanted. The first pass passed because it had no cursor and took the full round; the second had one, so it was the first to try.

The reference had this written down. neverest's client ENABLEs CONDSTORE and QRESYNC on connect and says why in as many words; that comment is the fix, and reading the capability without following it through was the bug.

The ENABLE goes on the connection, once, where it belongs now that a connection lasts a pass. Two ways of degrading came with it, because a server that advertises an extension and then refuses it should cost the incremental round and nothing else: a refused ENABLE forgets the capability, so every enumerate after it takes the full round directly rather than asking once per mailbox, and a QRESYNC select refused anyway falls back to a full round with a warning rather than failing the pass. The warning is deliberate: with the capability read and the ENABLE accepted, reaching it means a server said one thing and did another.

**The sync dialog named the account over a blank line.** Contacts sets its title and its step together, because a book's pass reports both before it touches the network. Mail and calendar set the title and then went to the network for the roster, the mailbox LIST in one case and the whole CalDAV discovery walk in the other, so the line under the title stayed empty for the slowest round trip of the pass and only filled in once the per-collection work started. Both now report the step beside the title, before that round.

Capabilities moved: mail, offline-store.
