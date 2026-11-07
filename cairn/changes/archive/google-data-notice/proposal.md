---
cairn: change
id: google-data-notice
status: landed
created: 2026-11-07
---

# The app's notice before Google's consent page

## Why

Google's Workspace API user data policy, under "Transparent and accurate notice and control", wants a disclosure of the data accessed and how it is used, "within the application itself", "displayed in the normal usage of the application", that "cannot be placed only in a privacy policy or terms of service" and "must accompany and immediately precede a request for user consent", the consent requiring "affirmative user action (for example, tap to accept, tick a check-box...)", never taken from "navigation away from the disclosure (including tapping away or pressing the back or home button)", and never "auto-dismissing or expiring messages". The demo video of the verification (oauth-verification, submission.md §3) opens on it. Google Play's User Data policy asks the same of a prominent disclosure, and its best practices recommend "Agree" and a second option that lets the user agree later.

## What

- A dialog over the sign-in page, shown when Continue is about to open Google's consent page, and only then: no other provider sees it. It names Pimalaya; one line per asked Google data (Gmail, Google Calendar, Google Tasks, Google Contacts) with its uses; the address read to check the sign-in, where the grant reads one; that the data goes only between Google and the phone and stays there, never received by Pimalaya, sold or used for ads; the phone mirrors, where calendars or contacts are asked; a link to the privacy policy (https://pimalaya.org/android/privacy/, its Google section and the Limited Use statement).
- Agree starts Continue's loader and opens the consent page; Not now, back, a tap outside or any other close opens nothing, and Continue shows the notice again. It shows before every Google consent, a sign-in again's included.
- PRIVACY.md's Google section names Google Tasks, the labels and sending addresses, the address check and the notice.
