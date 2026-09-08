---
cairn: log
change: offline-first-writes
landed: 2026-09-08
---

# Every action is a local write; only a sync needs the network

Contacts were offline first and the other two domains were not. Saving a contact staged into the store and returned; marking a message read, flagging one, deleting one, editing a calendar entry, creating one, deleting one and sending a message all called the server first and wrote the store only once it answered. With the radio off, all six failed outright, which is the premise of an offline-first client broken in six places.

**The store was not what stood in the way.** It is io-pimdir's, and it already carried bindings with a base revision, a base flag set and a conflict. What was missing is that mail and calendar never ran the engine: a refresh called `replaceMessages` or `replaceEvents` and overwrote the collection wholesale, which is exactly the write that would erase a staged edit, so staging one was never safe.

**Mail and calendar now run io-pimdir's sync.** `MailEngine` and `CalendarEngine` sit beside `OfflineEngine` on a new `PimdirEngine`, which is the half of a driver that has nothing to do with a domain: the storage yields, the staged mutations, the wire shapes. What differs is the three remote yields. Mail keeps its one authentication per account by priming a cache from the account walk and answering every mailbox's enumerate and meta fetch out of it, the shape the contacts driver already uses for JMAP and Google; a calendar's enumerate carries the objects themselves, so its fetch is a cache lookup. `PimdirItems.replace`, which existed for the read-only mirrors, is gone.

**The bridge learned the rest of the mutations.** `MutationJson` had `edit` alone, because contacts was all that ever staged; it now has `setFlags`, `remove` and `add` beside it. No engine behaviour is new, only the translation.

**A staged removal was never loaded back, so no domain's delete could reach a server.** The engine derives a remove push from a placement whose status is `Tombstone`, and the store's load excluded every deleted row, so the delete stayed local and the next enumerate read the member the server still held as one to add back. The load now hands tombstones over and an upsert honours one. That is a fix contacts needed too.

**A mail account records where its trash is.** The delete has to know, with no network, whether the account has a mailbox marked `\Trash` (RFC 6154), because that decides between a move, which takes the row out of the list, and a `\Deleted` marker, which leaves it there saying so. The walk's mailbox roster now carries the role and the account remembers it. A JMAP account recording none refuses the delete outright rather than staging a change RFC 8621 gives no keyword to carry.

**Sending is an outbox.** `compose` and `submit` were one native call and are now two: composing is a pure function of the draft, so a message can be written and sent with the radio off, and the sync hands it over. The queued bytes carry a `Bcc` header, which RFC 5322 section 3.6.3 provides for a message prepared for sending, and the submission strips it into the envelope's blind recipients, so a blind copy is neither lost nor disclosed. The outbox is a collection of the app's own that no roster replace drops, its rows show as pending in the merged list, and deleting one discards it.

**What is not covered.** Mail and calendar still have no background pass: an outbox message goes out on the next mail sync, which is a refresh or a connect, not a schedule. That was true of every mail read before this too.

Capabilities moved: mail, calendar, offline-store.
