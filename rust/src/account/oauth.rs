//! # OAuth
//!
//! OAuth 2.0 onboarding on top of io-oauth: authorization URL, redirect
//! validation, session strings and scope negotiation as pure functions,
//! and metadata, registration and token exchanges on the [`Client`].

use std::{borrow::Cow, collections::BTreeMap, str::FromStr};

use io_http::rfc9110::request::HttpRequest;
use io_oauth::{
    rfc6749::{
        access_token_request::{
            Oauth20AccessTokenRequest, Oauth20AccessTokenRequestParams,
            Oauth20AccessTokenRequestResult,
        },
        auth_request::Oauth20AuthRequestParams,
        auth_response::Oauth20AuthParams,
        issue_access_token::{Oauth20AccessTokenErrorParams, Oauth20AccessTokenSuccessParams},
        refresh_access_token::{
            Oauth20AccessTokenRefresh, Oauth20AccessTokenRefreshParams,
            Oauth20AccessTokenRefreshResult,
        },
        state::Oauth20State,
    },
    rfc7591::register::{
        Oauth20ClientInformation, Oauth20ClientRegister, Oauth20ClientRegisterParams,
        Oauth20ClientRegisterResult,
    },
    rfc7636::pkce::{
        Oauth20PkceCodeChallenge, Oauth20PkceCodeChallengeMethod, Oauth20PkceCodeVerifier,
    },
};
use io_pim_discovery::rfc8414::{DiscoveryOauthServerMetadata, DiscoveryOauthServerResolve};
use rand::{rng, seq::IndexedRandom};
use secrecy::SecretString;
use serde::{
    Deserialize,
    de::value::{Error as DeserializeError, StrDeserializer},
};
use serde_json::from_str;
use url::Url;

use crate::{
    client::{Client, Host, trust::require_https},
    error::BridgeError,
};

/// The RFC 6749 §4.1.1 authorization request [`authorize_url`] builds.
pub struct AuthorizationRequest<'a> {
    /// The authorization endpoint, https only.
    pub authorization_endpoint: &'a str,
    /// The client the grant is for.
    pub client_id: &'a str,
    /// Where the browser is sent back.
    pub redirect_uri: &'a str,
    /// The space-separated scopes asked for.
    pub scope: &'a str,
    /// The session's CSRF state ([`session_params`]).
    pub state: &'a str,
    /// The session's PKCE verifier, sent as its S256 challenge.
    pub pkce_verifier: &'a str,
    /// Provider-specific query parameters as a JSON object, empty for none.
    ///
    /// Google needs access_type=offline and prompt=consent, or it issues no
    /// refresh token.
    pub extras: &'a str,
}

/// Builds the RFC 6749 §4.1.1 authorization URL with PKCE S256 and state.
pub fn authorize_url(request: &AuthorizationRequest) -> Result<String, String> {
    let AuthorizationRequest {
        authorization_endpoint,
        client_id,
        redirect_uri,
        scope,
        state,
        pkce_verifier,
        extras,
    } = *request;
    let endpoint = Url::parse(authorization_endpoint).map_err(|err| {
        format!("Invalid authorization endpoint `{authorization_endpoint}`: {err}")
    })?;
    // NOTE: the browser opens this URL, so https only: not plain http, nor
    // a scheme another app handles.
    require_https(&endpoint, "authorization endpoint")?;

    let state = parse_state(state)?;
    let verifier = parse_pkce_verifier(pkce_verifier)?;

    let challenge = Oauth20PkceCodeChallenge {
        method: Oauth20PkceCodeChallengeMethod::Sha256,
        verifier,
    };

    let extras: BTreeMap<String, String> = if extras.is_empty() {
        BTreeMap::new()
    } else {
        from_str(extras).map_err(|err| format!("Invalid OAuth extras JSON: {err}"))?
    };

    let params = Oauth20AuthRequestParams {
        client_id: client_id.into(),
        redirect_uri: Some(redirect_uri.into()),
        scope: scope.split_whitespace().map(Cow::Borrowed).collect(),
        state: Some(Cow::Owned(state)),
        pkce_code_challenge: Some(Cow::Owned(challenge)),
        extras: extras
            .into_iter()
            .map(|(key, value)| (Cow::Owned(key), Cow::Owned(value)))
            .collect(),
    };

    Ok(params.build_url(&endpoint).to_string())
}

