---
cairn: change
id: graph-contact-reads
status: landed
created: 2026-10-07
---

# Graph contacts read 20 to a batch

## Why

Found by reading the code, not measured on a device. A Graph contacts delta row carries an id and a `changeKey` and no body: a delta query cannot `$expand` the stash extended property the vCard projection reads. A complete round primes a body cache with one full listing, but an incremental round read every changed contact with its own `GET /me/contacts/{id}`. A bulk change made elsewhere (an import, a merge, hundreds of contacts edited in Outlook) was read one request a contact, one after another; and a contact deleted between the delta and its read failed the whole pass with a 404.

## What

1. **Batched read.** `read_cards` (over a `GraphCardReads` seam, the client or a fake) sends the reads 20 to a `$batch`, each the request line `read_graph_card` sends (`/me/contacts/{id}` with the stash `$expand`). A reply the batch could not serve (429, 503, any other failure, or a body that does not parse) is sent again on its own through the transport's throttled path; a 404 leaves the contact out. The calendar's `$batch` helpers (`batched`, `relative`, `graph_url`, `alone`) move into `client/graph.rs`, shared by both.
2. **Bridge.** `Native.readGraphCards` and `PimalayaClient.readGraphCards`, a typed `List<Card>`.
3. **Engine.** `OfflineEngine.fetch` on Graph takes the complete round's listing first and reads the rest in one batched call, where it called `readCard` per handle; a contact the read leaves out is left out of the reply, as a multiget leaves out a missing resource, and naming drops it from the page (`PimdirEngine.named`). CardDAV, Google and JMAP paths are unchanged.
4. **Timing.** The contacts enumerate counts its network and its listed members toward the page clock, so contact pages log the mail and calendar per-page line; each Graph fetch logs how many it was asked, how many came from the listing, how many it read in how many batches and the network time.

## Out of scope

- The complete round's full listing (`list_graph_cards`) keeps `$top=100`: Graph documents no larger page for contacts with an extended-property `$expand`, and nothing here measures one.
- The push path's own `$batch` envelope (`GraphBatch`), which carries bodies the io-msgraph batch type would need re-serialising; unchanged.
