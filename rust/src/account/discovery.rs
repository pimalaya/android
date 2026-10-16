//! # Discovery
//!
//! Account onboarding on the [`Client`]: RFC 6764 DAV discovery, provider
//! and protocol search over io-pim-discovery, and the CardDAV walk to the
//! principal and its addressbook home set.

use core::error::Error as StdError;

use io_pim_discovery::{
    autoconfig::{
        cname::DiscoveryDnsCname,
        isp::DiscoveryIsp,
        mailconf::DiscoveryMailconf,
        mx::{DiscoveryDnsMx, mx_base_domain},
    },
    compose::{
        config::{DiscoveryConfigSource, DiscoveryService, DiscoveryServiceConfig},
        providers::DiscoveryKnownProvider,
    },
    coroutine::{DiscoveryCoroutine, DiscoveryCoroutineState, DiscoveryYield},
    pacc::discover::DiscoveryPacc,
    rfc6764::{resolve::DiscoveryDavResolve, service::DiscoveryDavService},
    rfc8620::resolve::DiscoveryJmapResolve,
    rfc9110::DiscoveryProbeAuth,
};
use io_webdav::{
    rfc4918::WebdavAuth, rfc5397::current_user_principal::WebdavCurrentUserPrincipal,
    rfc6352::addressbook::home_set::CarddavAddressbookHomeSet,
};
use url::Url;

use crate::{
    client::{Client, Host, USER_AGENT, trust::trusted_href},
    error::{BridgeError, coroutine_error},
};

/// RFC 8484 DoH resolver for discovery lookups when the caller passed none.
///
/// DoH rides the same TLS transport as every other request, so it works on
/// mobile networks blocking outbound DNS over TCP.
const DNS_RESOLVER: &str = "https://cloudflare-dns.com/dns-query";

/// Discovery operations: RFC 6764 discovery, provider and protocol search.
impl<H: Host> Client<H> {
    /// Matches the domain's MX records against the fixed provider rules.
    ///
    /// Catches every domain whose mail lives at Google or Microsoft, their
    /// own domains included (no separate list needed). Returns the
    /// provider's fixed configs, empty when no rule matched.
    pub fn search_provider(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (email, domain) = search_domain(email)?;

        let mx = DiscoveryDnsMx::new(&domain, self.search_resolver(resolver)?);
        let records = self.run_mechanism(mx)?;

        for record in records {
            let exchange = record.rdata.exchange.to_string();

            if let Some(provider) = DiscoveryKnownProvider::from_mx(&exchange) {
                let mut configs = provider.configs(&email);

                // NOTE: a bare-domain search has no email, so the
                // username hint is dropped rather than minted.
                if email.is_empty() {
                    for config in &mut configs {
                        config.username = None;
                    }
                }

                return Ok(configs);
            }
        }

        Ok(Vec::new())
    }

    /// Discovers the domain's PACC document, flattened into service configs.
    pub fn search_pacc(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (_, domain) = search_domain(email)?;

        let pacc = DiscoveryPacc::new(&domain, self.search_resolver(resolver)?)
            .map_err(|err| err.to_string())?;
        let config = self.run_mechanism(pacc)?;

        Ok(DiscoveryServiceConfig::from_pacc(&config))
    }

    /// Resolves the domain's CardDAV context root (RFC 6764) into a config.
    pub fn search_carddav(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (_, domain) = search_domain(email)?;

        let resolve = DiscoveryDavResolve::new(
            &domain,
            DiscoveryDavService::Carddav,
            self.search_resolver(resolver)?,
        )
        .with_secure_only(true);
        let url = self.run_mechanism(resolve)?;

        Ok(vec![DiscoveryServiceConfig::from_dav(
            DiscoveryService::Carddav,
            url,
        )])
    }

    /// Resolves the domain's CalDAV context root (RFC 6764) into a config.
    pub fn search_caldav(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (_, domain) = search_domain(email)?;

        let resolve = DiscoveryDavResolve::new(
            &domain,
            DiscoveryDavService::Caldav,
            self.search_resolver(resolver)?,
        )
        .with_secure_only(true);
        let url = self.run_mechanism(resolve)?;

        Ok(vec![DiscoveryServiceConfig::from_dav(
            DiscoveryService::Caldav,
            url,
        )])
    }