/// Validates the RFC 6749 §4.1.2 redirect against the state: the code.
///
/// A redirect whose RFC 9207 `iss` names another server than `issuer`
/// (empty when unknown) is the mix-up attack and refused. No error names
/// the redirect URL, which carries the code.
pub fn validate_redirect(redirect_url: &str, state: &str, issuer: &str) -> Result<String, String> {
    let url = Url::parse(redirect_url).map_err(|err| format!("Invalid redirect URL: {err}"))?;

    let state = parse_state(state)?;

    if !issuer.is_empty() {
        let named = url
            .query_pairs()
            .find(|(key, _)| key == "iss")
            .map(|(_, value)| value.into_owned());
        if let Some(named) = named
            && !same_issuer(&named, issuer)
        {
            return Err(format!(
                "Authorization response from {named}, not from {issuer}"
            ));
        }
    }

    Oauth20AuthParams::from(&url)
        .validate(Some(&state))
        .map(|code| code.into_owned())
        .map_err(|err| err.to_string())
}

/// Whether two issuer identifiers are equal once parsed.
///
/// RFC 9207 section 2.4 compares them as strings; a parse only settles a
/// trailing slash on a bare origin.
fn same_issuer(left: &str, right: &str) -> bool {
    match (Url::parse(left), Url::parse(right)) {
        (Ok(left), Ok(right)) => left == right,
        _ => left == right,
    }
}

/// Generates a session's state (32 chars) and PKCE verifier (64 chars).
pub fn session_params() -> (String, String) {
    (random_string(32), random_string(64))
}

/// The scope to request for the space-separated `domains` one grant covers.
///
/// Each domain's standard scope (draft-ietf-mailmaint-oauth-public) and
/// `offline_access`, kept if advertised or nothing is: the whole set fails
/// as "invalid scope", a wrong one asks consent for the wrong thing.
pub fn domain_scope(scopes_supported: &str, domains: &str) -> String {
    let mut wanted: Vec<&str> = Vec::new();
    for domain in domains.split_whitespace() {
        match domain {
            "mail" => wanted.push("urn:ietf:params:oauth:scope:mail"),
            "contacts" => wanted.push("urn:ietf:params:oauth:scope:contacts"),
            // NOTE: both spellings, because the app names the domain
            // `calendar` and the scope registry `calendars`.
            "calendar" | "calendars" => wanted.push("urn:ietf:params:oauth:scope:calendars"),
            _ => (),
        }
    }
    wanted.push("offline_access");

    let advertised: Vec<&str> = scopes_supported.split_whitespace().collect();
    if advertised.is_empty() {
        return wanted.join(" ");
    }

    wanted
        .into_iter()
        .filter(|scope| advertised.contains(scope))
        .collect::<Vec<&str>>()
        .join(" ")
}

/// Parses the session PKCE verifier, rejecting bytes outside the
/// RFC 7636 unreserved set.
pub fn parse_pkce_verifier(verifier: &str) -> Result<Oauth20PkceCodeVerifier, String> {
    Oauth20PkceCodeVerifier::from_str(verifier)
        .map_err(|byte| format!("Invalid PKCE verifier byte 0x{byte:x}"))
}

/// Letters and digits, valid as both PKCE verifier and VSCHAR state.
///
/// The RFC 7636 unreserved set also allows `-._~`, which not every server
/// accepts; 62 symbols keep ample entropy.
const ALPHANUMERIC: &[u8] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";

/// One cryptographically random alphanumeric string of the given size.
fn random_string(size: usize) -> String {
    let mut rng = rng();

    (0..size)
        .map(|_| *ALPHANUMERIC.choose(&mut rng).expect("non-empty charset") as char)
        .collect()
}

/// Parses the session CSRF state, rejecting bytes outside the RFC 6749
/// VSCHAR set.
fn parse_state(state: &str) -> Result<Oauth20State, String> {
    let deserializer = StrDeserializer::<DeserializeError>::new(state);
    Oauth20State::deserialize(deserializer).map_err(|err| format!("Invalid OAuth state: {err}"))
}

