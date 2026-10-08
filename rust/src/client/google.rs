//! Google People operations: contact groups and connections as
//! addressbooks and cards, the batch create, read and delete verbs,
//! group membership edits and the sync-token round.

use std::collections::HashMap;

use io_gpeople::{
    coroutine::{GpeopleCoroutine, GpeopleCoroutineState, GpeopleYield},
    v1::{
        rest::{
            contact_groups::{
                GpeopleContactGroupType,
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
    client::{Client, convert::coroutine_error},
    types::{Addressbook, BridgeError, Card, CardDelta},
};

/// The person fields an update with no base replaces: every field the
/// vCard projection writes, `clientData` (the stash) included.
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

/// Google People operations: contact groups and connections as
/// addressbooks and cards, the batch create and delete verbs, group
/// membership edits and the sync-token round.
impl<'a, 'local> Client<'a, 'local> {
    /// Lists the Google account's contact groups as addressbooks: the
    /// myContacts system group first (as Contacts, the group every
    /// contact belongs to), then the user's own groups. Memberships
    /// are m:n labels, so one card can appear under several books.
    /// Doubles as the connection check during onboarding. Collection
    /// URLs are left empty: the caller composes them.
    pub fn list_google_addressbooks(
        &mut self,
        token: &str,
    ) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let mut books = Vec::new();
        let mut page_token: Option<String> = None;
        loop {
            let params = GpeopleContactGroupsListParams {
                page_token: page_token.as_deref(),
                ..Default::default()
            };
            let coroutine = GpeopleContactGroupsList::new(&auth, &[], &params)
                .map_err(|err| err.to_string())?;
            let page = self.run_google(coroutine)?;

            for group in page.contact_groups {
                if group.metadata.as_ref().and_then(|m| m.deleted) == Some(true) {
                    continue;
                }

                let id = group
                    .resource_name
                    .strip_prefix("contactGroups/")
                    .unwrap_or(&group.resource_name)
                    .to_string();
                if id.is_empty() {
                    continue;
                }

                // NOTE: of the system groups, only myContacts is a
                // container; the others are not addressbooks.
                if id == "myContacts" {
                    books.insert(
                        0,
                        Addressbook {
                            id,
                            name: "Contacts".to_string(),
                            url: String::new(),
                            description: None,
                            color: None,
                        },
                    );
                } else if group.group_type == Some(GpeopleContactGroupType::UserContactGroup) {
                    let name = group
                        .name
                        .or(group.formatted_name)
                        .unwrap_or_else(|| id.clone());
                    books.push(Addressbook {
                        id,
                        name,
                        url: String::new(),
                        description: None,
                        color: None,
                    });
                }
            }

            match page.next_page_token {
                Some(next) => page_token = Some(next),
                None => break,
            }
        }

        Ok(books)
    }

    /// Lists the account's contacts (`people.connections.list`), each
    /// projected onto a vCard document; the person id is the addressing
    /// key and the person etag the ETag.
    pub fn list_google_cards(&mut self, token: &str) -> Result<Vec<Card>, BridgeError> {
        let mut reads = PeopleCalls {
            client: self,
            auth: HttpAuthBearer::new(token),
        };
        list_cards(&mut reads)
    }

    /// Lists the People contact changes since `cursor` (deleted persons
    /// ride flagged in the response); without one, the initial round
    /// lists every contact and requests the token to delta from next
    /// time. Returns [`None`] when the cursor cannot be used, so the
    /// caller falls back to an initial round: Google expired the token,
    /// or it was issued for another request shape ([`SYNC_CHECKPOINT`]).
    pub fn sync_google_cards(
        &mut self,
        token: &str,
        cursor: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let mut reads = PeopleCalls {
            client: self,
            auth: HttpAuthBearer::new(token),
        };
        sync_cards(&mut reads, cursor)
    }

    /// Reads the named People contacts, each projected onto a vCard
    /// document, [`GOOGLE_BATCH_GET_CHUNK`] to a `people:batchGet`
    /// where [`Self::read_google_card`] sends one request a contact; a
    /// contact Google no longer holds is left out.
    pub fn read_google_cards(
        &mut self,
        token: &str,
        ids: &[&str],
    ) -> Result<Vec<Card>, BridgeError> {
        let mut reads = PeopleCalls {
            client: self,
            auth: HttpAuthBearer::new(token),
        };
        read_cards(&mut reads, ids)
    }

    /// Creates the vCard as a People contact. The server names the
    /// resource, so the returned card carries the server-assigned id.
    pub fn create_google_card(&mut self, token: &str, vcard: &str) -> Result<Card, BridgeError> {
        let person = GpeoplePerson::from_vcard(vcard)?;
        let auth = HttpAuthBearer::new(token);

        let coroutine = GpeopleContactCreate::new(&auth, &person, GPEOPLE_PERSON_VCARD_FIELDS, &[])
            .map_err(|err| err.to_string())?;
        Ok(google_card(self.run_google(coroutine)?))
    }

    /// Reads the People contact `id`, projected onto a vCard document.
    pub fn read_google_card(&mut self, token: &str, id: &str) -> Result<Card, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GpeoplePersonGet::new(
            &auth,
            &format!("people/{id}"),
            GPEOPLE_PERSON_VCARD_FIELDS,
            &[],
        )
        .map_err(|err| err.to_string())?;

        Ok(google_card(self.run_google(coroutine)?))
    }

    /// Updates the People contact `id` from the vCard. With a base
    /// vCard (the state last synced with the server) the update mask
    /// shrinks to the fields the edit changed; without one every
    /// managed field is replaced. People requires the person's current
    /// etag on updates, so a missing one is fetched first; a stale one
    /// fails the update (no silent last-write-wins). A stash write
    /// (clientData in the mask) merges the server's foreign clientData
    /// entries under the same guard.
    pub fn update_google_card(
        &mut self,
        token: &str,
        id: &str,
        vcard: &str,
        base_vcard: Option<&str>,
        etag: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let auth = HttpAuthBearer::new(token);
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
            let current = self.run_google(coroutine)?;

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
        match self.run_google(coroutine) {
            Ok(updated) => Ok(google_card(updated)),
            // NOTE: the connections.list etag the engine carries is
            // rejected by updateContact (only a people.get etag is
            // accepted), so re-read the etag and retry once; the engine
            // still guards concurrency by re-conflicting the row.
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
                person.etag = self.run_google(coroutine)?.etag;

                let coroutine = GpeopleContactUpdate::new(
                    &auth,
                    &person,
                    &fields,
                    GPEOPLE_PERSON_VCARD_FIELDS,
                    &[],
                )
                .map_err(|err| err.to_string())?;
                Ok(google_card(self.run_google(coroutine)?))
            }
            Err(err) => Err(err),
        }
    }

    /// Deletes the People contact `id`.
    pub fn delete_google_card(&mut self, token: &str, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine = GpeopleContactDelete::new(&auth, &format!("people/{id}"))
            .map_err(|err| err.to_string())?;
        self.run_google(coroutine)?;

        Ok(())
    }

    /// Creates the vCards as People contacts by batch calls (200 per
    /// request instead of one), returning the created cards in input
    /// order: a create carries no correlation key, so request order is
    /// the response's contract. The server names the resources, so the
    /// returned cards carry the server-assigned ids.
    pub fn create_google_cards(
        &mut self,
        token: &str,
        vcards: &[String],
    ) -> Result<Vec<Card>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let persons = vcards
            .iter()
            .map(|vcard| GpeoplePerson::from_vcard(vcard))
            .collect::<Result<Vec<_>, _>>()?;

        let mut cards = Vec::with_capacity(persons.len());
        for chunk in persons.chunks(GOOGLE_CREATE_CHUNK) {
            let coroutine =
                GpeopleContactsBatchCreate::new(&auth, chunk, GPEOPLE_PERSON_VCARD_FIELDS, &[])
                    .map_err(|err| err.to_string())?;
            let response = self.run_google(coroutine)?;

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

    /// Deletes the People contacts `ids` by batch calls (500 per
    /// request instead of one).
    pub fn delete_google_cards(&mut self, token: &str, ids: &[String]) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let names: Vec<String> = ids.iter().map(|id| format!("people/{id}")).collect();
        for chunk in names.chunks(GOOGLE_DELETE_CHUNK) {
            let coroutine =
                GpeopleContactsBatchDelete::new(&auth, chunk).map_err(|err| err.to_string())?;
            self.run_google(coroutine)?;
        }

        Ok(())
    }

    /// Adds and removes the People contact `id` from contact groups,
    /// one members:modify call per group (the API only accepts adds
    /// into user groups; removing a contact from its last group is
    /// rejected server-side).
    pub fn update_google_card_books(
        &mut self,
        token: &str,
        id: &str,
        add: &[String],
        remove: &[String],
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let person = vec![format!("people/{id}")];

        for group in add {
            let coroutine = GpeopleContactGroupMembersModify::new(
                &auth,
                &format!("contactGroups/{group}"),
                &person,
                &[],
            )
            .map_err(|err| err.to_string())?;
            let out = self.run_google(coroutine)?;

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
            let out = self.run_google(coroutine)?;

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

/// Google coroutine runner: the resume loop that pumps a Google People
/// coroutine, routing every yield to the transport stream opened on the
/// People API origin.
impl<'a, 'local> Client<'a, 'local> {
    /// Runs a Google People coroutine to completion, routing every
    /// yield to the transport stream opened on the People API origin.
    fn run_google<C, T>(&mut self, coroutine: C) -> Result<T, BridgeError>
    where
        C: GpeopleCoroutine<
                Yield = GpeopleYield,
                Return = Result<GpeopleSendOutput<T>, GpeopleSendError>,
            >,
    {
        self.try_google(coroutine)?
            .map_err(|err| coroutine_error(&err))
    }

    /// [`Self::run_google`] keeping People's own error, so a caller can
    /// tell its reasons apart (an expired sync token); the outer error
    /// is the transport's.
    fn try_google<C, T>(
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
                    arg = Some(self.http_read(GPEOPLE_API_BASE)?);
                }
                GpeopleCoroutineState::Yielded(GpeopleYield::WantsWrite(bytes)) => {
                    self.http_write(GPEOPLE_API_BASE, &bytes)?;
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

/// What a People checkpoint starts with: the request shape its sync
/// token was issued for.
///
/// Google binds a sync token to the listing that issued it, every other
/// parameter having to match, the field mask among them. A token issued
/// for another shape (the bare tokens of the 100-person pages before
/// this one) is therefore never sent with this one: the round starts
/// over in full instead. Bump it whenever [`sync_fields`] or
/// [`PEOPLE_PAGE_SIZE`] change.
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

/// The two requests a People read sends, apart so the reads can run over
/// a fake: a page of `people.connections.list`, and a `people:batchGet`.
pub(super) trait PeopleReads {
    /// Lists one page of connections; People's own error is kept, so an
    /// expired sync token can be told apart.
    fn connections(
        &mut self,
        fields: &[GpeoplePersonField],
        params: &GpeopleConnectionsListParams,
    ) -> Result<Result<GpeopleConnectionsListResponse, GpeopleSendError>, BridgeError>;

    /// Reads at most [`GOOGLE_BATCH_GET_CHUNK`] persons by resource name,
    /// everything the vCard projection reads.
    fn batch_get(&mut self, names: &[String]) -> Result<Vec<GpeoplePersonResponse>, BridgeError>;
}

/// The People reads of one native call, over its transport.
struct PeopleCalls<'c, 'a, 'local> {
    client: &'c mut Client<'a, 'local>,
    auth: HttpAuthBearer,
}

impl PeopleReads for PeopleCalls<'_, '_, '_> {
    fn connections(
        &mut self,
        fields: &[GpeoplePersonField],
        params: &GpeopleConnectionsListParams,
    ) -> Result<Result<GpeopleConnectionsListResponse, GpeopleSendError>, BridgeError> {
        let coroutine = GpeopleConnectionsList::new(&self.auth, fields, params)
            .map_err(|err| err.to_string())?;
        self.client.try_google(coroutine)
    }

    fn batch_get(&mut self, names: &[String]) -> Result<Vec<GpeoplePersonResponse>, BridgeError> {
        let coroutine =
            GpeoplePersonsBatchGet::new(&self.auth, names, GPEOPLE_PERSON_VCARD_FIELDS, &[])
                .map_err(|err| err.to_string())?;
        Ok(self.client.run_google(coroutine)?.responses)
    }
}

/// Every contact of the account, [`PEOPLE_PAGE_SIZE`] to a page.
pub(super) fn list_cards<R: PeopleReads>(reads: &mut R) -> Result<Vec<Card>, BridgeError> {
    let mut cards = Vec::new();
    let mut page_token: Option<String> = None;

    loop {
        let params = GpeopleConnectionsListParams {
            page_size: Some(PEOPLE_PAGE_SIZE),
            page_token: page_token.as_deref(),
            ..Default::default()
        };
        let page = reads
            .connections(GPEOPLE_PERSON_VCARD_FIELDS, &params)?
            .map_err(|err| coroutine_error(&err))?;
        cards.extend(page.connections.into_iter().map(google_card));

        match page.next_page_token {
            Some(next) => page_token = Some(next),
            None => return Ok(cards),
        }
    }
}

/// One People sync round from `cursor`, [`PEOPLE_PAGE_SIZE`] to a page,
/// each changed person carrying its whole body; [`None`] when the
/// cursor cannot be used and the round has to start over without one.
///
/// The round is the account's, whatever book asks for it: People has one
/// sync token for every contact, and the books are its contact groups,
/// which the caller projects the round onto.
pub(super) fn sync_cards<R: PeopleReads>(
    reads: &mut R,
    cursor: Option<&str>,
) -> Result<Option<CardDelta>, BridgeError> {
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
        let page = match reads.connections(&fields, &params)? {
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

/// Reads the named contacts, [`GOOGLE_BATCH_GET_CHUNK`] to a request, in
/// the order given. A contact Google no longer holds (`NOT_FOUND`) is
/// left out; any other failure of one contact fails the read.
pub(super) fn read_cards<R: PeopleReads>(
    reads: &mut R,
    ids: &[&str],
) -> Result<Vec<Card>, BridgeError> {
    let names: Vec<String> = ids.iter().map(|id| format!("people/{id}")).collect();
    let mut cards = Vec::with_capacity(ids.len());

    for chunk in names.chunks(GOOGLE_BATCH_GET_CHUNK) {
        let mut read: HashMap<String, GpeoplePerson> = HashMap::with_capacity(chunk.len());
        for response in reads.batch_get(chunk)? {
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

/// io-gpeople person to the JNI-facing card shape: the projected
/// vCard document, the person id (resource name minus the `people/`
/// prefix) as both display id and addressing key (uri), the person
/// etag as ETag, and the contact group memberships (minus their
/// `contactGroups/` prefix) as the card's books.
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

#[cfg(test)]
#[path = "google_tests.rs"]
mod tests;
