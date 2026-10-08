//! The People reads over a fake People: a sync round walks the account
//! 1,000 persons a page, carries its request shape in its checkpoint and
//! starts over on an expired token, and a body read goes 200 contacts to
//! a `people:batchGet`.
//!
//! The fake serves the two requests of [`PeopleReads`] and counts each.

use std::collections::BTreeSet;

use io_gpeople::v1::{rest::people::GpeopleStatus, send::GpeopleApiError};
use serde_json::{Value, json};

use super::*;

/// A People account, and what was asked of it.
#[derive(Default)]
struct FakePeople {
    /// The persons a full listing answers, in order.
    persons: Vec<Value>,
    /// The persons a listing from a sync token answers.
    changes: Vec<Value>,
    /// The token the last page of a listing issues.
    issued: String,
    /// The error a listing from a sync token is refused with.
    refusal: Option<(u16, Value)>,
    /// The ids `people:batchGet` no longer finds.
    gone: BTreeSet<String>,
    /// Every listing page asked for: its size and its sync token.
    pages: Vec<(Option<u32>, Option<String>)>,
    /// Every `people:batchGet` sent, by how many names it carried.
    batches: Vec<usize>,
}

fn person(id: &str) -> Value {
    json!({
        "resourceName": format!("people/{id}"),
        "etag": format!("e-{id}"),
        "names": [{"displayName": format!("Jane {id}"), "givenName": "Jane", "familyName": id}],
        "memberships": [{
            "contactGroupMembership": {"contactGroupResourceName": "contactGroups/myContacts"},
        }],
    })
}

fn deleted(id: &str) -> Value {
    json!({
        "resourceName": format!("people/{id}"),
        "etag": format!("e-{id}"),
        "metadata": {"deleted": true},
    })
}

fn account(size: usize) -> FakePeople {
    FakePeople {
        persons: (0..size)
            .map(|index| person(&format!("c{index}")))
            .collect(),
        issued: "token-2".into(),
        ..Default::default()
    }
}

fn api_error(status: u16, body: Value) -> GpeopleSendError {
    GpeopleSendError::Api(GpeopleApiError::parse(status, body.to_string().as_bytes()))
}

impl PeopleReads for FakePeople {
    fn connections(
        &mut self,
        fields: &[GpeoplePersonField],
        params: &GpeopleConnectionsListParams,
    ) -> Result<Result<GpeopleConnectionsListResponse, GpeopleSendError>, BridgeError> {
        self.pages
            .push((params.page_size, params.sync_token.map(str::to_string)));
        if params.request_sync_token {
            assert_eq!(fields, sync_fields(), "a sync round asks the same mask");
        }

        let source = match params.sync_token {
            Some(token) => {
                assert_eq!(token, "token-1", "the bare token, its shape stripped");
                if let Some((status, body)) = &self.refusal {
                    return Ok(Err(api_error(*status, body.clone())));
                }
                &self.changes
            }
            None => &self.persons,
        };

        let size = params.page_size.unwrap_or(100) as usize;
        let start: usize = params.page_token.map_or(0, |token| token.parse().unwrap());
        let end = (start + size).min(source.len());
        let last = end == source.len();

        Ok(Ok(GpeopleConnectionsListResponse {
            connections: source[start..end]
                .iter()
                .map(|person| serde_json::from_value(person.clone()).unwrap())
                .collect(),
            next_page_token: (!last).then(|| end.to_string()),
            next_sync_token: (last && params.request_sync_token).then(|| self.issued.clone()),
            ..Default::default()
        }))
    }

    fn batch_get(&mut self, names: &[String]) -> Result<Vec<GpeoplePersonResponse>, BridgeError> {
        assert!(!names.is_empty() && names.len() <= GOOGLE_BATCH_GET_CHUNK);
        self.batches.push(names.len());

        Ok(names
            .iter()
            .map(|name| {
                let id = name.strip_prefix("people/").unwrap();
                match self.gone.contains(id) {
                    true => GpeoplePersonResponse {
                        requested_resource_name: Some(name.clone()),
                        status: Some(GpeopleStatus {
                            code: Some(5),
                            message: Some("Requested entity was not found.".into()),
                            ..Default::default()
                        }),
                        ..Default::default()
                    },
                    false => GpeoplePersonResponse {
                        requested_resource_name: Some(name.clone()),
                        person: Some(serde_json::from_value(person(id)).unwrap()),
                        http_status_code: Some(200),
                        ..Default::default()
                    },
                }
            })
            .collect())
    }
}

#[test]
fn a_full_round_walks_the_account_a_thousand_a_page() {
    let mut people = account(2_500);

    let delta = sync_cards(&mut people, None).unwrap().unwrap();

    // NOTE: 25 pages of 100 before.
    assert_eq!(
        people.pages,
        [
            (Some(1_000), None),
            (Some(1_000), None),
            (Some(1_000), None)
        ]
    );
    assert_eq!(delta.changed.len(), 2_500);
    assert_eq!(delta.token.as_deref(), Some("v2:token-2"));
    assert!(delta.changed[0].vcard.contains("Jane c0"));
    assert_eq!(delta.changed[0].books, ["myContacts"]);
}

