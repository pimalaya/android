---
cairn: delta
change: onboarding-polish
---

## ADDED Requirements

### Requirement: The setup mode decides what the domain screen asks
The connection flow SHALL ask, once the address is entered, whether to set the account up the standard way or step by step, and the answer SHALL decide what the domain screen offers. The standard setup SHALL offer one switch per domain and no configuration; the advanced setup SHALL offer, under each switch, one option per discovered service and authentication method plus manual entry.

#### Scenario: The standard setup
- GIVEN an address whose services are discovered
- WHEN the standard setup is chosen
- THEN each domain is one switch carrying its name and glyph
- AND nothing on the screen names a protocol or an authentication method

#### Scenario: The advanced setup
- GIVEN the same address
- WHEN the advanced setup is chosen
- THEN each switched-on domain lists its configurations, manual entry among them

### Requirement: The standard setup connects with a password alone
The standard setup SHALL connect a domain with the best-ranked discovered configuration that signs in with a login and a password, and SHALL NOT connect one any other way. A domain offering no password sign-in SHALL be shown switched off and unswitchable, naming the setup that can connect it. An address offering none for any domain SHALL send the flow to the advanced setup rather than to an empty screen.

#### Scenario: A password sign-in exists
- GIVEN an address whose mail is discovered with a password sign-in
- WHEN the standard setup switches mail on
- THEN the credential prompt asks for a login and a password under a plain sign-in title

#### Scenario: One domain is out of reach
- GIVEN an address whose contacts are only offered behind a browser grant
- WHEN the standard setup is shown
- THEN the contacts switch is off, unswitchable, and says the advanced setup can connect it

#### Scenario: Nothing is in reach
- GIVEN an address offering no password sign-in at all
- WHEN the standard setup is shown
- THEN the flow offers the advanced setup instead

### Requirement: A connected account is synchronised before the app shows it
Finishing the connection flow SHALL synchronise every domain the account covers in one pass, behind the flow's own loader, and SHALL land on the list of the first domain it covers. The user SHALL NOT have to refresh a domain to see what was just connected.

#### Scenario: An account covering three domains
- GIVEN a connection flow that connected mail, contacts and calendars
- WHEN it finishes
- THEN all three are fetched in one pass
- AND the app lands on the mail list holding the messages that pass fetched

#### Scenario: One domain fails
- GIVEN a pass whose calendar listing fails
- WHEN it finishes
- THEN the domains that succeeded are shown, and the failure is reported once
