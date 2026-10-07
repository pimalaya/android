---
cairn: log
change: band-round-undated-mail
landed: 2026-10-07
---

# A band round keeps undated mail

Capabilities moved: offline-store (modified: a round lands page by page).

graph-unfiltered-delta left an undated message dropped by every widening: a band round lists by a date filter that never returns one, yet its last page found absent every unstamped binding of unknown date. io-pimdir ff28408 (pimdir 00535c1) records the round's kind and infers no delete of an undated member on a band round; the bridge moves to it, taken as a git dependency pinned by rev.

**Store.** `sources.round_band` comes with the canonical schema on a fresh store and is added on open to an older one by the generic reconcile (`INTEGER NOT NULL DEFAULT 0`, so an open round from before reads as one over its whole scope). `open_round` binds `:band` from the `openRound` op, `close_round` clears it, and `load_round`'s seventh column goes back to the engine as the round's `band`; `list_unstamped_bindings` is the crate's new text, crossing JNI as every statement does.

**Bridge.** `PimdirWriteOp::OpenRound { band }` and `PimdirRound::band` cross the JSON wire (`openRound.band`, `round.band`, false when absent).

**Tests.** `an_undated_message_survives_a_widening` runs again over the fake Graph and passes. A unit test carries the kind across the wire both ways; `PimdirStorageTest` keeps an undated member out of a band round's unstamped bindings and in a whole-scope round's; `PimdirDbTest` adds the column under a round left open by an older store. No IMAP or JMAP widening test: neither connector has a fake server to run the engine over, and the fix is the engine's, the same for every backend.
