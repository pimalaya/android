//! # Microsoft Graph contacts
//!
//! The contact verbs of the [`Msgraph`] backend: contact folders and
//! contacts as addressbooks and cards, the $batch push and the delta round.
//!
//! A delta row carries no body (a delta query cannot `$expand` the stash),
//! so a round reads what changed 20 to a `$batch` ([`Msgraph::read_cards`]).
//! A request the batch could not serve goes again alone, where the
//! transport rides out throttling; a contact gone since is left out.

use std::collections::{BTreeMap, VecDeque};

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::{
    coroutine::*,
    v1::{
        rest::users::{
            contact_folders::{
                MsgraphContactFolder,
                child_folders::MsgraphContactChildFoldersList,
                list::{MsgraphContactFoldersList, MsgraphContactFoldersListResponse},
            },
            contacts::{
                MsgraphContact,
                create::MsgraphContactCreate,
                delete::MsgraphContactDelete,
                delta::{MsgraphContactsDelta, MsgraphContactsDeltaResponse},
                get::MsgraphContactGet,
                list::{
                    MsgraphContactsList, MsgraphContactsListParams, MsgraphContactsListResponse,
                },
                update::MsgraphContactUpdate,
                vcard::MSGRAPH_CONTACT_STASH_EXPAND,
            },
        },
        send::{MSGRAPH_API_BASE, MsgraphSend, MsgraphSendError, MsgraphSendOutput},
    },
};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use url::Url;

use crate::{
    client::{
        Host,
        msgraph::{Msgraph, alone, batched, graph_url, parse_graph_url},
    },
    contact::{
        Addressbook, Card, CardDelta,
        push::{PushChange, PushOutcome, rejected, required},
    },
    error::{BridgeError, coroutine_error, graph_refuses},
    offline::default_role,
};

/// How many inner requests one Graph $batch call carries (the
/// endpoint's hard limit).
const GRAPH_BATCH_CHUNK: usize = 20;

