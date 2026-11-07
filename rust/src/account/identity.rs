//! # Identity
//!
//! The address a browser grant signed in as, against the account's.

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_jmap::rfc8621::email_submission::JMAP_SUBMISSION_CAPABILITY;
use io_msgraph::v1::send::MsgraphSend;
use serde::{Deserialize, Serialize};
use url::Url;

use crate::{
    account::{GOOGLE_PREFIX, JMAP_PREFIX, MSGRAPH_PREFIX, jmap_session_url},
    calendar::gcal::Gcal,
    client::{
        Auth, Client, Host,
        jmap::Jmap,
        msgraph::{Msgraph, graph_url},
    },
    error::BridgeError,
    mail::gmail::Gmail,
};

/// The mail scope, under which the mailbox's profile names its address.
const MAIL_SCOPE: &str = "https://mail.google.com/";

/// The calendar scopes, under which the primary calendar is the address: the
/// calendar list's, and the full one older grants hold.
const CALENDAR_SCOPES: [&str; 2] = [
    "https://www.googleapis.com/auth/calendar.calendarlist.readonly",
    "https://www.googleapis.com/auth/calendar",
];

/// The hosts one token of that authorization server signs in to.
const GOOGLE_HOSTS: [&str; 5] = [
    "imap.gmail.com",
    "smtp.gmail.com",
    "www.googleapis.com",
    "gmail.googleapis.com",
    "people.googleapis.com",
];

/// The consumer Gmail domains, one mailbox whatever its dots and tags.
const GMAIL_DOMAINS: [&str; 2] = ["gmail.com", "googlemail.com"];

/// The Graph API's host.
const GRAPH_HOST: &str = "graph.microsoft.com";

/// Where a grant's address is read.
#[derive(Debug, Eq, PartialEq)]
pub enum Identity {
    /// The JMAP session's `username` at the session URL, then its identities.
    Jmap(Url),
    /// Graph's `/me`: `mail`, else `userPrincipalName`, then SMTP proxies.
    Graph,
    /// The mailbox profile's address, then its send-as addresses.
    Mailbox,
    /// The primary calendar's id.
    Calendar,
}

/// What a grant signed in as, against the address it was asked for.
#[derive(Debug, Default, Eq, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GrantCheck {
    /// The address read, none where the grant's API names none.
    pub signed_as: Option<String>,
    /// Whether that is another address.
    pub refused: bool,
}

impl Identity {
    /// Where to read the address of a grant over `bases` with `scope`.
    pub fn of(bases: &[String], scope: &str) -> Option<Self> {
        if let Some(base) = bases.iter().find(|base| base.starts_with(JMAP_PREFIX)) {
            return jmap_session_url(base).ok().map(Self::Jmap);
        }

        let hosts: Vec<String> = bases
            .iter()
            .filter_map(|base| Url::parse(base).ok()?.host_str().map(str::to_lowercase))
            .collect();

        let google = bases.iter().any(|base| base.starts_with(GOOGLE_PREFIX))
            || hosts
                .iter()
                .any(|host| GOOGLE_HOSTS.contains(&host.as_str()));
        if google {
            let scopes: Vec<&str> = scope.split_whitespace().collect();
            if scopes.contains(&MAIL_SCOPE) {
                return Some(Self::Mailbox);
            }
            let calendar = scopes.iter().any(|scope| CALENDAR_SCOPES.contains(scope));
            return calendar.then_some(Self::Calendar);
        }

        let graph = bases.iter().any(|base| base.starts_with(MSGRAPH_PREFIX))
            || hosts.iter().any(|host| host == GRAPH_HOST);
        graph.then_some(Self::Graph)
    }

