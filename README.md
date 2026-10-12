<div align="center">
  <img src="./logo.svg" alt="Logo" width="128" height="128" />
  <h1>🗂️ Pimalaya Android</h1>
  <p>Android app to manage your mail, contacts and calendars</p>
  <p>
    <a href="https://matrix.to/#/#pimalaya:matrix.org"><img alt="Matrix" src="https://img.shields.io/badge/chat-%23pimalaya-blue?style=flat&logo=matrix&logoColor=white"/></a>
    <a href="https://fosstodon.org/@pimalaya"><img alt="Mastodon" src="https://img.shields.io/badge/news-%40pimalaya-blue?style=flat&logo=mastodon&logoColor=white"/></a>
    <a href="https://pimalaya.org/sponsor/"><img alt="Sponsor" src="https://img.shields.io/badge/sponsor-pink?style=flat&logo=github-sponsors&logoColor=white"/></a>
  </p>
</div>

<table><tr>
<td><img src="screenshots/onboarding.jpg" width="200" alt="Automatic account setup from an email address" /></td>
<td><img src="screenshots/contacts.jpg" width="200" alt="Merged contact list across every account" /></td>
<td><img src="screenshots/contact.jpg" width="200" alt="Contact detail with identity and emails" /></td>
<td><img src="screenshots/accounts.jpg" width="200" alt="Accounts drawer" /></td>
<td><img src="screenshots/birthday.jpg" width="200" alt="Next birthday reminder" /></td>
</tr></table>

