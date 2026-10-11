---
cairn: log
change: sync-feedback-pinned
landed: 2026-10-11
---

# Steps count their bodies, the strip stays on screen, Load more says it is loading

Capabilities moved: offline-store (modified: a sync says what it is working on, now pinned above the list, a mail step weighing one section); mail (added: a mail step counts the bodies it brings; modified: the mail list shows its background download, the mail list ends on Load more). The device test is pending, so the change stays active.

**Why.** Device feedback on *Load more* over Gmail (2026-10-11): the bar only ran indeterminate during a step, the strip and the download glyph scrolled away with the list's header so a tap at the list's end showed nothing, and the button looked the same once tapped.

**Count.** `MailTally` holds a plan, the bodies settled and those in flight; its `Watch` reads the scratch directory a bridge call writes into every 250 ms on one daemon thread and counts the files that appeared, one last time as it closes. No bridge change: every backend already writes one file per message (Gmail per batch of 10, Graph per `$batch`, IMAP as each streamed body starts, JMAP per blob). `MailEngine.tally` is set by `RemotePass.stepMail`, told to the pass as the step's `STAGE_DOWNLOAD` advance; `MailEngine.stepRead` (Gmail, Graph, JMAP through `GmailStep` and `GraphStep`) plans `MailStep.COUNT` before `mailStep`, watches it, then closes the plan on what landed; `fetchBodies` watches `fetchMessageSources`; `ImapStep.download` plans the bodies its listing left before reading them. `MainActivity.planSteps` weighs each stepped account one section (`firstMail`, `loadMore`).

**Background.** `MainActivity.bodiesTally`: `planBodies` adds the run's plan to what the loop landed, the fill pool's engines count their files, `bodyHost().progress` settles each chunk by the drop in bodies left, and the loop's end resets it. `ListHeader.filled` draws it, the bar only growing while the fill runs.

**Strip.** sync_strip.xml (account line, detail line with the download glyph beside it, the thin bar) is included under the app bar in panel_mail, panel_contacts and panel_calendar, the list and its empty state below it; list_header.xml keeps the title, the count, the search field, the chips and the week. `ListHeader` takes the panel's strip and renders a pass (`sync`, `waiting`), or on the mail list the background download (`filling`, `filled`: *Loading older mail*, the pulsing glyph, a bar while bodies are counted), hiding it when nothing runs; the count line no longer hides under a pass, the search field and the chips still do.

**Footer.** `MailList.footerState(wifi, busy)`: `WIFI` off Wi-Fi, `LOADING` while `MainActivity.syncHeld` (a pass or a background run), else `LOAD`; the button is enabled only for `LOAD`, dimmed otherwise, and reads *Loading more* while loading. `MailList.redrawFooter` runs as a sync starts or ends and as a background run shows or ends. Strings `mail_more_loading` (en, fr).

**Tests.** Java (457 green): `MailTallyTest` (a step's plan closed on what it brought, the cap, a run settling chunks and adding a replan, a watch counting files written during a call); `MailStepTest` (a Gmail step planning 50 then closing on its two files, the footer states, *Loading more* in en and fr); `ImapStepTest` (a first step planning the 67 bodies its listing left, all of them counted).

**Left open.** The device test. The listing itself is not counted (a Gmail walk, an IMAP floor).
