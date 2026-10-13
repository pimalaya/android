//! # JMAP mail tests
//!
//! The JMAP mail verbs: a send through Drafts to Sent over a fake account,
//! delta projection, `Email/set` arguments and refusals, blob download URLs.
//!
//! The fake answers the requests a submission sends (RFC 8621 sections
//! 4.8, 6.1, 7.5 and RFC 8620 section 6.1) the way a server does where it
//! matters here, and records what it was asked.

use secrecy::SecretString;
use serde_json::{Map, Value, from_value, json, to_value};

use crate::{
    client::{
        Auth, Client,
        fake::{FakeRequest, FakeResponse, FakeServer, jmap_session},
    },
    error::REFUSED,
    mail::jmap::*,
};

/// A message to Ada, copying Bob, blind to Carol.
const MESSAGE: &str = "From: Jane <jane@example.com>\r\n\
    To: ada@example.com\r\n\
    Cc: bob@example.com\r\n\
    Bcc: carol@example.com\r\n\
    Subject: Hi\r\n\
    Message-ID: <m1@example.com>\r\n\
    \r\n\
    Hello\r\n";

/// A session whose mail account is `u42`, downloading from `template`.
fn session(template: &str) -> JmapSession {
    let mut session = jmap_session(json!({}), json!({ "urn:ietf:params:jmap:mail": "u42" }));
    session.download_url = template.to_string();
    session
}

/// A JMAP mail account, and what a submission asked of it.
struct FakeMail {
    identities: Vec<JmapIdentity>,
    mailboxes: Vec<(String, String, String)>,
    uploaded: Vec<Vec<u8>>,
    imported: Vec<(String, String)>,
    submitted: Vec<Value>,
    destroyed: Vec<String>,
    /// The error the import answers the message with.
    import_refusal: Option<Value>,
    /// The error the submission answers the message with.
    refusal: Option<Value>,
    /// Whether the submission's answer never comes.
    lost: bool,
}

fn identity(id: &str, email: &str) -> JmapIdentity {
    from_value(json!({ "id": id, "name": "", "email": email })).unwrap()
}

fn mailbox(id: &str, role: &str) -> (String, String, String) {
    (id.into(), id.into(), role.into())
}

fn account() -> FakeMail {
    FakeMail {
        identities: vec![
            identity("i0", "other@example.com"),
            identity("i1", "Jane@Example.com"),
        ],
        mailboxes: vec![
            mailbox("mb-inbox", "inbox"),
            mailbox("mb-drafts", "drafts"),
            mailbox("mb-sent", "sent"),
        ],
        uploaded: Vec::new(),
        imported: Vec::new(),
        submitted: Vec::new(),
        destroyed: Vec::new(),
        import_refusal: None,
        refusal: None,
        lost: false,
    }
}

impl FakeMail {
    /// Sends `raw` over this account, the server taking `max_upload` bytes
    /// at most.
    fn send(&mut self, raw: &[u8], max_upload: Option<u64>) -> Result<(), BridgeError> {
        let mut client = Client::new(FakeServer::new(|request| self.answer(request)));
        let mut jmap = Jmap {
            client: &mut client,
            session_url: Url::parse("https://api.example.com/jmap/session").unwrap(),
            auth: Auth::Bearer("token"),
        };
        let mut session = session("");
        session.upload_url = "https://api.example.com/jmap/upload/{accountId}/".into();
        let mut calls = JmapCalls {
            jmap: &mut jmap,
            session,
            auth: SecretString::from("Bearer token"),
        };
        calls.send(raw, max_upload)
    }

