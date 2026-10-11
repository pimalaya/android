---
cairn: change
id: sync-feedback-pinned
status: active
created: 2026-10-11
---

# Steps count their bodies, the strip stays on screen, Load more says it is loading

## Why

Device feedback on *Load more* over Gmail (2026-10-11): the step model is right and fast, but it gives no feedback.

- The strip's bar only ran indeterminate. A step (`RemotePass.stepMail`) opened its section at 0 of 1 and closed it at 1 of 1: nothing counted in between, since the bodies are read inside one bridge call (`mailStep` on Gmail, Graph and JMAP) or one upgrade per mailbox (IMAP), and the step's engine had no progress observer.
- The strip and the background download glyph live in the list's header, which scrolls away: at the end of the list, where *Load more* is, a tap showed nothing at all. It already hid the strip before, it is a real problem now that the button sits at the bottom.
- *Load more* looked the same once tapped, and a second tap during a step did nothing visible either.

## What

### Bodies counted as their files land

Every backend writes one file per message into the scratch directory a bridge call is given (Gmail by batch of 10, Graph by `$batch` of 20, IMAP as each streamed body starts, JMAP one blob at a time). A `MailTally` watches that directory while the call runs, every quarter second, and counts the files that appeared: real increments, no bridge change, the same for every backend and for both entry points (`mailStep`, `fetchMessageSources`).

- **A step** plans its count before reading: about 50 (`MailStep.COUNT`) for a step the bridge walks (Gmail, Graph, JMAP), set to what it brought once it answers; for IMAP, the bodies its listing left to read, known once its mailboxes are listed. The bar runs indeterminate while it lists (the first account's only, a later account holding the bar), then fills with the bodies landed out of that plan.
- **The pass** weighs each account's step alike, so a several-account *Load more* fills the bar once over them, each account's section filled by its own count.
- **The background body downloads** count the same way: the run's plan (the bodies left over every account), settled per chunk of 24 by what the chunk took, the files of the chunk under way counted as they land, a replan adding to the plan rather than starting over. The header fill (IMAP headers widening) has no count: the glyph alone.

### A strip pinned above the list

The strip (account, line, bar) moves out of the list's header into a strip pinned under the app bar of the mail, contacts and calendar tabs, so it shows whatever the scroll. Same lines, same bar, same margins; it hides when nothing runs. The download glyph moves into it too, on the mail tab: while the background downloads run and no pass does, the strip reads *Loading older mail* with the glyph pulsing beside it, over a bar while bodies are counted. The meta line (the count) stays in the header and no longer hides under a pass. The search field and the chips keep stepping aside while a pass shows, so the list keeps about the same height.

### Load more says it is loading

While a step or any pass holds the sync (the first sync, a pull, the drawer's sync, a background run), the footer's button is disabled and reads *Loading more*; it comes back to *Load more* once every account's step ended, or goes when nothing is left. Off Wi-Fi it stays *Load more*, disabled, its line saying it waits for Wi-Fi.

## Out of scope

- Counting the listing itself (Gmail's set-difference walk, an IMAP floor): unknown in size before it lands.
- A size on the footer.
