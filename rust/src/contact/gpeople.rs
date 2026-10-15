//! # Google People
//!
//! The [`Gpeople`] backend: contact groups and connections as addressbooks
//! and cards, the batch verbs, group memberships and the sync-token round.

use std::collections::HashMap;

use io_gpeople::{
    coroutine::{GpeopleCoroutine, GpeopleCoroutineState, GpeopleYield},
    v1::{
        rest::{
            contact_groups::{
                GpeopleContactGroup, GpeopleContactGroupType,
                list::{GpeopleContactGroupsList, GpeopleContactGroupsListParams},
                members::modify::GpeopleContactGroupMembersModify,
            },
            people::{
                GpeoplePerson, GpeoplePersonField, GpeoplePersonResponse,
                batch_create_contacts::GpeopleContactsBatchCreate,
                batch_delete_contacts::GpeopleContactsBatchDelete,
                connections::list::{
                    GpeopleConnectionsList, GpeopleConnectionsListParams,
                    GpeopleConnectionsListResponse,
                },
                create_contact::GpeopleContactCreate,
                delete_contact::GpeopleContactDelete,
                get::GpeoplePersonGet,
                get_batch_get::GpeoplePersonsBatchGet,
                update_contact::GpeopleContactUpdate,
                vcard::{GPEOPLE_PERSON_STASH_KEY, GPEOPLE_PERSON_VCARD_FIELDS},
            },
        },
        send::{GPEOPLE_API_BASE, GpeopleSendError, GpeopleSendOutput},
    },
};
use io_http::rfc6750::bearer::HttpAuthBearer;

use crate::{
    client::{Client, Host},
    contact::{Addressbook, Card, CardDelta},
    error::{BridgeError, coroutine_error},
    offline::default_role,
};

/// The person fields an update with no base replaces: every field the
/// vCard projection writes, the `clientData` stash included.
///
/// People-only fields stay out, so they survive the update untouched.
const MANAGED_FIELDS: &[GpeoplePersonField] = &[
    GpeoplePersonField::Addresses,
    GpeoplePersonField::Biographies,
    GpeoplePersonField::Birthdays,
    GpeoplePersonField::ClientData,
    GpeoplePersonField::EmailAddresses,
    GpeoplePersonField::ImClients,
    GpeoplePersonField::Names,
    GpeoplePersonField::Nicknames,
    GpeoplePersonField::Occupations,
    GpeoplePersonField::Organizations,
    GpeoplePersonField::PhoneNumbers,
    GpeoplePersonField::Relations,
    GpeoplePersonField::Urls,
];

/// How many contacts one people.batchCreateContacts call carries.
const GOOGLE_CREATE_CHUNK: usize = 200;

/// How many resource names one people.batchDeleteContacts call carries.
const GOOGLE_DELETE_CHUNK: usize = 500;

/// A Google account's People API, signed in to with an OAuth 2.0 token.
pub struct Gpeople<'c, H> {
    /// The client the requests go through.
    pub client: &'c mut Client<H>,
    /// The OAuth 2.0 access token.
    pub token: &'c str,
}