    /// Answers one request: a blob upload (RFC 8620 section 6.1), or one
    /// JMAP API request, its method calls in order (RFC 8620 section 3.4).
    fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
        assert_eq!(request.header("Authorization"), Some("Bearer token"));
        match (request.method.as_str(), request.path.as_str()) {
            ("POST", "/jmap/upload/u42/") => {
                assert_eq!(request.header("Content-Type"), Some("message/rfc822"));
                self.uploaded.push(request.body.clone());
                let blob = json!({
                    "accountId": "u42",
                    "blobId": "blob1",
                    "type": "message/rfc822",
                    "size": request.body.len(),
                });
                FakeResponse::json(201, &blob)
            }
            ("POST", "/jmap/") => {
                let calls: Vec<(String, Map<String, Value>, String)> =
                    from_value(request.json::<Value>()["methodCalls"].take()).unwrap();
                let mut responses = Vec::new();
                for (name, mut args, id) in calls {
                    assert_eq!(args.remove("accountId"), Some(json!("u42")));
                    let (name, result) = match name.as_str() {
                        "Identity/get" => ("Identity/get", self.identities_get()),
                        "Mailbox/get" => ("Mailbox/get", self.mailboxes_get()),
                        "Email/import" => ("Email/import", self.import(&args)),
                        "EmailSubmission/set" if self.lost => {
                            self.submitted.push(Value::Object(args));
                            return FakeResponse::lost();
                        }
                        "EmailSubmission/set" => {
                            self.submitted.push(Value::Object(args));
                            ("EmailSubmission/set", self.submission())
                        }
                        "Email/set" => ("Email/set", self.destroy(&args)),
                        other => panic!("unexpected method {other}"),
                    };
                    responses.push(json!([name, result, id]));
                }
                let body = json!({ "methodResponses": responses, "sessionState": "s1" });
                FakeResponse::json(200, &body)
            }
            other => panic!("unexpected request {other:?}"),
        }
    }

    /// The `Identity/get` response: every identity.
    fn identities_get(&self) -> Value {
        json!({
            "accountId": "u42",
            "state": "i1",
            "list": self.identities,
            "notFound": [],
        })
    }

    /// The `Mailbox/get` response: every mailbox, named by its path.
    fn mailboxes_get(&self) -> Value {
        let list: Vec<Value> = self
            .mailboxes
            .iter()
            .map(|(id, path, role)| json!({ "id": id, "name": path, "role": role }))
            .collect();
        json!({ "accountId": "u42", "state": "m1", "list": list, "notFound": [] })
    }

    /// The `Email/import` response of the one email imported, `m0`.
    fn import(&mut self, args: &Map<String, Value>) -> Value {
        let import = &args["emails"]["m0"];
        let drafts = import["mailboxIds"].as_object().unwrap();
        assert_eq!(drafts.len(), 1);
        let drafts = drafts.keys().next().unwrap();
        let blob = import["blobId"].as_str().unwrap();
        self.imported.push((blob.into(), drafts.clone()));

        let mut result = json!({ "accountId": "u42", "oldState": "e1", "newState": "e2" });
        match self.import_refusal.clone() {
            Some(refusal) => result["notCreated"] = json!({ "m0": refusal }),
            None => {
                result["created"] = json!({ "m0": {
                    "id": "email1",
                    "blobId": blob,
                    "threadId": "t1",
                    "size": 120,
                } })
            }
        }
        result
    }

    /// The `EmailSubmission/set` response of the one submission, `s0`.
    fn submission(&self) -> Value {
        let mut result = json!({ "accountId": "u42", "oldState": "u1", "newState": "u2" });
        match self.refusal.clone() {
            Some(refusal) => result["notCreated"] = json!({ "s0": refusal }),
            None => result["created"] = json!({ "s0": { "id": "sub1" } }),
        }
        result
    }

    /// The `Email/set` response destroying what it names.
    fn destroy(&mut self, args: &Map<String, Value>) -> Value {
        let destroyed: Vec<String> = from_value(args["destroy"].clone()).unwrap();
        self.destroyed.extend(destroyed.iter().cloned());
        json!({
            "accountId": "u42",
            "oldState": "e2",
            "newState": "e3",
            "destroyed": destroyed,
        })
    }
}

