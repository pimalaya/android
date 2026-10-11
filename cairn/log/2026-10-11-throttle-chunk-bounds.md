---
cairn: log
change: throttle-chunk-bounds
landed: 2026-10-11
---

# Chunk sizes are bounded

Capabilities moved: mail (modified: a connection is reused only after its answer is read to its end, a chunk size out of range counting as framing that cannot be told).

**Why.** The bridge's HTTP framing (rust/src/client/throttle.rs) parsed a chunk size as any hex `usize`: `FFFFFFFFFFFFFFFF` overflowed `size + 2` (a panic in debug) and in `dechunk` wrapped `at + size + 2` past the bounds check, slicing out of range (a panic in release), inside a native call.

**Fix.** `chunk_size` takes bare hex digits before any extension (no sign, no inner spaces) up to `CHUNK_CAP`, 64 MiB; anything else is broken framing (`ChunkFeed::Broken`, `Body::Unframed`), so the answer passes through to the coroutine untouched and its connection is not settled for reuse. `dechunk` compares against the bytes left (`bytes.len() - at < size + 2`), which cannot overflow.

**Tests.** throttle.rs: sizes `FFFFFFFFFFFFFFFF`, 17 digits, one past the cap, `-1`, `+5`, empty, `5 5` and non-UTF-8 are broken in both parsers; a chunk past the bytes read is partial; an answer, 200 or 429, with an overflowing chunk passes through unsettled and unretried.