/// Graph contact folder and contact verbs.
impl<H: Host> Msgraph<'_, H> {
    /// Lists the contact folders as addressbooks, the default Contacts
    /// folder first ([`graph_default_book`]), then every folder at every
    /// depth, each level after the one above it.
    ///
    /// A book is keyed by its folder's id, never by its name. URLs are left
    /// empty for the caller, which alone knows the account.
    pub fn list_addressbooks(&mut self) -> Result<Vec<Addressbook>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);

        let mut books = vec![graph_default_book()];
        let mut parents: VecDeque<Option<String>> = VecDeque::from([None]);

        while let Some(parent) = parents.pop_front() {
            let params = Default::default();
            let page = match &parent {
                None => {
                    let coroutine = MsgraphContactFoldersList::new(&auth, "me", &params)
                        .map_err(|err| err.to_string())?;
                    self.run(coroutine)?
                }
                Some(id) => {
                    let coroutine = MsgraphContactChildFoldersList::new(&auth, "me", id, &params)
                        .map_err(|err| err.to_string())?;
                    self.run(coroutine)?
                }
            };

            for folder in self.contact_folder_pages(&auth, page)? {
                if folder.id.is_empty() {
                    continue;
                }
                parents.push_back(Some(folder.id.clone()));
                books.push(Addressbook {
                    name: if folder.display_name.is_empty() {
                        folder.id.clone()
                    } else {
                        folder.display_name
                    },
                    id: folder.id,
                    url: String::new(),
                    description: None,
                    color: None,
                    role: String::new(),
                    writable: true,
                });
            }
        }

        Ok(books)
    }

    /// Every folder of a contact folder listing, following its next links.
    fn contact_folder_pages(
        &mut self,
        auth: &HttpAuthBearer,
        mut page: MsgraphContactFoldersListResponse,
    ) -> Result<Vec<MsgraphContactFolder>, BridgeError> {
        let mut folders = Vec::new();

        loop {
            folders.extend(page.value);
            match page.next_link {
                Some(next) => {
                    let url = parse_graph_url(&next)?;
                    page = self.run(MsgraphSend::<MsgraphContactFoldersListResponse>::get(
                        auth, url,
                    ))?;
                }
                None => break,
            }
        }

        Ok(folders)
    }

    /// Lists the contacts of the folder, an empty id naming the default
    /// Contacts folder.
    pub fn list_cards(&mut self, folder: &str) -> Result<Vec<Card>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let folder = (!folder.is_empty()).then_some(folder);

        let params = MsgraphContactsListParams {
            top: Some(100),
            expand: Some(MSGRAPH_CONTACT_STASH_EXPAND),
            ..Default::default()
        };
        let coroutine = MsgraphContactsList::new(&auth, "me", folder, &params)
            .map_err(|err| err.to_string())?;
        let mut page = self.run(coroutine)?;

        let mut cards = Vec::new();
        loop {
            cards.extend(page.value.into_iter().map(graph_card));

            match page.next_link {
                Some(next) => {
                    let url = parse_graph_url(&next)?;
                    page = self.run(MsgraphSend::<MsgraphContactsListResponse>::get(&auth, url))?;
                }
                None => break,
            }
        }

        Ok(cards)
    }

    /// Creates the vCard as a contact in the folder (empty id for the
    /// default one), under the id Graph assigns.
    pub fn create_card(&mut self, folder: &str, vcard: &str) -> Result<Card, BridgeError> {
        let contact = MsgraphContact::create_from_vcard(vcard)?;
        let auth = HttpAuthBearer::new(self.token);
        let folder = (!folder.is_empty()).then_some(folder);

        let coroutine = MsgraphContactCreate::new(&auth, "me", folder, &contact)
            .map_err(|err| err.to_string())?;
        let mut created = self.run(coroutine)?;

        // NOTE: create responses cannot $expand, so the stash just sent
        // is re-attached before projecting the card.
        created.single_value_extended_properties =
            MsgraphContact::from_vcard(vcard)?.single_value_extended_properties;
        Ok(graph_card(created))
    }

    /// Reads the Graph contact `id` as a vCard.
    pub fn read_card(&mut self, id: &str) -> Result<Card, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = MsgraphContactGet::new(&auth, "me", id, Some(MSGRAPH_CONTACT_STASH_EXPAND))
            .map_err(|err| err.to_string())?;
        Ok(graph_card(self.run(coroutine)?))
    }

    /// Reads the named contacts in order, 20 to a `$batch`, a reply the
    /// batch could not serve sent again alone.
    ///
    /// A contact Graph no longer holds (404) is left out; any other
    /// failure fails the read.
    pub fn read_cards(&mut self, ids: &[&str]) -> Result<Vec<Card>, BridgeError> {
        let urls = ids
            .iter()
            .map(|id| contact_url(id))
            .collect::<Result<Vec<_>, _>>()?;

        let replies = batched(|requests| self.batch(requests), &urls)?;
        let mut cards = Vec::with_capacity(ids.len());
        for (id, reply) in ids.iter().zip(replies) {
            let contact = match reply {
                Some(reply) if reply.status == 404 => None,
                Some(reply) if (200..300).contains(&reply.status) => {
                    match reply.parse::<MsgraphContact>() {
                        Ok(contact) => Some(contact),
                        Err(_) => alone(self.contact(id))?,
                    }
                }
                _ => alone(self.contact(id))?,
            };
            if let Some(contact) = contact {
                cards.push(graph_card(contact));
            }
        }

        Ok(cards)
    }

    /// Updates the contact `id` read at the changeKey `if_match`.
    ///
    /// Graph contacts take no If-Match (docs/providers.md), so the contact
    /// is read first: one the server holds at another changeKey, or a
    /// changeKey unknown, is a conflict (412) and is not written over, left
    /// for the next listing to merge.
    ///
    /// The PATCH is trimmed to the fields changed since the base; without
    /// one, the create-shaped body cannot clear fields but carries none of
    /// the nulls Outlook rejects with HTTP 500.
    pub fn update_card(
        &mut self,
        id: &str,
        vcard: &str,
        base_vcard: Option<&str>,
        if_match: Option<&str>,
    ) -> Result<Card, BridgeError> {
        let held = self.read_card(id)?;
        if if_match.is_none() || held.etag.as_deref() != if_match {
            return Err(changed_since_read(id));
        }

        let contact = match base_vcard {
            Some(base) => MsgraphContact::update_from_vcard(vcard, base)?,
            None => MsgraphContact::create_from_vcard(vcard)?,
        };
        let auth = HttpAuthBearer::new(self.token);

        let coroutine =
            MsgraphContactUpdate::new(&auth, "me", id, &contact).map_err(|err| err.to_string())?;
        let mut updated = self.run(coroutine)?;

        // NOTE: update responses cannot $expand, so the full stash
        // (a delta may skip it in the body) is re-attached first.
        updated.single_value_extended_properties =
            MsgraphContact::from_vcard(vcard)?.single_value_extended_properties;
        Ok(graph_card(updated))
    }

    /// The updates of `chunk` kept from being written, by index, with why:
    /// one naming no changeKey, or whose contact the server holds at another
    /// changeKey than it was read at, as [`Self::update_card`] guards. The
    /// contacts are read 20 to a `$batch`; one gone is left to its PATCH.
    fn stale_updates(
        &mut self,
        chunk: &[PushChange],
    ) -> Result<BTreeMap<usize, String>, BridgeError> {
        let mut updates = Vec::new();
        for (index, change) in chunk.iter().enumerate() {
            if change.op == "update" {
                updates.push((index, required(&change.id, "update", "id")?));
            }
        }
        let mut stale = BTreeMap::new();
        if updates.is_empty() {
            return Ok(stale);
        }

        let ids: Vec<&str> = updates.iter().map(|(_, id)| *id).collect();
        let held: BTreeMap<String, Option<String>> = self
            .read_cards(&ids)?
            .into_iter()
            .map(|card| (card.id, card.etag))
            .collect();
        for (index, id) in updates {
            let read = chunk[index].if_match.as_deref();
            if let Some(key) = held.get(id)
                && (read.is_none() || key.as_deref() != read)
            {
                stale.insert(index, changed_since_read(id).message);
            }
        }
        Ok(stale)
    }

    /// Deletes the Graph contact `id`.
    pub fn delete_card(&mut self, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine =
            MsgraphContactDelete::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run(coroutine)?;

        Ok(())
    }

    /// Pushes one round of changes as $batch calls, chunks of
    /// [`GRAPH_BATCH_CHUNK`].
    ///
    /// Each inner status is one change's outcome: a rejected change does
    /// not fail its round, and a 404 on a destroy has converged.
    ///
    /// An update is guarded by the changeKey it was read at, the contacts
    /// read first ([`Self::stale_updates`]): one changed on the server since
    /// is not written over but answered rejected, left pending for the next
    /// listing to merge (pimdir SYNC §5).
    pub fn push_cards(
        &mut self,
        folder: &str,
        changes: &[PushChange],
    ) -> Result<Vec<PushOutcome>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let create_url = if folder.is_empty() {
            "/me/contacts".to_string()
        } else {
            format!("/me/contactFolders/{folder}/contacts")
        };

        let mut outcomes = Vec::with_capacity(changes.len());
        for chunk in changes.chunks(GRAPH_BATCH_CHUNK) {
            let stale = self.stale_updates(chunk)?;
            let mut requests = Vec::with_capacity(chunk.len());
            for (index, change) in chunk.iter().enumerate() {
                if let Some(error) = stale.get(&index) {
                    outcomes.push(rejected(change.reference.clone(), error.clone(), false));
                    continue;
                }
                let request = match change.op.as_str() {
                    "create" => {
                        let vcard = required(&change.vcard, "create", "vcard")?;
                        GraphBatchRequest {
                            id: index.to_string(),
                            method: "POST",
                            url: create_url.clone(),
                            headers: Some(GraphBatchHeaders::JSON),
                            body: Some(MsgraphContact::create_from_vcard(vcard)?),
                        }
                    }
                    "update" => {
                        let id = required(&change.id, "update", "id")?;
                        let vcard = required(&change.vcard, "update", "vcard")?;
                        let contact = match change.base_vcard.as_deref() {
                            Some(base) => MsgraphContact::update_from_vcard(vcard, base)?,
                            None => MsgraphContact::create_from_vcard(vcard)?,
                        };
                        GraphBatchRequest {
                            id: index.to_string(),
                            method: "PATCH",
                            url: format!("/me/contacts/{id}"),
                            headers: Some(GraphBatchHeaders::JSON),
                            body: Some(contact),
                        }
                    }
                    "destroy" => {
                        let id = required(&change.id, "destroy", "id")?;
                        GraphBatchRequest {
                            id: index.to_string(),
                            method: "DELETE",
                            url: format!("/me/contacts/{id}"),
                            headers: None,
                            body: None,
                        }
                    }
                    op => return Err(format!("Unknown Graph push op `{op}`").into()),
                };
                requests.push(request);
            }
            if requests.is_empty() {
                continue;
            }

            let url = parse_graph_url(&format!("{MSGRAPH_API_BASE}$batch"))?;
            let batch = GraphBatch { requests };
            let coroutine = MsgraphSend::<GraphBatchReplies>::post_json(&auth, url, &batch)
                .map_err(|err| err.to_string())?;
            let out = self.run(coroutine)?;

            let mut replies: BTreeMap<String, GraphBatchReply> = out
                .responses
                .into_iter()
                .map(|reply| (reply.id.clone(), reply))
                .collect();

            for (index, change) in chunk.iter().enumerate() {
                if stale.contains_key(&index) {
                    continue;
                }
                let Some(reply) = replies.remove(&index.to_string()) else {
                    return Err(format!("Graph batch response is missing request {index}").into());
                };

                let accepted = (200..300).contains(&reply.status)
                    || (change.op == "destroy" && reply.status == 404);
                if accepted {
                    let body = reply.body.unwrap_or_default();
                    outcomes.push(PushOutcome {
                        reference: change.reference.clone(),
                        accepted: true,
                        id: body.get("id").and_then(|id| id.as_str()).map(Into::into),
                        etag: body
                            .get("changeKey")
                            .and_then(|key| key.as_str())
                            .map(Into::into),
                        ..Default::default()
                    });
                } else {
                    let error = reply.body.as_ref().and_then(|body| body.get("error"));
                    let field = |name: &str| {
                        error
                            .and_then(|error| error.get(name))
                            .and_then(|value| value.as_str())
                            .unwrap_or_default()
                            .to_string()
                    };
                    let (code, message) = (field("code"), field("message"));
                    outcomes.push(rejected(
                        change.reference.clone(),
                        format!("HTTP {} {message}", reply.status),
                        graph_refuses(reply.status, &code, &message),
                    ));
                }
            }
        }

        Ok(outcomes)
    }

    /// Lists the contact changes since `delta_link`, every contact
    /// without one.
    ///
    /// Rows carry only the id and changeKey, bodies being fetched apart.
    /// [`None`] when the server expired the link (HTTP 410).
    pub fn delta_cards(
        &mut self,
        folder: &str,
        delta_link: Option<&str>,
    ) -> Result<Option<CardDelta>, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let folder = (!folder.is_empty()).then_some(folder);

        let first = match delta_link {
            Some(link) => {
                let url = parse_graph_url(link)?;
                self.run_delta(MsgraphSend::get(&auth, url))?
            }
            None => {
                let coroutine = MsgraphContactsDelta::new(&auth, "me", folder, Some("changeKey"))
                    .map_err(|err| err.to_string())?;
                self.run_delta(coroutine)?
            }
        };
        let Some(mut page) = first else {
            return Ok(None);
        };

        let mut delta = CardDelta {
            changed: Vec::new(),
            vanished: Vec::new(),
            token: None,
            complete: false,
        };

        loop {
            for row in page.value {
                if row.removed.is_some() {
                    delta.vanished.push(row.contact.id);
                } else {
                    delta.changed.push(Card {
                        uri: row.contact.id.clone(),
                        id: row.contact.id,
                        etag: row.contact.change_key,
                        vcard: String::new(),
                        vcard_base64: None,
                        books: Vec::new(),
                    });
                }
            }

            if page.delta_link.is_some() {
                delta.token = page.delta_link;
            }
            match page.next_link {
                Some(next) => {
                    let url = parse_graph_url(&next)?;
                    page = match self.run_delta(MsgraphSend::get(&auth, url))? {
                        Some(page) => page,
                        None => return Ok(None),
                    };
                }
                None => break,
            }
        }

        Ok(Some(delta))
    }

    /// Runs one delta request, an expired link (HTTP 410) as [`None`]:
    /// [`Self::run`] erases the status the fallback needs.
    fn run_delta<C>(
        &mut self,
        coroutine: C,
    ) -> Result<Option<MsgraphContactsDeltaResponse>, BridgeError>
    where
        C: MsgraphCoroutine<
                Yield = MsgraphYield,
                Return = Result<MsgraphSendOutput<MsgraphContactsDeltaResponse>, MsgraphSendError>,
            >,
    {
        match self.try_run(coroutine)? {
            Ok(page) => Ok(Some(page)),
            Err(err) if err.status() == Some(410) => Ok(None),
            Err(err) => Err(coroutine_error(&err)),
        }
    }
}

