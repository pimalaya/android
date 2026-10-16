---
cairn: log
change: mx-autoconfig
landed: 2026-10-16
---

# A domain with no autoconfig is asked of its MX provider

Capabilities moved: onboarding (ADDED A domain with no autoconfig is asked of its MX provider).

`test@migadu.net` found nothing: the phone's log showed the six https probes on `migadu.net` failing and nothing more, the domain publishing only an MX record at `flatmail.com`. The fix is io-pim-discovery's (`mx-autoconfig`, same day): `mx_base_domain`, `DiscoveryDnsCname` and `DiscoveryIsp::host_url`. `search_autoconfig` now follows the alias of `autoconfig.<domain>`, then, with nothing found, asks each MX provider's ISP URL, its alias and its ISPDB entry, tagged `Mx`. The request names the address once; it carried `test@migadu.net@migadu.net`. io-pim-discovery is pinned to 6ceeaf4 by `[patch.crates-io]` until its release.

Verified: cargo test (505), clippy and fmt on rust/, `:app:assembleDebug`, `:app:testDebugUnitTest` and `:client:testDebugUnitTest`. The library's CLI answers `test@migadu.net` live with `imap.migadu.com` and `smtp.migadu.com`. Not run on a device.
