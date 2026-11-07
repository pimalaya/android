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
