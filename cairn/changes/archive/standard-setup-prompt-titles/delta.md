---
cairn: delta
change: standard-setup-prompt-titles
---

## MODIFIED Requirements

### Requirement: The standard setup connects with a password alone
The standard setup SHALL connect a domain with the best-ranked discovered configuration that signs in with a login and a password, and SHALL NOT connect one any other way. A domain offering no password sign-in SHALL be shown switched off and unswitchable, naming the setup that can connect it. An address offering none for any domain SHALL send the flow to the advanced setup rather than to an empty screen. Every credential prompt it opens SHALL name the domains it signs in for, and SHALL say how far along the sequence is whenever that sequence has more than one step.

#### Scenario: A password sign-in exists
- GIVEN an address whose mail is discovered with a password sign-in
- WHEN the standard setup switches mail on
- THEN the credential prompt asks for a login and a password
- AND names mail in its title

#### Scenario: Several domains, several servers
- GIVEN a standard setup connecting three domains at three servers
- WHEN the prompts run
- THEN each names its own domain and its place in the sequence
- AND none of them names a protocol or an authentication method

#### Scenario: One domain is out of reach
- GIVEN an address whose contacts are only offered behind a browser grant
- WHEN the standard setup is shown
- THEN the contacts switch is off, unswitchable, and says the advanced setup can connect it

#### Scenario: Nothing is in reach
- GIVEN an address offering no password sign-in at all
- WHEN the standard setup is shown
- THEN the flow offers the advanced setup instead
