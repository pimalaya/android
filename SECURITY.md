# Security policy

## Supported versions

Pimalaya for Android is in beta; only the latest release, and the master branch it is built from, receive security fixes.

| Version | Supported |
|---------|-----------|
| Latest release | :white_check_mark: |
| Older releases | :x: |

## Reporting a vulnerability

Report suspected vulnerabilities privately by email to pimalaya.org@posteo.net rather than opening a public issue. Include the affected version or commit, the impact, and a reproduction when you have one; you can expect an acknowledgement within a few days and a coordinated disclosure once a fix is ready.

Pimalaya stores account credentials and OAuth tokens encrypted with a key held by the Android Keystore and talks to your servers directly over TLS, so reports about credential handling, the sync bridge or the transport layer are especially welcome.