/// The conflict of an update of `id` whose contact the server changed since
/// it was read: a precondition failed (412), as a stale If-Match is.
fn changed_since_read(id: &str) -> BridgeError {
    BridgeError {
        message: format!("Graph contact {id} changed on the server since it was read"),
        status: Some(412),
        ..Default::default()
    }
}

/// An io-msgraph contact as a [`Card`]: the Graph id as id and uri, the
/// changeKey as ETag.
fn graph_card(contact: MsgraphContact) -> Card {
    let vcard = contact.to_vcard();
    Card {
        id: contact.id.clone(),
        uri: contact.id,
        etag: contact.change_key,
        vcard,
        vcard_base64: None,
        books: Vec::new(),
    }
}

/// The default Contacts folder, the account's default book.
///
/// Graph serves it outside the folder list, an empty folder id addressing
/// it, and a contact lands there when no folder is named.
fn graph_default_book() -> Addressbook {
    Addressbook {
        id: String::new(),
        name: "Contacts".to_string(),
        url: String::new(),
        description: None,
        color: None,
        role: default_role(true),
        writable: true,
    }
}

/// The request a batched card read sends beside the shared `$batch`
/// ([`Msgraph::batch`]): one contact on its own.
impl<H: Host> Msgraph<'_, H> {
    /// Reads one contact, its stash expanded.
    fn contact(&mut self, id: &str) -> Result<MsgraphContact, BridgeError> {
        let auth = HttpAuthBearer::new(self.token);
        let coroutine = MsgraphContactGet::new(&auth, "me", id, Some(MSGRAPH_CONTACT_STASH_EXPAND))
            .map_err(|err| err.to_string())?;
        self.run(coroutine)
    }
}

