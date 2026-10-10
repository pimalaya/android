---
cairn: change
id: files-review-fixes
status: active
created: 2026-10-10
---

# Files and links: the review of b48309a and 376e572

## Why

An independent review of attachments-as-files, files-tab and item-links confirmed eight defects, from an out-of-memory crash on large attachments to store work on the main thread.

## What

1. **Files travel as files.** The bridge writes a part out of the message's blob to a cache file (`Native.messagePart(sourcePath, section, targetPath)`, answering its size); `FileActions.Source` hands a `File`; saving and importing hash and copy a buffer at a time (`PimdirHash.of(File)`, `PimdirBlobs.put(hash, File)`); an import is staged in the cache from the document's stream. No base64 on the heap, no whole file in an array.
2. **A text part keeps its bytes**: read from the raw message and only its `Content-Transfer-Encoding` undone, where the parser had converted it to UTF-8 from its charset; its size follows.
3. **An attachment is not unlinked**: the Linked card offers no unlink for a rule's `attachment`, and `recordAttachments` records the reference again for a stand-in it holds.
4. **Import and export survive a recreation** (`onSaveInstanceState`); an export reads its file before opening the document, and deletes a document it could not fill.
5. **No store work on the main thread** in the Linked card (links read on io and drawn once read, link, unlink, opening the other item, `openEvent`), the reader's link count, folder names.
6. **The picker's pending search** is dropped when it closes, and no work is queued on a closing activity.
7. **A folder name is taken once**, checked in `FileStore.createFolder` and `renameFolder` off the main thread, the reader's *New folder* included.
8. **Media types**: the provider reads the extension after the last dot, and a file stating no type, or only the generic one, is handed over as its extension's.

Also: the cache's copies are cleared on a fresh start and when an account goes; a long name is cut keeping its extension; each message of the body step records its attachments on its own; saving a file a folder holds already says so rather than "Saved".

Left: an inline part carrying a `Content-ID` is skipped already, the rule being `Content-Disposition: attachment`; the body step still parses a body twice (pimdir's derive, then the bridge's part walk), which only matters for time, not memory.
