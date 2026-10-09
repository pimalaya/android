---
cairn: log
change: tls-hostname-check
landed: 2026-10-09
---

# TLS checks the certificate names the host

Capabilities moved: none in the spec's words (a connection to a server was always meant to be authenticated); the transport now does what that assumed.

`Transport` opened TLS with `SSLSocketFactory.createSocket(socket, host, port, true)` and a handshake, which on Android validates the chain but checks no hostname, so a certificate any trusted CA issued for another domain passed, on HTTPS, IMAPS, SMTPS and both STARTTLS upgrades alike. Both paths now go through `Transport.tls`, which checks the session against the host with the platform's HTTPS verifier (`HttpsURLConnection.getDefaultHostnameVerifier`) after the handshake and closes the socket with an `SSLPeerUnverifiedException` when it does not match. Found by a read-only production review on 2026-10-09.
