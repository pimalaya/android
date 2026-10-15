//! # Account setup exports
//!
//! The JNI entry points of onboarding: discovery and provider search, the
//! OAuth 2.0 grant, and the account base URL and what it tells.

use std::collections::BTreeSet;

use io_oauth::rfc7591::register::{Oauth20ClientInformation, Oauth20ClientRegisterParams};
use io_pim_discovery::compose::{
    collect::DiscoveryConfigCollector,
    config::{DiscoveryService, DiscoveryServiceConfig},
};
use jni::{
    Env, EnvUnowned,
    errors::Error,
    objects::{JClass, JObject, JString},
};
use serde::Serialize;
use serde_json::{from_str, to_string};

use crate::{
    account::{
        self, Backend,
        oauth::{
            AuthorizationRequest, CodeExchange, TokenRefresh, authorize_url, domain_scope,
            session_params, validate_redirect,
        },
    },
    client::Client,
    ffi::{ErrorReply, error_json, host::JniHost, parse_url, read_string},
};

/// `Native.searchProvider`: the configs the fixed provider rules give.
///
/// Matched against the email's MX records. Returns a JSON array of service
/// configs, empty when no rule matched.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_searchProvider<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(
            env,
            &transport,
            &email,
            &resolver,
            SearchMechanism::Provider,
        );
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.searchPacc`: discovers the email domain's PACC document.
///
/// Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_searchPacc<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Pacc);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.searchCarddav`: resolves the domain's CardDAV root (RFC 6764).
///
/// Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_searchCarddav<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json =
            search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Carddav);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.searchCaldav`: resolves the domain's CalDAV root (RFC 6764).
///
/// Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_searchCaldav<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json =
            search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Caldav);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.searchAutoconfig`: fetches the email domain's Mozilla autoconfig.