/// The RFC 6749 §4.1.3 exchange [`Client::oauth_request_access_token`] runs.
pub struct CodeExchange<'a> {
    /// The token endpoint, https only.
    pub token_endpoint: &'a Url,
    /// The client the grant is for.
    pub client_id: &'a str,
    /// The client secret, when the registration issued one (Google desktop).
    pub client_secret: Option<&'a str>,
    /// The authorization code the redirect carried.
    pub code: &'a str,
    /// The redirect URI of the authorization request.
    pub redirect_uri: &'a str,
    /// The PKCE verifier of the authorization request.
    pub pkce_verifier: &'a str,
}

/// The RFC 6749 §6 refresh [`Client::oauth_refresh_access_token`] runs.
pub struct TokenRefresh<'a> {
    /// The token endpoint, https only.
    pub token_endpoint: &'a Url,
    /// The client the grant is for.
    pub client_id: &'a str,
    /// The client secret, when the registration issued one.
    pub client_secret: Option<&'a str>,
    /// The refresh token the last exchange issued.
    pub refresh_token: &'a str,
    /// The original grant's space-separated scopes, empty for the default.
    pub scope: &'a str,
}

/// OAuth operations: metadata, registration and token exchanges.
impl<H: Host> Client<H> {
    /// Fetches an authorization server's RFC 8414 metadata from its issuer.
    ///
    /// The `oauth-authorization-server` well-known first, then OpenID
    /// Connect Discovery (the mailmaint draft requires one); refused unless
    /// it is the issuer's own, over https ([`trusted_metadata`]).
    pub fn oauth_server_metadata(
        &mut self,
        issuer: &Url,
    ) -> Result<DiscoveryOauthServerMetadata, BridgeError> {
        require_https(issuer, "issuer")?;
        let metadata = self.fetch_oauth_server_metadata(issuer)?;
        trusted_metadata(issuer, metadata)
    }

    fn fetch_oauth_server_metadata(
        &mut self,
        issuer: &Url,
    ) -> Result<DiscoveryOauthServerMetadata, BridgeError> {
        let primary = DiscoveryOauthServerMetadata::well_known_url(issuer);
        match self.run_discovery(DiscoveryOauthServerResolve::new(primary)) {
            Ok(metadata) => Ok(metadata),
            Err(primary_err) => {
                let fallback = DiscoveryOauthServerMetadata::openid_well_known_url(issuer);
                self.run_discovery(DiscoveryOauthServerResolve::new(fallback))
                    .map_err(|fallback_err| {
                        format!("{primary_err} (OpenID fallback also failed: {fallback_err})")
                            .into()
                    })
            }
        }
    }

