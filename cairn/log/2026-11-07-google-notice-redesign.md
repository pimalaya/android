---
cairn: log
change: google-notice-redesign
date: 2026-11-07
---

# The Google notice, shorter and drawn

The owner found the first notice (google-data-notice) a block of text. Designed first on the Android canvas (row "Proposal: the Google notice", GoogleNotice and GoogleNoticeDark frames), approved by the owner the same day, then built.

- About 75 words instead of 120: a row per asked service (its domain glyph on a washed disc, the service's name in medium weight, its uses on one secondary line), a surface card with the phone's glyph for where the data stays ("Only on this phone": no Pimalaya server, between Google and the phone only, never sold or used for ads), then one small-print line with the address check and the phone mirrors as they apply and the privacy policy's link. Agree is the filled accent pill (button_pill, the accent's contrast ink), Not now stays text. Titled "Pimalaya and your Google data".
- Every rule of the Workspace API user data policy the first version met still holds: the app named, each data and use, the secondary uses, storage and sharing, the link, a tap to agree, backing out declining.
- GoogleNotice builds the view in Java like GoogleLimits; sizes in dimens.xml (`notice_disc`, `notice_glyph`). English and French strings rewritten. GoogleNoticeTest reads the shown dialog's texts per scope set instead of a text builder. `:app:assembleDebug` and `:app:testDebugUnitTest` green. Not yet seen on a device.

Capabilities moved: onboarding (requirement "Google's consent page follows the app's notice of what it reads", modified: its layout).
