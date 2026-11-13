---
cairn: log
change: a-read-verifies-and-a-send-leaves-its-receipt
date: 2026-11-13
---

# A read verifies its body, and a send leaves its receipt

Two open lines of pimdir's audit of 2026-11-12 (pimdir cairn/changes/audit-2026-11-12/tasks.md): A3, and the receipt of A4. The locks of A4 are not landed: they need decisions that are the owner's, and wait as a proposal (cairn/changes/owner-lock-must/).

- **A stored message is read verified** (A3; STORAGE §5, §7). Opening a message and extracting an attachment read the blob file whole in the bridge and parsed it as it stood, where §5 allows the exclusive create and copy, the only move before API 30, because a read verifies: the part a killed copy left under the name parsed as a shorter message. `Native.parseMessageFile` and `Native.messagePart` now take the name the row gives the body, open the file following no link (`blobs::verified`, sharing its open with the check a write makes of a name held) and hash the bytes already in memory before parsing them or creating the target. Bytes failing the name answer `{"error", "failing": true}`, which `MailClient` throws as `BodyFailing`. `MailReader` then moves the file into quarantine/ by the path every other read takes (`PimdirBlobs.quarantine`, which checks again under the filing lock) and fetches the message as one the phone lacks (`MailReader.fetch`), for the reader (`read`) and for an attachment (`part`, which `MessageView.fileOf` calls). A missing or unreadable file is an error as before, never `failing`.
- **A submission handed over leaves its receipt** (A4; STORAGE §15.4, §15.5). `PimdirQueue.acknowledge` served an intent carried out and a withdrawal alike, and recorded nothing. It now reads the row's collection (`load_action`), removes the row (`cancel_action`), records its receipt (`record_receipt`, naming no item: the sent copy arrives with a later sync) and releases the pin, in one transaction, as io-pimdir's `acknowledge_action` does. `PimdirQueue.cancel` is the withdrawal and records none. The drain acknowledges (`Outbox.acknowledge`); a message deleted from the outbox (`Outbox.withdraw`), an account forgotten and a submission anchored again on the outbox cancel. A receipt is kept seven days, io-pimdir's bound and the least §15.4 allows, the older ones dropped (`prune_receipts`) in the transaction recording the next. Nothing in the app reads a receipt: it is what the standard owes a producer following the row.

Tests. Three new, each run first on the earlier behaviour, the fix put out by hand:

- `a_stored_message_failing_its_name_is_not_read` (rust/src/ffi/mail.rs): a message filed by the exclusive copy, then its first bytes alone under the name, then a link to the very bytes, then no file. With the read left as `fs::read` it failed at its first `failing` assertion, the part answered as a message with an attachment of 3 octets for 5.
- `aStoredBodyFailingItsNameIsSetAsideAndFetchedAgain` (StorePerformanceTest): the same part under a stored message's name, opened then read for its attachment. On a libpimalaya.so built with that read unverified it failed `the whole attachment expected:<5> but was:<3>`.
- `aSubmissionHandedOverLeavesItsReceiptAndOneWithdrawnNone` (MailStoreTest), read back through `load_receipt`: with `PimdirQueue` recording nothing it failed on the receipt of the message sent, read back as none (a `NullPointerException` at MailStoreTest.java:767); with the receipt recorded and `prune_receipts` left out it failed `dropped past seven days expected null`.

MailClientTest and FileStoreTest were restated to pass each stored file's name, the two exports taking it.

Checked: `cargo fmt --check`, `cargo clippy --all-targets -- -D warnings`, `cargo test` (656 passed, 1 ignored), `:app:assembleDebug`, `:app:testDebugUnitTest` (1,016 tests, 5 skipped) and `:client:testDebugUnitTest` (14), offline, on the host. `PIMALAYA_IO_PIMDIR_SPEC` named io-pimdir's spec/ as committed at 145bbcb (REVISION ae8b231, the draft-10 tags), exported to a scratch directory: its working tree was being edited toward another draft meanwhile. Nothing was installed nor run on a device, and no account was reached.

Left, and for the owner:

- The owner and staging locks (STORAGE §8), the last open point of A4: cairn/changes/owner-lock-must/ holds the mechanism (`flock(2)` from the Rust core, a registry per process, the `fstat` against `lstat` check) and four decisions, the first being what the app shows when another process holds the store, the reinstall screen being wrong for it. `flock(2)` reaching the app's system call filter on API 26 is to be checked on a device before the lock ships.
- On a device: a message and an attachment opened on a phone below Android 11, where the exclusive copy is the only move, and on a current one. The two exports changed their arguments, which the host tests run on the host library alone.
- A saved file is served from its blob unverified (`FileStore.saved`, behind FilesList and `MessageView.fileOf`), and `PimdirItems.blobFile` follows a link (`File.isFile`): the same class as A3, outside its two functions, not changed here.
- A body set aside with no network leaves its row saying it is held: the reader says the message is not on the phone, as for any body the phone lacks, and the next open fetches it.
- pimdir's own ledger lines (A3, A4, and the last line of its cairn/changes/owner-lock-must/tasks.md) are not ticked from here.

Capabilities moved: offline-store (A body is named by its content and checked before use; A submission handed over leaves its receipt, new).