    /// Reads the addresses `token` signs in as, the one the API names first.
    pub fn read<H: Host>(
        &self,
        client: &mut Client<H>,
        token: &str,
    ) -> Result<Vec<String>, BridgeError> {
        match self {
            Self::Jmap(url) => {
                let mut jmap = Jmap {
                    client,
                    session_url: url.clone(),
                    auth: Auth::Bearer(token),
                };
                Ok(vec![jmap.session()?.username])
            }
            Self::Graph => {
                let auth = HttpAuthBearer::new(token);
                let mut url = graph_url("me")?;
                url.query_pairs_mut()
                    .append_pair("$select", "mail,userPrincipalName,proxyAddresses");
                let me = Msgraph { client, token }.run(MsgraphSend::<GraphMe>::get(&auth, url))?;

                let proxies = me.proxy_addresses.into_iter().flatten();
                let smtp = proxies.filter_map(|proxy| {
                    let (kind, address) = proxy.split_once(':')?;
                    kind.eq_ignore_ascii_case("smtp")
                        .then(|| address.to_string())
                });
                Ok(me
                    .mail
                    .or(me.user_principal_name)
                    .into_iter()
                    .chain(smtp)
                    .collect())
            }
            Self::Mailbox => Ok(vec![Gmail { client, token }.address()?]),
            Self::Calendar => Ok(Gcal { client, token }.primary_id()?.into_iter().collect()),
        }
    }

    /// Reads the other addresses `token` sends as, where a call names them.
    pub fn aliases<H: Host>(
        &self,
        client: &mut Client<H>,
        token: &str,
    ) -> Result<Vec<String>, BridgeError> {
        match self {
            Self::Jmap(url) => {
                let mut jmap = Jmap {
                    client,
                    session_url: url.clone(),
                    auth: Auth::Bearer(token),
                };
                let mut calls = jmap.calls()?;
                if !calls
                    .session
                    .capabilities
                    .contains_key(JMAP_SUBMISSION_CAPABILITY)
                {
                    return Ok(Vec::new());
                }

                let identities = calls.identities()?;
                Ok(identities
                    .into_iter()
                    .map(|identity| identity.email)
                    .collect())
            }
            Self::Mailbox => Gmail { client, token }.send_as(),
            Self::Graph | Self::Calendar => Ok(Vec::new()),
        }
    }

    /// Whether `own` and `address` name one mailbox, ignoring case.
    fn same(&self, own: &str, address: &str) -> bool {
        match self {
            Self::Mailbox | Self::Calendar => gmail_mailbox(own) == gmail_mailbox(address),
            Self::Jmap(_) | Self::Graph => own.to_lowercase() == address.to_lowercase(),
        }
    }
}

impl<H: Host> Client<H> {
    /// Whether a grant over `bases` signed in as `address`; unread passes.
    pub fn check_grant(
        &mut self,
        bases: &[String],
        scope: &str,
        token: &str,
        address: &str,
    ) -> GrantCheck {
        let Some(identity) = Identity::of(bases, scope) else {
            return GrantCheck::default();
        };

        let read = identity.read(self, token).unwrap_or_else(|err| {
            log::warn!("grant identity unread: {err}");
            Vec::new()
        });

        // NOTE: a username that is no address names no account to compare.
        let Some(signed_as) = read.first().filter(|signed| signed.contains('@')) else {
            return GrantCheck::default();
        };

        let owns = |owned: &[String]| owned.iter().any(|own| identity.same(own, address));
        let refused = !owns(&read)
            && match identity.aliases(self, token) {
                Ok(aliases) => !owns(&aliases),
                Err(err) => {
                    log::warn!("grant aliases unread: {err}");
                    true
                }
            };

        GrantCheck {
            signed_as: Some(signed_as.clone()),
            refused,
        }
    }
}

/// Graph's `/me` as selected, its proxy addresses included.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
struct GraphMe {
    mail: Option<String>,
    user_principal_name: Option<String>,
    proxy_addresses: Option<Vec<String>>,
}

/// An address lowercased, a consumer Gmail one as its mailbox's.
fn gmail_mailbox(address: &str) -> String {
    let address = address.to_lowercase();
    let Some((local, domain)) = address.rsplit_once('@') else {
        return address;
    };
    if !GMAIL_DOMAINS.contains(&domain) {
        return address;
    }

    let local = local.split_once('+').map_or(local, |(local, _)| local);
    format!("{}@gmail.com", local.replace('.', ""))
}

