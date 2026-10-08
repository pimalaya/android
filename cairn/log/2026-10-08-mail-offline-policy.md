---
cairn: log
change: mail-offline-policy
landed: 2026-10-08
---

# Mail bodies downloaded by policy, up to a whole mailbox

Commit cd03491.

Capabilities moved: mail (added: an account chooses which bodies it keeps offline, a mailbox can be downloaded whole, bodies download behind the list, the drawer shows bodies left to download; modified: an opened message is stored and read back, an account bounds its mail).

**Policy.** `MailOffline` keeps per account bodies on open (default), in the background, or whole mailbox (the bound held at all mail), a metered switch off by default, and per mailbox "kept whole", set from the mail filter page's Download and Stop. `MailScope` reads a mailbox's bound, none when kept whole.

**Body step.** `MailBodies` plans the bodies lacking, newest first, shown mailboxes first, within each mailbox's bound, and downloads 24 an account a step after each pass and fill step, on the fill pool, through the engine's upgrade to `Full` (`MailEngine.download`), which links a body held under the same link id. It runs in the foreground only, with no other sync, on an unmetered network unless allowed; the drawer pill counts what is left.

**Bridge.** A body that is not UTF-8 crosses JNI as `bodyBase64` on fetch replies and `storeObject` writes, so it is stored as the bytes the server sent.

**What the implementation corrected.** The delta was revised in the same commit to what was built, so it was folded as written.

**Left open.** No storage cap by design; background sync stays off (release plan item 3), so bodies download only while the app is open.
