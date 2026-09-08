<div align="center">
  <img src="./logo.svg" alt="Logo" width="128" height="128" />
  <h1>🗂️ Pimalaya Android</h1>
  <p>Android app to manage your personal information</p>
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
> Pimalaya for Android is early-stage software under active development: it is not yet published on any store, and everything is subject to change. See [docs/design.md](./docs/design.md) for the design and the [docs](./docs) folder for the living documentation.

## Table of contents

- [Features](#features)
- [Coverage](#coverage)
- [Installation](#installation)
- [AI policy](https://github.com/pimalaya/.github/blob/master/AI_POLICY.md)
- [License](#license)
- [Social](#social)
- [Contributing](./CONTRIBUTING.md)
- [Sponsoring](#sponsoring)

## Features

- **Three domains, one app**: mail, contacts and calendars behind three icons in the top bar, over one store and one account list.
- **Merged views**: each domain is a single list across every account and every collection, filterable on both axes, rather than a mailbox or an addressbook at a time.
- **Multiple accounts and backends**: keep CardDAV, JMAP, Microsoft and Google addressbooks side by side in a single app.
- **Offline first**: every contact is stored locally and rendered instantly, with edits pushed on the next sync.
- **Incremental sync**: each pass transfers only what changed, on every backend.
- **Two-way phone sync**: mirror an addressbook into Android's own contacts, so any contacts app can read and edit it.
- **Background sync**: keep each addressbook current on a schedule, from every fifteen minutes to once a day.
- **Conservative conflict handling**: a three-way merge keeps both sides of a genuine clash for you to resolve by hand.
- **Automatic setup**: type an email address or a bare domain and the server settings are discovered for you.
- **Flexible authentication**: password, API token or OAuth 2.0, with shipped Google and Microsoft sign-in or your own; credentials are encrypted by the Android Keystore.
- **Full vCard editor**: a friendly form, an advanced per-property editor and a free-hand source editor.
- **Merged contact view**: one deduplicated list over every account, with search, import, export and a duplicate remover.
- **Mail you can answer**: open a message, mark it read or important, delete it into the account's trash, and write a new one over SMTP with the copy filed in your sent mailbox.
- **Calendar entries you can write**: create, edit and delete, each pushed guarded by a precondition so a shared calendar is never overwritten blind.

Contacts is the mature domain. Mail and calendar are behind it: mail has no attachments, no reply and no drafts, calendar has no recurrence editing, and both sync by relisting rather than incrementally.

## Coverage

| Standard | What is covered |
|----------|-----------------|
| [vCard 4.0][rfc6350] | The contact data model: a full-fidelity local store and a complete property and parameter editor |
| [CardDAV][rfc6352] | Contact synchronization against any standards-compliant server |
| [CardDAV service discovery][rfc6764] and [PACC][pacc] | Locating the addressbook home from an email address or a bare domain |
| [JMAP][rfc8620] and [JMAP for Contacts][rfc9610] | Contact synchronization against JMAP servers such as Fastmail |
| [OAuth 2.0][rfc6749] and [dynamic client registration][rfc7591] | Signing in without a provider console, next to the shipped Google and Microsoft clients |
| [Microsoft Graph][msgraph] | Contact synchronization against Outlook and Microsoft 365 |
| [Google People API][people] | Contact synchronization against Google accounts |
| [IMAP4rev1][rfc3501] | Mail: the mailbox list, the envelope spine of each merged across accounts, one message read whole, and the markers, moves and appends a reader writes back |
| [Special-use mailboxes][rfc6154] | Which mailbox is the trash and which is the sent one, asked of the server rather than guessed from its name |
| [Internet Message Format][rfc5322], [MIME][rfc2045] and [encoded words][rfc2047] | Composing a message: header folding, a subject or a name outside US-ASCII, and a quoted-printable body |
| [SMTP][rfc5321] and [SMTP authentication][rfc4954] | Handing a composed message over to the account's submission server |
| [iCalendar][rfc5545] | The calendar data model, recurrence rules included: a rule is expanded into the occurrences an agenda window shows |
| [CalDAV][rfc4791] | Calendar synchronization against any standards-compliant server |

[rfc2045]: https://www.rfc-editor.org/rfc/rfc2045
[rfc2047]: https://www.rfc-editor.org/rfc/rfc2047
[rfc3501]: https://www.rfc-editor.org/rfc/rfc3501
[rfc4954]: https://www.rfc-editor.org/rfc/rfc4954
[rfc4791]: https://www.rfc-editor.org/rfc/rfc4791
[rfc5321]: https://www.rfc-editor.org/rfc/rfc5321
[rfc5322]: https://www.rfc-editor.org/rfc/rfc5322
[rfc5545]: https://www.rfc-editor.org/rfc/rfc5545
[rfc6154]: https://www.rfc-editor.org/rfc/rfc6154
[rfc6350]: https://www.rfc-editor.org/rfc/rfc6350
[rfc6352]: https://www.rfc-editor.org/rfc/rfc6352
[rfc6749]: https://www.rfc-editor.org/rfc/rfc6749
[rfc6764]: https://www.rfc-editor.org/rfc/rfc6764
[rfc7591]: https://www.rfc-editor.org/rfc/rfc7591
[rfc8620]: https://www.rfc-editor.org/rfc/rfc8620
[rfc9610]: https://www.rfc-editor.org/rfc/rfc9610
[pacc]: https://datatracker.ietf.org/doc/html/draft-ietf-mailmaint-pacc
[msgraph]: https://learn.microsoft.com/en-us/graph/api/resources/contact
[people]: https://developers.google.com/people

## Installation

Pimalaya for Android is not yet published, therefore the only way to install the app is to check out the [releases](https://github.com/pimalaya/android/actions/workflows/releases.yml) GitHub workflow, look for the *Artifacts* section, download the APK and manually install it.

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
