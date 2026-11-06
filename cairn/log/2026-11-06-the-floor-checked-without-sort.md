---
cairn: log
change: the-floor-checked-without-sort
date: 2026-11-06
---

# The floor without SORT is checked by a search

A fix found reading the code for the production release (production-release section 1), folded without a proposal.

Capabilities moved: mail (modified: a mailbox's first pass lists its newest messages alone, the sampled floor now checked on a server without `SORT`).

Without `SORT` the floor is the 50th newest `Date` of a sample of the newest UIDs, which stops once a part holds nothing dated or received at or above it. Mail appended in rising date order above recent mail, its `INTERNALDATE` its `Date`, settled the sample after its first parts with the floor at the imports' own dates, and the round then listed every envelope since. `ImapSession::checked_floor` now runs the round's search from the sampled floor: the messages below the sample it names are read into the floor when they are few, else `floor_day` halves the days with `SENTSINCE` searches, which read no headers, down to the day holding the count-th newest, and reads that day's headers. Bound: the sample, one search the round reuses, about log2 of the days searched, and the headers of under one day plus the count. Tests in rust/src/mail/imap/tests.rs: `without_sort_imports_appended_in_date_order_floor_this_autumn`, and `without_sort_a_sample_is_bounded` restated, which asserted the floor left in the imports.