/// The address one contact is read at: the read [`MsgraphContactGet`]
/// sends, its stash expanded.
fn contact_url(id: &str) -> Result<Url, BridgeError> {
    let mut url = graph_url(&format!("me/contacts/{id}"))?;
    url.query_pairs_mut()
        .append_pair("$expand", MSGRAPH_CONTACT_STASH_EXPAND);
    Ok(url)
}

/// The Graph JSON batching envelope (`POST $batch`).
#[derive(Serialize)]
struct GraphBatch {
    requests: Vec<GraphBatchRequest>,
}

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
struct GraphBatchRequest {
    id: String,
    method: &'static str,
    url: String,
    #[serde(skip_serializing_if = "Option::is_none")]
    headers: Option<GraphBatchHeaders>,
    #[serde(skip_serializing_if = "Option::is_none")]
    body: Option<MsgraphContact>,
}

#[derive(Serialize)]
struct GraphBatchHeaders {
    #[serde(rename = "Content-Type")]
    content_type: &'static str,
}

impl GraphBatchHeaders {
    const JSON: Self = Self {
        content_type: "application/json",
    };
}

#[derive(Deserialize)]
struct GraphBatchReplies {
    #[serde(default)]
    responses: Vec<GraphBatchReply>,
}

#[derive(Deserialize)]
struct GraphBatchReply {
    id: String,
    status: u16,
    #[serde(default)]
    body: Option<Value>,
}

#[cfg(test)]
mod tests {
    //! The Graph card read over a fake Graph server: what one `$batch` read
    //! answers is what reading the same contacts one request at a time
    //! answers, for a twentieth of the requests.

    use std::collections::{BTreeMap, BTreeSet};

    use io_msgraph::{
        coroutine::{MsgraphCoroutine, MsgraphCoroutineState, MsgraphYield},
        v1::rest::batch::{
            MSGRAPH_BATCH_MAX_REQUESTS, MsgraphBatchRequest, MsgraphBatchResponse,
            MsgraphBatchResponses,
        },
    };
    use serde_json::{Value, json};