/// Google People contact verbs.
impl<H: Host> Gpeople<'_, H> {
    /// Lists the contact groups as addressbooks, myContacts first.
    ///
    /// Memberships are m:n labels, so a card can sit in several books.
    /// Doubles as the onboarding connection check. URLs are left empty
    /// for the caller to compose.
    pub fn list_addressbooks(&mut self) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);

        let mut books = Vec::new();
        let mut page_token: Option<String> = None;
        loop {
            let params = GpeopleContactGroupsListParams {
                page_token: page_token.as_deref(),
                ..Default::default()
            };
            let coroutine = GpeopleContactGroupsList::new(&auth, &[], &params)
                .map_err(|err| err.to_string())?;
            let page = self.run(coroutine)?;

            for book in page.contact_groups.into_iter().filter_map(google_book) {
                if book.role.is_empty() {
                    books.push(book);
                } else {
                    books.insert(0, book);
                }
            }

            match page.next_page_token {
                Some(next) => page_token = Some(next),
                None => break,
            }
        }

        Ok(books)
    }

    /// Lists the contact changes since `cursor`, bodies whole, all without one.
    ///
    /// [`None`] when Google expired the cursor or it is of another shape
    /// ([`SYNC_CHECKPOINT`]). The round is the account's whatever book
    /// asks: one sync token covers every contact group.
    pub fn sync_cards(&mut self, cursor: Option<&str>) -> Result<Option<CardDelta>, BridgeError> {
        let sync_token = match cursor {
            None => None,
            Some(cursor) => match cursor.strip_prefix(SYNC_CHECKPOINT) {
                Some(token) => Some(token),
                None => {
                    log::info!("people checkpoint issued for another listing, listing in full");
                    return Ok(None);
                }
            },
        };

        // NOTE: a sync token binds to its field mask, so every round asks the
        // same one.
        let fields = sync_fields();
        let mut delta = CardDelta {
            changed: Vec::new(),
            vanished: Vec::new(),
            token: None,
            complete: false,
        };
        let mut page_token: Option<String> = None;

        loop {
            let params = GpeopleConnectionsListParams {
                page_size: Some(PEOPLE_PAGE_SIZE),
                page_token: page_token.as_deref(),
                request_sync_token: true,
                sync_token,
                ..Default::default()
            };
            let page = match self.connections(&fields, &params)? {
                Ok(page) => page,
                Err(err) if sync_token.is_some() && err.is_sync_token_expired() => {
                    log::info!("people sync token expired, listing in full");
                    return Ok(None);
                }
                Err(err) => return Err(coroutine_error(&err)),
            };

            for person in page.connections {
                let deleted = person
                    .metadata
                    .as_ref()
                    .and_then(|metadata| metadata.deleted)
                    .unwrap_or(false);
                if deleted {
                    delta.vanished.push(person.id().to_string());
                } else {
                    delta.changed.push(google_card(person));
                }
            }

            if let Some(next) = page.next_sync_token {
                delta.token = Some(format!("{SYNC_CHECKPOINT}{next}"));
            }
            match page.next_page_token {
                Some(next) => page_token = Some(next),
                None => return Ok(Some(delta)),
            }
        }
    }

    /// Reads the named contacts in order, [`GOOGLE_BATCH_GET_CHUNK`] to a
    /// `people:batchGet`.
    ///
    /// A contact Google no longer holds is left out; any other failure of
    /// one contact fails the read.
    pub fn read_cards(&mut self, ids: &[&str]) -> Result<Vec<Card>, BridgeError> {
        let names: Vec<String> = ids.iter().map(|id| format!("people/{id}")).collect();
        let mut cards = Vec::with_capacity(ids.len());

        for chunk in names.chunks(GOOGLE_BATCH_GET_CHUNK) {
            let mut read: HashMap<String, GpeoplePerson> = HashMap::with_capacity(chunk.len());
            for response in self.batch_get(chunk)? {
                let name = response.requested_resource_name.clone();
                match response.person {
                    Some(person) => {
                        let name = name.unwrap_or_else(|| person.resource_name.clone());
                        read.insert(name, person);
                    }
                    // NOTE: google.rpc.Code 5 is NOT_FOUND.
                    None if response.http_status_code == Some(404)
                        || response.status.as_ref().and_then(|status| status.code) == Some(5) => {}
                    None => {
                        let message = response
                            .status
                            .and_then(|status| status.message)
                            .unwrap_or_else(|| "no person and no reason".to_string());
                        let name = name.unwrap_or_default();
                        return Err(format!("People batch read of {name} failed: {message}").into());
                    }
                }
            }

            cards.extend(
                chunk
                    .iter()
                    .filter_map(|name| read.remove(name))
                    .map(google_card),
            );
        }

        Ok(cards)
    }

    /// Creates the vCard as a contact, under the id the server assigns.
    pub fn create_card(&mut self, vcard: &str) -> Result<Card, BridgeError> {
        let person = GpeoplePerson::from_vcard(vcard)?;
        let auth = HttpAuthBearer::new(self.token);

        let coroutine = GpeopleContactCreate::new(&auth, &person, GPEOPLE_PERSON_VCARD_FIELDS, &[])
            .map_err(|err| err.to_string())?;
        Ok(google_card(self.run(coroutine)?))
    }

    /// Reads the People contact `id`, projected onto a vCard document.
    pub fn read_card(&mut self, id: &str) -> Result<Card, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GpeoplePersonGet::new(
            &auth,
            &format!("people/{id}"),
            GPEOPLE_PERSON_VCARD_FIELDS,
            &[],
        )
        .map_err(|err| err.to_string())?;

        Ok(google_card(self.run(coroutine)?))
    }

    /// Updates the contact `id`, masked to the fields changed since the
    /// base, else every [`MANAGED_FIELDS`].
    ///
    /// People requires the current etag, so a missing one is fetched; a
    /// stale one fails the update (no silent last-write-wins).
    pub fn update_card(
        &mut self,
        id: &str,
        vcard: &str,
        base_vcard: Option<&str>,
        etag: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let resource_name = format!("people/{id}");

        let mut person = GpeoplePerson::from_vcard(vcard)?;
        person.resource_name = resource_name.clone();

        let fields = match base_vcard {
            Some(base) => person.changed_fields(&GpeoplePerson::from_vcard(base)?),
            None => MANAGED_FIELDS.to_vec(),
        };
        if fields.is_empty() {
            return Ok(Card {
                id: id.to_string(),
                uri: id.to_string(),
                etag: etag.map(str::to_string),
                vcard: vcard.to_string(),
                books: Vec::new(),
            });
        }

        // NOTE: a masked clientData update replaces the whole list, so
        // the server's foreign entries are merged in first (the etag
        // guards the race); the same fetch sources a missing etag.
        let needs_merge = fields.contains(&GpeoplePersonField::ClientData);
        if needs_merge || etag.is_none() {
            let coroutine = GpeoplePersonGet::new(
                &auth,
                &resource_name,
                &[GpeoplePersonField::ClientData],
                &[],
            )
            .map_err(|err| err.to_string())?;
            let current = self.run(coroutine)?;

            if needs_merge {
                let mut merged: Vec<_> = current
                    .client_data
                    .into_iter()
                    .filter(|entry| entry.key.as_deref() != Some(GPEOPLE_PERSON_STASH_KEY))
                    .collect();
                merged.append(&mut person.client_data);
                person.client_data = merged;
            }
            person.etag = match etag {
                Some(etag) => etag.to_string(),
                None => current.etag,
            };
        } else {
            person.etag = etag.unwrap_or_default().to_string();
        }

        let coroutine =
            GpeopleContactUpdate::new(&auth, &person, &fields, GPEOPLE_PERSON_VCARD_FIELDS, &[])
                .map_err(|err| err.to_string())?;
        match self.run(coroutine) {
            Ok(updated) => Ok(google_card(updated)),
            // NOTE: updateContact accepts a people.get etag only, not the
            // connections.list one the engine carries: retry once on a
            // fresh one, the engine still re-conflicting a changed row.
            Err(err)
                if err.status == Some(400) && err.message.to_ascii_lowercase().contains("etag") =>
            {
                let coroutine = GpeoplePersonGet::new(
                    &auth,
                    &resource_name,
                    &[GpeoplePersonField::ClientData],
                    &[],
                )
                .map_err(|err| err.to_string())?;
                person.etag = self.run(coroutine)?.etag;

                let coroutine = GpeopleContactUpdate::new(
                    &auth,
                    &person,
                    &fields,
                    GPEOPLE_PERSON_VCARD_FIELDS,
                    &[],
                )
                .map_err(|err| err.to_string())?;
                Ok(google_card(self.run(coroutine)?))
            }
            Err(err) => Err(err),
        }
    }

    /// Deletes the People contact `id`.
    pub fn delete_card(&mut self, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GpeopleContactDelete::new(&auth, &format!("people/{id}"))
            .map_err(|err| err.to_string())?;
        self.run(coroutine)?;

        Ok(())
    }

    /// Creates the vCards as contacts, [`GOOGLE_CREATE_CHUNK`] a call,
    /// returned in input order.
    ///
    /// A create carries no correlation key: request order is the
    /// response's contract.
    pub fn create_cards(&mut self, vcards: &[String]) -> Result<Vec<Card>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);

        let persons = vcards
            .iter()
            .map(|vcard| GpeoplePerson::from_vcard(vcard))
            .collect::<Result<Vec<_>, _>>()?;

        let mut cards = Vec::with_capacity(persons.len());
        for chunk in persons.chunks(GOOGLE_CREATE_CHUNK) {
            let coroutine =
                GpeopleContactsBatchCreate::new(&auth, chunk, GPEOPLE_PERSON_VCARD_FIELDS, &[])
                    .map_err(|err| err.to_string())?;
            let response = self.run(coroutine)?;

            // NOTE: a count mismatch would misattribute server ids to
            // vCards, so abort rather than guess.
            if response.created_people.len() != chunk.len() {
                return Err(format!(
                    "Google batch create answered {} contacts for {}",
                    response.created_people.len(),
                    chunk.len()
                )
                .into());
            }
            for created in response.created_people {
                let person = created
                    .person
                    .ok_or("Google batch create answered a personless entry")?;
                cards.push(google_card(person));
            }
        }

        Ok(cards)
    }

    /// Deletes the contacts `ids`, [`GOOGLE_DELETE_CHUNK`] a call.
    pub fn delete_cards(&mut self, ids: &[String]) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);

        let names: Vec<String> = ids.iter().map(|id| format!("people/{id}")).collect();
        for chunk in names.chunks(GOOGLE_DELETE_CHUNK) {
            let coroutine =
                GpeopleContactsBatchDelete::new(&auth, chunk).map_err(|err| err.to_string())?;
            self.run(coroutine)?;
        }

        Ok(())
    }

    /// Adds and removes the contact `id` from groups, one members:modify
    /// call per group.
    ///
    /// Adds go into user groups only; removal from the last group is
    /// rejected server-side.
    pub fn update_card_books(
        &mut self,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let person = vec![format!("people/{id}")];

        for group in add {
            let coroutine = GpeopleContactGroupMembersModify::new(
                &auth,
                &format!("contactGroups/{group}"),
                &person,
                &[],
            )
            .map_err(|err| err.to_string())?;
            let out = self.run(coroutine)?;

            if !out.not_found_resource_names.is_empty() {
                return Err(format!(
                    "Google group member add rejected: {:?} not found",
                    out.not_found_resource_names
                )
                .into());
            }
        }

        for group in remove {
            let coroutine = GpeopleContactGroupMembersModify::new(
                &auth,
                &format!("contactGroups/{group}"),
                &[],
                &person,
            )
            .map_err(|err| err.to_string())?;
            let out = self.run(coroutine)?;

            if !out
                .can_not_remove_last_contact_group_resource_names
                .is_empty()
            {
                return Err("Google refused to remove the contact from its last group".into());
            }
            if !out.not_found_resource_names.is_empty() {
                return Err(format!(
                    "Google group member removal rejected: {:?} not found",
                    out.not_found_resource_names
                )
                .into());
            }
        }

        Ok(())
    }
}

