---
cairn: change
id: mx-autoconfig
status: landed
created: 2026-10-16
---

# Discover a domain through its MX provider

## Why

`test@migadu.net` found no mail server. The domain publishes only an MX record, at `flatmail.com`, whose `autoconfig.flatmail.com` aliases `autoconfig.migadu.com` under a certificate for another name. Thunderbird finds it through its MX fallback; the app's autoconfig search stopped at the domain's own locations. It also asked for `emailaddress=test@migadu.net@migadu.net`, passing the whole address where the local part was expected.

## What

`search_autoconfig` takes io-pim-discovery's new bricks (its own `mx-autoconfig` change): the alias of `autoconfig.<domain>` is followed, and a domain with no autoconfig of its own asks the provider its MX records name, its ISP URL, the alias of that URL, then its ISPDB entry. https only, as before. io-pim-discovery is pinned to its git commit until it is released.