    /// Fetches the domain's Mozilla autoconfig (IMAP and SMTP) into configs.
    ///
    /// Tries the ISP URL, the target of its alias, its unauthenticated
    /// fallback, the ISPDB, then mailconf, in spec order; the first answer
    /// wins and a failing one is skipped, since most domains publish at
    /// exactly one of them. A domain publishing none falls back to the
    /// provider its MX records name, as Thunderbird does.
    pub fn search_autoconfig(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (address, domain) = search_domain(email)?;
        let resolver = self.search_resolver(resolver)?;
        let host = format!("autoconfig.{domain}");

        let mut located = Vec::new();
        if let Ok(url) = DiscoveryIsp::host_url(&address, &host, true) {
            located.push((url, DiscoveryConfigSource::IspMain));
        }
        if let Some(url) = self.alias_url(&host, &address, &resolver) {
            located.push((url, DiscoveryConfigSource::IspMain));
        }
        if let Ok(url) = DiscoveryIsp::fallback_url(&domain, true) {
            located.push((url, DiscoveryConfigSource::IspFallback));
        }
        if let Ok(url) = DiscoveryIsp::db_url(&domain, true) {
            located.push((url, DiscoveryConfigSource::Ispdb));
        }

        // NOTE: the mailconf TXT record names the document's URL; https
        // only, since an http one would be answered by an on-path attacker.
        let mailconf = DiscoveryMailconf::new(&domain, resolver.clone());
        if let Ok(url) = self.run_mechanism(mailconf)
            && url.scheme() == "https"
        {
            located.push((url, DiscoveryConfigSource::Mailconf));
        }

        let configs = self.first_autoconfig(located, email);
        if !configs.is_empty() {
            return Ok(configs);
        }

        let mx = DiscoveryDnsMx::new(&domain, resolver.clone());
        let mut bases: Vec<String> = Vec::new();
        for record in self.run_mechanism(mx).unwrap_or_default() {
            let exchange = record.rdata.exchange.to_string();
            if let Some(base) = mx_base_domain(&exchange)
                && base != domain
                && !bases.contains(&base)
            {
                bases.push(base);
            }
        }

        for base in bases {
            let host = format!("autoconfig.{base}");

            let mut located = Vec::new();
            if let Ok(url) = DiscoveryIsp::host_url(&address, &host, true) {
                located.push((url, DiscoveryConfigSource::Mx));
            }
            if let Some(url) = self.alias_url(&host, &address, &resolver) {
                located.push((url, DiscoveryConfigSource::Mx));
            }
            if let Ok(url) = DiscoveryIsp::db_url(&base, true) {
                located.push((url, DiscoveryConfigSource::Mx));
            }

            let configs = self.first_autoconfig(located, email);
            if !configs.is_empty() {
                return Ok(configs);
            }
        }

        Ok(Vec::new())
    }

    /// The ISP URL on the last target of `host`'s alias, if it is one.
    ///
    /// A provider aliasing its customers' autoconfig to its own host rarely
    /// holds a certificate for the alias, so the alias itself fails.
    fn alias_url(&mut self, host: &str, address: &str, resolver: &Url) -> Option<Url> {
        let cname = DiscoveryDnsCname::new(host, resolver.clone());
        let target = self.run_mechanism(cname).ok().flatten()?;
        DiscoveryIsp::host_url(address, target, true).ok()
    }

    /// The configs of the first located autoconfig document that has any.
    fn first_autoconfig(
        &mut self,
        located: Vec<(Url, DiscoveryConfigSource)>,
        email: &str,
    ) -> Vec<DiscoveryServiceConfig> {
        for (url, source) in located {
            let Ok(config) = self.run_mechanism(DiscoveryIsp::new(url)) else {
                continue;
            };
            let configs = DiscoveryServiceConfig::from_autoconfig(&config, email, source);
            if !configs.is_empty() {
                return configs;
            }
        }

        Vec::new()
    }

    /// Resolves the domain's JMAP session URL (RFC 8620) into a config.
    pub fn search_jmap(
        &mut self,
        email: &str,
        resolver: Option<&str>,
    ) -> Result<Vec<DiscoveryServiceConfig>, BridgeError> {
        let (_, domain) = search_domain(email)?;

        let resolve = DiscoveryJmapResolve::new(&domain, self.search_resolver(resolver)?);
        let session = self.run_mechanism(resolve)?;

        Ok(vec![DiscoveryServiceConfig::from_jmap(
            session.url,
            &session.auth_schemes,
        )])
    }

    /// Refines a config's auth methods from the schemes its endpoints
    /// advertise on an unauthenticated 401 (PACC §5.4.2).
    ///
    /// URLs are tried until one advertises a scheme; none leaves the config
    /// as discovered.
    pub fn search_probe(
        &mut self,
        mut config: DiscoveryServiceConfig,
    ) -> Result<DiscoveryServiceConfig, BridgeError> {
        for url in config.probe_urls() {
            match self.run_mechanism(DiscoveryProbeAuth::new(url)) {
                Ok(schemes) if !schemes.is_empty() => {
                    config.refine_auth(&schemes);
                    break;
                }
                Ok(_) | Err(_) => {}
            }
        }

        Ok(config)
    }