/// The People coroutine runner.
impl<H: Host> Gpeople<'_, H> {
    /// Runs a People coroutine to completion on the People API origin.
    fn run<C, T>(&mut self, coroutine: C) -> Result<T, BridgeError>
    where
        C: GpeopleCoroutine<
                Yield = GpeopleYield,
                Return = Result<GpeopleSendOutput<T>, GpeopleSendError>,
            >,
    {
        self.try_run(coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// [`Self::run`] keeping People's own error, so a caller can
    /// tell its reasons apart (an expired sync token); the outer error
    /// is the transport's.
    fn try_run<C, T>(
        &mut self,
        mut coroutine: C,
    ) -> Result<Result<T, GpeopleSendError>, BridgeError>
    where
        C: GpeopleCoroutine<
                Yield = GpeopleYield,
                Return = Result<GpeopleSendOutput<T>, GpeopleSendError>,
            >,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                GpeopleCoroutineState::Complete(Ok(output)) => return Ok(Ok(output.response)),
                GpeopleCoroutineState::Complete(Err(err)) => return Ok(Err(err)),
                GpeopleCoroutineState::Yielded(GpeopleYield::WantsRead) => {
                    arg = Some(self.client.http_read(GPEOPLE_API_BASE)?);
                }
                GpeopleCoroutineState::Yielded(GpeopleYield::WantsWrite(bytes)) => {
                    self.client.http_write(GPEOPLE_API_BASE, &bytes)?;
                    arg = None;
                }
            }
        }
    }
}