    /// Registers a public client (RFC 7591, auth method `none`).
    pub fn oauth_register_client(
        &mut self,
        registration_endpoint: &Url,
        params: &Oauth20ClientRegisterParams,
    ) -> Result<Oauth20ClientInformation, BridgeError> {
        require_https(registration_endpoint, "registration endpoint")?;
        let request = oauth_post_request(registration_endpoint);
        let mut coroutine =
            Oauth20ClientRegister::new(request, params).map_err(|err| err.to_string())?;
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20ClientRegisterResult::Ok(Ok(client)) => return Ok(client),
                Oauth20ClientRegisterResult::Ok(Err(err)) => {
                    let detail = err
                        .error_description
                        .unwrap_or_else(|| format!("{:?}", err.error));
                    return Err(format!("Client registration rejected: {detail}").into());
                }
                Oauth20ClientRegisterResult::Err(err) => return Err(err.to_string().into()),
                Oauth20ClientRegisterResult::WantsRead => {
                    arg = Some(self.read(registration_endpoint.as_str())?);
                }
                Oauth20ClientRegisterResult::WantsWrite(bytes) => {
                    self.write(registration_endpoint.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Exchanges an authorization code for tokens (RFC 6749 §4.1.3).
    pub fn oauth_request_access_token(
        &mut self,
        exchange: &CodeExchange,
    ) -> Result<Oauth20AccessTokenSuccessParams, BridgeError> {
        let CodeExchange {
            token_endpoint,
            client_id,
            client_secret,
            code,
            redirect_uri,
            pkce_verifier,
        } = *exchange;
        require_https(token_endpoint, "token endpoint")?;
        let verifier = parse_pkce_verifier(pkce_verifier)?;
        let params = Oauth20AccessTokenRequestParams {
            code: code.into(),
            redirect_uri: Some(redirect_uri.into()),
            client_id: client_id.into(),
            client_secret: client_secret.map(SecretString::from),
            pkce_code_verifier: Some(Cow::Owned(verifier)),
        };

        let mut coroutine =
            Oauth20AccessTokenRequest::new(oauth_post_request(token_endpoint), params);
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20AccessTokenRequestResult::Ok(Ok(success)) => return Ok(success),
                Oauth20AccessTokenRequestResult::Ok(Err(err)) => {
                    return Err(oauth_error(err).into());
                }
                Oauth20AccessTokenRequestResult::Err(err) => return Err(err.to_string().into()),
                Oauth20AccessTokenRequestResult::WantsRead => {
                    arg = Some(self.read(token_endpoint.as_str())?);
                }
                Oauth20AccessTokenRequestResult::WantsWrite(bytes) => {
                    self.write(token_endpoint.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Refreshes tokens against the token endpoint (RFC 6749 §6).
    pub fn oauth_refresh_access_token(
        &mut self,
        refresh: &TokenRefresh,
    ) -> Result<Oauth20AccessTokenSuccessParams, BridgeError> {
        let TokenRefresh {
            token_endpoint,
            client_id,
            client_secret,
            refresh_token,
            scope,
        } = *refresh;
        require_https(token_endpoint, "token endpoint")?;
        let mut params = Oauth20AccessTokenRefreshParams::new(client_id, refresh_token);
        params.client_secret = client_secret.map(SecretString::from);
        params.scopes = scope.split_whitespace().map(Cow::Borrowed).collect();

        let mut coroutine =
            Oauth20AccessTokenRefresh::new(oauth_post_request(token_endpoint), params);
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                Oauth20AccessTokenRefreshResult::Ok(Ok(success)) => return Ok(success),
                Oauth20AccessTokenRefreshResult::Ok(Err(err)) => {
                    return Err(oauth_error(err).into());
                }
                Oauth20AccessTokenRefreshResult::Err(err) => return Err(err.to_string().into()),
                Oauth20AccessTokenRefreshResult::WantsRead => {
                    arg = Some(self.read(token_endpoint.as_str())?);
                }
                Oauth20AccessTokenRefreshResult::WantsWrite(bytes) => {
                    self.write(token_endpoint.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// POST skeleton for the token or registration endpoint.
///
/// io-oauth adds content headers and body; the explicit `host:port` Host
/// header is the one the Fastmail flow was verified with.
fn oauth_post_request(endpoint: &Url) -> HttpRequest {
    let host = endpoint.host_str().unwrap_or("");
    let port = endpoint.port_or_known_default().unwrap_or(443);

    HttpRequest {
        method: "POST".into(),
        url: endpoint.clone(),
        headers: Vec::new(),
        body: Vec::new(),
    }
    .header("Host", format!("{host}:{port}"))
}

/// The metadata fetched for `issuer`, when it is that issuer's.
///
/// RFC 8414 section 3.3: its `issuer` must match, or a host serving
/// another's metadata steers the grant. Endpoints must be https: the
/// browser opens one, and the code, verifier and refresh tokens go to them.
fn trusted_metadata(
    issuer: &Url,
    metadata: DiscoveryOauthServerMetadata,
) -> Result<DiscoveryOauthServerMetadata, BridgeError> {
    if metadata.issuer != *issuer {
        return Err(format!(
            "Authorization server metadata for {issuer} names another issuer, {}",
            metadata.issuer
        )
        .into());
    }

    let endpoints = [
        (&metadata.authorization_endpoint, "authorization endpoint"),
        (&metadata.token_endpoint, "token endpoint"),
        (&metadata.registration_endpoint, "registration endpoint"),
        (
            &metadata.device_authorization_endpoint,
            "device authorization endpoint",
        ),
    ];
    for (endpoint, what) in endpoints {
        let Some(endpoint) = endpoint else {
            continue;
        };
        require_https(endpoint, what)?;
    }

    Ok(metadata)
}

/// Formats an RFC 6749 §5.2 token error response.
fn oauth_error(err: Oauth20AccessTokenErrorParams) -> String {
    match err.error_description {
        Some(description) => format!("OAuth error {:?}: {description}", err.error),
        None => format!("OAuth error {:?}", err.error),
    }
}

#[cfg(test)]
mod tests {
    use crate::account::oauth::*;

    /// The authorization URL byte for byte, for Fastmail-shaped inputs.
    ///
    /// Guards the wire format the endpoint validates: parameter order,
    /// percent encoding, space-as-plus in the scope.
    #[test]
    fn authorize_url_matches_the_verified_fastmail_shape() {
        let url = authorize_url(&AuthorizationRequest {
            authorization_endpoint: "https://api.fastmail.com/oauth/authorize",
            client_id: "07ee41ae",
            redirect_uri: "org.pimalaya:/oauth2redirect",
            scope: "urn:ietf:params:oauth:scope:contacts offline_access",
            state: "stateABCDEF0123456789abcdef012345",
            pkce_verifier: "verifierABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef01",
            extras: "",
        })
        .unwrap();

        assert_eq!(
            url,
            "https://api.fastmail.com/oauth/authorize\
             ?client_id=07ee41ae\
             &code_challenge=3UabJaVjZLMdt78g6JRyEM8pdmTSqJNYLL3y6RSjDr8\
             &code_challenge_method=S256\
             &redirect_uri=org.pimalaya%3A%2Foauth2redirect\
             &response_type=code\
             &scope=offline_access+urn%3Aietf%3Aparams%3Aoauth%3Ascope%3Acontacts\
             &state=stateABCDEF0123456789abcdef012345",
        );
    }

    /// One Google grant asks the three scopes with incremental auth on.
    #[test]
    fn authorize_url_asks_google_once_for_every_domain() {
        let url = authorize_url(&AuthorizationRequest {
            authorization_endpoint: "https://accounts.google.com/o/oauth2/v2/auth",
            client_id: "123.apps.googleusercontent.com",
            redirect_uri: "com.googleusercontent.apps.123:/oauth2redirect",
            scope: "https://mail.google.com/ https://www.googleapis.com/auth/calendar https://www.googleapis.com/auth/contacts",
            state: "stateABCDEF0123456789abcdef012345",
            pkce_verifier: "verifierABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef01",
            extras: r#"{"access_type":"offline","prompt":"consent","include_granted_scopes":"true"}"#,
        })
        .unwrap();

        let url = Url::parse(&url).unwrap();
        let pairs: BTreeMap<String, String> = url.query_pairs().into_owned().collect();
        let mut scopes: Vec<&str> = pairs["scope"].split(' ').collect();
        scopes.sort();
        assert_eq!(
            scopes,
            [
                "https://mail.google.com/",
                "https://www.googleapis.com/auth/calendar",
                "https://www.googleapis.com/auth/contacts",
            ]
        );
        assert_eq!(pairs["include_granted_scopes"], "true");
        assert_eq!(pairs["access_type"], "offline");
    }

    /// A success redirect on an RFC 8252 private-use scheme, with RFC 9207
    /// iss, yields the code.
    #[test]
    fn validate_redirect_accepts_the_single_slash_scheme() {
        let code = validate_redirect(
            "org.pimalaya:/oauth2redirect\
             ?code=abc123\
             &state=stateABCDEF0123456789abcdef012345\
             &iss=https%3A%2F%2Fapi.fastmail.com",
            "stateABCDEF0123456789abcdef012345",
            "https://api.fastmail.com/",
        );

        assert_eq!(code.unwrap(), "abc123");
    }

    /// Session strings have their sizes and validate as state and verifier.
    #[test]
    fn session_params_generate_valid_strings() {
        let (state, verifier) = session_params();

        assert_eq!(state.len(), 32);
        assert_eq!(verifier.len(), 64);
        assert!(state.bytes().all(|byte| byte.is_ascii_alphanumeric()));
        assert!(verifier.bytes().all(|byte| byte.is_ascii_alphanumeric()));
        assert!(parse_state(&state).is_ok());
        assert!(parse_pkce_verifier(&verifier).is_ok());
    }

    /// The scope is the standard pair met with the advertised set, or the
    /// pair as-is when none is advertised.
    #[test]
    fn domain_scope_asks_for_the_domains_the_grant_covers() {
        assert_eq!(
            domain_scope("", "contacts"),
            "urn:ietf:params:oauth:scope:contacts offline_access",
        );
        // NOTE: a calendar grant must not ask to manage contacts.
        assert_eq!(
            domain_scope("", "calendars"),
            "urn:ietf:params:oauth:scope:calendars offline_access",
        );
        // NOTE: a grant shared by several domains asks for all of theirs.
        assert_eq!(
            domain_scope("", "mail calendars"),
            "urn:ietf:params:oauth:scope:mail urn:ietf:params:oauth:scope:calendars offline_access",
        );
        assert_eq!(
            domain_scope(
                "mail urn:ietf:params:oauth:scope:contacts calendars",
                "contacts"
            ),
            "urn:ietf:params:oauth:scope:contacts",
        );
        assert_eq!(domain_scope("mail calendars", "contacts"), "");
    }

    /// An error redirect surfaces the server's error code.
    #[test]
    fn validate_redirect_surfaces_the_server_error() {
        let err = validate_redirect(
            "org.pimalaya:/oauth2redirect\
             ?error=invalid_request\
             &state=stateABCDEF0123456789abcdef012345",
            "stateABCDEF0123456789abcdef012345",
            "",
        )
        .unwrap_err();

        assert_eq!(err, "Authorization error: InvalidRequest");
    }

    /// RFC 9207: a redirect naming another issuer is refused; one naming
    /// none passes, the parameter being optional.
    #[test]
    fn validate_redirect_refuses_another_issuer() {
        const STATE: &str = "stateABCDEF0123456789abcdef012345";
        let redirect =
            |iss: &str| format!("org.pimalaya:/oauth2redirect?code=abc123&state={STATE}{iss}");

        let err = validate_redirect(
            &redirect("&iss=https%3A%2F%2Fevil.example"),
            STATE,
            "https://api.fastmail.com",
        )
        .unwrap_err();
        assert_eq!(
            err,
            "Authorization response from https://evil.example, not from https://api.fastmail.com"
        );

        assert!(validate_redirect(&redirect(""), STATE, "https://api.fastmail.com").is_ok());
        assert!(validate_redirect(&redirect("&iss=https%3A%2F%2Fevil.example"), STATE, "").is_ok());
    }

    /// No failure names the redirect URL, which carries the code.
    #[test]
    fn validate_redirect_errors_never_carry_the_code() {
        let err = validate_redirect("not a url ?code=secretcode", "s", "").unwrap_err();
        assert!(!err.contains("secretcode"), "{err}");

        let err = validate_redirect(
            "org.pimalaya:/oauth2redirect?code=secretcode&state=other",
            "stateABCDEF0123456789abcdef012345",
            "",
        )
        .unwrap_err();
        assert!(!err.contains("secretcode"), "{err}");
    }

    /// A non-https or foreign-scheme endpoint is refused; a loopback one
    /// passes for a custom client's test server.
    #[test]
    fn authorize_url_opens_https_only() {
        let build = |endpoint: &str| {
            authorize_url(&AuthorizationRequest {
                authorization_endpoint: endpoint,
                client_id: "client",
                redirect_uri: "org.pimalaya:/oauth2redirect",
                scope: "mail",
                state: "stateABCDEF0123456789abcdef012345",
                pkce_verifier: "verifierABCDEF0123456789abcdef0123456789ABCDEF0123456789abcdef01",
                extras: "",
            })
        };

        assert!(build("http://auth.example.org/authorize").is_err());
        assert!(build("intent://auth#Intent;scheme=x;end").is_err());
        assert!(build("http://127.0.0.1:8080/authorize").is_ok());
        assert!(build("https://auth.example.org/authorize").is_ok());
    }
}
