---
cairn: tasks
change: sync-fixes
---

- [x] Graph: select and order by `sentDateTime`, date from it, none when absent; test
- [x] One throttling layer under every HTTP runner: `Retry-After`, bounded jittered back-off on 429, 503 and Google rate-limit 403s; tests for the head, framing, reasons, `Retry-After` and back-off
- [x] Gmail paced near 40 requests a second across workers; test
- [x] `cargo test`, `cargo clippy`, `cargo fmt`
