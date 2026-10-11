---
cairn: tasks
change: oauth-pending-grant-encrypted
---

# Tasks

Java paths are relative to android/app/src/main/java/org/pimalaya/, tests to android/app/src/test/java/org/pimalaya/.

- [x] SecureStore.java: `seal` and `open` package-private and static over the Keystore key, `save` and `loadAll` through them
- [x] PendingGrantStore.java: sealed save, load (unreadable dropped), clear, legacy plaintext wiped on open
- [x] OauthFlow.java: persist, restore and clear through the store; `cancel()`
- [x] MainActivity.java: `cancelAuth` and `authBack` cancel the grant
- [x] FakeAndroidKeyStore.java: software "AndroidKeyStore" JCA provider for Robolectric
- [x] PendingGrantTest.java: sealed round trip, clear, legacy wipe, unreadable dropped, started grant sealed, redirect clears before the exchange, cancel clears
- [x] CHANGELOG.md: Security line
- [x] `:app:assembleDebug :app:testDebugUnitTest` green, `cairn/verify.sh` conformant
