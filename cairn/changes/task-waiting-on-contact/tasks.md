---
cairn: tasks
change: task-waiting-on-contact
---

# Tasks

Route decided 2026-11-19 by the owner: a join on `item_address`, no pimdir change.

- [-] pimdir rule and io-pimdir release: dropped, the join needs none
- [ ] App statements (rust/pimdir/queries/app/): the open waiting tasks whose `contact` address a card holds; the cards holding a task's `contact` address; tests over a seeded store, the SQLite floor check
- [x] Canvas frames, light and dark: the Waiting on picker, the task page's Linked card (*Captured from* row), the contact page's tasks waiting on the person (2026-11-19: TaskWaiting, TaskWaitingPick, ContactWaiting)
- [ ] Task page: Waiting on picks a contact (search, address choice), opens it when one holds the address
- [ ] Task page: Linked card, the source mail as a *Captured from* row
- [ ] Contact page: tasks waiting on the person in its Linked card
- [ ] Tests, device check, spec folded, log, CHANGELOG
