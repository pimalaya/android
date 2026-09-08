---
cairn: tasks
change: mail-body-cache
---

- [x] Rust: `parse_message` moved out of the IMAP client, it parses no protocol
- [x] Rust: IMAP `fetch_source`, JMAP `fetch_source` over `blobId` and the blob download
- [x] JNI: `fetchMessageSource` and `parseMessage`, base64 across the boundary
- [x] Store: an item write with no body keeps the stored one; level follows the body
- [x] Store: `MailStore` reads and files a message's source
- [x] Reader: the store first, the server only for what it does not hold
- [x] Build: `:app:assembleDebug` and `:app:testDebugUnitTest`
- [x] Fold the delta and log
