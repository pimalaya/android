---
cairn: delta
change: network-warnings
---

# Delta

Folds into spec/network-trust.md.

## ADDED Requirements

### Requirement: A weak server is warned about, not refused
A server negotiating TLS older than 1.2, a discovery redirect to a private or local address, or an href the server names on another site SHALL raise one warning before anything is sent there, naming the server and what is weak, with Connect anyway and Cancel. The choice SHALL be kept per server. A server negotiating weaker than what was accepted for it SHALL not be synced, and its account SHALL say why.

#### Scenario: A TLS 1.0 server
- GIVEN the setup of an account whose mail server speaks TLS 1.0 alone
- WHEN Continue signs in
- THEN the warning names the server and its old encryption, and Connect anyway connects

## MODIFIED Requirements

## REMOVED Requirements