#[test]
fn a_delta_round_sends_the_token_alone() {
    let mut people = account(10);
    people.changes = vec![person("c1"), deleted("c2")];

    let delta = sync_cards(&mut people, Some("v2:token-1"))
        .unwrap()
        .unwrap();

    assert_eq!(people.pages, [(Some(1_000), Some("token-1".into()))]);
    assert_eq!(delta.changed.len(), 1);
    assert_eq!(delta.changed[0].id, "c1");
    assert_eq!(delta.vanished, ["c2"]);
    assert_eq!(delta.token.as_deref(), Some("v2:token-2"));
}

#[test]
fn a_token_of_another_shape_starts_over_unsent() {
    let mut people = account(10);

    assert!(sync_cards(&mut people, Some("token-1")).unwrap().is_none());
    assert!(people.pages.is_empty());
}

#[test]
fn an_expired_token_starts_over() {
    for (status, body) in [
        (410, json!({"error": {"code": 410, "message": "Gone"}})),
        (
            400,
            json!({"error": {
                "code": 400,
                "message": "Sync token is expired. Clear local cache and retry call without the sync token.",
                "status": "FAILED_PRECONDITION",
                "details": [{
                    "@type": "type.googleapis.com/google.rpc.ErrorInfo",
                    "reason": "EXPIRED_SYNC_TOKEN",
                }],
            }}),
        ),
    ] {
        let mut people = account(10);
        people.refusal = Some((status, body));

        assert!(
            sync_cards(&mut people, Some("v2:token-1"))
                .unwrap()
                .is_none()
        );
    }
}

#[test]
fn another_refusal_fails_the_round() {
    let mut people = account(10);
    people.refusal = Some((
        400,
        json!({"error": {"code": 400, "message": "Invalid personFields mask", "status": "INVALID_ARGUMENT"}}),
    ));

    let Err(err) = sync_cards(&mut people, Some("v2:token-1")) else {
        panic!("the round went through");
    };
    assert_eq!(err.status, Some(400));
}

#[test]
fn a_listing_reads_a_thousand_a_page() {
    let mut people = account(1_200);

    let cards = list_cards(&mut people).unwrap();

    assert_eq!(cards.len(), 1_200);
    assert_eq!(people.pages, [(Some(1_000), None), (Some(1_000), None)]);
}

#[test]
fn a_body_read_goes_two_hundred_to_a_batch() {
    let mut people = account(0);
    people.gone.insert("c7".into());
    let ids: Vec<String> = (0..450).map(|index| format!("c{index}")).collect();
    let ids: Vec<&str> = ids.iter().map(String::as_str).collect();

    let cards = read_cards(&mut people, &ids).unwrap();

    // NOTE: 450 people.get before.
    assert_eq!(people.batches, [200, 200, 50]);
    assert_eq!(cards.len(), 449);
    assert_eq!(cards[0].id, "c0");
    assert_eq!(cards[7].id, "c8");
    assert_eq!(cards[448].id, "c449");
    assert!(cards[0].vcard.contains("Jane c0"));
}

#[test]
fn a_body_read_fails_on_anything_but_not_found() {
    struct Refusing;
    impl PeopleReads for Refusing {
        fn connections(
            &mut self,
            _: &[GpeoplePersonField],
            _: &GpeopleConnectionsListParams,
        ) -> Result<Result<GpeopleConnectionsListResponse, GpeopleSendError>, BridgeError> {
            unreachable!()
        }

        fn batch_get(
            &mut self,
            names: &[String],
        ) -> Result<Vec<GpeoplePersonResponse>, BridgeError> {
            Ok(names
                .iter()
                .map(|name| GpeoplePersonResponse {
                    requested_resource_name: Some(name.clone()),
                    status: Some(GpeopleStatus {
                        code: Some(7),
                        message: Some("The caller does not have permission".into()),
                        ..Default::default()
                    }),
                    ..Default::default()
                })
                .collect())
        }
    }

    let Err(err) = read_cards(&mut Refusing, &["c1"]) else {
        panic!("the read went through");
    };
    assert!(err.message.contains("people/c1"));
}

/// myContacts is the default book, and a user group a book beside it;
/// the other system groups are no book at all.
#[test]
fn my_contacts_is_the_default_book() {
    let listed = |group: Value| google_book(serde_json::from_value(group).unwrap());

    let contacts = listed(json!({
        "resourceName": "contactGroups/myContacts", "groupType": "SYSTEM_CONTACT_GROUP",
    }))
    .unwrap();
    let friends = listed(json!({
        "resourceName": "contactGroups/abc", "groupType": "USER_CONTACT_GROUP",
        "name": "Friends",
    }))
    .unwrap();
    let starred = listed(json!({
        "resourceName": "contactGroups/starred", "groupType": "SYSTEM_CONTACT_GROUP",
    }));

    assert_eq!(contacts.role, "default");
    assert!(contacts.writable);
    assert_eq!(friends.role, "");
    assert_eq!(friends.name, "Friends");
    assert!(starred.is_none());
}
