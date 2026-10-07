---
cairn: log
change: graph-contact-reads
landed: 2026-10-07
---

# Graph contacts read 20 to a batch

Capabilities moved: carddav-sync (added: A Graph book reads its changed contacts 20 to a batch).

Found by reading the code, mirroring calendar-sync-speed. A Graph contacts delta row carries no body (a delta cannot `$expand` the stash property), and only a complete round primed a body cache with its full listing, so an incremental round read every changed contact with its own `GET /me/contacts/{id}`: a bulk change elsewhere (an import, a merge, Outlook editing hundreds) cost one request a contact, and a contact gone between the delta and its read failed the pass with a 404.

**Bridge.** `read_cards` (over the `GraphCardReads` seam, the client or a fake) sends the reads 20 to a `$batch`, each the request line `read_graph_card` sends; a reply the batch could not serve is sent again alone through the throttled transport, and a 404 leaves the contact out. The calendar's `$batch` helpers (`batched`, now over a send closure, `relative`, `graph_url`, `alone`) moved into `client/graph.rs`, shared. `Native.readGraphCards`, `PimalayaClient.readGraphCards` (a `List<Card>`).

**Engine.** `OfflineEngine.fetchGraph` takes the complete round's listing first and reads the rest in one batched call; a contact the read leaves out is left out of the fetch reply, as a multiget leaves one out, and `PimdirEngine.named` drops it from the page. The contacts enumerate now counts its network and its members toward the page clock, so contact pages log the per-page line, and each Graph fetch logs `graph fetch <book>: N asked, H from the listing, M read in B batches, remote T ms, G gone`. CardDAV, Google and JMAP fetches are unchanged; the full listing keeps `$top=100`.

**Tests.** `graph_tests`: the batched read builds the cards the one-by-one read built (stash included), a throttled batch read again alone builds them too, only the requests refused inside a batch (429, 503, 500) read again, 120 changed contacts in one read take 6 requests against 120 (a pass naming 64 at a time sends 7), a contact gone in the batch or before its lone read left out, any other lone failure failing the read, the batch URLs equal to `MsgraphContactGet`'s request line. `OfflineEngineGraphFetchTest` (the real engine, bridge and store): 120 handles in one batched call and no `readCard`, a gone contact left out with no error, a complete round reading only the contact its listing lacked.
