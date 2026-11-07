---
cairn: tasks
change: network-warnings
---

# Tasks

- [ ] Measure: a TLS 1.0-only and a TLS 1.1-only test server reached from Android 8, 10, 14 and 16 through Transport.java, with and without the versions enabled on the socket
- [ ] The warning dialog designed on the canvas, light and dark, then built: what is weak, the server, Connect anyway or Cancel; the choice kept per server, shown in the Servers card
- [ ] L6 TLS 1.0 and 1.1 raise the warning; a server negotiating weaker than accepted is not synced, its account card offering the dialog
- [ ] L2 redirects to private or local addresses raise the warning; non-network schemes stay refused
- [ ] Public suffix: a list in the bridge (its update path written down), `trusted_href` asking rather than refusing another site
- [ ] L8 written into spec/network-trust.md
- [ ] Tests for each rule; fold, log, CHANGELOG
- [ ] network-trust-hardening's Open list pointed here