    use crate::{
        account::{Account, Backend},
        client::{
            Auth, Client,
            fake::{FakeRequest, FakeResponse, FakeServer},
            msgraph::relative,
        },
        contact::{CardUpdate, Contacts, msgraph::*},
    };

    /// A Graph contacts folder.
    #[derive(Default)]
    struct FakeGraph {
        /// Contacts by id.
        contacts: BTreeMap<String, Value>,
        /// The contacts whose request inside a batch is refused, with the
        /// status it is refused with.
        refused: BTreeMap<String, u16>,
        /// Whether every request inside a batch is refused (throttled).
        throttled: bool,
    }

    fn contact(id: &str) -> Value {
        json!({
            "id": id,
            "changeKey": format!("ck-{id}"),
            "displayName": format!("Jane {id}"),
            "givenName": "Jane",
            "surname": id,
            "emailAddresses": [{"name": format!("Jane {id}"), "address": format!("{id}@example.com")}],
            "singleValueExtendedProperties": [{
                "id": "String {c8e5e5cf-3f6c-4f0a-9d4e-52f1e7b2a9d3} Name cardamum-vcard",
                "value": format!("NOTE:kept {id}"),
            }],
        })
    }

    /// A folder of `size` contacts.
    fn folder(size: usize) -> FakeGraph {
        let mut graph = FakeGraph::default();
        for index in 0..size {
            let id = format!("AAMkc{index:03}=");
            graph.contacts.insert(id.clone(), contact(&id));
        }
        graph
    }

    impl FakeGraph {
        fn answer(&self, request: &FakeRequest) -> FakeResponse {
            assert_eq!(request.header("Authorization"), Some("Bearer token"));
            match (request.method.as_str(), request.path.as_str()) {
                ("POST", "/v1.0/$batch") => self.batch(request),
                ("GET", path) => {
                    let (status, body) = self.get(path, request.query.iter().cloned().collect());
                    FakeResponse::json(status, &body)
                }
                other => panic!("unexpected request {other:?}: {}", request.text),
            }
        }

        fn batch(&self, request: &FakeRequest) -> FakeResponse {
            let requests: Vec<MsgraphBatchRequest> =
                serde_json::from_value(request.json::<Value>()["requests"].take()).unwrap();
            assert!(!requests.is_empty() && requests.len() <= MSGRAPH_BATCH_MAX_REQUESTS);

            // NOTE: in no particular order, as Graph answers them.
            let responses = requests
                .iter()
                .rev()
                .map(|request| {
                    assert_eq!(request.method, "GET");
                    let id = request
                        .url
                        .trim_start_matches("/me/contacts/")
                        .split('?')
                        .next()
                        .unwrap();
                    let (status, body) = match self.refused.get(id) {
                        _ if self.throttled => (429, json!({"error": {"code": "TooManyRequests"}})),
                        Some(status) => (*status, json!({"error": {"code": "Refused"}})),
                        None => {
                            let url = format!("https://graph.microsoft.com/v1.0{}", request.url);
                            let url = Url::parse(&url).unwrap();
                            self.get(url.path(), url.query_pairs().into_owned().collect())
                        }
                    };
                    MsgraphBatchResponse {
                        id: request.id.clone(),
                        status,
                        body: Some(body),
                        ..Default::default()
                    }
                })
                .collect();
            FakeResponse::json(200, &MsgraphBatchResponses { responses })
        }

        /// Answers one GET by its path and query.
        fn get(&self, path: &str, query: BTreeMap<String, String>) -> (u16, Value) {
            let segments: Vec<&str> = path.trim_start_matches('/').split('/').collect();
            match segments.as_slice() {
                ["v1.0", "me", "contacts", id] => {
                    assert_eq!(query["$expand"], MSGRAPH_CONTACT_STASH_EXPAND);
                    assert_eq!(query.len(), 1, "the single read's query and no other");
                    match self.contacts.get(*id) {
                        Some(contact) => (200, contact.clone()),
                        None => (404, json!({"error": {"code": "ErrorItemNotFound"}})),
                    }
                }
                other => panic!("unexpected request {other:?}"),
            }
        }

        fn ids(&self) -> Vec<String> {
            self.contacts.keys().cloned().collect()
        }
    }

    /// How many `$batch` requests went out.
    fn batches(requests: &[FakeRequest]) -> usize {
        requests
            .iter()
            .filter(|request| request.path == "/v1.0/$batch")
            .count()
    }

    /// How many contacts were read alone.
    fn singles(requests: &[FakeRequest]) -> usize {
        requests
            .iter()
            .filter(|request| request.path.starts_with("/v1.0/me/contacts/"))
            .count()
    }

    /// What the one-by-one read ([`Msgraph::read_card`]) built.
    fn one_by_one(
        graph: &FakeGraph,
        ids: &[&str],
    ) -> Vec<(String, String, Option<String>, String)> {
        ids.iter()
            .map(|id| {
                let contact: MsgraphContact =
                    serde_json::from_value(graph.contacts[*id].clone()).unwrap();
                graph_card(contact)
            })
            .map(|card| plain(&card))
            .collect()
    }

    fn plain(card: &Card) -> (String, String, Option<String>, String) {
        (
            card.id.clone(),
            card.uri.clone(),
            card.etag.clone(),
            card.vcard.clone(),
        )
    }

