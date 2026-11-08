---
cairn: log
change: a-search-reads-days-in-the-phone-zone
date: 2026-11-08
---

# A search reads its days in the phone's zone

Moves the bridge from io-pimdir b25523d to 5c18a02, the commit pimdir CI and the CLIs (neverest, himalaya, cardamum, calendula) pin, so every consumer of storage-draft-09 runs one io-pimdir. 5c18a02 follows pimdir's a-query-states-its-zone (SEARCH §8.1: a span starts at its first day's midnight in the client's zone, which a client should state as its index's `floating_zone`), and its vendored queries.json moved to version 3 for a case's `zone` and `now`.

- The bridge's own compile (rust/src/search/compile.rs), which the shape needs on FTS4, follows io-pimdir's: `bounds` and `span` take a jiff `TimeZone`, a day, a month, a year, `today` and `yesterday` starting at `Zoned::start_of_day` in it, a relative amount unchanged. `find::run` reads the zone from `index_meta.floating_zone` (the phone's zone the refresh writes) for a query holding a date term alone, UTC with no index, an unknown zone refusing the query. No Java change: `pimdirIndexHits` and `pimdirIndexSearch` keep their signatures.
- the_search_vectors_answer_on_the_floor reads queries.json at 3: the cases stating no zone and the occurrence windows on the UTC index as before, then each zoned case on the index refreshed in its zone, its `now` in place of the file's. 177 cases run.
- Proven failing: with the zone forced to UTC, the same seven Europe/Paris cases fail as in io-pimdir's proof (2026-12-31, 2027-01-01, 2026-12, 2027, both `today`, `yesterday`).
- `cargo test` (645 passed), `:app:assembleDebug` and `:app:testDebugUnitTest` green.

Capabilities moved: offline-store (requirement "A search's days are the index's zone's", added).