/// How many persons one `people.connections.list` page carries, the
/// API's ceiling.
const PEOPLE_PAGE_SIZE: u32 = 1000;

/// How many resource names one `people:batchGet` carries, the API's
/// ceiling.
const GOOGLE_BATCH_GET_CHUNK: usize = 200;

/// What a People checkpoint starts with: its sync token's request shape.
///
/// Google binds a token to every parameter of its listing, the field mask
/// included, so one of another shape starts the round over in full.
/// Bump it whenever [`sync_fields`] or [`PEOPLE_PAGE_SIZE`] change.
const SYNC_CHECKPOINT: &str = "v2:";

/// The field mask of a sync round: everything the vCard projection
/// reads, so the listing carries each body whole, plus the metadata
/// that flags a deleted person.
fn sync_fields() -> Vec<GpeoplePersonField> {
    GPEOPLE_PERSON_VCARD_FIELDS
        .iter()
        .copied()
        .chain([GpeoplePersonField::Metadata])
        .collect()
}

/// The two requests a People read sends: a page of
/// `people.connections.list`, and a `people:batchGet`.
impl<H: Host> Gpeople<'_, H> {
    /// Lists one page of connections; People's own error is kept, so an
    /// expired sync token can be told apart.
    fn connections(
        &mut self,
        fields: &[GpeoplePersonField],
        params: &GpeopleConnectionsListParams,
    ) -> Result<Result<GpeopleConnectionsListResponse, GpeopleSendError>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine =
            GpeopleConnectionsList::new(&auth, fields, params).map_err(|err| err.to_string())?;
        self.try_run(coroutine)
    }

    /// Reads at most [`GOOGLE_BATCH_GET_CHUNK`] persons by resource name,
    /// everything the vCard projection reads.
    fn batch_get(&mut self, names: &[String]) -> Result<Vec<GpeoplePersonResponse>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = GpeoplePersonsBatchGet::new(&auth, names, GPEOPLE_PERSON_VCARD_FIELDS, &[])
            .map_err(|err| err.to_string())?;
        Ok(self.run(coroutine)?.responses)
    }
}