///
/// Where a mail domain's IMAP and SMTP endpoints come from. Returns a JSON
/// array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_searchAutoconfig<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(
            env,
            &transport,
            &email,
            &resolver,
            SearchMechanism::Autoconfig,
        );
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.searchJmap`: resolves the domain's JMAP session URL (RFC 8620).
///
/// Returns a JSON array of service configs.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_searchJmap<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    email: JString<'local>,
    resolver: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = search_mechanism_json(env, &transport, &email, &resolver, SearchMechanism::Jmap);
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.searchMerge`: merges per-mechanism config lists into one.
///
/// Takes a JSON array of arrays in mechanism-priority order; pure. Returns
/// one deduplicated array, restricted to the services the app drives.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_searchMerge<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    lists: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let json = match search_merge(&read_string(env, &lists)) {
            Ok(json) => json,
            Err(err) => error_json(err),
        };
        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.searchProbe`: refines one config's password and bearer methods.
///
/// Probes its endpoints for their advertised schemes (an unauthenticated
/// 401). Returns the config, refined or not, as JSON.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_searchProbe<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    config: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let raw = read_string(env, &config);

        let json = match from_str(&raw) {
            Err(err) => error_json(format!("Invalid service config: {err}")),
            Ok(config) => match Client::new(JniHost::new(env, &transport, None))
                .search_probe(config)
            {
                Ok(config) => to_string(&config).unwrap_or_else(|err| error_json(err.to_string())),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// One discovery mechanism the search verbs dispatch on.
enum SearchMechanism {
    Provider,
    Pacc,
    Carddav,
    Caldav,
    Autoconfig,
    Jmap,
}

/// Runs one discovery mechanism and serializes its service configs.
fn search_mechanism_json<'local>(
    env: &mut Env<'local>,
    transport: &JObject<'local>,
    email: &JString<'local>,
    resolver: &JString<'local>,
    mechanism: SearchMechanism,
) -> String {
    let email = read_string(env, email);
    let resolver = read_string(env, resolver);
    let resolver = (!resolver.is_empty()).then_some(resolver.as_str());

    let mut client = Client::new(JniHost::new(env, transport, None));
    let configs = match mechanism {
        SearchMechanism::Provider => client.search_provider(&email, resolver),
        SearchMechanism::Pacc => client.search_pacc(&email, resolver),
        SearchMechanism::Carddav => client.search_carddav(&email, resolver),
        SearchMechanism::Caldav => client.search_caldav(&email, resolver),
        SearchMechanism::Autoconfig => client.search_autoconfig(&email, resolver),
        SearchMechanism::Jmap => client.search_jmap(&email, resolver),
    };

    match configs {
        Ok(configs) => to_string(&configs).unwrap_or_else(|err| error_json(err.to_string())),
        Err(err) => error_json(err),
    }
}

/// Merges per-mechanism config lists through io-pim-discovery's collector.
fn search_merge(lists: &str) -> Result<String, String> {
    let lists: Vec<Vec<DiscoveryServiceConfig>> =
        from_str(lists).map_err(|err| format!("Invalid config lists: {err}"))?;

    // NOTE: the last gate before the connection screen: a service missing
    // here is discovered, merged away and never seen. Graph and Google
    // contacts stay out, their sign-ins being the app's own
    // (`OnboardingFlow.addProviderOptions`).
    let services = BTreeSet::from([
        DiscoveryService::Imap,
        DiscoveryService::Smtp,
        DiscoveryService::Caldav,
        DiscoveryService::Carddav,
        DiscoveryService::Jmap,
        DiscoveryService::Msgraph,
        DiscoveryService::MsgraphCalendar,
        DiscoveryService::Gmail,
        DiscoveryService::Gcal,
    ]);
    let mut collector = DiscoveryConfigCollector::new(services);

    for configs in lists {
        collector.collect(configs);
    }

    to_string(&collector.finish()).map_err(|err| err.to_string())
}

/// `Native.oauthAuthorizeUrl`: builds the RFC 6749 authorization URL.
///
/// With PKCE (S256) and CSRF state; pure. `extras` is a JSON object of
/// provider query parameters, empty or null for none. Returns `{"url": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_oauthAuthorizeUrl<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    authorization_endpoint: JString<'local>,
    client_id: JString<'local>,
    redirect_uri: JString<'local>,
    scope: JString<'local>,
    state: JString<'local>,
    pkce_verifier: JString<'local>,
    extras: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let authorization_endpoint = read_string(env, &authorization_endpoint);
        let client_id = read_string(env, &client_id);
        let redirect_uri = read_string(env, &redirect_uri);
        let scope = read_string(env, &scope);
        let state = read_string(env, &state);
        let pkce_verifier = read_string(env, &pkce_verifier);
        let extras = read_string(env, &extras);

        let request = AuthorizationRequest {
            authorization_endpoint: &authorization_endpoint,
            client_id: &client_id,
            redirect_uri: &redirect_uri,
            scope: &scope,
            state: &state,
            pkce_verifier: &pkce_verifier,
            extras: &extras,
        };
        let json = match authorize_url(&request) {
            Ok(url) => {
                to_string(&UrlReply { url: &url }).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.oauthValidateRedirect`: extracts the code of an auth redirect.
///
/// Checked against the expected CSRF state and, when `issuer` is not
/// empty, its RFC 9207 `iss`; pure. Returns `{"code": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_oauthValidateRedirect<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    redirect_url: JString<'local>,
    state: JString<'local>,
    issuer: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let redirect_url = read_string(env, &redirect_url);
        let state = read_string(env, &state);
        let issuer = read_string(env, &issuer);

        let json = match validate_redirect(&redirect_url, &state, &issuer) {
            Ok(code) => to_string(&CodeReply { code: &code })
                .unwrap_or_else(|err| error_json(err.to_string())),
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.oauthSessionParams`: one session's CSRF state and PKCE verifier.
///
/// Pure. Returns `{"state": "..", "verifier": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_oauthSessionParams<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let (state, verifier) = session_params();
        let json = to_string(&SessionParamsReply {
            state: &state,
            verifier: &verifier,
        })
        .unwrap_or_else(|err| error_json(err.to_string()));

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.oauthDomainScope`: the scope to request for the grant's domains.
///
/// `domains` is space-separated (`mail`, `contacts`, `calendars`), negotiated
/// against the server's space-separated advertised scopes (empty for none);
/// pure. Returns `{"scope": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_oauthDomainScope<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    scopes_supported: JString<'local>,
    domains: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let scopes_supported = read_string(env, &scopes_supported);
        let domains = read_string(env, &domains);
        let scope = domain_scope(&scopes_supported, &domains);
        let json = to_string(&ScopeReply { scope: &scope })
            .unwrap_or_else(|err| error_json(err.to_string()));

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.oauthRequestAccessToken`: exchanges an authorization code.
///
/// With the PKCE verifier and the client secret, if issued (empty: none).
/// Returns the RFC 6749 §5.1 params as JSON: `access_token`, `token_type`,
/// `expires_in`, `refresh_token`, `scope`, `issued_at`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_oauthRequestAccessToken<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token_endpoint: JString<'local>,
    client_id: JString<'local>,
    client_secret: JString<'local>,
    code: JString<'local>,
    redirect_uri: JString<'local>,
    pkce_verifier: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token_endpoint = read_string(env, &token_endpoint);
        let client_id = read_string(env, &client_id);
        let client_secret = read_string(env, &client_secret);
        let secret = (!client_secret.is_empty()).then_some(client_secret.as_str());
        let code = read_string(env, &code);
        let redirect_uri = read_string(env, &redirect_uri);
        let pkce_verifier = read_string(env, &pkce_verifier);

        let json = match parse_url(&token_endpoint) {
            Err(err) => error_json(err),
            Ok(url) => match Client::new(JniHost::new(env, &transport, None))
                .oauth_request_access_token(&CodeExchange {
                    token_endpoint: &url,
                    client_id: &client_id,
                    client_secret: secret,
                    code: &code,
                    redirect_uri: &redirect_uri,
                    pkce_verifier: &pkce_verifier,
                }) {
                Ok(tokens) => to_string(&tokens).unwrap_or_else(|err| error_json(err.to_string())),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.oauthServerMetadata`: fetches an issuer's RFC 8414 metadata.
///
/// From the `oauth-authorization-server` well-known, falling back to OpenID
/// Connect Discovery. Returns its JSON: issuer, endpoints, grants, scopes,
/// and `registration_endpoint` when the server supports RFC 7591.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_oauthServerMetadata<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    issuer: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let issuer = read_string(env, &issuer);

        let json = match parse_url(&issuer) {
            Err(err) => error_json(err),
            Ok(url) => {
                match Client::new(JniHost::new(env, &transport, None)).oauth_server_metadata(&url) {
                    Ok(metadata) => {
                        to_string(&metadata).unwrap_or_else(|err| error_json(err.to_string()))
                    }
                    Err(err) => error_json(err),
                }
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.oauthRegisterClient`: registers a public client (RFC 7591).
///
/// `token_endpoint_auth_method: none`, the given redirect URI, code and
/// refresh grants, client name and scope. Returns `{"client_id": "..",
/// "client_secret": ".." | null}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_oauthRegisterClient<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    registration_endpoint: JString<'local>,
    redirect_uri: JString<'local>,
    client_name: JString<'local>,
    scope: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let registration_endpoint = read_string(env, &registration_endpoint);
        let redirect_uri = read_string(env, &redirect_uri);
        let client_name = read_string(env, &client_name);
        let scope = read_string(env, &scope);

        let params = oauth_register_params(&redirect_uri, &client_name, &scope);

        let json = match parse_url(&registration_endpoint) {
            Err(err) => error_json(err),
            Ok(url) => match Client::new(JniHost::new(env, &transport, None))
                .oauth_register_client(&url, &params)
            {
                Ok(client) => client_information_json(&client),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.oauthRefreshAccessToken`: refreshes tokens at the token endpoint.
///
/// `scope` is the original grant's, space-separated (empty keeps the server
/// default); the secret rides along if issued (empty: none). Returns the
/// shape of `oauthRequestAccessToken`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_oauthRefreshAccessToken<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    token_endpoint: JString<'local>,
    client_id: JString<'local>,
    client_secret: JString<'local>,
    refresh_token: JString<'local>,
    scope: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let token_endpoint = read_string(env, &token_endpoint);
        let client_id = read_string(env, &client_id);
        let client_secret = read_string(env, &client_secret);
        let secret = (!client_secret.is_empty()).then_some(client_secret.as_str());
        let refresh_token = read_string(env, &refresh_token);
        let scope = read_string(env, &scope);

        let json = match parse_url(&token_endpoint) {
            Err(err) => error_json(err),
            Ok(url) => match Client::new(JniHost::new(env, &transport, None))
                .oauth_refresh_access_token(&TokenRefresh {
                    token_endpoint: &url,
                    client_id: &client_id,
                    client_secret: secret,
                    refresh_token: &refresh_token,
                    scope: &scope,
                }) {
                Ok(tokens) => to_string(&tokens).unwrap_or_else(|err| error_json(err.to_string())),
                Err(err) => error_json(err),
            },
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// Public-client registration params.
///
/// A loopback redirect, no secret, the code and refresh grants the app drives.
fn oauth_register_params(
    redirect_uri: &str,
    client_name: &str,
    scope: &str,
) -> Oauth20ClientRegisterParams {
    Oauth20ClientRegisterParams {
        redirect_uris: vec![redirect_uri.to_string()],
        token_endpoint_auth_method: Some("none".to_string()),
        grant_types: vec![
            "authorization_code".to_string(),
            "refresh_token".to_string(),
        ],
        response_types: vec!["code".to_string()],
        client_name: (!client_name.is_empty()).then(|| client_name.to_string()),
        scope: (!scope.is_empty()).then(|| scope.to_string()),
        ..Default::default()
    }
}

/// Serializes the issued client information, the secret only if returned.
// NOTE: exposes the client secret, which a public-client registration
// should not carry.
fn client_information_json(client: &Oauth20ClientInformation) -> String {
    use secrecy::ExposeSecret;

    let secret = client
        .client_secret
        .as_ref()
        .map(|secret| secret.expose_secret());
    to_string(&ClientInformationReply {
        client_id: &client.client_id,
        client_secret: secret,
    })
    .unwrap_or_else(|err| error_json(err.to_string()))
}

/// `Native.accountInfo`: the backend behind an account base URL; pure.
///
/// `{"backend", "accountLevel", "submitsOverSession"}`, the flags those of
/// [`Backend`]; `backend` is `carddav`, `graph`, `jmap`, `google` or `local`
/// (on-device).
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_accountInfo<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    base_url: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let base_url = read_string(env, &base_url);

        // NOTE: the local account has no transport, so it is not a
        // Backend; reporting it so keeps its cards non-account-level
        // (one book, per-collection keys) and out of backend matches.
        let info = if base_url.starts_with(account::LOCAL_PREFIX) {
            AccountInfoReply {
                account_level: false,
                backend: "local",
                submits_over_session: false,
            }
        } else {
            let backend = Backend::of(&base_url);
            AccountInfoReply {
                account_level: backend.account_level(),
                backend: backend.name(),
                submits_over_session: backend.submits_over_session(),
            }
        };
        let json = to_string(&info).unwrap_or_else(|err| error_json(err.to_string()));

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// `Native.accountBase`: builds an account base URL of the given kind.
///
/// `googleCarddav`, `google` or `msgraph` from an email, `jmap` from an
/// HTTPS session URL or bare host; pure. Returns `{"url": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_client_Native_accountBase<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    kind: JString<'local>,
    value: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let kind = read_string(env, &kind);
        let value = read_string(env, &value);

        let json = match account::base_url(&kind, &value) {
            Ok(url) => {
                to_string(&UrlReply { url: &url }).unwrap_or_else(|err| error_json(err.to_string()))
            }
            Err(err) => error_json(err),
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<ErrorReply>()
}

/// A reply of one URL.
#[derive(Serialize)]
struct UrlReply<'a> {
    url: &'a str,
}

/// The reply of `oauthValidateRedirect`.
#[derive(Serialize)]
struct CodeReply<'a> {
    code: &'a str,
}

/// The reply of `oauthSessionParams`.
#[derive(Serialize)]
struct SessionParamsReply<'a> {
    state: &'a str,
    verifier: &'a str,
}

/// The reply of `oauthDomainScope`.
#[derive(Serialize)]
struct ScopeReply<'a> {
    scope: &'a str,
}

/// The reply of `oauthRegisterClient`, `client_secret` null if none issued.
#[derive(Serialize)]
struct ClientInformationReply<'a> {
    client_id: &'a str,
    client_secret: Option<&'a str>,
}

/// The reply of `accountInfo`.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct AccountInfoReply {
    account_level: bool,
    backend: &'static str,
    submits_over_session: bool,
}
