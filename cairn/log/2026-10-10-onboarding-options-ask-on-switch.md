---
cairn: log
change: onboarding-options-ask-on-switch
landed: 2026-10-10
---

# Onboarding options are off by default, each permission asked as its option is turned on

Capabilities moved: onboarding (added: a permission is asked only as its option is turned on; the setups offer new-mail notifications; the standard setup starts with nothing ticked). The restated "Showing contacts on the phone is chosen" and "New mail notifies" fold with `phone-contacts-mirror` and `background-check`, still active.

**Options.** Both setups offer, under the domain they serve, *Show in phone contacts*, *Show in phone calendar* and *Notify new mail*, each with one plain line, the account settings reusing the strings. The standard setup draws them as ticks (`OnboardingFlow.tickRow`), as its domains are, and opens with every domain unticked, ticks kept when stepping back. `SetupSwitches` holds them, off at every run, an option turning on only once its permission came back granted.

**Permissions.** Contacts and calendar are asked through `MainActivity.askMirrors`, notifications through `askNotifications` (Android 13 and later), only as an option is turned on in a setup or in the account settings. Continue asks nothing; the ask-once on return (`notifications-asked`) and `requestNotifications` are gone. A denial Android will not prompt for again (`PermissionAnswer.blocked`) opens a dialog pointing to Android settings. A settings option reads off while its permission is missing, so turning it on asks again; a book turned on in settings never prompts.

**Notifications.** `BackgroundCheck` keeps the accounts that notify, so none does until turned on; turning it on sets background sync to 15 minutes when it was off. A setup writes it only when ticked, for an account covering mail.