#[cfg(test)]
mod tests {
    use serde_json::{Value, json};

    use crate::{
        account::identity::{GrantCheck, Identity, MAIL_SCOPE},
        client::{
            Client,
            fake::{BoxedServer, FakeResponse, FakeServer, jmap_session},
        },
    };

    /// The calendar scopes the app's Google client asks for.
    const CALENDAR_GRANT: &str = "https://www.googleapis.com/auth/calendar.events \
                                  https://www.googleapis.com/auth/calendar.calendarlist.readonly";

    fn bases(bases: &[&str]) -> Vec<String> {
        bases.iter().map(|base| base.to_string()).collect()
    }

    /// A Gmail mailbox at `address`, its send-as list `send_as`.
    fn gmail(address: &'static str, send_as: Value) -> Client<BoxedServer<'static>> {
        Client::new(BoxedServer::boxed(move |request| {
            match request.path.as_str() {
                "/gmail/v1/users/me/profile" => {
                    FakeResponse::json(200, &json!({ "emailAddress": address }))
                }
                "/gmail/v1/users/me/settings/sendAs" => {
                    FakeResponse::json(200, &json!({ "sendAs": send_as }))
                }
                other => panic!("unexpected request {other}"),
            }
        }))
    }

    /// Graph's `alice@corp.example`, its proxies named only when selected.
    fn graph(proxies: Value) -> Client<BoxedServer<'static>> {
        Client::new(BoxedServer::boxed(move |request| {
            assert_eq!(request.path, "/v1.0/me");
            let mut user = json!({
                "mail": "alice@corp.example",
                "userPrincipalName": "alice@corp.example",
            });
            let select = request.param("$select").unwrap_or_default();
            if select.split(',').any(|name| name == "proxyAddresses") {
                user["proxyAddresses"] = proxies.clone();
            }
            FakeResponse::json(200, &user)
        }))
    }

