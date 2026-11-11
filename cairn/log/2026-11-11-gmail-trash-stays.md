---
cairn: log
change: gmail-trash-stays
date: 2026-11-11
---

# A Gmail delete stays in the trash through its sync

Found while recording the Google verification video (docs/providers.md, 02-mail-app.mp4 0:45 to 1:00): a message deleted from the inbox showed in Trash, left it on the next sync, and came back on the sync after with no body.

- Root cause, in the app: once the inbox's sync relocated the message, `MailEngine.pushRemove` withdrew its pending create in Trash, counting on Trash's next listing to bring it back. On Gmail that listing could not name the move in the same pass: io-pimdir lists a mailbox before it pushes, and the bridge replays the account's history once per pass (`GmailRun`, cached by start id), so Trash reused the copy read before the move. The next pass brought the message as a new item, and the body step skips the trash. Already noted in mail-named-by-message-id's tasks ("a move's target withdrawn on delivery rather than landed").
- On an account listed account-wide (Gmail, JMAP), `pushRemove` keeps the target's pending create once the move goes through: one id names the message in every mailbox, so a create pushed before it lands files no second copy (pimdir SYNC §5). IMAP and Graph still withdraw, as does a move answered 404.
- `GmailRun` keys its cached history by an epoch; `refiled(id)`, called by `Mail::relocate` and `Mail::copy` on Gmail in place of `forget`, bumps it, so a mailbox listed after the move re-reads the history and lands the create within the same pass. A pass lists inbox, sent, drafts, the other labels by name, junk, then trash, over two Gmail sessions sharing one `GmailRun`, so the order is the usual one but not guaranteed.
- Tests failing first: MailMoveTest `aGmailDeleteStaysInTheTrashThroughThePassPushingIt` ("still in the trash"), `aGmailDeleteLandsOnItsArrivalWhole` (no body); gmail_step tests `a_move_pushed_in_a_pass_lands_in_the_target_listed_after_it` (the trash row still the unbound create).
- Tracked, `@Ignore`d with a reason: `aGmailDeleteForGoodBeforeTheTrashLandsItIsPushed` and `aGmailRestoreBeforeTheTrashLandsItIsPushed` (a pass that skips the trash, then an action on the unbound row: io-pimdir withdraws a pending create on Remove or Move, mutate.rs around 457 to 466, SYNC §7, so the action stays local and the message comes back; closing it needs io-pimdir to mark a delivered move's destination create and hold an action on it until it lands); `aGmailDeleteOfAnOpenedMessageLandsRead` (the `\Seen` staged by opening is lost, `nameByMessageId` copying the arrival's markers onto a matched create, meant for sent copies).
- Checked: `cargo test` (646 passed), `cargo fmt`, `:app:testDebugUnitTest` (913 tests, 8 skipped), `:client` tests, in the agent's worktree; merged and run again with the day's other fixes.

Capabilities moved: mail (A message is deleted into the account's trash, modified: the account-wide paragraph and the Gmail scenario).