    /// The DNS resolver URL for the search mechanisms, DoH by default.
    fn search_resolver(&self, resolver: Option<&str>) -> Result<Url, BridgeError> {
        let resolver = resolver.unwrap_or(DNS_RESOLVER);
        Url::parse(resolver)
            .map_err(|err| format!("Invalid DNS resolver URL `{resolver}`: {err}").into())
    }
}

/// CardDAV walk: the PROPFIND probes for the principal and its home set.
impl<H: Host> Client<H> {
    /// PROPFIND the context root for `DAV:current-user-principal`, on a
    /// server the credential may follow to ([`trusted_href`]).
    pub fn principal(
        &mut self,
        base_url: &Url,
        auth: &WebdavAuth,
    ) -> Result<Option<Url>, BridgeError> {
        let principal = self.run_redirect(base_url, |url| {
            WebdavCurrentUserPrincipal::new(url, auth, USER_AGENT)
        })?;
        principal
            .map(|principal| trusted_href(base_url, principal))
            .transpose()
    }

    /// PROPFIND the principal for `CARDDAV:addressbook-home-set`, on a
    /// server the credential may follow to ([`trusted_href`]).
    pub fn addressbook_home_set(
        &mut self,
        principal: &Url,
        auth: &WebdavAuth,
    ) -> Result<Option<Url>, BridgeError> {
        let home = self.run_redirect(principal, |url| {
            CarddavAddressbookHomeSet::new(url, auth, USER_AGENT, url.path())
        })?;
        home.map(|home| trusted_href(principal, home)).transpose()
    }
}

/// Discovery coroutine runners, routing each yield to the host.
impl<H: Host> Client<H> {
    /// Runs a discovery coroutine to completion on the yielded URLs.
    pub fn run_discovery<C, T, E>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: DiscoveryCoroutine<Yield = DiscoveryYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                DiscoveryCoroutineState::Complete(Ok(url)) => return Ok(url),
                DiscoveryCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsRead { url }) => {
                    arg = Some(self.read(url.as_str())?);
                }
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsWrite { url, bytes }) => {
                    self.write(url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Runs one mechanism coroutine, transport failures resumed as EOF.
    ///
    /// The empty resume slice lets the coroutine run its own fallbacks (the
    /// JMAP resolve falls back to the domain) and fail on its own terms.
    fn run_mechanism<C, T, E>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: DiscoveryCoroutine<Yield = DiscoveryYield, Return = Result<T, E>>,
        E: StdError + 'static,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                DiscoveryCoroutineState::Complete(Ok(output)) => return Ok(output),
                DiscoveryCoroutineState::Complete(Err(err)) => return Err(coroutine_error(&err)),
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsRead { url }) => {
                    arg = Some(self.read(url.as_str()).unwrap_or_default());
                }
                DiscoveryCoroutineState::Yielded(DiscoveryYield::WantsWrite { url, bytes }) => {
                    arg = match self.write(url.as_str(), &bytes) {
                        Ok(()) => None,
                        Err(_) => Some(Vec::new()),
                    };
                }
            }
        }
    }
}

/// Splits a trimmed search input into (email, lowercased domain).
///
/// A bare domain yields an empty email: every mechanism is domain-driven,
/// so a domain searches as well as an address.
fn search_domain(input: &str) -> Result<(String, String), BridgeError> {
    let input = input.trim();

    let (email, domain) = match input.split_once('@') {
        Some((_, domain)) => (input, domain),
        None => ("", input),
    };

    let domain = domain.trim_matches('.').to_ascii_lowercase();

    if domain.is_empty() {
        return Err(format!("Search input `{input}` has no domain").into());
    }

    Ok((email.to_string(), domain))
}

#[cfg(test)]
mod tests {
    use crate::account::discovery::*;

    /// An address searches by its domain, a bare domain as itself with no
    /// email, and an input without a domain is rejected.
    #[test]
    fn search_domain_accepts_emails_and_bare_domains() {
        let (email, domain) = search_domain("user@Example.COM.").unwrap();
        assert_eq!(email, "user@Example.COM.");
        assert_eq!(domain, "example.com");

        let (email, domain) = search_domain(" example.com ").unwrap();
        assert_eq!(email, "");
        assert_eq!(domain, "example.com");

        assert!(search_domain("").is_err());
        assert!(search_domain("user@").is_err());
    }
}
