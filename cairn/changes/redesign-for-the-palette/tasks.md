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
- [ ] Device check (owner): avatars light and dark, the toolboxes on Contacts, Tasks, selections and the contact page, the selection disc, the dock's wording, a task In progress
- [ ] Fold the delta into the spec and log
