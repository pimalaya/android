---
cairn: log
change: body-loss-races
landed: 2026-10-12
---

# Bodies survive a concurrent sync, and a message gone converges

Capabilities moved: offline-store (new: a body filed ahead of its write keeps its file; modified: a refresh keeps the bodies it does not restate), mail (new: a push converges on a message gone).

Bodies of new Gmail mail went missing between syncs, and a sync once failed on a 404. `PimdirStorage.applyUpsert` no longer lets a mail upsert merged from a placement loaded before the body was filed (the reader, a fill chunk) take the body away: one carrying no object below the row's level keeps the row's body and level, and `writeBinding` its base. A downloaded body moved in by `MailEngine.stored` is pinned (`PimdirItems.pin`) until the upgrade's write returned; `unlinkCollected` reads and unlinks inside its own transaction and skips a pinned hash; `PimdirQueue.enqueue` files its body inside its transaction; a persisted `storeObject` missing its file is refused. Gmail and Graph item writes and JMAP's `notFound` converge on a 404, a copy keeping it, and `MailEngine`'s push accepts a removal, move or marker on a message gone and refuses a copy of one, so a push never fails a pass with a 404 that `MailEngine.refused` would read as a mailbox refused.