    fn plains(cards: &[Card]) -> Vec<(String, String, Option<String>, String)> {
        cards.iter().map(plain).collect()
    }

    #[test]
    fn a_batched_read_builds_the_cards_the_one_by_one_read_built() {
        let graph = folder(45);
        let ids = graph.ids();
        let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
        let mut client = Client::new(FakeServer::new(|request| graph.answer(request)));

        let read = Msgraph {
            client: &mut client,
            token: "token",
        }
        .read_cards(&ids)
        .unwrap();

        assert_eq!(plains(&read), one_by_one(&graph, &ids));
        assert!(read[0].vcard.contains("NOTE:kept"), "the stash rode along");
        assert_eq!(batches(&client.host.requests), 3);
        assert_eq!(singles(&client.host.requests), 0, "everything rode a batch");
    }

    #[test]
    fn a_batch_graph_could_not_serve_is_read_again_alone() {
        let mut graph = folder(24);
        let ids = graph.ids();
        let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
        graph.throttled = true;
        let mut client = Client::new(FakeServer::new(|request| graph.answer(request)));

        let read = Msgraph {
            client: &mut client,
            token: "token",
        }
        .read_cards(&ids)
        .unwrap();

        assert_eq!(plains(&read), one_by_one(&graph, &ids));
        assert_eq!(batches(&client.host.requests), 2);
        assert_eq!(singles(&client.host.requests), 24);
    }

    #[test]
    fn only_the_requests_the_batch_refused_are_read_again() {
        let mut graph = folder(20);
        let ids = graph.ids();
        let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
        graph.refused.insert(ids[3].to_owned(), 429);
        graph.refused.insert(ids[11].to_owned(), 503);
        graph.refused.insert(ids[17].to_owned(), 500);
        let mut client = Client::new(FakeServer::new(|request| graph.answer(request)));

        let read = Msgraph {
            client: &mut client,
            token: "token",
        }
        .read_cards(&ids)
        .unwrap();

        assert_eq!(plains(&read), one_by_one(&graph, &ids));
        assert_eq!(batches(&client.host.requests), 1);
        assert_eq!(singles(&client.host.requests), 3);
    }

    #[test]
    fn a_hundred_and_twenty_changed_contacts_ride_six_batches() {
        // NOTE: a bulk change elsewhere (an import, a merge, Outlook editing
        // many at once) names every contact in one delta round.
        let graph = folder(120);
        let ids = graph.ids();
        let ids: Vec<&str> = ids.iter().map(String::as_str).collect();
        let mut client = Client::new(FakeServer::new(|request| graph.answer(request)));

        let read = Msgraph {
            client: &mut client,
            token: "token",
        }
        .read_cards(&ids)
        .unwrap();

        assert_eq!(read.len(), 120);
        let requests = &client.host.requests;
        assert_eq!(
            batches(requests) + singles(requests),
            6,
            "where it was 120 GETs"
        );
    }

    #[test]
    fn a_contact_gone_since_the_delta_is_left_out() {
        let graph = folder(3);
        let ids = graph.ids();
        let mut client = Client::new(FakeServer::new(|request| graph.answer(request)));

        let read = Msgraph {
            client: &mut client,
            token: "token",
        }
        .read_cards(&[&ids[0], "AAMkgone=", &ids[2]])
        .unwrap();

        let read: Vec<&str> = read.iter().map(|card| card.uri.as_str()).collect();
        assert_eq!(read, [ids[0].as_str(), ids[2].as_str()]);
        assert_eq!(singles(&client.host.requests), 0, "a 404 is not read again");
    }

    #[test]
    fn a_contact_gone_before_its_lone_read_is_left_out() {
        let mut graph = folder(3);
        graph.refused.insert("AAMkc001=".into(), 429);
        let mut client = Client::new(FakeServer::new(|request| {
            let response = graph.answer(request);
            // NOTE: gone once the batch refused to serve it.
            if request.path == "/v1.0/$batch" {
                graph.contacts.remove("AAMkc001=");
            }
            response
        }));

        let read = Msgraph {
            client: &mut client,
            token: "token",
        }
        .read_cards(&["AAMkc000=", "AAMkc001=", "AAMkc002="])
        .unwrap();

        let read: Vec<&str> = read.iter().map(|card| card.uri.as_str()).collect();
        assert_eq!(read, ["AAMkc000=", "AAMkc002="]);
    }

    #[test]
    fn any_other_failure_of_a_lone_read_fails_the_read() {
        let mut graph = folder(2);
        graph.throttled = true;
        let mut client = Client::new(FakeServer::new(|request| match request.path.as_str() {
            "/v1.0/$batch" => graph.answer(request),
            _ => FakeResponse::json(500, &json!({"error": {"code": "InternalServerError"}})),
        }));

        let err = Msgraph {
            client: &mut client,
            token: "token",
        }
        .read_cards(&["AAMkc000=", "AAMkc001="])
        .err()
        .unwrap();
        assert_eq!(err.status, Some(500));
    }

