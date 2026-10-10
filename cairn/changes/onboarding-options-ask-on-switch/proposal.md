---
cairn: change
id: onboarding-options-ask-on-switch
status: active
created: 2026-10-10
---

# Onboarding options: off by default, each permission asked as its switch turns on

## Why

The user's device test of the setups (2026-10-10):

- The phone switches read like a manual. "Also in the phone's Contacts app" over "The dialer and messaging apps see them, and edits there come back here." asks a regular person to parse three ideas before deciding.
- They start on, and their permissions are asked when Continue is tapped (`OnboardingFlow.confirmSetup` through `MainActivity.askMirrors`): a prompt nobody asked for, at a moment unrelated to the switch it serves. That reverses the 2026-10-09 decision "on by default, asked on Continue".
- New-mail notifications are not offered during setup at all; their permission is asked "once a mail account with notifications on exists" (`MainActivity.askNotifications`, on every return to the app), again a prompt at a random moment, and the setting itself is on by default for every account.

The rule the user asks for: the three permissions (contacts, calendar, notifications) are asked only when the option needing them is switched on, never otherwise.

## What

### Wording

Short and plain, the same in the setups and in the account settings (one string each, the settings rows reusing the setups'):

| Option | English | French |
|---|---|---|
| Contacts mirror | Show in phone contacts | Afficher dans les contacts du téléphone |
| its line (setups) | Calls and texts show their names. | Les appels et SMS affichent leur nom. |
| Calendar mirror | Show in phone calendar | Afficher dans l'agenda du téléphone |
| its line (setups) | Other apps and widgets see the events. | Les autres apps et les widgets voient les événements. |
| Notifications | Notify new mail (the settings' existing string) | Notifier les nouveaux e-mails |
| its line (setups) | Checks for mail in the background. | Vérifie les e-mails en arrière-plan. |

The notifications line says the one thing the title does not: turning it on means background checks.

### Off by default, asked on the switch

- Both setups start with the three switches off, and `open()` turns them off again for each run.
- Turning a phone switch on asks its permission pair at once (`askMirrors` with that mirror alone); a refusal turns the switch back off. Continue asks nothing.
- Turning the notifications switch on asks `POST_NOTIFICATIONS` on Android 13 and later (nothing earlier); a refusal turns it back off.
- The setups' state moves into `SetupSwitches` (the mirrors and the notifications, with the answer handling), so the defaults and the refusals are JVM-tested.

### Notify new mail in the setups

- Standard setup: a row under the Mail row of the domain card, shown while mail is ticked, like the phone rows under Contacts and Calendar.
- Advanced setup: the last row of the Mail card, under the sending rows, like the phone row of the Contacts and Calendar cards.
- On commit, an account covering mail with the switch on gets `BackgroundCheck.setNotifies(email, true)`. Off, nothing is written: the setting is off by default, and setting up an existing address again leaves its choice alone.
- **Background sync.** Notifications come from the background run, so `BackgroundCheck.setNotifies(…, true)` turns the account's background sync on, at the default 15 minutes, when it is off (an existing account whose interval was turned off and gains mail). A new account is already at 15 minutes. The settings switch, dimmed while background sync is off, never reaches that case.

### Notifications off by default

`BackgroundCheck` keeps the accounts that notify rather than those that do not, so an account notifies only once turned on, in a setup or in its settings. Accounts set up by earlier builds stop notifying until turned on; the background check is not released yet, so no user loses anything.

### Nowhere else

- Removed: the Continue-time `askMirrors` in `OnboardingFlow.confirmSetup`, `MainActivity.askNotifications` (the ask-once on return, and its `notifications-asked` preference) and the fire-and-forget `requestNotifications`.
- Kept, as the other place each is asked: the account settings switches. A book's "Show in phone contacts", a calendar's "Show in phone calendar" and "Notify new mail" each ask on turn-on and fall back off on refusal; the notifications switch now waits for the answer before storing anything.
- Turning a book on in settings brings it to the phone only when the account's other books are already there and the permission is held; it never prompts. Its "Show in phone contacts" box does.
- No startup request exists beside those (checked: `onCreate`, `onResume`, the sync paths only read the grants).

### A permission revoked in the system settings

Each settings option reads as off while its permission is missing (the book's and the calendar's box, the notifications switch), so turning it on asks again. The background paths already skip what they are not permitted to do (`PhoneRemote`, `CalendarRemote`, `BackgroundJob.notify`).

## Revises

- `docs/release-orchestration.md`, decision "Onboarding switch": now off by default, asked on the switch; two decisions added (notifications in the setups, when permissions are asked).
- `PRIVACY.md`: the options' names, their defaults, when each permission is asked.
- `phone-contacts-mirror`, `phone-calendar-mirror` (proposal and delta: on by default, asked on Continue) and `background-check` (proposal: notifications on by default, asked once a notifying account exists): edited in place with a pointer here, since they are still active.

## Out of scope

- Seeding the setup's switches from an existing account's settings when an address is set up again.
- A "needs permission" line: the options read off instead. (A denial Android will no longer prompt for does say so, in a dialog opening Android settings; see tasks.)