/// An io-gpeople person as a [`Card`]: the person id as id and uri, the
/// group memberships as books.
fn google_card(person: GpeoplePerson) -> Card {
    let vcard = person.to_vcard();
    let id = person.id().to_string();
    let books = person
        .memberships
        .iter()
        .filter_map(|membership| membership.contact_group_membership.as_ref())
        .filter_map(|group| {
            group
                .contact_group_resource_name
                .as_deref()
                .map(|name| name.strip_prefix("contactGroups/").unwrap_or(name))
                .or(group.contact_group_id.as_deref())
        })
        .map(str::to_string)
        .collect();

    Card {
        uri: id.clone(),
        id,
        etag: (!person.etag.is_empty()).then_some(person.etag),
        vcard,
        books,
    }
}

/// An io-gpeople contact group as an [`Addressbook`].
///
/// [`None`] for a deleted group and system groups but myContacts, which
/// every contact belongs to and a create lands in: the default book.
fn google_book(group: GpeopleContactGroup) -> Option<Addressbook> {
    if group.metadata.as_ref().and_then(|m| m.deleted) == Some(true) {
        return None;
    }

    let id = group
        .resource_name
        .strip_prefix("contactGroups/")
        .unwrap_or(&group.resource_name)
        .to_string();
    if id.is_empty() {
        return None;
    }

    let default = id == "myContacts";
    if !default && group.group_type != Some(GpeopleContactGroupType::UserContactGroup) {
        return None;
    }

    let name = if default {
        "Contacts".to_string()
    } else {
        group
            .name
            .or(group.formatted_name)
            .unwrap_or_else(|| id.clone())
    };

    Some(Addressbook {
        id,
        name,
        url: String::new(),
        description: None,
        color: None,
        role: default_role(default),
        writable: true,
    })
}

