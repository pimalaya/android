---
cairn: log
change: a-delivered-create-waits-for-its-arrival
date: 2026-11-12
---

# A delete for good or a restore on a trash row not yet listed reaches the server

The app side of a change made first in pimdir (sync-draft-09, 317768b, its log a-delivered-create-waits-for-its-arrival) and io-pimdir (3a51ae7 on branch delivered-create, pushed 2026-11-12, now the app's pin). Closes the gap gmail-trash-stays left (2026-11-11): on Gmail or JMAP, a delete for good or a restore of a trash row whose move had gone through but which no listing had named yet stayed on the phone, io-pimdir withdrawing a pending create, and the message came back.

- io-pimdir: a create its source accepted before a listing names it (a relocation into it, an add accepted with no handle) is delivered: its binding keeps the provisional handle with a base naming nothing, it derives no change, and a Remove or Move staged on it is held until its arrival lands, then pushed against the listed handle. A push outcome states whether a relocation happened; only a relocation delivers. sync-draft-09: an older store is refused on open and goes to the reinstall screen (the owner chose the bump over editing the draft in place, accepting that existing stores are rebuilt).
- App: the delivery is recorded under the store lock before the relocation leaves (PimdirStorage.deliver), so a delete for good staged while the move is in flight is held, and a failed relocation is retried rather than turned into a delete; the app's statements count delivered creates as pending creates (downloads skip them, JMAP appends still link) and skip provisional handles where a member is meant; `nameByMessageId` copies an arrival's markers only onto a sent copy, so an opened message moved to Trash keeps its read marker.
- A "Waiting for the server" notice, at the end of a pass the screen watched, names per mailbox the deletes, restores and moves held on a delivered create (HeldActions, held_actions.sql), once, what was told kept by change stamp in the no-backup directory keyed by the store's id and incarnation. A create waiting with nothing staged on it tells nothing.
- JmapRun refreshes its replay after a move, as GmailRun does, so a JMAP move lands in the same pass.
- Tests failing first (MailMoveTest): `aGmailDeleteForGoodBeforeTheTrashLandsItIsPushed`, `aGmailRestoreBeforeTheTrashLandsItIsPushed`, `aGmailDeleteOfAnOpenedMessageLandsRead` (all three un-ignored), `aMoveTargetListedBeforeItsPushKeepsItsMarkers`, `aGmailDeleteForGoodDuringTheRelocationIsPushed`, `aGmailRestoreWhoseTargetLandedFirstIsNeverDestroyed`, `anOrdinaryGmailDeleteTellsNothingWaiting`, `aDeleteForGoodHeldOnTheTrashIsToldOnceAcrossARestart`; jmap_step `a_move_the_run_pushed_reads_the_changes_again`; PimdirStorageTest's draft literal follows sync-draft-09.
- Reviewed by an independent agent over five passes, which also found and confirmed the account-wide destroy fixed separately (account-wide-removal-never-destroys).
- Left: a delivered create a listing can never name (Trash out of the account's date scope, gmail.rs dropping such delta members) waits, told by the notice, while Gmail's 30-day purge may delete it; the app delivers before the move leaves, so a move that fails while the message moves elsewhere on the server leaves the trash create waiting (low); io-pimdir's `collect_before` (STORAGE §11.3) may collect a live delivered create holding a body (low, open in pimdir).

Capabilities moved: mail (A message is deleted into the account's trash, modified), offline-store (the waiting notice, the store's sync draft).
