---
cairn: tasks
change: a-graph-message-is-read-raw
---

# Tasks

- [x] Pin io-msgraph at 92c28d5 in rust/Cargo.toml's `[patch.crates-io]`, `cargo update -p io-msgraph` (offline, the commit in cargo's git cache).
- [x] Adapt to 01105a5..92c28d5: two `MsgraphMessagesListResponse` literals of the Graph mail tests take `..Default::default()`; nothing else needed an edit.
- [x] `MESSAGE_SELECT` names `internetMessageHeaders`.
- [x] `graph_summary`: a row listing header fields derived through `derive_meta`; a row listing none read as before, `internetMessageId` through io-pimdir's `message_id`; the app's own parser gone.
- [x] Tests over pimdir's fixtures (io-pimdir v0.12.0 spec/vectors): a row with header fields named as the vectors state, the size and the mark Graph's own, a row without any keyed by its `internetMessageId`, every read naming a member selecting the member; each seen failing on the old code first.
- [x] Spec folded (mail), log written.
- [ ] Left: a device check against a Microsoft 365 account (see the log).