    /// The path and query a coroutine's request line asks for.
    fn requested<C>(mut coroutine: C) -> String
    where
        C: MsgraphCoroutine<Yield = MsgraphYield>,
    {
        match coroutine.resume(None) {
            MsgraphCoroutineState::Yielded(MsgraphYield::WantsWrite(bytes)) => {
                let request = String::from_utf8(bytes).unwrap();
                let line = request.lines().next().unwrap();
                line.split(' ').nth(1).unwrap().to_owned()
            }
            _ => panic!("no request"),
        }
    }

    #[test]
    fn a_batched_read_asks_what_the_single_read_asks() {
        let auth = HttpAuthBearer::new("token");
        let ids = ["AAMk-1=", "AAMkAGI2_x-y=="];

        let mut asked = BTreeSet::new();
        let urls: Vec<Url> = ids.iter().map(|id| contact_url(id).unwrap()).collect();
        batched(
            |requests| {
                asked.extend(requests.iter().map(|request| request.url.clone()));
                Ok(Vec::new())
            },
            &urls,
        )
        .unwrap();

        for id in ids {
            let single = requested(
                MsgraphContactGet::new(&auth, "me", id, Some(MSGRAPH_CONTACT_STASH_EXPAND))
                    .unwrap(),
            );
            let batched = relative(&contact_url(id).unwrap());
            assert_eq!(single, format!("/v1.0{batched}"));
            assert!(asked.contains(&batched));
        }
    }

    /// The folder Graph serves outside the folder list is where a contact
    /// lands when none is named: the default book.
    #[test]
    fn the_default_contacts_folder_is_the_default_book() {
        let book = graph_default_book();

        assert_eq!(book.id, "");
        assert_eq!(book.role, "default");
        assert!(book.writable);
    }

    /// A folder below the top level is a book of its own, at any depth,
    /// its pages followed.
    #[test]
    fn a_contact_folder_below_the_top_level_is_listed() {
        let folder = |id: &str, name: &str| json!({"id": id, "displayName": name});
        let mut client = Client::new(FakeServer::new(|request: &FakeRequest| {
            let skip = request.param("$skip");
            let page = match (request.path.as_str(), skip) {
                ("/v1.0/me/contactFolders", None) => json!({"value": [folder("F", "Family")]}),
                ("/v1.0/me/contactFolders/F/childFolders", None) => json!({
                    "value": [folder("K", "Kids")],
                    "@odata.nextLink":
                        "https://graph.microsoft.com/v1.0/me/contactFolders/F/childFolders?$skip=1",
                }),
                ("/v1.0/me/contactFolders/F/childFolders", Some("1")) => {
                    json!({"value": [folder("C", "Cousins")]})
                }
                ("/v1.0/me/contactFolders/K/childFolders", None) => {
                    json!({"value": [folder("S", "School")]})
                }
                (
                    "/v1.0/me/contactFolders/C/childFolders"
                    | "/v1.0/me/contactFolders/S/childFolders",
                    None,
                ) => json!({"value": []}),
                other => panic!("unexpected request {other:?}"),
            };
            FakeResponse::json(200, &page)
        }));

        let books = Msgraph {
            client: &mut client,
            token: "token",
        }
        .list_addressbooks()
        .unwrap();

        let listed: Vec<(&str, &str)> = books
            .iter()
            .map(|book| (book.id.as_str(), book.name.as_str()))
            .collect();
        assert_eq!(
            listed,
            [
                ("", "Contacts"),
                ("F", "Family"),
                ("K", "Kids"),
                ("C", "Cousins"),
                ("S", "School"),
            ]
        );
    }

    /// A change Graph refuses as it stands inside a `$batch` is answered
    /// refused, one throttled or failing on Graph's side is not: the round
    /// goes on either way.
    #[test]
    fn a_batched_change_refused_as_it_stands_is_told_refused() {
        let mut client = Client::new(FakeServer::new(|request: &FakeRequest| {
            let requests: Vec<MsgraphBatchRequest> =
                serde_json::from_value(request.json::<Value>()["requests"].take()).unwrap();
            let responses = requests
                .iter()
                .map(|request| {
                    let (status, body) = match request.id.as_str() {
                        "0" => (201, json!({"id": "n0", "changeKey": "k0"})),
                        "1" => (
                            400,
                            json!({"error": {"code": "ErrorInvalidProperty", "message": "bad"}}),
                        ),
                        "2" => (
                            429,
                            json!({"error": {"code": "TooManyRequests", "message": "slow"}}),
                        ),
                        _ => (
                            503,
                            json!({"error": {"code": "ServiceUnavailable", "message": "down"}}),
                        ),
                    };
                    MsgraphBatchResponse {
                        id: request.id.clone(),
                        status,
                        body: Some(body),
                        ..Default::default()
                    }
                })
                .collect();
            FakeResponse::json(200, &MsgraphBatchResponses { responses })
        }));
        let vcard = "BEGIN:VCARD\r\nVERSION:4.0\r\nFN:Jane\r\nEND:VCARD\r\n";
        let changes: Vec<PushChange> = ["a", "b", "c", "d"]
            .iter()
            .map(|reference| {
                serde_json::from_value(json!({"ref": reference, "op": "create", "vcard": vcard}))
                    .unwrap()
            })
            .collect();

        let outcomes = Msgraph {
            client: &mut client,
            token: "token",
        }
        .push_cards("", &changes)
        .unwrap();

        let told: Vec<(&str, bool, bool)> = outcomes
            .iter()
            .map(|outcome| {
                (
                    outcome.reference.as_str(),
                    outcome.accepted,
                    outcome.refused,
                )
            })
            .collect();
        assert_eq!(
            told,
            [
                ("a", true, false),
                ("b", false, true),
                ("c", false, false),
                ("d", false, false)
            ]
        );
    }

