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

Pimalaya is an open-source mail, contacts and calendar client for Android (https://github.com/pimalaya/android). Users read, search, compose, send, label, move, archive and delete their Gmail messages in the app. The scope is needed for two reasons no narrower scope covers: the app connects over IMAP and SMTP, which accept only this scope, and it permanently deletes messages when the user empties Trash or deletes a message from Trash (Gmail API `users.messages.delete`), which `gmail.modify` does not permit. This is the "email client that allows users to compose, send, read, and process email via a user interface" use case of the Gmail API policy.

**`https://www.googleapis.com/auth/calendar.events`**

Users list, create, edit and delete events in their Google calendars, recurring events and attendees included, in the calendars they own and in those shared with them for writing alike; a meeting the user organizes invites its guests through Google. `calendar.events.owned` would leave out the shared calendars, and the app never creates, renames, deletes or shares a calendar, so the full `calendar` scope is not requested.

**`https://www.googleapis.com/auth/calendar.calendarlist.readonly`**

The app lists the user's calendars (name, colour, whether the user can write to it, which one is primary) so the user chooses which to sync, and reads the primary calendar's id to check that the account signed in is the one the user entered. It never changes the list.

**`https://www.googleapis.com/auth/tasks`**

Users list, create, complete, edit and delete the tasks of their Google Tasks lists in the app's Tasks tab.

**`https://www.googleapis.com/auth/contacts`**

Users list, create, edit and delete their Google contacts (People API), including which labels a contact carries, and may mirror them into the phone's own Contacts app.

**Security assessment statement** (in the notes field)

Pimalaya has no server. The app runs the OAuth authorization code flow with PKCE on the user's device, calls Google's APIs, IMAP and SMTP directly from the device, and stores the data and the tokens only in the app's private storage, the tokens encrypted with an Android Keystore key. No restricted data and no token is sent to, stored on or processed by any server of ours or of a third party. The source code is public at https://github.com/pimalaya/android. Per the restricted scope verification requirements, the security assessment applies to apps that store or transmit restricted scope data on servers, which this app does not.

## 3. Google demo video

Unlisted on YouTube, the phone in English, recorded with Android's screen recorder on the Play build, then the other clients.

1. Open the app, add an account with the [test account] address, pick the Google sign-in; show the in-app disclosure and tap to confirm it.
2. In the browser, tap the address bar and scroll the URL to `client_id=`; hold two seconds.
3. Show the consent screen: the name Pimalaya, every scope listed; tick them and continue.
4. Mail: the inbox lists, a message opens, a reply is written and sent, a message moves to another label, one is deleted, then deleted from Trash for good (the restricted scope's reason).
5. Calendar: an event created, edited, deleted; the calendar list.
6. Tasks: a task created and completed.
7. Contacts: a contact created and edited.
8. Optional: the same changes seen in Gmail and Calendar on the web, proving the round trip.
9. For each other Android client (GitHub APK, F-Droid): install that build, start a Google sign-in, show `client_id=` in the address bar and the consent screen.
10. For each other interface's client in the project, if any by then: run its account wizard, show its disclosure and confirm, `client_id=` in the address bar, the consent screen, then each scope's feature in that interface.

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

- https://mail.google.com/ (restricted): the user deletes a message from Trash for good (Gmail API users.messages.delete), which gmail.modify does not permit, and the app's advanced mail setup signs in to IMAP and SMTP over OAuth, which accept only this scope.
- calendar.events: the user lists, creates, edits and deletes events in the calendars they own and in those shared with them for writing; calendar.events.owned would leave the shared ones out. The app never creates, renames, deletes or shares a calendar, so it does not request calendar.
- calendar.calendarlist.readonly: the app lists the user's calendars so the user picks which to sync, and reads the primary calendar's id to check that the signed-in account is the one entered. It never changes the list.
- tasks: the user lists, creates, completes, edits and deletes tasks; tasks.readonly would make them read-only.
- contacts: the user lists, creates, edits and deletes contacts and changes their labels; contacts.readonly would make them read-only.

DEMO VIDEO

[URL] (unlisted), recorded on the release build, the phone in English:

- [mm:ss] the in-app disclosure of the data accessed and its use, accepted with its Agree button before the browser opens;
- [mm:ss] client_id=803561575535-v7nav2ev8dvv995g3j3mhnoq5423abco in the address bar;
- [mm:ss] the consent screen naming Pimalaya, every scope listed and expanded;
- [mm:ss] mail: a message read, a reply sent, a message moved to a label, a message deleted then deleted from Trash for good; then Gmail on the web showing the reply in Sent, the label, and the message gone from Trash;
- [mm:ss] the advanced mail setup: IMAP and SMTP signing in with the same Google account, a message listed and one sent;
- [mm:ss] calendar: the calendar list, an event created, edited and deleted, each step seen in Google Calendar on the web;
- [mm:ss] tasks: a task created and completed, seen in Google Tasks on the web;
- [mm:ss] contacts: a contact created, edited and labelled, seen in Google Contacts on the web.

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

Pimalaya has no server. The app runs the OAuth authorization code flow with PKCE on the user's device, calls Google's APIs, IMAP and SMTP directly from the device, and stores the data and the tokens only in the app's private storage. No restricted scope data and no token is sent to, stored on or processed by any server of ours or of a third party; the source code is public at https://github.com/pimalaya/android. The restricted scope verification requirements apply the security assessment to apps that store or transmit restricted scope data on servers, which this app does not, so we ask you to confirm that no assessment is required.

Best regards,
[name]
Pimalaya
```
