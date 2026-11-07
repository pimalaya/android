---
cairn: change
id: network-warnings
status: active
created: 2026-11-07
---

# Weak network settings warned about, not refused

## Why

The S2 network-trust audit's low findings left open by network-trust-hardening (L2, L6, L8, the public-suffix rule) were planned as refusals. The owner decided on 2026-11-07 to inform rather than restrict: some people run old servers, TLS 1.0 only for one, and a refusal locks them out of their own mail. So the app warns, plainly, and lets them go on.

## What

- **One warning dialog**, large, at setup and at sign-in-again, before anything is sent to the server: what is weak, in plain words, the server it concerns, and Connect anyway or Cancel. The choice is kept per server and shown in the account's Servers card.
- **L6, old TLS.** No floor is added: a server negotiating TLS 1.0 or 1.1 raises the warning. First, measure what the phone's own TLS stack still offers: every connection goes through the platform's sockets (client/Transport.java), and a version a newer Android removed cannot be offered back by a warning; the measure says which Android versions can still reach such a server.
- **L2, discovery leaving the web.** A discovery redirect to a private or local address (localhost, 10/8, 172.16/12, 192.168/16, link-local) raises the warning, naming the target, before the app connects there. A redirect to a non-network scheme stays refused, having no use.
- **Public suffix.** A calendar or contacts href the server names on another site than its own, refused today (`trust::trusted_href`), raises the warning naming both hosts. The site is read with a public-suffix list, so sites under `co.uk` and the like no longer read as one site with every other host there.
- **L8, documented.** spec/network-trust.md says what discovery trusts: DNS-over-HTTPS answers are not DNSSEC-checked, and a PACC document's digest is as trustworthy as that DNS.
- **After setup.** A background sync cannot ask. A server that negotiates weaker than what was accepted for it (a downgrade, which an attacker on the path can cause) is not synced, and its account card says why, offering the dialog again.

## Kept

A password or token is never sent in clear, without TLS at all (network-trust spec): a warning cannot make it unreadable to whoever is on the path. Confirmed by the owner on 2026-11-07, for now.
