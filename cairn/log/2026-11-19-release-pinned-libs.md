---
cairn: log
change: release-pinned-libs
date: 2026-11-19
---

# Released libraries, no git revisions

Closed without new code. The family release rounds (2026-11-13 to 2026-11-18) released every library the bridge pinned by git revision: rust/Cargo.toml now names only crates.io versions (ical-rs 0.7.1, io-http 0.8, io-imap 0.11.2, io-pimdir 0.17.0, io-webdav 0.11, io-jmap 0.10.1, io-msgraph 0.9.3) and Cargo.lock holds no git source.

The bound on what a server sends is io-http 0.8's own: every buffered answer is refused past 256 MiB (`HTTP_DEFAULT_MAX_BODY_LEN`). The tighter 64 MiB per-call bound the change planned would have needed an option in five more libraries; the owner judged it not needed while no out-of-memory crash is reported.

Capabilities moved: spec/network-trust.md gains "A server's answer is read up to a bound".
