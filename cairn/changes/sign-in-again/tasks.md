---
cairn: tasks
change: sign-in-again
---

# Tasks

- [x] Design on the canvas (version 115), light and dark, copied in designs/: the *Sign-in* card's row, the drawer card refused, the domains page on an account, the server-change confirmation, the refusal of another account
- [x] The sync records an account refused for its sign-in (a 401 past the renewal, `invalid_grant`, an IMAP or SMTP authentication failure) and clears it on a sync that signs in; the drawer card and the report say it
- [x] The domains page opened on an account: stored settings, covered domains locked, *Current* in the settings menu, discovery behind
- [x] Identity checked after a browser grant (oauth-hardening L4)
- [x] Credentials replaced in place keeping the store; a server, protocol or login change confirmed, that domain dropped and listed again; the sending server changed in place
- [x] Tests (SignInAgainTest, WrongGrantTest, SignInRefusalsTest, rust account::identity): a password replaced keeps the outbox and staged edits; a grant for another address refused; a server change drops and lists that domain alone
- [ ] Device check; fold, log, CHANGELOG
- [ ] Aliases accepted by the identity check (proposal, Open)
