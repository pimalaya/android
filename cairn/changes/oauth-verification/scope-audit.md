---
cairn: change
id: oauth-verification
part: scope audit
---

# Google scope audit (2026-11-07)

Every Google action the Android app performs, the scopes Google accepts for each, and the narrowest set covering them. Three independent read-only audits (mail; calendars and tasks; contacts plus a sweep of every Google host the app and its io-g* crates reach), merged here. Scope acceptance comes from each method's "Authorization scopes" section in Google's reference, fetched on 2026-11-07; nothing was tried live. Crates read at the versions rust/Cargo.lock pins: io-gmail 0.6.0, io-gcal 0.3.0, io-gpeople 0.6.0, io-gtasks f4c4aed.

Scope names drop the `https://www.googleapis.com/auth/` prefix; MGC is `https://mail.google.com/`.

## Verdict

| Domain | Today | Needed | Change |
|---|---|---|---|
| Mail | MGC | MGC | none: forced twice, see below |
| Calendars | `calendar` | `calendar.events` + `calendar.calendarlist.readonly` | narrowed 2026-11-07 (narrow-google-calendar-scope) |
| Tasks | `tasks` | `tasks` | none |
| Contacts | `contacts` | `contacts` | none |

Recommended final set, five scopes: MGC, `calendar.events`, `calendar.calendarlist.readonly`, `tasks`, `contacts`. No scope is requested and unused, none used without being requested, and no other Google API is called (no userinfo, openid, revocation, Play Services or Firebase).

## Mail

