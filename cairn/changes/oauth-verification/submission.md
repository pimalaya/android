---
cairn: change
id: oauth-verification
part: submission texts
---

# Submission texts

Drafts to paste into the consoles. Google's are in English, as the review is. Bracketed parts are to fill.

## 1. Google branding (Google Auth Platform > Branding)

- App name: Pimalaya
- User support email: picked from the signed-in account or a Google Group it manages
- App logo: logo.svg rendered at 120x120 PNG, transparent, with 8% margin on each side: the mark's bounds in the 1000x1000 canvas are x 123 to 877 and y 100 to 900, so the root takes `viewBox="23.8095 23.8095 952.381 952.381"` (the tallest side at 84%) before `rsvg-convert -w 120 -h 120`
- Application home page: https://pimalaya.org/
- Privacy policy: https://pimalaya.org/privacy/
- Terms of service: https://pimalaya.org/terms/
- Authorized domains: pimalaya.org
- Developer contact: a mailbox read daily (Google's review questions land there, and an unanswered thread stalls the review)

## 2. Google data access justifications

**`https://mail.google.com/`**

Updated 2026-11-11 (drop-google-imap): the submitted text also named IMAP and SMTP, which the app no longer offers for Google; paste this one into the Data Access page.

Pimalaya is an open-source mail, contacts and calendar client for Android (https://github.com/pimalaya/android). Users read, search, compose, send, label, move, archive and delete their Gmail messages in the app, over the Gmail API alone. The scope is needed for one reason no narrower scope covers: the app permanently deletes messages when the user empties Trash or deletes a message from Trash (Gmail API `users.messages.delete`), which `gmail.modify` does not permit. This is the "email client that allows users to compose, send, read, and process email via a user interface" use case of the Gmail API policy.

**`https://www.googleapis.com/auth/calendar.events`**

Users list, create, edit and delete events in their Google calendars, recurring events and attendees included, in the calendars they own and in those shared with them for writing alike; a meeting the user organizes invites its guests through Google. `calendar.events.owned` would leave out the shared calendars, and the app never creates, renames, deletes or shares a calendar, so the full `calendar` scope is not requested.

**`https://www.googleapis.com/auth/calendar.calendarlist.readonly`**

The app lists the user's calendars (name, colour, whether the user can write to it, which one is primary) so the user chooses which to sync, and reads the primary calendar's id to check that the account signed in is the one the user entered. It never changes the list.

**`https://www.googleapis.com/auth/tasks`**

Users list, create, complete, edit and delete the tasks of their Google Tasks lists in the app's Tasks tab.

**`https://www.googleapis.com/auth/contacts`**

Users list, create, edit and delete their Google contacts (People API), including which labels a contact carries, and may mirror them into the phone's own Contacts app.

**One paragraph per class** (2026-11-11, for the Data Access page's one box per class, each under 1000 characters)

Restricted scopes (609 characters):

```text
Pimalaya is an open-source mail, contacts and calendar client for Android (https://github.com/pimalaya/android). Users read, search, compose, send, label, move, archive and delete their Gmail messages in the app, over the Gmail API alone. The scope is needed for one reason no narrower scope covers: the app permanently deletes messages when the user empties Trash or deletes a message from Trash (Gmail API users.messages.delete), which gmail.modify does not permit. This is the "email client that allows users to compose, send, read, and process email via a user interface" use case of the Gmail API policy.
```

Sensitive scopes (888 characters):

```text
Pimalaya syncs the user's Google calendars, tasks and contacts with the app, on the device only. calendar.events: users list, create, edit and delete events, recurring ones and attendees included, in the calendars they own and in those shared with them for writing (calendar.events.owned would leave the shared ones out); the app never creates, renames, deletes or shares a calendar, so the full calendar scope is not requested. calendar.calendarlist.readonly: the app lists the user's calendars so the user picks which to sync, and reads the primary calendar's id to check that the signed-in account is the one entered; it never changes the list. tasks: users list, create, rename, complete and delete their tasks; tasks.readonly would make them read-only. contacts: users list, create, edit and delete their contacts and change their labels; contacts.readonly would make them read-only.
```

**Security assessment statement** (in the notes field)

Pimalaya has no server. The app runs the OAuth authorization code flow with PKCE on the user's device, calls Google's APIs directly from the device, and stores the data and the tokens only in the app's private storage, the tokens encrypted with an Android Keystore key. No restricted data and no token is sent to, stored on or processed by any server of ours or of a third party. The source code is public at https://github.com/pimalaya/android. Per the restricted scope verification requirements, the security assessment applies to apps that store or transmit restricted scope data on servers, which this app does not.

## 3. Google demo video

Recorded as short clips, joined by assemble-video.sh (next to this file), uploaded unlisted on YouTube. The release build, so the "Pimalaya" project's Android client (one client serves every release channel; the debug client stays out). English everywhere: the phone, the browser, Google's sites. No sound needed: the script drops it.

What Google's checklist (2026-11-10) asks of it: the consent screen with every scope readable, the scopes exactly the Data Access page's, every scope's feature working, and every write or delete seen in the Google account itself ("if the app demonstrates permanent deletion, the video must also show the email being purged in the Gmail source account").

### Recording

- App clips: Android's screen recorder. Web clips: any desktop recorder, the browser full screen. The script scales and pads both to 1920x1080.
- Or mirror the phone on the desktop (`nix-shell -p scrcpy --run scrcpy`, USB debugging on) beside the browser and record the desktop: an app clip and the web clip after it then fit in one take, and the write and its proof sit side by side.
- Name each file `NN-name.mp4` as below, all in one folder; a retake replaces its file, an extra clip slots in with a letter (`06b-calendar-guest.mp4`). The script joins them in name order.
- Between an app clip and its web clip, pull the app's list down so the sync carries the change to Google, and refresh the web page.
- In each web clip, open the account menu (the avatar, top right) once so the address shows: it ties the clip to the account the app signed in.

### Before the first clip

- Pimalaya's access to the test account revoked at https://myaccount.google.com/permissions (done 2026-11-10), the address not in the app (removed off camera).
- On the test account: three messages in the inbox (sent from another test account), a Gmail label `Demo`, a second calendar beside the primary one, a contact label `Demo`.
- The desktop browser signed in to the same account, one tab each on Gmail, Google Calendar, Google Tasks (https://tasks.google.com) and Google Contacts.

### The clips

**01-signin.mp4** (phone, one take, never cut: the reviewer must see the flow unbroken)

1. Open Pimalaya, tap Add account, type the test address, tap Continue.
2. Switch on Mail, Contacts and Calendars, tap Continue.
3. The notice "Pimalaya and your Google data": hold 3 seconds, tap Agree.
4. In the browser, tap the address bar and scroll the URL to `client_id=803561575535-v7nav2ev8dvv995g3j3mhnoq5423abco`; hold 2 seconds.
5. Sign in. On the consent screen, hold on the name Pimalaya, then scroll slowly over every permission, expanding any collapsed list, tick every box, tap Continue.
6. Back in the app, "Review your settings", Finish; let the first sync run. (Recorded 2026-11-11, 2:01: "Review your domains" comes before the notice, and the calendars show only as "Subscribed calendars: All", so clip 06 opens on the list.)

**02-mail-app.mp4** (phone)

1. The inbox lists; open the first message.
2. Reply with one line, send.
3. Open the second message, Move to, `Demo`.
4. Open the third message, Delete: it goes to Trash. Pull the inbox down.

**03-mail-web.mp4** (Gmail): the account menu; Sent shows the reply; the `Demo` label holds the moved message; Trash holds the deleted one.

**04-mail-delete-app.mp4** (phone): open Trash, open that message, Delete, confirm: "Deleted for good". Pull down.

**05-mail-delete-web.mp4** (Gmail): refresh Trash: the message is gone; search `in:anywhere` with its subject: nothing. This is Google's own example, and the reason for `https://mail.google.com/` (`gmail.modify` cannot delete for good).

**06-calendar-app.mp4** (phone)

0. The account's settings, Subscribed calendars: both calendars listed by name (`calendar.calendarlist.readonly`); hold 2 seconds, back.
1. New event "Demo meeting", tomorrow at 10:00, save.
2. Open it, change the title to "Demo meeting, moved" and the time to 11:00, save.
3. New event "Demo call", tomorrow at 15:00, save. Pull down.
4. Optional, since the justification names invitations: a guest added to "Demo meeting, moved" (another test account).

**07-calendar-web.mp4** (Google Calendar, tomorrow): the account menu; "Demo meeting, moved" at 11:00 and "Demo call" at 15:00.

**08-calendar-delete-app.mp4** (phone): open "Demo call", Delete, confirm. Pull down.

**09-calendar-delete-web.mp4** (Google Calendar): refresh: "Demo call" is gone.

**10-tasks-app.mp4** (phone)

1. New task "Demo task", save; open it, rename it "Demo task, edited", save; Mark done.
2. New task "Demo task 2", save. Pull down.

**11-tasks-web.mp4** (Google Tasks): the account menu; "Demo task, edited" under Completed, "Demo task 2" open.

**12-tasks-delete-app.mp4** (phone): delete "Demo task 2". Pull down.

**13-tasks-delete-web.mp4** (Google Tasks): refresh: "Demo task 2" is gone.

**14-contacts-app.mp4** (phone)

1. New contact "Demo Contact" with an email address, save.
2. Open it, add a phone number, save.
3. "Addressbooks of this contact": add it to `Demo` (the Google label).
4. New contact "Demo Contact 2", save. Pull down.

**15-contacts-web.mp4** (Google Contacts): the account menu; "Demo Contact" with its phone number and the `Demo` label; "Demo Contact 2".

**16-contacts-delete-app.mp4** (phone): delete "Demo Contact 2". Pull down.

**17-contacts-delete-web.mp4** (Google Contacts): refresh: "Demo Contact 2" is gone.

No IMAP clips: the app no longer offers Gmail over IMAP and SMTP (drop-google-imap, 2026-11-11), so `https://mail.google.com/` keeps deleting for good as its one reason, filmed in 02.

### Recorded (2026-11-11, ~/Videos/pimalaya-oauth-demo/, superseded takes in takes/)

Each domain's web proof was filmed in the same clip, on the phone's browser, so the plan's web clips merged into their app clips: 01-signin.mp4 (1:43), 02-mail-app.mp4 (2:10, covers 02 to 05), 06-calendar.mp4 (1:25, covers 06 to 09; the calendar list itself is in 01), 10-tasks.mp4 (1:38, covers 10 to 13, each change confirmed on the web before the next), 14-contacts.mp4 (2:41, covers 14 to 17, the label through Seed amis).

### Joining

```sh
nix-shell -p ffmpeg --run 'sh cairn/changes/oauth-verification/assemble-video.sh <clips folder>'
```

It writes demo.mp4 into the clips folder, never touching the clips, and prints where each title card and clip starts: the timestamps of the reply (§5). A `NN-name.txt` beside a clip becomes a 4-second title card before it, one with no clip of its name a card in its own place (00-intro.txt). Joined 2026-11-11: 10:01, six cards (intro, then one per part, naming its scope).

### Upload

Unlisted on YouTube. Title: Pimalaya for Android: Google OAuth verification demo. Description (its timestamps are the title cards' starts, which YouTube turns into chapters):

```text
Demo video for the Google OAuth verification of Pimalaya, an open-source mail, contacts and calendar app for Android.

Google Cloud project: pimalaya-513918 (803561575535)
OAuth client: 803561575535-v7nav2ev8dvv995g3j3mhnoq5423abco.apps.googleusercontent.com
App: org.pimalaya, release build 0.2.0, recorded on a phone in English

Scopes shown in use:
- https://mail.google.com/ : read, reply, label, delete, and delete for good from Trash (purged in Gmail)
- https://www.googleapis.com/auth/calendar.events : create, edit and delete an event
- https://www.googleapis.com/auth/calendar.calendarlist.readonly : the calendar list, to pick the calendars to sync
- https://www.googleapis.com/auth/tasks : create, rename, complete and delete a task
- https://www.googleapis.com/auth/contacts : create, edit, label and delete a contact

Each change made in the app is then shown in Google's own web app (Gmail, Google Calendar, Google Tasks, Google Contacts), signed in to the same account.

Pimalaya has no server: the app signs in with OAuth and PKCE on the device and talks to Google's APIs directly.

Home page: https://pimalaya.org/
Privacy policy: https://pimalaya.org/privacy/
Source code: https://github.com/pimalaya/android

00:00 Intro
00:04 Sign-in: in-app notice, Google consent screen, client ID, calendar list
01:51 Gmail
04:05 Google Calendar
05:34 Google Tasks
07:16 Google Contacts
```

## 4. Microsoft branding (Entra admin center > App registrations > Pimalaya > Branding & properties)

- Name: Pimalaya
- Logo: 215x215 PNG
- Home page URL: https://pimalaya.org/
- Terms of service URL: https://pimalaya.org/terms/
- Privacy statement URL: https://pimalaya.org/privacy/
- Publisher domain: pimalaya.org

Admin consent link for the docs, for a tenant that blocks user consent (`<tenant>` is the tenant's domain or ID):

```
https://login.microsoftonline.com/<tenant>/adminconsent?client_id=ba9b19e1-973e-4d7c-aed9-848bd2fee385
```

## 5. Reply to the review checklist

Trust and Safety's first mail (2026-11-10) is Google's generic checklist, not findings. Reply in its thread once tasks.md §2's checklist items are done, timestamps and video URL filled from the final video.

```text
Hello,

Thank you for the checklist. We audited project pimalaya-513918 (803561575535) against it; our answers follow, section by section.

SCOPES

The app requests exactly the five scopes on the Data Access page. Each backs a feature the app ships today; none is requested for a future feature. Pimalaya is a single mail, contacts and calendar app, not an integration platform.

- https://mail.google.com/ (restricted): the user deletes a message from Trash for good (Gmail API users.messages.delete), which gmail.modify does not permit. Since our submission the app reaches Gmail over the Gmail API alone: it no longer offers IMAP and SMTP, which the submitted justification also named, so this is now the scope's one reason.
- calendar.events: the user lists, creates, edits and deletes events in the calendars they own and in those shared with them for writing; calendar.events.owned would leave the shared ones out. The app never creates, renames, deletes or shares a calendar, so it does not request calendar.
- calendar.calendarlist.readonly: the app lists the user's calendars so the user picks which to sync, and reads the primary calendar's id to check that the signed-in account is the one entered. It never changes the list.
- tasks: the user lists, creates, completes, edits and deletes tasks; tasks.readonly would make them read-only.
- contacts: the user lists, creates, edits and deletes contacts and changes their labels; contacts.readonly would make them read-only.

DEMO VIDEO

[URL] (unlisted, 10:01), recorded on the release build, the phone in English, a title card before each part; every change made in the app is then shown in Google's own web app, on the same phone:

- 0:18 the in-app disclosure of the data accessed and its use, accepted with its Agree button before the browser opens;
- 0:23 the consent screen naming Pimalaya, every scope listed, each one's details opened;
- 0:56 client_id=803561575535-v7nav2ev8dvv995g3j3mhnoq5423abco in the address bar;
- 1:31 the calendar list, read to pick the calendars to sync (calendar.calendarlist.readonly);
- 1:55 mail: a message read, a reply sent, a message moved to a label and one deleted, then deleted from Trash for good (2:55); Gmail on the web shows the label, the message purged from its Bin (3:31) and the reply in Sent (https://mail.google.com/);
- 4:09 calendar: an event created, edited and deleted, each change seen in Google Calendar on the web (calendar.events);
- 5:38 tasks: a task created, renamed, completed and deleted, each change seen in Google Tasks on the web (tasks);
- 7:20 contacts: a contact created, edited, given a label and deleted, each change seen in Google Contacts on the web (contacts).

APP ACCESS

Pimalaya has no account, login or server of its own, and no phone verification or payment step: it signs in with any Google account the reviewer chooses. The app is not public yet: it sits on Google Play's internal testing track and its public release waits for this verification, so no unverified scope serves production traffic; the project stays In production. We will send the release build's APK on request. Steps: install and open Pimalaya, tap Add account, enter a Gmail address and tap Continue, read the notice and tap Agree, then sign in and allow every scope in the browser; once synced, mail, calendar, tasks and contacts each have their own section in the app.

PRIVACY POLICY

https://pimalaya.org/privacy/, linked from the home page https://pimalaya.org/ and from the consent screen; the Android app's details are at https://pimalaya.org/android/privacy/. Its Google user data section answers each point under its own heading:

- Access: Gmail messages, labels and sending addresses; calendars and their events; task lists and tasks; contacts and their labels; the account's address. No aggregated or anonymized data is derived from them.
- Use: only to show them to the user and to carry out the user's own actions on them, on the device.
- Sharing: with no one. The data goes only back to Google and, when the user turns the option on, into the phone's own Contacts and Calendar apps. It is never sold, never used for advertising and never read by a person.
- Protection: every connection to Google is encrypted with TLS, the data stays in the app's private storage, and the OAuth tokens are encrypted with an Android Keystore key. No server of ours holds any of it.
- Retention and deletion: kept while the account is in the app; removing the account or uninstalling the app deletes it from the device, and the user can revoke access at any time at https://myaccount.google.com/permissions.

LIMITED USE AND AI

The app uses Google user data only to provide its user-facing features: no advertising, no sale, no transfer to third parties, no human reading. It uses no AI or ML model and sends no Google user data to any AI service. The privacy policy states that Pimalaya's use and transfer of information received from Google APIs adheres to the Google API Services User Data Policy, including the Limited Use requirements.

USE CASE

A mail client in which users read, compose, send and organize their own mail, the email client use case of the Gmail API policy. No bulk or unsolicited mail, no email warming. No Data Portability API is requested.

SECURITY ASSESSMENT

Pimalaya has no server. The app runs the OAuth authorization code flow with PKCE on the user's device, calls Google's APIs directly from the device, and stores the data and the tokens only in the app's private storage. No restricted scope data and no token is sent to, stored on or processed by any server of ours or of a third party; the source code is public at https://github.com/pimalaya/android. The restricted scope verification requirements apply the security assessment to apps that store or transmit restricted scope data on servers, which this app does not, so we ask you to confirm that no assessment is required.

Best regards,
[name]
Pimalaya
```
