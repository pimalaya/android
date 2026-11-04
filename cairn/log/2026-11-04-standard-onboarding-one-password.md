---
cairn: log
change: standard-onboarding-one-password
date: 2026-11-04
---

# The standard setup asks for one password

Landed in code on 2026-10-09 (6ee05a4, 1c2918e) and used on the device since; folded on 2026-11-04, before one-onboarding-path replaces the setup mode.

Capabilities moved: onboarding (added: the standard setup asks for one password; modified: the setup mode decides what the domain screen asks, the standard setup connects with the best sign-in found, the setup names sending in the words of the setup it is in).

The flow starts in the standard setup, the advanced one a link away on the address screen and beside the password field. When every connected domain signs in with a password, the domain screen carries one password field, the login being the address, tried against every password domain and reported per domain; a refused domain can be left out or set up in the advanced setup. The standard setup connects the discovered submission endpoint with mail and asks nothing about sending. The delta stated the domains as ticked by default; onboarding-options-ask-on-switch, landed the next day, opens them unticked, which the folded text says.
