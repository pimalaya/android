---
cairn: change
id: mail-load-more-graph
status: active
created: 2026-10-11
---

# Load more on Microsoft Graph

## Why

`mail-load-more` landed the generic step and Gmail's; a Graph account still steps from the store (`WidenStep`): it widens the folder holding the most recent floor 50 messages at a time until 50 stored rows lie below the frontier, then the body step reads each body with its own `$value` GET. docs/mail-window-files-references-plan.md section B.13 settles Graph's own step: Graph pages by a date keyset already, and `$batch` takes 20 requests a call.

## What

- **The step** (`GraphStep`, `MailStep.of` for a Graph account) lists the account: `/me/messages` with `$select=id,sentDateTime,receivedDateTime,parentFolderId` and the MAPI size expanded, `$filter=sentDateTime le F` (the account's frontier, and `ge` its bound), `$orderby=sentDateTime desc`, `$top` 50 and some slack. The ids held at `F` are dropped (the step before brought them); a page short of 50 is followed by a keyset re-query below the oldest `Date` it reached, never by `nextLink`; the messages sharing the 50th one's `Date` all come along. Messages in the trash, the junk, a mailbox kept whole or a folder outside the roster are skipped. On `InefficientFilter` or a 504, each folder is listed the same way and the lists merged by `Date`. A view filtered to one folder lists that folder.
- **Headers** come through the existing band rounds: every stepped folder is listed down to the oldest `Date` the step brought, those holding its messages first, so their coverage floors keep pace with the frontier. `Band.skip` goes: a second holding more than a page goes on below it, the delta's first pass naming the rest.
- **Bodies** come through `$batch` of up to 20 `$value` GETs and about 4 MB of declared size, one batch at a time on the account's one session; a message over 1 MB or of unknown size is read alone with a plain GET, as is one whose batched answer is not a base64 body. A throttled item (429 inside the batch) waits its own `Retry-After` once, then goes again; it or any other failed item is left out of the answer. The step reads its messages' bodies itself (sizes from its listing) and the engine files them as the Gmail step's are; `fetchMessageSources` on Graph reads the sizes first (20 to a `$batch`), then batches the same way.
- **Delta** unchanged.

## Out of scope

- The generic retry of failed bodies, token renewal mid-run, connection eviction, junk and trash on demand, the per-mailbox frontier (the main tree).
- io-pimdir's `size_agrees` comparing the MAPI size a listing states with the MIME length of a body held under the same link id (see tasks).
