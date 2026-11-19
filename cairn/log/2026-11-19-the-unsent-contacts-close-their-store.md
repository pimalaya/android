---
cairn: log
change: the-unsent-contacts-close-their-store
date: 2026-11-19
---

# The unsent contacts close their store

`UnsentContacts.rows` (android/app/src/main/java/org/pimalaya/contact/UnsentContacts.java), added by commit f53627d (google-contact-refusals item 3), opened a new `CardStore` over cards.db on every call and never closed it: each sync end (`SyncCoordinator.offerUnsent`) and each contacts list reload (`ContactsList`) left one more connection open, which the device logcat of 2026-11-19 showed as `SQLiteConnectionPool` "was leaked!" warnings growing with each sync.

`rows` now takes the caller's `CardStore`: the app's one store (`App.cards`), which both callers already hold as `parts.cards()`. No other `new CardStore(` in the app's main code is left unclosed (the only other one is `App.cards` itself).

Regression test: `UnsentContactsTest.readingTheRowsOpensNoCardStoreOfItsOwn` counts the open connections to cards.db through the test SQLite shadow (`ShadowHostSqliteConnection.openNow`, new); before the fix it failed with `expected:<1> but was:<4>` after three calls.

No capability spec moved: the rows read are the same, only which connection reads them.
