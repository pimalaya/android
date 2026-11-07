//! # Identity
//!
//! The address a browser grant signed in as, against the account's.

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::v1::rest::users::get::MsgraphUserGet;
use serde::Serialize;
use url::Url;

use crate::{
    account::{GOOGLE_PREFIX, JMAP_PREFIX, MSGRAPH_PREFIX, jmap_session_url},
    calendar::gcal::Gcal,
    client::{Auth, Client, Host, jmap::Jmap, msgraph::Msgraph},
    error::BridgeError,
    mail::gmail::Gmail,
};

/// The mail scope, under which the mailbox's profile names its address.
const MAIL_SCOPE: &str = "https://mail.google.com/";

/// The calendar scope, under which the primary calendar is the address.
const CALENDAR_SCOPE: &str = "https://www.googleapis.com/auth/calendar";

/// The hosts one token of that authorization server signs in to.
const GOOGLE_HOSTS: [&str; 6] = [
    "imap.gmail.com",
    "smtp.gmail.com",
    "www.googleapis.com",
    "gmail.googleapis.com",
    "people.googleapis.com",
    "apidata.googleusercontent.com",
];

/// The Graph API's host.
const GRAPH_HOST: &str = "graph.microsoft.com";

/// Where a grant's address is read.
#[derive(Debug, Eq, PartialEq)]
pub enum Identity {
    /// The JMAP session's `username`, at the session URL.
    Jmap(Url),
    /// Graph's `/me`: its `mail`, else its `userPrincipalName`.
    Graph,
    /// The mailbox profile's address.
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
            return scopes.contains(&CALENDAR_SCOPE).then_some(Self::Calendar);
        }

        let graph = bases.iter().any(|base| base.starts_with(MSGRAPH_PREFIX))
            || hosts.iter().any(|host| host == GRAPH_HOST);
        graph.then_some(Self::Graph)
    }

    /// Reads the address `token` signs in as, none when the API names none.
    pub fn read<H: Host>(
        &self,
        client: &mut Client<H>,
        token: &str,
    ) -> Result<Option<String>, BridgeError> {
        match self {
            Self::Jmap(url) => {
                let mut jmap = Jmap {
                    client,
                    session_url: url.clone(),
                    auth: Auth::Bearer(token),
                };
                Ok(Some(jmap.session()?.username).filter(|name| !name.is_empty()))
            }
            Self::Graph => {
                let auth = HttpAuthBearer::new(token);
                let get = MsgraphUserGet::new(&auth, "me").map_err(|err| err.to_string())?;
                let user = Msgraph { client, token }.run(get)?;
                Ok(user.mail.or(user.user_principal_name))
            }
            Self::Mailbox => Gmail { client, token }.address().map(Some),
            Self::Calendar => Gcal { client, token }.primary_id(),
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

        // NOTE: a username that is no address names no account to compare.
        let signed_as = match identity.read(self, token) {
            Ok(signed_as) => signed_as.filter(|signed| signed.contains('@')),
            Err(err) => {
                log::warn!("grant identity unread: {err}");
                None
            }
        };
        let refused = signed_as
            .as_deref()
            .is_some_and(|signed| signed.to_lowercase() != address.to_lowercase());

        GrantCheck { signed_as, refused }
    }
}

#[cfg(test)]
mod tests {
    use serde_json::json;

    use crate::{
        account::identity::{GrantCheck, Identity},
        client::{
            Client,
            fake::{FakeResponse, FakeServer, jmap_session},
        },
    };

    fn bases(bases: &[&str]) -> Vec<String> {
        bases.iter().map(|base| base.to_string()).collect()
    }

    /// The API a grant's address is read from, by its servers and scope.
    #[test]
    fn a_grant_is_read_where_its_token_is_good() {
        let gmail = bases(&["google://alice@gmail.com", "imaps://imap.gmail.com:993"]);
        let google = "https://mail.google.com/ https://www.googleapis.com/auth/calendar";
        assert_eq!(Identity::of(&gmail, google), Some(Identity::Mailbox));

        let calendar = bases(&["https://apidata.googleusercontent.com/caldav/v2/"]);
        let scope = "https://www.googleapis.com/auth/calendar";
        assert_eq!(Identity::of(&calendar, scope), Some(Identity::Calendar));

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
        let mut client = Client::new(FakeServer::new(|request| {
            assert_eq!(request.path, "/gmail/v1/users/me/profile");
            FakeResponse::json(200, &json!({ "emailAddress": "bob@gmail.com" }))
        }));

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