    /// A JMAP session of `jane@example.com` that sends as `emails`.
    fn jmap(emails: &'static [&'static str]) -> Client<BoxedServer<'static>> {
        let session = jmap_session(
            json!({
                "urn:ietf:params:jmap:mail": {},
                "urn:ietf:params:jmap:submission": {},
            }),
            json!({ "urn:ietf:params:jmap:mail": "a1" }),
        );
        Client::new(BoxedServer::boxed(move |request| {
            if request.method == "GET" {
                return FakeResponse::json(200, &session);
            }
            let call = &request.json::<Value>()["methodCalls"][0];
            assert_eq!(call[0], "Identity/get");
            let list: Vec<Value> = emails
                .iter()
                .map(|email| json!({ "id": email, "name": "", "email": email }))
                .collect();
            let get = json!({ "accountId": "a1", "state": "s1", "list": list, "notFound": [] });
            let body = json!({
                "methodResponses": [["Identity/get", get, call[2]]],
                "sessionState": "s1",
            });
            FakeResponse::json(200, &body)
        }))
    }

    /// The API a grant's address is read from, by its servers and scope.
    #[test]
    fn a_grant_is_read_where_its_token_is_good() {
        let gmail = bases(&["google://alice@gmail.com", "imaps://imap.gmail.com:993"]);
        let google = "https://mail.google.com/ https://www.googleapis.com/auth/calendar";
        assert_eq!(Identity::of(&gmail, google), Some(Identity::Mailbox));

        let calendar = bases(&["google://alice@gmail.com"]);
        assert_eq!(
            Identity::of(&calendar, CALENDAR_GRANT),
            Some(Identity::Calendar)
        );
        let older = "https://www.googleapis.com/auth/calendar";
        assert_eq!(Identity::of(&calendar, older), Some(Identity::Calendar));
        let events = "https://www.googleapis.com/auth/calendar.events";
        assert_eq!(Identity::of(&calendar, events), None);

        let contacts = bases(&["google://alice@gmail.com"]);
        let scope = "https://www.googleapis.com/auth/contacts";
        assert_eq!(Identity::of(&contacts, scope), None);

        let graph = bases(&["msgraph://alice@outlook.com"]);
        assert_eq!(
            Identity::of(&graph, "Contacts.ReadWrite"),
            Some(Identity::Graph)
        );

        let outlook = bases(&["imaps://outlook.office365.com:993"]);
        assert_eq!(Identity::of(&outlook, "offline_access"), None);

        let jmap = bases(&["jmap://api.example.com/jmap/session"]);
        assert!(matches!(Identity::of(&jmap, ""), Some(Identity::Jmap(_))));

        let dav = bases(&["https://dav.example.org/"]);
        assert_eq!(Identity::of(&dav, "openid"), None);
    }

    #[test]
    fn a_grant_for_another_address_is_refused() {
        let send_as = json!([{ "sendAsEmail": "bob@gmail.com", "isPrimary": true }]);
        let mut client = gmail("bob@gmail.com", send_as);

        let check = client.check_grant(
            &bases(&["google://alice@gmail.com"]),
            "https://mail.google.com/",
            "token",
            "alice@gmail.com",
        );

        assert_eq!(
            check,
            GrantCheck {
                signed_as: Some("bob@gmail.com".into()),
                refused: true,
            }
        );
    }

    /// A calendar grant reads its address off the calendar list, the one the
    /// narrow scopes cover.
    #[test]
    fn a_calendar_grant_for_another_address_is_refused() {
        let mut client = Client::new(FakeServer::new(|request| {
            assert_eq!(request.path, "/calendar/v3/users/me/calendarList/primary");
            FakeResponse::json(200, &json!({ "id": "bob@gmail.com" }))
        }));

        let check = client.check_grant(
            &bases(&["google://alice@gmail.com"]),
            CALENDAR_GRANT,
            "token",
            "alice@gmail.com",
        );

        assert_eq!(
            check,
            GrantCheck {
                signed_as: Some("bob@gmail.com".into()),
                refused: true,
            }
        );
    }

    #[test]
    fn a_consumer_gmail_address_is_taken_in_any_spelling() {
        let mut client = Client::new(FakeServer::new(|request| {
            assert_eq!(request.path, "/gmail/v1/users/me/profile");
            FakeResponse::json(200, &json!({ "emailAddress": "alice@gmail.com" }))
        }));

        for typed in [
            "A.Lice@gmail.com",
            "alice+news@gmail.com",
            "a.l.ice+x.y@googlemail.com",
        ] {
            let base = format!("google://{typed}");
            let check = client.check_grant(&bases(&[&base]), MAIL_SCOPE, "token", typed);

            assert!(!check.refused, "{typed} is refused");
        }
    }

    #[test]
    fn the_gmail_spelling_stays_off_workspace_domains() {
        let send_as = json!([{ "sendAsEmail": "alice@corp.example", "isPrimary": true }]);
        let mut client = gmail("alice@corp.example", send_as);

        for typed in ["a.lice@corp.example", "alice+news@corp.example"] {
            let base = format!("google://{typed}");
            let check = client.check_grant(&bases(&[&base]), MAIL_SCOPE, "token", typed);

            assert!(check.refused, "{typed} is taken");
        }
    }

    #[test]
    fn a_gmail_send_as_address_is_taken() {
        let send_as = json!([
            { "sendAsEmail": "alice@corp.example", "isPrimary": true },
            { "sendAsEmail": "Sales@Corp.Example", "verificationStatus": "accepted" },
        ]);
        let mut client = gmail("alice@corp.example", send_as);

        let check = client.check_grant(
            &bases(&["google://sales@corp.example"]),
            MAIL_SCOPE,
            "token",
            "sales@corp.example",
        );

        assert_eq!(
            check,
            GrantCheck {
                signed_as: Some("alice@corp.example".into()),
                refused: false,
            }
        );
    }

    #[test]
    fn a_graph_proxy_address_is_taken() {
        let proxies = json!(["SMTP:alice@corp.example", "smtp:al@corp.example"]);
        let mut client = graph(proxies);

        let check = client.check_grant(
            &bases(&["msgraph://al@corp.example"]),
            "Mail.ReadWrite",
            "token",
            "AL@corp.example",
        );

        assert_eq!(
            check,
            GrantCheck {
                signed_as: Some("alice@corp.example".into()),
                refused: false,
            }
        );
    }

    #[test]
    fn a_jmap_identity_is_taken() {
        let mut client = jmap(&["jane@example.com", "J@Alias.example"]);

        let check = client.check_grant(
            &bases(&["jmap://identities.example.com/jmap/session"]),
            "",
            "token",
            "j@alias.example",
        );

        assert_eq!(
            check,
            GrantCheck {
                signed_as: Some("jane@example.com".into()),
                refused: false,
            }
        );
    }

    #[test]
    fn an_address_on_no_alias_list_is_refused() {
        let send_as = json!([
            { "sendAsEmail": "alice@corp.example", "isPrimary": true },
            { "sendAsEmail": "sales@corp.example", "verificationStatus": "pending" },
        ]);
        let check = gmail("alice@corp.example", send_as).check_grant(
            &bases(&["google://sales@corp.example"]),
            MAIL_SCOPE,
            "token",
            "sales@corp.example",
        );
        assert!(check.refused, "an unverified send-as address is taken");

        let proxies = json!(["SMTP:alice@corp.example", "SIP:al@corp.example"]);
        let check = graph(proxies).check_grant(
            &bases(&["msgraph://al@corp.example"]),
            "Mail.ReadWrite",
            "token",
            "al@corp.example",
        );
        assert!(check.refused, "a proxy address other than SMTP is taken");

        let check = jmap(&["jane@example.com", "j@alias.example"]).check_grant(
            &bases(&["jmap://others.example.com/jmap/session"]),
            "",
            "token",
            "bob@alias.example",
        );
        assert!(check.refused, "an address no identity names is taken");
    }

    #[test]
    fn the_same_address_in_another_case_is_taken() {
        let session = jmap_session(json!({}), json!({}));
        let mut client = Client::new(FakeServer::new(move |_| FakeResponse::json(200, &session)));

        let check = client.check_grant(
            &bases(&["jmap://api.example.com/jmap/session"]),
            "",
            "token",
            "Jane@Example.com",
        );

        assert_eq!(check.signed_as.as_deref(), Some("jane@example.com"));
        assert!(!check.refused);
    }

    #[test]
    fn a_username_naming_no_address_refuses_nothing() {
        let mut session = jmap_session(json!({}), json!({}));
        session.username = "jane".into();
        let mut client = Client::new(FakeServer::new(move |_| FakeResponse::json(200, &session)));

        let check = client.check_grant(
            &bases(&["jmap://api.example.org/jmap/session"]),
            "",
            "token",
            "jane@example.org",
        );

        assert_eq!(check, GrantCheck::default());
    }

    #[test]
    fn graph_names_the_principal_where_it_has_no_mail() {
        let mut client = Client::new(FakeServer::new(|request| {
            assert_eq!(request.path, "/v1.0/me");
            FakeResponse::json(
                200,
                &json!({ "id": "1", "mail": null, "userPrincipalName": "bob@corp.example" }),
            )
        }));

        let check = client.check_grant(
            &bases(&["msgraph://alice@corp.example"]),
            "Calendars.ReadWrite",
            "token",
            "alice@corp.example",
        );

        assert_eq!(check.signed_as.as_deref(), Some("bob@corp.example"));
        assert!(check.refused);
    }

    #[test]
    fn an_unreadable_address_refuses_nothing() {
        let mut client = Client::new(FakeServer::new(|_| {
            FakeResponse::json(403, &json!({ "error": { "code": 403 } }))
        }));

        let check = client.check_grant(
            &bases(&["msgraph://alice@corp.example"]),
            "Contacts.ReadWrite",
            "token",
            "alice@corp.example",
        );

        assert_eq!(check, GrantCheck::default());
    }
}