#[cfg(test)]
mod tests {
    //! The People reads over a fake People server: a sync round walks the
    //! account 1,000 persons a page, carries its request shape in its
    //! checkpoint and starts over on an expired token, and a body read goes
    //! 200 contacts to a `people:batchGet`.

    use std::collections::BTreeSet;

    use io_gpeople::v1::{
        query::to_field_mask,
        rest::people::{GpeopleStatus, get_batch_get::GpeoplePersonsBatchGetResponse},
    };
    use serde_json::{Value, json};

    use crate::{
        client::fake::{FakeRequest, FakeResponse, FakeServer},
        contact::gpeople::*,
    };

    /// A People account.
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
    }

    impl FakePeople {
        fn answer(&self, request: &FakeRequest) -> FakeResponse {
            assert_eq!(request.header("Authorization"), Some("Bearer token"));
            match (request.method.as_str(), request.path.as_str()) {
                ("GET", "/v1/people/me/connections") => self.connections(request),
                ("GET", "/v1/people:batchGet") => self.batch_get(request),
                other => panic!("unexpected request {other:?}: {}", request.text),
            }
        }

        fn connections(&self, request: &FakeRequest) -> FakeResponse {
            let request_sync_token = request.param("requestSyncToken") == Some("true");
            if request_sync_token {
                assert_eq!(
                    request.param("personFields"),
                    Some(to_field_mask(&sync_fields()).as_str()),
                    "a sync round asks the same mask"
                );
            }

            let source = match request.param("syncToken") {
                Some(token) => {
                    assert_eq!(token, "token-1", "the bare token, its shape stripped");
                    if let Some((status, body)) = &self.refusal {
                        return FakeResponse::json(*status, body);
                    }
                    &self.changes
                }
                None => &self.persons,
            };

            let size: usize = request
                .param("pageSize")
                .map_or(100, |size| size.parse().unwrap());
            let start: usize = request
                .param("pageToken")
                .map_or(0, |token| token.parse().unwrap());
            let end = (start + size).min(source.len());
            let last = end == source.len();

            let page = GpeopleConnectionsListResponse {
                connections: source[start..end]
                    .iter()
                    .map(|person| serde_json::from_value(person.clone()).unwrap())
                    .collect(),
                next_page_token: (!last).then(|| end.to_string()),
                next_sync_token: (last && request_sync_token).then(|| self.issued.clone()),
                ..Default::default()
            };
            FakeResponse::json(200, &page)
        }

        fn batch_get(&self, request: &FakeRequest) -> FakeResponse {
            let names = names(request);
            assert!(!names.is_empty() && names.len() <= GOOGLE_BATCH_GET_CHUNK);

            let responses = names
                .iter()
                .map(|name| {
                    let id = name.strip_prefix("people/").unwrap();
                    match self.gone.contains(id) {
                        true => GpeoplePersonResponse {
                            requested_resource_name: Some(name.to_string()),
                            status: Some(GpeopleStatus {
                                code: Some(5),
                                message: Some("Requested entity was not found.".into()),
                                ..Default::default()
                            }),
                            ..Default::default()
                        },
                        false => GpeoplePersonResponse {
                            requested_resource_name: Some(name.to_string()),
                            person: Some(serde_json::from_value(person(id)).unwrap()),
                            http_status_code: Some(200),
                            ..Default::default()
                        },
                    }
                })
                .collect();
            FakeResponse::json(200, &GpeoplePersonsBatchGetResponse { responses })
        }
    }

    /// The resource names a `people:batchGet` asks for.
    fn names(request: &FakeRequest) -> Vec<&str> {
        request
            .query
            .iter()
            .filter(|(key, _)| key == "resourceNames")
            .map(|(_, name)| name.as_str())
            .collect()
    }

    /// Every listing page asked for: its size and its sync token.
    fn pages(requests: &[FakeRequest]) -> Vec<(Option<u32>, Option<String>)> {
        requests
            .iter()
            .filter(|request| request.path == "/v1/people/me/connections")
            .map(|request| {
                (
                    request.param("pageSize").map(|size| size.parse().unwrap()),
                    request.param("syncToken").map(str::to_string),
                )
            })
            .collect()
    }

    /// Every `people:batchGet` sent, by how many names it carried.
    fn batches(requests: &[FakeRequest]) -> Vec<usize> {
        requests
            .iter()
            .filter(|request| request.path == "/v1/people:batchGet")
            .map(|request| names(request).len())
            .collect()
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

    #[test]
    fn a_full_round_walks_the_account_a_thousand_a_page() {
        let people = account(2_500);
        let mut client = Client::new(FakeServer::new(|request| people.answer(request)));

        let delta = Gpeople {
            client: &mut client,
            token: "token",
        }
        .sync_cards(None)
        .unwrap()
        .unwrap();

        assert_eq!(
            pages(&client.host.requests),
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
        let mut client = Client::new(FakeServer::new(|request| people.answer(request)));

        let delta = Gpeople {
            client: &mut client,
            token: "token",
        }
        .sync_cards(Some("v2:token-1"))
        .unwrap()
        .unwrap();

        assert_eq!(
            pages(&client.host.requests),
            [(Some(1_000), Some("token-1".into()))]
        );
        assert_eq!(delta.changed.len(), 1);
        assert_eq!(delta.changed[0].id, "c1");
        assert_eq!(delta.vanished, ["c2"]);
        assert_eq!(delta.token.as_deref(), Some("v2:token-2"));
    }

    #[test]
    fn a_token_of_another_shape_starts_over_unsent() {
        let people = account(10);
        let mut client = Client::new(FakeServer::new(|request| people.answer(request)));

        let mut gpeople = Gpeople {
            client: &mut client,
            token: "token",
        };
        assert!(gpeople.sync_cards(Some("token-1")).unwrap().is_none());
        assert!(client.host.requests.is_empty());
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
            let mut client = Client::new(FakeServer::new(|request| people.answer(request)));

            let mut gpeople = Gpeople {
                client: &mut client,
                token: "token",
            };
            assert!(gpeople.sync_cards(Some("v2:token-1")).unwrap().is_none());
        }
    }

    #[test]
    fn another_refusal_fails_the_round() {
        let mut people = account(10);
        people.refusal = Some((
            400,
            json!({"error": {"code": 400, "message": "Invalid personFields mask", "status": "INVALID_ARGUMENT"}}),
        ));
        let mut client = Client::new(FakeServer::new(|request| people.answer(request)));

        let mut gpeople = Gpeople {
            client: &mut client,
            token: "token",
        };
        let Err(err) = gpeople.sync_cards(Some("v2:token-1")) else {
            panic!("the round went through");
        };
        assert_eq!(err.status, Some(400));
    }

    #[test]
    fn a_body_read_goes_two_hundred_to_a_batch() {
        let mut people = account(0);
        people.gone.insert("c7".into());
        let ids: Vec<String> = (0..450).map(|index| format!("c{index}")).collect();
        let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
        let mut client = Client::new(FakeServer::new(|request| people.answer(request)));

        let cards = Gpeople {
            client: &mut client,
            token: "token",
        }
        .read_cards(&ids)
        .unwrap();

        assert_eq!(batches(&client.host.requests), [200, 200, 50]);
        assert_eq!(cards.len(), 449);
        assert_eq!(cards[0].id, "c0");
        assert_eq!(cards[7].id, "c8");
        assert_eq!(cards[448].id, "c449");
        assert!(cards[0].vcard.contains("Jane c0"));
    }

    #[test]
    fn a_body_read_fails_on_anything_but_not_found() {
        let mut client = Client::new(FakeServer::new(|request| {
            assert_eq!(request.path, "/v1/people:batchGet");
            let responses = names(request)
                .iter()
                .map(|name| GpeoplePersonResponse {
                    requested_resource_name: Some(name.to_string()),
                    status: Some(GpeopleStatus {
                        code: Some(7),
                        message: Some("The caller does not have permission".into()),
                        ..Default::default()
                    }),
                    ..Default::default()
                })
                .collect();
            FakeResponse::json(200, &GpeoplePersonsBatchGetResponse { responses })
        }));

        let mut gpeople = Gpeople {
            client: &mut client,
            token: "token",
        };
        let Err(err) = gpeople.read_cards(&["c1"]) else {
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
}