#[test]
fn a_message_goes_through_drafts_to_sent_with_its_blind_copy_in_the_envelope() {
    let mut mail = account();

    mail.send(MESSAGE.as_bytes(), None).unwrap();

    let uploaded = String::from_utf8(mail.uploaded[0].clone()).unwrap();
    assert!(!uploaded.contains("Bcc"), "{uploaded}");
    assert!(uploaded.contains("Message-ID: <m1@example.com>"));
    assert_eq!(mail.imported, [("blob1".into(), "mb-drafts".into())]);
    assert_eq!(
        mail.submitted,
        [json!({
            "create": { "s0": {
                "identityId": "i1",
                "emailId": "email1",
                "envelope": {
                    "mailFrom": { "email": "jane@example.com", "parameters": null },
                    "rcptTo": [
                        { "email": "ada@example.com", "parameters": null },
                        { "email": "bob@example.com", "parameters": null },
                        { "email": "carol@example.com", "parameters": null },
                    ],
                },
            } },
            "onSuccessUpdateEmail": { "#s0": {
                "mailboxIds/mb-drafts": null,
                "mailboxIds/mb-sent": true,
                "keywords/$draft": null,
            } },
        })]
    );
    assert!(mail.destroyed.is_empty());
}

#[test]
fn an_account_with_no_sent_mailbox_keeps_no_copy() {
    let mut mail = account();
    mail.mailboxes.retain(|(_, _, role)| role != "sent");

    mail.send(MESSAGE.as_bytes(), None).unwrap();

    assert_eq!(mail.submitted[0]["onSuccessDestroyEmail"], json!(["#s0"]));
    assert!(mail.submitted[0].get("onSuccessUpdateEmail").is_none());
}

#[test]
fn a_wildcard_identity_sends_as_any_address_of_its_domain() {
    let mut mail = account();
    mail.identities = vec![identity("i9", "*@example.com")];

    mail.send(MESSAGE.as_bytes(), None).unwrap();

    assert_eq!(mail.submitted[0]["create"]["s0"]["identityId"], "i9");
}

#[test]
fn a_sender_the_server_refuses_is_refused_for_good_and_its_draft_destroyed() {
    let mut mail = account();
    mail.refusal = Some(json!({ "type": "forbiddenFrom", "description": "Not yours" }));

    let refused = mail.send(MESSAGE.as_bytes(), None).unwrap_err();

    assert_eq!(refused.status, Some(REFUSED));
    assert_eq!(
        refused.message,
        "The server refused to send the message: JMAP forbiddenFrom: Not yours"
    );
    assert_eq!(mail.destroyed, ["email1"]);
}

#[test]
fn a_rate_limit_waits_for_the_next_drain() {
    let mut mail = account();
    mail.refusal = Some(json!({ "type": "rateLimit" }));

    let failed = mail.send(MESSAGE.as_bytes(), None).unwrap_err();

    assert_eq!(failed.status, None);
    assert_eq!(
        mail.destroyed,
        ["email1"],
        "the next drain imports it again"
    );
}

#[test]
fn a_submission_whose_answer_never_came_keeps_its_draft() {
    let mut mail = account();
    mail.lost = true;

    let failed = mail.send(MESSAGE.as_bytes(), None).unwrap_err();

    assert_eq!(failed.status, None);
    assert!(
        mail.destroyed.is_empty(),
        "the server may have sent it and filed it in Sent already"
    );
}

#[test]
fn an_import_the_server_refuses_is_refused_for_good() {
    let mut mail = account();
    mail.import_refusal = Some(json!({ "type": "invalidEmail" }));

    let refused = mail.send(MESSAGE.as_bytes(), None).unwrap_err();

    assert_eq!(refused.status, Some(REFUSED));
    assert!(mail.submitted.is_empty());
}

#[test]
fn what_no_pass_changes_is_refused_before_anything_is_written() {
    let mut nobody = account();
    nobody.identities = vec![identity("i0", "other@example.com")];
    let mut no_drafts = account();
    no_drafts.mailboxes.retain(|(_, _, role)| role != "drafts");
    let mut too_large = account();

    let refusals = [
        nobody.send(MESSAGE.as_bytes(), None).unwrap_err(),
        no_drafts.send(MESSAGE.as_bytes(), None).unwrap_err(),
        too_large.send(MESSAGE.as_bytes(), Some(10)).unwrap_err(),
    ];

    for refused in &refusals {
        assert_eq!(refused.status, Some(REFUSED), "{}", refused.message);
    }
    for mail in [nobody, no_drafts, too_large] {
        assert!(mail.uploaded.is_empty());
    }
}

