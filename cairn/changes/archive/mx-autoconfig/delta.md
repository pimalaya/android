---
cairn: delta
change: mx-autoconfig
---

# Delta

## ADDED Requirements

### Requirement: A domain with no autoconfig is asked of its MX provider
When the address's domain publishes no autoconfig, the protocol sweep SHALL ask the provider its MX records name (the registrable domain of each MX host, never a public suffix) for its autoconfig, and SHALL follow an `autoconfig.<domain>` alias to its target when the alias itself fails, over https only.

#### Scenario: A Migadu-hosted domain
- GIVEN `test@migadu.net`, whose only record is an MX at `flatmail.com`
- WHEN discovery runs
- THEN mail (IMAP, `imap.migadu.com:993`) and sending (SMTP, `smtp.migadu.com:465`) are offered

## MODIFIED Requirements

None.

## REMOVED Requirements

None.
