---
cairn: delta
change: advanced-onboarding-pages
---

## ADDED Requirements

### Requirement: The advanced setup signs in on one page
The advanced setup SHALL gather every sign-in its selection needs on one page, one card per server and login, and SHALL NOT open a dialog per sign-in. A card SHALL ask what its method needs: a login and a password, an API token, a server with a login and a password for manual entry, or a browser grant. Domains sharing a server and a login SHALL share a card. A password card after the first SHALL offer to reuse the first card's login and password. Connecting SHALL run every card and report each one's outcome on its card, keeping the cards that succeeded.

#### Scenario: Two servers, two passwords
- GIVEN an advanced selection reading mail over IMAP and contacts over CardDAV, on two servers
- WHEN the sign-in page opens
- THEN it shows one card per server, each with a login and a password
- AND no dialog opens

#### Scenario: Reusing a password
- GIVEN the same page
- WHEN the second card's reuse box is ticked
- THEN its fields hide and it signs in with the first card's login and password

#### Scenario: One card refused
- GIVEN the same page and a password the contacts server refuses
- WHEN Connect is tapped
- THEN the contacts card says the password was not accepted
- AND mail stays connected

#### Scenario: Manual entry
- GIVEN mail set to manual entry
- WHEN the sign-in page opens
- THEN mail's card asks for a server, a login and a password

### Requirement: An OAuth client is entered on a page
When a server registers no client on its own, or the user asks to use their own, the advanced setup SHALL ask for the client on a page of its own (client ID, optional secret, authorization and token endpoints, scope, redirect URI, the discovered ones prefilled), and SHALL return to the sign-in page with nothing lost.

#### Scenario: A server without dynamic registration
- GIVEN a browser card whose server refuses dynamic client registration
- WHEN its sign-in starts
- THEN the OAuth client page opens with the endpoints prefilled and the client ID empty

## MODIFIED Requirements

## REMOVED Requirements