/// One email of a mail delta, filed in `mailboxes`.
fn filed(id: &str, mailboxes: &[&str]) -> JmapEmail {
    let mailbox_ids: BTreeMap<&str, bool> = mailboxes.iter().map(|id| (*id, true)).collect();
    from_value(json!({
        "id": id,
        "mailboxIds": mailbox_ids,
        "subject": id,
        "sentAt": "2026-10-10T12:00:00Z",
    }))
    .unwrap()
}

#[test]
fn a_mailbox_projects_the_account_changes_by_mailbox_ids() {
    let delta = JmapDelta {
        next: "s2".into(),
        emails: vec![
            filed("m1", &["inbox", "clients"]),
            filed("m2", &["archive"]),
        ],
        gone: vec!["m3".into()],
    };

    let inbox = project(&delta, "inbox", &Scope::default());
    let archive = project(&delta, "archive", &Scope::default());

    let members = |page: &MailPage| -> Vec<String> {
        page.items.iter().map(|item| item.handle.clone()).collect()
    };
    assert_eq!(members(&inbox), ["m1"]);
    assert_eq!(inbox.vanished, ["m3", "m2"]);
    assert_eq!(inbox.checkpoint.as_deref(), Some("s2"));
    assert_eq!(members(&archive), ["m2"]);
    assert_eq!(archive.vanished, ["m3", "m1"]);
}

#[test]
fn an_email_set_on_a_message_gone_answers_404_for_the_caller_to_converge() {
    let gone = JmapEmailSetItemError::NotFound { description: None };
    let failed = email_set_refusal("m1", Some(&gone)).unwrap_err();
    assert_eq!(failed.status, Some(404));
    assert!(converged(Err::<(), _>(failed)).unwrap().is_none());

    let refused = JmapEmailSetItemError::TooManyKeywords { description: None };
    assert_eq!(
        email_set_refusal("m1", Some(&refused)).unwrap_err().status,
        None
    );
    assert!(email_set_refusal("m1", None).is_ok());
}

/// Every variable is filled, or the download reaches a path with a brace
/// in it and the server answers 404 rather than the message.
#[test]
fn a_download_url_leaves_no_variable_behind() {
    let session =
        session("https://api.example.com/jmap/download/{accountId}/{blobId}/{name}?accept={type}");

    let url = download_url(&session, "G12ab").unwrap();

    assert_eq!(
        url.as_str(),
        "https://api.example.com/jmap/download/u42/G12ab/message.eml?accept=message%2Frfc822"
    );
}

/// A blob id is opaque, so it routinely carries what a path separates
/// segments with.
#[test]
fn a_blob_id_is_encoded_rather_than_pasted() {
    let session = session("https://api.example.com/d/{accountId}/{blobId}");

    let url = download_url(&session, "a/b c").unwrap();

    assert_eq!(url.as_str(), "https://api.example.com/d/u42/a%2Fb%20c");
}

#[test]
fn a_relocation_trades_one_membership_for_the_other() {
    let args = to_value(relocation_args("m1", "inbox", "trash")).unwrap();

    assert_eq!(
        args,
        json!({ "update": { "m1": { "mailboxIds/trash": true, "mailboxIds/inbox": null } } })
    );
}

#[test]
fn a_copy_adds_a_membership() {
    let args = to_value(copy_args("m1", "archive")).unwrap();

    assert_eq!(
        args,
        json!({ "update": { "m1": { "mailboxIds/archive": true } } })
    );
}

#[test]
fn a_delete_destroys() {
    let args = to_value(destroy_args("m1")).unwrap();

    assert_eq!(args, json!({ "destroy": ["m1"] }));
}
