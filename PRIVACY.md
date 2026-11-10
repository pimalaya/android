# Privacy Policy

Last updated: 10 November 2026

This privacy policy describes how the **Pimalaya** Android app (package `org.pimalaya`, the "app") handles your data. Pimalaya is a free and open-source mail, contacts and calendar app published by the Pimalaya project. Its source code is available at https://github.com/pimalaya/android. The [Pimalaya privacy policy](https://pimalaya.org/privacy/) covers every Pimalaya app; this one details the Android app.

## Summary

Pimalaya does not collect, sell, or share your personal data with us or with any third party. It is a client that syncs your mail, contacts and calendars between your device and the servers **you** choose to connect to. Everything the app stores stays on your device, except the data it exchanges with those servers on your behalf and what you choose to show in your device's own Contacts and Calendar apps.

## The data the app handles

- **Mail.** The app stores the headers of your messages (sender, subject, date, read state and other marks, and the mailbox each is filed in) so it can list them. It downloads and stores whole messages, attachments included, as the server sent them: the newest messages of the mailboxes your filter shows, more each time you ask for more, in the background on Wi-Fi, every message of a mailbox you choose to keep whole in its settings, and any message you open. The messages of the trash and the junk are downloaded only when you open them. The messages you write are composed and kept on the device until they are sent. Opening a message loads no remote images or other content.
- **Contacts.** The app stores the full contact data (names, phone numbers, email addresses, postal addresses, organizations, notes, photos, and the other fields of a vCard) of the addressbooks you connect, and of its on-device addressbook, which lives only on your device.
- **Calendars.** The app stores your calendar entries in full, as your server holds them: title, times and time zones, location, description, recurrence, attendees, reminders, and every other property of the entry.
- **Deleted items.** When a message, contact or calendar entry is deleted, the app keeps it, with its content when it was stored, so you can restore it from Deleted items. It is kept until you free that space in Deleted items, or remove the account.
- **Account credentials.** To connect to a server the app stores the credentials you provide: a password, an API token, or the OAuth 2.0 tokens obtained when you sign in through your provider. These are encrypted with a key held by the Android Keystore and are sent only to the server you configured, or to that provider's own identity endpoint when refreshing a token.
- **Server configuration.** The addresses, usernames, and protocol settings of the accounts you add are stored on your device so the app can reconnect.

All of this is kept in the app's private on-device storage.

## On your device, outside the app

