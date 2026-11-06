---
cairn: tasks
change: redesign-for-the-palette
---

# Tasks

- [x] Canvas cleaned to the app as it is, in the palette, light and dark, after four audits of the code (2026-11-06)
- [x] Owner go-ahead to build the canvas into the app (2026-11-06); the setup row moved after the palette's
- [x] Avatars: the tonal set as colour resources, day and night (`avatar_fills`, `avatar_inks`), painted with its letter by `Avatar.paint` in the lists, the reader, the drawer and the account settings; a colour derived for a calendar without its own takes the solid tone of the same slot, which white event text reads on
- [x] Top-right actions in the toolbox shape: `ToolGroup`, a tonal pill that folds away while none of its buttons shows; the calendar's modes at its size; a selection's close and an editor's delete as round tonal buttons; the sync chooser's Reset as a tonal pill on a bar of the page's tone
- [x] Selection disc at the avatar's size (`selected_disc`, inset as the avatar)
- [x] Sync wording, every backend, English and French; the canvas's Sync wording board drawn as built; three unused CardDAV-era strings dropped
- [x] Badges on the wash (`tag_wash`, accent ink)
- [x] Build, unit tests (864, 5 skipped), installed on the device
- [x] Dark theme reviewed frame by frame (owner, 2026-11-06, "the app does not fully follow the design"): the palette's tokens already hold every view; what strayed were the platform widgets DeviceDefault colours from the wallpaper since Android 12. Popups and menus take the dialog ground, ripples the ink, links and selections the accent, checkboxes, radios and spinners palette styles, and each switch its tints in code (`Switches`, DeviceDefault's switch style being private); the launcher's Capture shortcut takes the brand magenta for the old teal. An HTML mail keeps its white page by choice (MessageView: inverting one turns some fully black)
- [x] The unread dot moved to the row's leading gutter, level with the sender (owner, 2026-11-06: at the end of the line it shifted the time); canvas version 109
- [x] Gutter dot rejected (owner, 2026-11-06: it shifted every list's avatars right): the unread mark is a badge on the avatar's top-right edge, ringed in the card's tone; canvas version 110
- [x] One avatar slot for every list (layout/avatar_slot.xml, `AvatarSlot.bind`): mail, contacts, agenda, files and a picked task; a calendar's disc takes its colour's hue in the palette's tones (`Avatar.paint` with a declared colour), no more white letter on a solid disc
- [x] One selection bar for every list (`SelectionBar`): cross, count and select-all on top, the actions on the navigation's item shape in its place; mail and contacts moved off the top bar; a picked task shows the check disc, its card untinted (`selectedTone` gone); clearing every contact now ends the selection, as on the other lists
- [x] Agenda and Files multi-select: long press, tap toggles, select-all, Delete after asking (an entry whole, `EventWrites.deleteWhole`; a folder's file); conflicted entries, the phone's own calendar and attachments are not selectable
- [x] Build, unit tests (864, 5 skipped), installed on the device
- [x] Round of 2026-11-06 (owner): the unread time back to the secondary ink; a task's box at the avatar's size and place (task_box.xml); the week card's today on the accent disc and a dot per calendar (CalendarList.week); a Settings page in the drawer for the week's first day (AppSettings, WeekStart); every page with its own bar in the main flipper, the lists in a column under the shared bar, so a page drops in and lifts away whole; the reader's Reply, Forward, Move and Delete in a bottom bar; canvas versions 111 and 112
- [x] Build, unit tests (864, 5 skipped); not installed (no device connected)
- [ ] Device check (owner): the week dots and today's disc, Settings and a Sunday start, every page's entrance and exit with its bar, the reader's bottom bar, the task boxes, the unread badge, the agenda's discs, every list's selection bar, the leave animation: avatars light and dark, the toolboxes on Contacts, Tasks, selections and the contact page, the selection disc, the dock's wording, a task In progress
- [ ] Fold the delta into the spec and log