What the app does over the Gmail API (the standard setup's path): users.getProfile (history id, and the sign-in identity check), users.settings.sendAs.list (identity check accepting an alias), users.labels.list, users.messages.list (with `q`), users.history.list, users.messages.get (`metadata`, `minimal`, `raw`, batched), users.messages.modify (read, star, move, copy), users.messages.trash and untrash, users.messages.delete (delete from Trash), users.messages.send. Over IMAP and SMTP (advanced setup only): XOAUTH2, then the usual IMAP verbs and SMTP submission. Never: drafts, threads, label writes, insert, import, batch modify or delete, watch, settings writes.

- MGC is forced twice. users.messages.delete accepts MGC alone, and Google's XOAUTH2 page gives MGC as the one scope for IMAP, POP and SMTP.
- Without those two, `gmail.modify` alone would cover everything else; nothing narrower would (`gmail.metadata` refuses `q` and `format=raw`, `gmail.readonly` cannot modify or send).
- Switching buys nothing at review: `gmail.modify` is restricted too, like every scope that reads Gmail bodies, so restricted verification and the CASA question stay. It would cost deleting for good on Gmail (Trash empties itself after 30 days) and IMAP and SMTP over OAuth, and touch the code keyed on the MGC string (`GOOGLE_SCOPES`, `domainScope`, the identity check in rust/src/account/identity.rs, io-pim-discovery's Gmail rule).
- Google's own wording backs MGC: request it "only if your application needs to immediately and permanently delete threads and messages, bypassing the trash", and "your app must show full utilization of https://mail.google.com/" for XOAUTH2. The video shows delete from Trash (submission.md §3); whether the reviewer also wants the advanced IMAP setup shown is unknown.

## Calendars

What the app does: calendarList.list (names, colours, access role, primary), events.list (full and incremental, by `iCalUID` for a series or a 409), events.get, events.import (a new event, mailing nobody), events.insert with `sendUpdates=all` (a meeting the user organizes), events.update and events.delete (If-Match, `sendUpdates`), events.instances (one occurrence), and calendars.get("primary") in the sign-in identity check (rust/src/calendar/gcal.rs:81, from identity.rs:132, when the grant holds `calendar` and not MGC). Never: creating, renaming, deleting, clearing or sharing a calendar, calendarList writes (colours, visibility), ACLs, settings, free/busy, watch.

- Event writes accept `calendar`, `calendar.events`, `calendar.app.created` or `calendar.events.owned`. `app.created` covers only calendars the app created (none); `events.owned` only calendars the user owns, which would break shared and subscribed calendars the app treats as writable. So `calendar.events`.
- calendarList.list is not covered by `calendar.events`: hence `calendar.calendarlist.readonly`.
- calendars.get("primary") is covered by neither, and is the one call forcing `calendar` today (the proposal's "only lists calendars and reads and writes events" missed it). calendarList.get("primary") returns the same id under `calendar.calendarlist.readonly`, and io-gcal 0.3.0 has it (`GcalCalendarListEntryGet`).
- Consent lines Google documents: `calendar` "See, edit, share, and permanently delete all the calendars you can access using Google Calendar"; `calendar.events` "View and edit events on all your calendars"; `calendar.calendarlist.readonly` "See the list of Google calendars you're subscribed to".
- Google's own example of an acceptable justification asks `calendar` for this app's use (support.google.com/cloud/answer/13464321, requirement 4): "My app will use https://www.googleapis.com/auth/calendar to show a user's Google calendar data on the scheduling screen of my app, so that users can manage their schedules through my app and sync the changes with their Google calendar."
- Too broad a scope costs a round trip, not a rejection outright (support.google.com/cloud/answer/13807380): the team mails a recommended "downscoping"; the reply is "Confirm Downscoping" (add the recommended scopes, resubmit, keep the approved ones until told) or "Unable to use narrower scopes" with the reason. Its worked cases are Drive and Gmail; none found for Calendar. Scopes for features not built yet are refused: "We require that you only request scope access for implemented features."
- No Calendar method needs `calendar` alone, so no feature earns it: every write the API has also accepts a granular scope (checked 2026-11-07 on calendars insert, patch, update, delete and clear, `calendar.calendars`; calendarList insert, patch and delete, `calendar.calendarlist`; acl.insert, `calendar.acls`; settings.list, `calendar.settings.readonly`; events.move, `calendar.events`). A future calendar feature adds its granular scope; only an app doing all of them makes `calendar` the honest union.

The narrowing, done whole (landed 2026-11-07, narrow-google-calendar-scope; the e2e fixture, which creates and deletes calendars, asks `calendar.calendars` beside the pair):

1. gcal.rs `primary_id`: calendarList.get("primary") in place of calendars.get.
2. identity.rs: the calendar identity keyed on the new scope, its tests following. Halfway traps: matching only the old string skips the wrong-account guard silently; matching the new one while still calling calendars.get gets a 403, logged as "grant identity unread", and the guard passes open.
3. Oauth.java: `GOOGLE_SCOPES`, the `withTasks` trigger, `domainScope`'s calendar case.
4. The discovered gcal option asks `calendar` (io-pim-discovery's Google rule): map it to the pair before OnboardingFlow computes `grantNeeds`, or change the rule upstream. Dropped unmapped by `fixedScope`, the calendar page's needs come out empty and it lands even when the user unticks the calendar boxes on Google's consent page.
5. The e2e token scope (E2eTarget.java), the oauth.rs test string, the Data Access page and submission.md §2.
6. Existing testers' grants carry `calendar`, which still covers every call; their next consent asks the pair.

## Tasks

What the app does: tasklists.list, tasks.list, tasks.get, tasks.insert (with `parent`, retried without), tasks.patch and tasks.delete (If-Match). Never: task list writes, tasks.move, tasks.clear. Writes accept `tasks` alone; `tasks.readonly` would make the Tasks tab read-only. Consent line: "Create, edit, organize, and delete all your tasks".

## Contacts

What the app does: contactGroups.list (books: myContacts and the user's labels), people.connections.list (sync with sync tokens), people.getBatchGet, people.get (etag and clientData before an edit), people.createContact and batchCreateContacts, people.updateContact, people.deleteContact and batchDeleteContacts, contactGroups.members.modify (label membership). Never: group create, rename or delete, photos, otherContacts, directory, searchContacts, people/me. The writes accept `contacts` alone; `contacts.readonly` would break every push, phone-app edits included.

## Sensitivity

- Restricted: MGC (and every Gmail scope reading bodies). None of the Calendar, Tasks or People scopes is on Google's restricted list (support.google.com/cloud/answer/13464325).
- Sensitive: Google's public pages give no per-scope class for Calendar, Tasks or People; Google names "reading events stored in Google Calendar" as a sensitive example, and the class shows only in the Cloud Console. To confirm on the Data Access page: if `calendar.calendarlist.readonly` comes out non-sensitive, it needs no justification.

## Side findings

- `include_granted_scopes=true` (OauthFlow.java:134): Google's native-app page says incremental authorization is not supported for installed apps; the explicit union of the stored grant's scopes (OnboardingFlow.java:3566) already does what the comment claims. Drop the extra and its comment. Done 2026-11-11 (log google-grant-asks-only-its-scopes).
- A contacts-only sign-in reads no identity (identity.rs returns none for a grant holding neither MGC nor a calendar scope), so the wrong-account guard does not cover it. Closing it would take `openid email` (likely non-sensitive, one more consent line); optional, not needed for verification.
- Removing an account does not revoke its Google grant. Google's revoke endpoint needs no scope; optional.
- submission.md wording: contacts' "including contact groups" overstates (the app lists labels and changes memberships, never creates or renames one); calendar's "reminders" names what the app does not show for Google entries (cairn/spec/calendar.md:257), so the video cannot show it.

## Not verified

- Per-scope sensitivity of the Calendar, Tasks and People scopes (Console only).
- That the consent screen shows the documented descriptions word for word.
- Whether a batched messages.get is authorized like a standalone call (inferred).
- That the primary calendar's id is the account's address: Google says it "usually matches"; the identity check already relies on it either way.
- Anything live: no build, no call; acceptance is from the documentation alone.