- **Contacts app.** When the option "Show in phone contacts" is on (off by default when you add an account, and changeable per addressbook in the account's settings), each addressbook is mirrored into your device's system Contacts under a device account of its own, in both directions. Other apps you allow to read your contacts can then see them. An address the device already syncs through another app (such as Google's own sync) is not mirrored unless you turn it on.
- **Calendar app.** When the option "Show in phone calendar" is on (off by default when you add an account, and per calendar in the account's settings), the chosen calendars are mirrored into your device's system Calendar under a device account named by the address, in both directions, reminders included. Other apps you allow to read your calendars can then see them, and your calendar app may show the reminders.
- **Notifications.** When new-mail notifications are on for an account (off by default, turned on when you add it or in its settings, which also turns background sync on), the app shows a notification for each new message in its inbox, with the sender and the subject. Android's notification settings control whether they show on the lock screen.

Mail is never written anywhere else on the device.

## Background activity

Each account syncs in the background at the interval its settings pick (every 15 minutes by default, or off), as Android schedules it, its mail limited to the mailboxes your filter shows. A background sync contacts only that account's servers, exactly as a sync you start in the app does.

## What we do not do

- We do not operate any server that receives your data; the app talks only to the providers you configure and the services listed below.
- We do not collect analytics, usage statistics, or crash reports.
- The app contains no advertising, no trackers, and no third-party analytics or advertising SDKs.
- We have no access to your mail, your contacts, your calendars, your credentials, or any other data the app holds.

## Network connections and third parties

The app makes network connections only to:

- **The servers you configure** (IMAP and SMTP, JMAP, CardDAV, CalDAV, the Gmail, Google People, Google Calendar and Google Tasks APIs, or Microsoft Graph), to sync your mail, contacts, calendars and tasks, to send the messages you write, and to authenticate. Data you send to those servers is governed by their own privacy policies.
- **Identity providers**, when you sign in with OAuth 2.0 (for example Google or Microsoft, or your server's own), to obtain and refresh access tokens, and, for a server that supports it, to discover its sign-in settings and register the app with it.
- **A public DNS-over-HTTPS resolver**, when you add an account, to locate your provider's servers. The app sends the domain part of your address (for example `example.com`) to Cloudflare's resolver at `https://cloudflare-dns.com/dns-query`. This step is subject to Cloudflare's privacy policy.
- **Server autoconfiguration**, when you add an account: your full address to your provider's own autoconfiguration endpoint (`autoconfig.<your domain>`), and the domain part alone to the Thunderbird autoconfiguration database at `autoconfig.thunderbird.net`, subject to its operator's privacy policy, and the standard discovery locations of your domain.

## Google user data

This section covers the data the app receives from Google's APIs (Gmail, Google Calendar, Google Tasks and Google People) when you connect a Google account. Before Google's sign-in page opens, the app shows what it will access and how it uses it, and opens that page only once you agree.

### Data accessed

For the parts of the account you choose to connect, the app accesses:

- **Gmail**: your messages, labels and sending addresses;
- **Google Calendar**: your list of calendars and the events in them;
- **Google Tasks**: your task lists and their tasks;
- **Google Contacts**: your contacts and their labels;
- **your account's address**, to check that you signed in with the account you entered.

The app derives no aggregated or anonymized data from it.

### How the data is used

Only to provide the app's features to you on your device: to read, search, send, mark, move and delete your mail; to read, create, change and delete events, and to invite the guests you add; to read, create, complete, change and delete tasks; to read, create, change and delete contacts and their labels; and to keep the app's copy in sync with your account. It is never used for advertising, never used to develop, improve or train any artificial intelligence or machine learning model, and never read by anyone but you.

### Sharing and transfer

The app shares Google user data with no one. It sends it nowhere but back to Google, to carry out your actions, and never to us, to advertisers, to data brokers, to artificial intelligence services or to any other third party. It is never sold. It reaches other apps on your device only through the device's Contacts and Calendar apps, when you turn the mirrors described above on.

### Protection

- The Pimalaya project runs no server: no copy of your Google data exists outside your device and Google.
- Every connection to Google is encrypted with TLS.
- You sign in on Google's own page, so the app never sees your Google password.
- The data stays in the app's private storage, which Android keeps from other apps, and is excluded from Android's cloud backups and device-to-device transfers. Your OAuth tokens are encrypted with a key held by the Android Keystore.

### Retention and deletion

The app keeps Google user data only while the account exists in the app; "Data retention and deletion" below says what removing the account or uninstalling the app deletes. You can revoke the app's access to your Google account at any time at https://myaccount.google.com/permissions, after which the app can no longer reach it.

### Limited Use

Pimalaya's use and transfer to any other app of information received from Google APIs will adhere to the [Google API Services User Data Policy](https://developers.google.com/terms/api-services-user-data-policy), including the Limited Use requirements.

## Permissions and why the app needs them

- **Contacts (`READ_CONTACTS`, `WRITE_CONTACTS`)**: to mirror your addressbooks into the device's system Contacts, in both directions, and to check whether an address is already there through another app. Asked only when you turn the option on.
- **Calendar (`READ_CALENDAR`, `WRITE_CALENDAR`)**: to mirror your calendars into the device's system Calendar, in both directions, and to check whether an address is already there through another app. Asked only when you turn the option on.
- **Sync settings (`READ_SYNC_SETTINGS`, `WRITE_SYNC_SETTINGS`)**: to register the device accounts of the mirrors so contacts and calendar apps list them, and to be told when you edit them there.
- **Notifications (`POST_NOTIFICATIONS`)**: to notify new mail, asked only when you turn notifications on.
- **Run at startup (`RECEIVE_BOOT_COMPLETED`)**: to keep the background sync scheduled after the device restarts.
- **Network access (`INTERNET`, `ACCESS_NETWORK_STATE`)**: to reach the servers you configure and to read the active network's settings for server discovery.

## Data retention and deletion

The app stores your data only for as long as the corresponding account exists on your device. You are in control:

- Removing an account inside the app deletes its stored credentials, its mail, including messages not sent yet, its calendar entries, and its device accounts, which removes its copies from the device's Contacts and Calendar apps. Its contacts are not deleted: they move into the app's on-device addressbook, where you can delete them.
- Freeing space in Deleted items removes the deleted messages, contacts and entries kept there for good.
- Uninstalling the app removes all data it stored on your device, and Android removes its device accounts with their copies in the Contacts and Calendar apps. On Android 10 and later the uninstall dialog offers to keep the app's data for a later reinstall: if you choose to keep it, it stays on the device until you uninstall again without keeping it, or clear the app's storage.

Data already synced to a remote server is not removed by any of these; delete it on the server if you want it gone.

## Children

The app is not directed at children and does not knowingly collect data from anyone. It has no data collection of its own.

## Changes to this policy

We may update this policy from time to time. Changes are published to this file in the app's public repository, and the "Last updated" date above reflects the latest revision.

## Contact

For any question about this policy or the app's handling of data, reach the Pimalaya project:

- Chat on [Matrix](https://matrix.to/#/#pimalaya:matrix.org)
- News on [Mastodon](https://fosstodon.org/@pimalaya) or [RSS](https://fosstodon.org/@pimalaya.rss)
- Mail at [pimalaya.org@posteo.net](mailto:pimalaya.org@posteo.net)
