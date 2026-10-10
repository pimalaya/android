# Privacy Policy

Last updated: 9 October 2026

This privacy policy describes how the **Pimalaya** Android app (package `org.pimalaya`, the "app") handles your data. Pimalaya is a free and open-source mail, contacts and calendar app published by the Pimalaya project. Its source code is available at https://github.com/pimalaya/android.

## Summary

Pimalaya does not collect, sell, or share your personal data with us or with any third party. It is a client that syncs your mail, contacts and calendars between your device and the servers **you** choose to connect to. Everything the app stores stays on your device, except the data it exchanges with those servers on your behalf and what you choose to show in your device's own Contacts and Calendar apps.

## The data the app handles

- **Mail.** The app stores the headers of your messages (sender, subject, date, read state and other marks, and the mailbox each is filed in) so it can list them. It downloads and stores a whole message, attachments included, as the server sent it, when you open it, and also in the background or for whole mailboxes when you choose so in the account's settings. The messages you write are composed and kept on the device until they are sent. Opening a message loads no remote images or other content.
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

Each account syncs in the background at the interval its settings pick (every 15 minutes by default, or off), as Android schedules it. A background sync contacts only that account's servers, exactly as a sync you start in the app does.

## What we do not do

- We do not operate any server that receives your data; the app talks only to the providers you configure and the services listed below.
- We do not collect analytics, usage statistics, or crash reports.
- The app contains no advertising, no trackers, and no third-party analytics or advertising SDKs.
- We have no access to your mail, your contacts, your calendars, your credentials, or any other data the app holds.

## Network connections and third parties

The app makes network connections only to:

- **The servers you configure** (IMAP and SMTP, JMAP, CardDAV, CalDAV, the Gmail, Google People and Google Calendar APIs, or Microsoft Graph), to sync your mail, contacts and calendars, to send the messages you write, and to authenticate. Data you send to those servers is governed by their own privacy policies.
- **Identity providers**, when you sign in with OAuth 2.0 (for example Google or Microsoft, or your server's own), to obtain and refresh access tokens, and, for a server that supports it, to discover its sign-in settings and register the app with it.
- **A public DNS-over-HTTPS resolver**, when you add an account, to locate your provider's servers. The app sends the domain part of your address (for example `example.com`) to Cloudflare's resolver at `https://cloudflare-dns.com/dns-query`. This step is subject to Cloudflare's privacy policy.
- **Server autoconfiguration**, when you add an account: your full address to your provider's own autoconfiguration endpoint (`autoconfig.<your domain>`), and the domain part alone to the Thunderbird autoconfiguration database at `autoconfig.thunderbird.net`, subject to its operator's privacy policy, and the standard discovery locations of your domain.

## Google user data

When you connect a Google account, the app accesses your Gmail messages (to read, send, mark, move and delete them), your Google contacts and your Google calendars, only to provide those features to you on your device. This data is stored on your device only, and is sent nowhere but back to Google; no server of Pimalaya receives it. It is never used for advertising, never sold, and never read by a person. It reaches other apps only through the device's Contacts and Calendar apps, when you turn the mirrors described above on.

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
- Uninstalling the app removes all data it stored on your device, and Android removes its device accounts with their copies in the Contacts and Calendar apps.

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
