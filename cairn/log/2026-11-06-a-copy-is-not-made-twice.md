---
cairn: log
change: a-copy-is-not-made-twice
date: 2026-11-06
---

# A copy is not made twice

The second of the duplicate writes left after network-trust-hardening M6 (production-release section 1), folded without a proposal.

Capabilities moved: mail (modified: An IMAP copy or append is not made twice, renamed A copy or append is not made twice, a plain copy now checked too).

A copy whose answer was lost stays staged and runs again on the next pass. The IMAP relocation fallback and the append already searched the target by `Message-ID` first; a plain `ImapSession::copy` did not, nor did Graph's `copy_message`, a POST. Both now skip the copy when the target holds the message: IMAP through `copied` (`UID SEARCH HEADER`), Graph by reading the source's `internetMessageId` and listing the target folder filtered on it. Tests: `a_copy_run_again_after_a_lost_answer_files_one_copy` in rust/src/mail/imap/tests.rs and rust/src/mail/msgraph/tests.rs. The Graph filter on `internetMessageId` is unchecked on a live tenant.