> [!WARNING]
> Pimalaya for Android is in beta: it works day to day, but expect rough edges, and what it stores on the phone may change between versions. Please report what goes wrong on the [issue tracker](https://github.com/pimalaya/android/issues).

## Table of contents

- [Features](#features)
- [Coverage](#coverage)
- [Installation](#installation)
  - [Google Play](#google-play)
  - [APK](#apk)
  - [Development builds](#development-builds)
  - [Sources](#sources)
- [AI policy](https://github.com/pimalaya/.github/blob/master/AI_POLICY.md)
- [License](#license)
- [Social](#social)
- [Contributing](./CONTRIBUTING.md)
- [Sponsoring](#sponsoring)

## Features

- **Mail, contacts and calendars in one app**: three lists behind a bottom bar, over one account list, plus a Files tab for your attachments and folders.
- **Every account in one list**: each list merges every account and every mailbox, address book or calendar, narrowed by a filter or a chip.
- **Many providers**: IMAP and SMTP, JMAP (Fastmail), CardDAV and CalDAV, Google (Gmail, Contacts, Calendar) and Microsoft 365, side by side.
- **Automatic setup**: type an email address and the servers and sign-in are found for you, including for custom domains hosted by Google or Microsoft.
- **Sign in your way**: password, app password or token, or OAuth 2.0 with the built-in Google and Microsoft sign-ins or your own; credentials are encrypted on the phone.
- **Offline first**: everything is stored on the phone and opens at once; reading, writing, deleting and editing work with no network and go out on the next sync.
- **Light on your quota**: mail comes in steps of about 50 messages, only the mailboxes you tick are synced, and each sync asks only for what changed.
- **Mail you can work with**: read, reply, forward, star, delete into the trash, and write new messages; attachments open in your apps or save to the phone.
- **Calendars you can edit**: create, edit and delete events, to-dos and journal entries, with recurring series shown in the agenda.
- **A complete contact editor**: a form for every common field, an advanced editor for every vCard property, and a raw source editor.
- **Duplicates handled**: contacts merged across accounts, a duplicate finder, manual linking, vCard import and export, and the next birthday at a glance.
- **Phone integration**: show your address books and calendars in Android's own Contacts and Calendar apps, both ways, and get notified of new mail.
- **Background sync**: accounts sync on their own while the app is closed, as Android allows.
- **Safe conflicts**: when the phone and the server both changed the same field, nothing is overwritten blindly and you choose what stays.
- **Deleted items**: deleted messages, contacts and events wait in one list, to restore or to free for good.
- **Links between items**: a message links to its sender's contact and to the event it invites to, and an attachment to its message.
- **Private by design**: no account with us, no analytics, no ads; the app talks only to your providers (see the [privacy policy](./PRIVACY.md)).

## Coverage

| Standard or API | What is covered |
|-----------------|-----------------|
| [IMAP4rev1][rfc3501] | Mail: every mailbox listed and synced, messages read whole, and the marks, moves, deletes and appends the app writes back |
| [CONDSTORE and QRESYNC][rfc7162] | Asking a mailbox only for what changed since the last sync |
| [UIDPLUS][rfc4315], [MOVE][rfc6851] and [SORT][rfc5256] | Deleting one message alone, moving without a copy, and finding the newest messages on servers that sort |
| [Special-use mailboxes][rfc6154] | Which mailbox is the inbox, the sent mail, the trash or the junk, asked of the server rather than guessed from its name |
| [SMTP][rfc5321] and [SMTP authentication][rfc4954] | Sending the messages you write through your account's server |
| [Internet Message Format][rfc5322], [MIME][rfc2045] and [encoded words][rfc2047] | Composing messages and reading their parts and attachments |
| [JMAP][rfc8620], [JMAP for Mail][rfc8621] and [JMAP for Contacts][rfc9610] | Mail and contacts over one JMAP session, as Fastmail serves them |
| [JMAP for Calendars][jmapcal] | Calendars over the same JMAP session |
| [vCard 4.0][rfc6350] | Contacts: every property kept as is, and edited by form or by hand |
| [CardDAV][rfc6352] | Address books on any standard server |
| [iCalendar][rfc5545] | Calendars: events, to-dos and journal entries, recurrence rules expanded into the agenda |
| [CalDAV][rfc4791] and [WebDAV sync][rfc6578] | Calendars on any standard server, synced by what changed |
| [CardDAV and CalDAV discovery][rfc6764] and [PACC][pacc] | Finding your servers from an email address or a bare domain |
| [Mozilla autoconfig][autoconfig] and [DNS over HTTPS][rfc8484] | Finding mail servers from your provider's or Thunderbird's database, asked through an encrypted resolver |
| [OAuth 2.0][rfc6749], [PKCE][rfc7636], [server metadata][rfc8414] and [dynamic client registration][rfc7591] | Signing in without a password, even on servers that need no provider console |
| [Gmail API][gmail], [Google People API][people] and [Google Calendar API][gcal] | Mail, contacts and calendars of Google accounts |
| [Microsoft Graph][msgraph] | Mail, contacts and calendars of Microsoft 365 and Outlook.com accounts |

[rfc2045]: https://www.rfc-editor.org/rfc/rfc2045
[rfc2047]: https://www.rfc-editor.org/rfc/rfc2047
[rfc3501]: https://www.rfc-editor.org/rfc/rfc3501
[rfc4315]: https://www.rfc-editor.org/rfc/rfc4315
[rfc4791]: https://www.rfc-editor.org/rfc/rfc4791
[rfc4954]: https://www.rfc-editor.org/rfc/rfc4954
[rfc5256]: https://www.rfc-editor.org/rfc/rfc5256
[rfc5321]: https://www.rfc-editor.org/rfc/rfc5321
[rfc5322]: https://www.rfc-editor.org/rfc/rfc5322
[rfc5545]: https://www.rfc-editor.org/rfc/rfc5545
[rfc6154]: https://www.rfc-editor.org/rfc/rfc6154
[rfc6350]: https://www.rfc-editor.org/rfc/rfc6350
[rfc6352]: https://www.rfc-editor.org/rfc/rfc6352
[rfc6578]: https://www.rfc-editor.org/rfc/rfc6578
[rfc6749]: https://www.rfc-editor.org/rfc/rfc6749
[rfc6764]: https://www.rfc-editor.org/rfc/rfc6764
[rfc6851]: https://www.rfc-editor.org/rfc/rfc6851
[rfc7162]: https://www.rfc-editor.org/rfc/rfc7162
[rfc7591]: https://www.rfc-editor.org/rfc/rfc7591
[rfc7636]: https://www.rfc-editor.org/rfc/rfc7636
[rfc8414]: https://www.rfc-editor.org/rfc/rfc8414
[rfc8484]: https://www.rfc-editor.org/rfc/rfc8484
[rfc8620]: https://www.rfc-editor.org/rfc/rfc8620
[rfc8621]: https://www.rfc-editor.org/rfc/rfc8621
[rfc9610]: https://www.rfc-editor.org/rfc/rfc9610
[jmapcal]: https://datatracker.ietf.org/doc/html/draft-ietf-jmap-calendars
[pacc]: https://datatracker.ietf.org/doc/html/draft-ietf-mailmaint-pacc
[autoconfig]: https://wiki.mozilla.org/Thunderbird:Autoconfiguration
[gmail]: https://developers.google.com/gmail/api
[people]: https://developers.google.com/people
[gcal]: https://developers.google.com/calendar
[msgraph]: https://learn.microsoft.com/en-us/graph/overview

## Installation

The app needs Android 8.0 or newer.

### Google Play

Pimalaya is in beta on [Google Play](https://play.google.com/store/apps/details?id=org.pimalaya): open its page and join the beta to install it and get updates automatically.

### APK

Every [release](https://github.com/pimalaya/android/releases) ships the APKs: pimalaya-arm64-v8a.apk suits most phones, pimalaya.apk works on any phone at about four times the size. Download one on your phone and open it, allowing your browser to install apps when Android asks.

The APKs are signed with the Pimalaya certificate, whose SHA-256 fingerprint is `2F:FD:69:7B:0A:C2:88:B6:A0:A2:24:AD:E9:73:53:B3:A2:77:E4:16:9A:37:50:BA:5C:55:75:43:3E:4A:FB:4D`.

An APK update installs over the previous one, keeping your accounts and data. The Play and APK installs are signed apart, so moving from one to the other means uninstalling first.

### Development builds

Every change to the master branch builds the APKs in the [releases workflow](https://github.com/pimalaya/android/actions/workflows/releases.yml): open the latest run and download an APK from its *Artifacts* section, which needs a GitHub account. These builds carry the latest changes, and the latest bugs.

### Sources

See the [contributing guide](./CONTRIBUTING.md) to build the app yourself.

## License

This project is licensed under either of:

- [MIT license](LICENSE-MIT)
- [Apache License, Version 2.0](LICENSE-APACHE)

## Social

- Chat on [Matrix](https://matrix.to/#/#pimalaya:matrix.org)
- News on [Mastodon](https://fosstodon.org/@pimalaya) or [RSS](https://fosstodon.org/@pimalaya.rss)
- Mail at [pimalaya.org@posteo.net](mailto:pimalaya.org@posteo.net)

## Sponsoring

[![nlnet](https://nlnet.nl/logo/banner-160x60.png)](https://nlnet.nl/)

Special thanks to the [NLnet foundation](https://nlnet.nl/) and the [European Commission](https://www.ngi.eu/) that have been financially supporting the project for years:

- 2022 → 2023: [NGI Assure](https://nlnet.nl/project/Himalaya/)
- 2023 → 2024: [NGI Zero Entrust](https://nlnet.nl/project/Pimalaya/)
- 2024 → 2026: [NGI Zero Core](https://nlnet.nl/project/Pimalaya-PIM/)
- 2026 → 2027: [NGI Zero Commons Fund](https://nlnet.nl/project/Pimalaya-pimdir/)

This program is part of Pimalaya, free software funded entirely by grants and donations. If you find it useful, consider [sponsoring](https://pimalaya.org/sponsor/) its development:

[![GitHub](https://img.shields.io/badge/-GitHub%20Sponsors-fafbfc?logo=GitHub%20Sponsors)](https://github.com/sponsors/soywod)
[![Ko-fi](https://img.shields.io/badge/-Ko--fi-ff5e5a?logo=Ko-fi&logoColor=ffffff)](https://ko-fi.com/pimalaya)
[![Buy Me a Coffee](https://img.shields.io/badge/-Buy%20Me%20a%20Coffee-ffdd00?logo=Buy%20Me%20A%20Coffee&logoColor=000000)](https://www.buymeacoffee.com/pimalaya)
[![Liberapay](https://img.shields.io/badge/-Liberapay-f6c915?logo=Liberapay&logoColor=222222)](https://liberapay.com/pimalaya)
[![thanks.dev](https://img.shields.io/badge/-thanks.dev-000000?logo=data:image/svg+xml;base64,PHN2ZyB3aWR0aD0iMjQuMDk3IiBoZWlnaHQ9IjE3LjU5NyIgY2xhc3M9InctMzYgbWwtMiBsZzpteC0wIHByaW50Om14LTAgcHJpbnQ6aW52ZXJ0IiB4bWxucz0iaHR0cDovL3d3dy53My5vcmcvMjAwMC9zdmciPjxwYXRoIGQ9Ik05Ljc4MyAxNy41OTdINy4zOThjLTEuMTY4IDAtMi4wOTItLjI5Ny0yLjc3My0uODktLjY4LS41OTMtMS4wMi0xLjQ2Mi0xLjAyLTIuNjA2di0xLjM0NmMwLTEuMDE4LS4yMjctMS43NS0uNjc4LTIuMTk1LS40NTItLjQ0Ni0xLjIzMi0uNjY5LTIuMzQtLjY2OUgwVjcuNzA1aC41ODdjMS4xMDggMCAxLjg4OC0uMjIyIDIuMzQtLjY2OC40NTEtLjQ0Ni42NzctMS4xNzcuNjc3LTIuMTk1VjMuNDk2YzAtMS4xNDQuMzQtMi4wMTMgMS4wMjEtMi42MDZDNS4zMDUuMjk3IDYuMjMgMCA3LjM5OCAwaDIuMzg1djEuOTg3aC0uOTg1Yy0uMzYxIDAtLjY4OC4wMjctLjk4LjA4MmExLjcxOSAxLjcxOSAwIDAgMC0uNzM2LjMwN2MtLjIwNS4xNTYtLjM1OC4zODQtLjQ2LjY4Mi0uMTAzLjI5OC0uMTU0LjY4Mi0uMTU0IDEuMTUxVjUuMjNjMCAuODY3LS4yNDkgMS41ODYtLjc0NSAyLjE1NS0uNDk3LjU2OS0xLjE1OCAxLjAwNC0xLjk4MyAxLjMwNXYuMjE3Yy44MjUuMyAxLjQ4Ni43MzYgMS45ODMgMS4zMDUuNDk2LjU3Ljc0NSAxLjI4Ny43NDUgMi4xNTR2MS4wMjFjMCAuNDcuMDUxLjg1NC4xNTMgMS4xNTIuMTAzLjI5OC4yNTYuNTI1LjQ2MS42ODIuMTkzLjE1Ny40MzcuMjYuNzMyLjMxMi4yOTUuMDUuNjIzLjA3Ni45ODQuMDc2aC45ODVabTE0LjMxNC03LjcwNmgtLjU4OGMtMS4xMDggMC0xLjg4OC4yMjMtMi4zNC42NjktLjQ1LjQ0NS0uNjc3IDEuMTc3LS42NzcgMi4xOTVWMTQuMWMwIDEuMTQ0LS4zNCAyLjAxMy0xLjAyIDIuNjA2LS42OC41OTMtMS42MDUuODktMi43NzQuODloLTIuMzg0di0xLjk4OGguOTg0Yy4zNjIgMCAuNjg4LS4wMjcuOTgtLjA4LjI5Mi0uMDU1LjUzOC0uMTU3LjczNy0uMzA4LjIwNC0uMTU3LjM1OC0uMzg0LjQ2LS42ODIuMTAzLS4yOTguMTU0LS42ODIuMTU0LTEuMTUydi0xLjAyYzAtLjg2OC4yNDgtMS41ODYuNzQ1LTIuMTU1LjQ5Ny0uNTcgMS4xNTgtMS4wMDQgMS45ODMtMS4zMDV2LS4yMTdjLS44MjUtLjMwMS0xLjQ4Ni0uNzM2LTEuOTgzLTEuMzA1LS40OTctLjU3LS43NDUtMS4yODgtLjc0NS0yLjE1NXYtMS4wMmMwLS40Ny0uMDUxLS44NTQtLjE1NC0xLjE1Mi0uMTAyLS4yOTgtLjI1Ni0uNTI2LS40Ni0uNjgyYTEuNzE5IDEuNzE5IDAgMCAwLS43MzctLjMwNyA1LjM5NSA1LjM5NSAwIDAgMC0uOTgtLjA4MmgtLjk4NFYwaDIuMzg0YzEuMTY5IDAgMi4wOTMuMjk3IDIuNzc0Ljg5LjY4LjU5MyAxLjAyIDEuNDYyIDEuMDIgMi42MDZ2MS4zNDZjMCAxLjAxOC4yMjYgMS43NS42NzggMi4xOTUuNDUxLjQ0NiAxLjIzMS42NjggMi4zNC42NjhoLjU4N3oiIGZpbGw9IiNmZmYiLz48L3N2Zz4=)](https://thanks.dev/u/gh/soywod)
[![PayPal](https://img.shields.io/badge/-PayPal-0079c1?logo=PayPal&logoColor=ffffff)](https://www.paypal.com/paypalme/soywod)