    /// A Graph folder whose contacts the server edits behind the listing:
    /// reads answer the contact at its changeKey now, writes are recorded.
    struct FakeEdited {
        /// Contacts by id, at the changeKey the server holds now.
        contacts: BTreeMap<String, Value>,
        /// The ids every PATCH addressed, in order.
        patched: Vec<String>,
    }

    impl FakeEdited {
        /// Jane `k1` edited on the server since the listing read `ck-k1-0`,
        /// Joe `k2` left alone.
        fn new() -> Self {
            let at = |id: &str, key: &str| {
                let mut contact = contact(id);
                contact["changeKey"] = json!(key);
                contact
            };
            Self {
                contacts: BTreeMap::from([
                    ("k1".to_string(), at("k1", "ck-k1-1")),
                    ("k2".to_string(), at("k2", "ck-k2-0")),
                ]),
                patched: Vec::new(),
            }
        }

        fn answer(&mut self, request: &FakeRequest) -> FakeResponse {
            if request.path == "/v1.0/$batch" {
                let requests: Vec<MsgraphBatchRequest> =
                    serde_json::from_value(request.json::<Value>()["requests"].take()).unwrap();
                let responses = requests
                    .iter()
                    .map(|inner| {
                        let id = inner
                            .url
                            .trim_start_matches("/me/contacts/")
                            .split('?')
                            .next()
                            .unwrap()
                            .to_string();
                        let (status, body) = self.one(&inner.method, &id);
                        MsgraphBatchResponse {
                            id: inner.id.clone(),
                            status,
                            body: Some(body),
                            ..Default::default()
                        }
                    })
                    .collect();
                return FakeResponse::json(200, &MsgraphBatchResponses { responses });
            }
            let id = request
                .path
                .trim_start_matches("/v1.0/me/contacts/")
                .to_string();
            let (status, body) = self.one(&request.method, &id);
            FakeResponse::json(status, &body)
        }

        fn one(&mut self, method: &str, id: &str) -> (u16, Value) {
            if method == "PATCH" {
                self.patched.push(id.to_string());
            }
            match self.contacts.get(id) {
                Some(contact) => (200, contact.clone()),
                None => (404, json!({"error": {"code": "ErrorItemNotFound"}})),
            }
        }
    }

    /// A vCard of the contact named `name`.
    fn vcard(name: &str) -> String {
        format!("BEGIN:VCARD\r\nVERSION:4.0\r\nFN:{name}\r\nN:{name};;;;\r\nEND:VCARD\r\n")
    }

    /// A contact the server edited between the listing and the push is not
    /// written over: left pending for the next listing to merge, the other
    /// contact of the round written.
    #[test]
    fn a_contact_edited_on_the_server_since_the_listing_is_not_written_over() {
        let mut graph = FakeEdited::new();
        let update = |reference: &str, id: &str, key: &str| {
            json!({
                "ref": reference,
                "op": "update",
                "id": id,
                "vcard": vcard("Jane Phone"),
                "baseVcard": vcard("Jane"),
                "ifMatch": key,
            })
        };
        let changes: Vec<PushChange> = serde_json::from_value(json!([
            update("jane", "k1", "ck-k1-0"),
            update("joe", "k2", "ck-k2-0"),
        ]))
        .unwrap();

        let mut client = Client::new(FakeServer::new(|request| graph.answer(request)));
        let outcomes = Msgraph {
            client: &mut client,
            token: "token",
        }
        .push_cards("", &changes)
        .unwrap();

        assert_eq!(graph.patched, ["k2"], "Jane's server edit kept");
        let told: BTreeMap<&str, (bool, bool)> = outcomes
            .iter()
            .map(|outcome| {
                (
                    outcome.reference.as_str(),
                    (outcome.accepted, outcome.refused),
                )
            })
            .collect();
        assert_eq!(told["jane"], (false, false), "rejected, not refused");
        assert_eq!(told["joe"], (true, false));
    }

    /// A lone update over a changeKey the server moved past is a conflict
    /// (412), never sent.
    #[test]
    fn a_lone_update_over_a_moved_change_key_is_never_sent() {
        let mut graph = FakeEdited::new();
        let account = Account {
            base_url: "msgraph://jane@example.com",
            backend: Backend::Graph,
            auth: Auth::Bearer("token"),
            zone: "",
        };
        let vcard = vcard("Jane Phone");
        let base = self::vcard("Jane");
        let card = CardUpdate {
            id: "k1",
            uri: "k1",
            vcard: &vcard,
            base_vcard: Some(&base),
            etag: Some("ck-k1-0"),
        };

        let mut client = Client::new(FakeServer::new(|request| graph.answer(request)));
        let Err(err) = (Contacts {
            client: &mut client,
            account: &account,
        })
        .update_card("msgraph://jane@example.com/contacts", &card) else {
            panic!("the update was written over the server edit");
        };

        assert_eq!(err.status, Some(412));
        assert!(graph.patched.is_empty(), "Jane's server edit kept");
    }
}
