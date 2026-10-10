---
cairn: change
id: sync-strip-progress
status: active
created: 2026-10-10
---

# The sync strip shows one progress, says which domain, and nothing pops up after

## Why

Device feedback on the strip (2026-10-10), in two rounds.

First: the bar only ever filled for mail. A contacts or calendar pass ran indeterminate end to end, so a first sync of a large book or agenda gave no idea how far it was. And the strip sat above the search field and the chips, growing the header by its height with controls that have nothing to do with the wait.

Then, on a Google account's onboarding: the bar restarted from empty at every counted step (the download, then the phone projection, then the collections' count), which reads as several syncs in a row; the step lines (*Reconciling with the phone contacts*, *Writing 230 contacts to the phone*) are engine talk; a first sync opened on *Checking the server for changes*, which is misleading and can last over a minute; and every pass ended on a toast counting what came in and went out, useful while developing, noise for a user.

## What

### One bar

The bar is one progress over the whole pass, the drawer's included (every account, every domain), and never moves back while it runs:

- **Sections.** Before it starts, the pass is sized in sections, one per domain and account, each weighing the collections stored for it (one at least, an account never synced included). A section fills its weight whatever its listing turns up, so sections fill at different speeds, a ten-thousand-message mailbox weighing as much as a three-event calendar. Device feedback: one bar matters more than an even pace.

- **Collections.** Within a section, the pass tells how many it will run before the first one runs, so the bar can fill from the first count. Each collection that lands, failed or not, fills its share.
- **Within a collection.** The two steps that move items batch by batch and can count for free, the download naming the members a listing did not carry (`PimdirEngine.named`) and the projection onto the phone (`PhoneRemote.push`, `CalendarRemote.push`), fill part of the collection's share: the download the first three fifths, the phone the rest, for contacts and calendars; the download all of it for mail. Whether a phone projection follows is not known when the download starts, so a book not on the phone jumps the last two fifths when it lands, a jump forward rather than a bar starting over.
- **Monotonic.** The strip keeps the highest value it showed for the pass: a step starting at nothing holds the bar.
- Calendars run three at a time: their item counts reach the strip only while one calendar runs alone. The engine tells a step's progress at most once per percent. The bar runs indeterminate only before anything is counted.

The first projection of a book switched on for the phone fills the same way.

### One line

The line names the domain (*Syncing mail*, *Syncing contacts*, *Syncing calendars*), never the step. A first sync reads the same: a warning line for it was tried and dropped, the bar saying enough.

### The background fill

After a first sync, older mail keeps downloading behind the strip, the count growing on its own. A download glyph beside the mail list's count says so, pulsing gently in opacity (still when Android removes animations), its long press naming it; it shows once a fill step downloaded something and goes when the fill ends or pauses. Not a spinner or a bar: the fill can run for hours and pauses on metered networks, and either would read as stuck.

### Controls

While the strip shows (a pass, a background run, a first projection), the list's search field and chip row are hidden, and they come back when it goes. A search field holding a query stays: hidden, it would filter the list with nothing saying so and no way to clear it.

### No report

A pass that went through says nothing: the counts toast, the outbox's sent toast, the pending-conflicts toasts and the background-busy toast are gone. A conflict marks its own row; a background run shows its own strip. A failure keeps its dialog.

## Out of scope

- Counting the server exchange itself: the listing is one round trip, or a walk whose size is unknown before it lands.
