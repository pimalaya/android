---
cairn: log
change: advanced-onboarding-pages
date: 2026-11-04
---

# The advanced setup as pages, one sign-in per server

Landed in code on 2026-10-09 (b88690b) and used on the device since; folded on 2026-11-04, before one-onboarding-path makes its sign-in page everyone's.

Capabilities moved: onboarding (added: the advanced setup signs in on one page, an OAuth client is entered on a page).

The advanced domain step is one card per domain with two-line radio rows, manual entry last, mail's card carrying its sending rows. Its sign-ins run on one page, one card per server and login (`planAuthSteps` grouping password steps by server and login), a password card after the first offering the first one's credentials, every card connected at once and its outcome reported on it. The OAuth client is entered on a page of its own rather than a dialog, and the addressbooks step is one card of checkbox rows.
