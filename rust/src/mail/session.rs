//! # Sessions
//!
//! The mail session a caller holds across native calls, and [`Mail`], the
//! router dispatching every mail verb to the session's backend.
//!
//! Other domains build a client per call and drop it. An IMAP connection
//! is a conversation instead: per call, a pass walking an account then
//! writing three markers would pay four greetings, TLS handshakes and
//! logins for three commands, so the session keeps it open.
//!
//! Opened once, handed to Java as a handle, a session holds only what
//! outlives a call: protocol state, endpoint, credential. No JNI reference
//! (a local one dies on return, a global one leaks): the [`Client`] rides
//! in per call, and [`Mail`] binds the two for its length.
//!
//! JMAP, Graph and Gmail sessions carry no protocol state, HTTP being
//! self-contained; they exist so every backend opens, runs and closes the
//! same way, the transport pooling the socket for the whole pass.

use std::{
    collections::{BTreeMap, HashMap},
    mem,
    path::Path,
    sync::Arc,
};

use serde::{Deserialize, Serialize};
use url::Url;

use crate::{
    account::{self, AccountJson, Backend},
    client::{Auth, Client, Host, jmap::Jmap, msgraph::Msgraph, parse_url},
    error::{BridgeError, REFUSED},
    mail::{
        Mailbox, compose,
        gmail::{Gmail, belongs},
        gmail_step::{self, GmailRun},
        imap::{self, ImapState},
        jmap_step::{self, JmapRun, StepRequest},
        listing::{Floor, FloorReply, MailPage, MailRequest, Named, Scope},
        msgraph::{GraphFetched, StepAsk},
        smtp,
    },
    offline::{summary::SummaryJson, wire::sorted},
};

/// One account's live mail connection, held across native calls.
pub struct MailSession {
    /// Where mail is read from and the credential, as the account stores
    /// them.
    account: AccountJson,
    kind: MailKind,
    /// What the session's pool run read of a Gmail account, shared with the
    /// run's other sessions; its own until it joins one ([`Self::join_run`]).
    gmail: Arc<GmailRun>,
    /// What the pool run read of a JMAP account's changes, shared alike.
    jmap: Arc<JmapRun>,
    /// The pool run the session works for, 0 for a session of its own (the
    /// reader's).
    run: i64,
}

/// The protocol state a session carries, if its backend has any.
enum MailKind {
    Imap(Box<ImapState>),
    Jmap(MailListing),
    Graph(MailListing),
    Gmail(MailListing),
}

/// What an HTTP mail session remembers across a pass.
///
/// What a Gmail pass reads beyond the roster, the account's listing and
/// envelopes, is the pool run's rather than one session's ([`GmailRun`]).
#[derive(Default)]
pub struct MailListing {
    /// The id every verb addresses a mailbox by, by the mailbox's name.
    pub ids: BTreeMap<String, String>,
}

impl MailListing {
    /// Keeps the ids a roster named, by path, and answers the roster.
    pub fn remember(&mut self, listed: Vec<(String, Mailbox)>) -> Vec<Mailbox> {
        self.ids.clear();
        let mut roster = Vec::with_capacity(listed.len());
        for (id, mailbox) in listed {
            self.ids.insert(mailbox.name.clone(), id);
            roster.push(mailbox);
        }
        roster
    }

    /// The id of the mailbox `mailbox`, reading the roster when none is held.
    ///
    /// So no session has to list the mailboxes before using one: one opened
    /// to widen a mailbox, a fill worker's, or one reopened starts with no
    /// roster. A name the roster just read does not hold is not there.
    pub fn resolve(
        &mut self,
        mailbox: &str,
        roster: impl FnOnce() -> Result<Vec<(String, Mailbox)>, BridgeError>,
    ) -> Result<String, BridgeError> {
        if let Some(id) = self.ids.get(mailbox) {
            return Ok(id.clone());
        }
        self.remember(roster()?);
        match self.ids.get(mailbox) {
            Some(id) => Ok(id.clone()),
            None => Err(format!("No mailbox named `{mailbox}`").into()),
        }
    }
}

impl MailSession {
    /// Opens and authenticates a session for the account.
    pub fn open<H: Host>(
        client: &mut Client<H>,
        account: AccountJson,
    ) -> Result<Self, BridgeError> {
        let opened = account.account();
        let kind = match opened.backend {
            Backend::Jmap => {
                // NOTE: checked eagerly, so a session that cannot
                // authenticate fails here rather than on its first verb.
                Jmap {
                    client,
                    session_url: account::jmap_session_url(opened.base_url)?,
                    auth: opened.auth,
                }
                .check_session()?;
                MailKind::Jmap(MailListing::default())
            }
            Backend::Graph => {
                Msgraph {
                    client,
                    token: opened.auth.secret(),
                }
                .check_mail()?;
                MailKind::Graph(MailListing::default())
            }
            Backend::Google => {
                Gmail {
                    client,
                    token: opened.auth.secret(),
                }
                .history_id()?;
                MailKind::Gmail(MailListing::default())
            }
            _ => {
                let url = parse_url(opened.base_url)?;
                MailKind::Imap(Box::new(imap::open(client, &url, &opened.auth)?))
            }
        };

        Ok(Self {
            account,
            kind,
            gmail: Arc::default(),
            jmap: Arc::default(),
            run: 0,
        })
    }

    /// The credential every verb signs in with, borrowed from the session.
    fn auth(&self) -> Auth<'_> {
        self.account.account().auth
    }

    /// The JMAP session resource this account is reached through.
    fn jmap_url(&self) -> Result<Url, BridgeError> {
        Ok(account::jmap_session_url(&self.account.base_url)?)
    }

    /// Whether a delete for good erases one message alone: every HTTP
    /// backend does, an IMAP server only with UIDPLUS (RFC 4315).
    pub fn expunges_one(&self) -> bool {
        match &self.kind {
            MailKind::Imap(state) => state.expunges_one(),
            _ => true,
        }
    }

    /// What the run this session works for read of its Gmail account.
    fn gmail_run(&self) -> Arc<GmailRun> {
        Arc::clone(&self.gmail)
    }

    /// What the run this session works for read of its JMAP account.
    fn jmap_run(&self) -> Arc<JmapRun> {
        Arc::clone(&self.jmap)
    }

    /// Joins the pool run numbered `run`, sharing what it reads of a Gmail
    /// or JMAP account with the run's other sessions; 0 gives the session
    /// an empty run of its own.
    pub fn join_run(&mut self, run: i64) {
        let account = format!("{}\u{0}{}", self.account.base_url, self.account.login);
        self.gmail = gmail_step::join(&account, run);
        self.jmap = jmap_step::join(&account, run);
        self.run = run;
    }

    /// Whether the session works for no pool run: opened for one request
    /// someone waits on, as the reader opening a message does.
    fn alone(&self) -> bool {
        self.run == 0
    }

    /// What this HTTP session remembers of the pass.
    fn listing(&mut self) -> &mut MailListing {
        match &mut self.kind {
            MailKind::Jmap(listing) | MailKind::Graph(listing) | MailKind::Gmail(listing) => {
                listing
            }
            MailKind::Imap(_) => unreachable!("no mailbox listing on an IMAP session"),
        }
    }

    /// The IMAP session bound to the call about to use it.
    ///
    /// Refuses an HTTP session rather than opening a second connection
    /// behind the caller's back: a verb reaching for this there has not
    /// said what it does on that backend.
    fn imap<'a, H: Host>(
        &'a mut self,
        client: &'a mut Client<H>,
    ) -> Result<imap::ImapSession<'a, H>, BridgeError> {
        match &mut self.kind {
            MailKind::Imap(state) => Ok(imap::ImapSession::bind(client, state)),
            MailKind::Jmap(_) | MailKind::Graph(_) | MailKind::Gmail(_) => {
                Err("This account speaks HTTP, which has no IMAP session".into())
            }
        }
    }
}

/// What a step is asked, each backend reading what it needs.
#[derive(Deserialize)]
pub struct MailStepRequest {
    /// How many messages the step brings, every backend; which of a listed
    /// page the store holds is asked of it ([`Client::held`]).
    pub count: usize,
    /// The instant (RFC 3339) the step starts below: Graph, and JMAP once
    /// no anchor is left.
    pub frontier: Option<String>,
    /// The account's bound, Graph.
    pub bound: Option<String>,
    /// The anchors the last step answered, oldest first, JMAP.
    pub anchors: Vec<String>,
    /// The mailboxes stepped, by name: Graph and a JMAP account step.
    #[serde(default)]
    pub mailboxes: Vec<String>,
}

/// The step [`Mail::step`] takes.
pub struct MailStep<'a> {
    /// The one mailbox a view filtered to it steps, empty for the account.
    pub mailbox: &'a str,
    /// What the step is asked.
    pub request: &'a MailStepRequest,
    /// The directory every message read is written into.
    pub dir: &'a Path,
}

/// The messages [`Mail::fetch_sources`] reads.
pub struct MessageFetch<'a> {
    /// The mailbox they are read from.
    pub mailbox: &'a str,
    /// Their ids.
    pub ids: &'a [String],
    /// The directory each is written into.
    pub dir: &'a Path,
}

/// What one step brought, its shape the backend's ([`Mail::step`]).
#[derive(Serialize)]
#[serde(untagged, rename_all_fields = "camelCase")]
pub enum MailStepReply {
    /// A Gmail or JMAP step.
    Named {
        /// The anchors the next step starts from, JMAP only.
        #[serde(skip_serializing_if = "Option::is_none")]
        anchors: Option<Vec<String>>,
        /// Whether the step walked by reception rather than by `Date`.
        by_reception: bool,
        /// Whether the step reached the end of the account.
        exhausted: bool,
        /// The account's state read before the step (JMAP's `Email` state).
        history_id: String,
        /// The messages stepped.
        items: Vec<NamedStepItem>,
    },
    /// A Graph step.
    Graph {
        /// Whether the step reached the end of the mailboxes.
        exhausted: bool,
        /// The messages stepped.
        items: Vec<GraphStepItem>,
    },
}

/// One message of a Gmail or JMAP step: named as a listed one is, with
/// the mailboxes it is a member of, by name, and its file.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct NamedStepItem {
    /// Its IMAP markers.
    pub flags: Vec<String>,
    /// Its provider id.
    pub handle: String,
    /// What the store files it under, the handle.
    pub link_id: String,
    /// The mailboxes it is a member of, by name.
    pub mailboxes: Vec<String>,
    /// The file its body was written to.
    pub path: String,
    /// When it was received, in milliseconds since the epoch.
    pub received: Option<i64>,
    /// The file's size in octets.
    pub size: usize,
    /// Its summary's sort key, empty when it has none.
    #[serde(skip_serializing_if = "String::is_empty")]
    pub sort_key: String,
    /// Its Annex A summary.
    #[serde(serialize_with = "sorted", skip_serializing_if = "Option::is_none")]
    pub summary: Option<SummaryJson>,
}

/// One message of a Graph step: the mailbox it was stepped in and, where
/// its body was read, its file.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct GraphStepItem {
    /// Its Graph id.
    pub handle: String,
    /// The one mailbox it was stepped in, null for a folder the step does
    /// not name.
    pub mailboxes: Vec<Option<String>>,
    /// The file its body was written to, absent for a held message.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub path: Option<String>,
    /// When it was received, in milliseconds since the epoch.
    pub received: Option<i64>,
    /// Its size in octets, the file's where its body was read.
    pub size: Option<u64>,
    /// Its sort key, the `sentDateTime` it was stepped by.
    pub sort_key: String,
}

/// Some messages read whole into files ([`Mail::fetch_sources`]), and the
/// ids gone.
#[derive(Serialize)]
pub struct MessageSources {
    /// The ids of messages no longer there.
    pub gone: Vec<String>,
    /// The messages read.
    pub items: Vec<MessageSource>,
}

/// One message read whole into a file.
#[derive(Serialize)]
#[serde(untagged)]
pub enum MessageSource {
    /// A Gmail message, with its labels and when it was received, in
    /// milliseconds since the epoch.
    Labelled {
        /// Its Gmail id.
        id: String,
        /// Its label ids.
        labels: Vec<String>,
        /// The file it was written to.
        path: String,
        /// When it was received, in milliseconds since the epoch.
        received: Option<i64>,
        /// The file's size in octets.
        size: usize,
    },
    /// A message of any other backend: its id, file and the file's size.
    Plain { id: String, path: String, size: u64 },
}

/// The marker [`Mail::set_flag`] writes.
pub struct FlagWrite<'a> {
    /// The mailbox holding the message.
    pub mailbox: &'a str,
    /// The message's id.
    pub id: &'a str,
    /// The marker: `\Seen`, `\Answered`, `\Flagged`, `\Deleted`.
    pub flag: &'a str,
    /// Whether the marker is added, else removed.
    pub add: bool,
}

/// The message [`Mail::relocate`] moves or [`Mail::copy`] copies.
pub struct MessageTransfer<'a> {
    /// The mailbox holding the message.
    pub mailbox: &'a str,
    /// The message's id.
    pub id: &'a str,
    /// The mailbox it goes to.
    pub target: &'a str,
}

/// The message [`Mail::append`] files.
pub struct MessageAppend<'a> {
    /// The mailbox it is filed in.
    pub mailbox: &'a str,
    /// The message's bytes.
    pub raw: Vec<u8>,
    /// The markers it is filed with.
    pub flags: &'a [String],
}

/// One call's use of a held session, every mail verb routed to the
/// backend the session speaks.
///
/// The session outlives the call and the client does not, so the two meet
/// here for the length of one call.
pub struct Mail<'s, 'c, H> {
    /// The session the caller holds.
    pub session: &'s mut MailSession,
    /// The client the call brought.
    pub client: &'c mut Client<H>,
}

/// The protocol-agnostic mail API the bridge exposes, each method routing
/// to the IMAP, JMAP, Graph or Gmail backend behind the session.
impl<H: Host> Mail<'_, '_, H> {
    /// The account's mailboxes.
    ///
    /// An HTTP session remembers each one's id, as every later verb
    /// addresses a mailbox by id and only the roster answers it.
    pub fn list_mailboxes(&mut self) -> Result<Vec<Mailbox>, BridgeError> {
        match self.session.kind {
            MailKind::Imap(_) => imap::list_mailboxes(&mut self.session.imap(self.client)?),
            MailKind::Jmap(_) | MailKind::Graph(_) | MailKind::Gmail(_) => {
                let listed = self.http_roster()?;
                Ok(self.session.listing().remember(listed))
            }
        }
    }

    /// One page of a mailbox's listing.
    pub fn enumerate(
        &mut self,
        mailbox: &str,
        request: &MailRequest,
    ) -> Result<MailPage, BridgeError> {
        match self.session.kind {
            MailKind::Imap(_) => self.session.imap(self.client)?.list_page(mailbox, request),
            MailKind::Gmail(_) => {
                let id = self.mailbox_id(mailbox)?;
                // NOTE: a label's delta is the account's history projected
                // onto it, replayed once for every session of the pool run.
                let run = self.session.gmail_run();
                let token = self.session.auth().secret().to_string();
                Gmail {
                    client: self.client,
                    token: &token,
                }
                .list_label(&run, &id, request)
            }
            MailKind::Jmap(_) => {
                let id = self.mailbox_id(mailbox)?;
                let url = self.session.jmap_url()?;
                let run = self.session.jmap_run();
                Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                }
                .list_page(&run, &id, request)
            }
            MailKind::Graph(_) => {
                let id = self.mailbox_id(mailbox)?;
                Msgraph {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .list_page(&id, request)
            }
        }
    }

    /// Names messages listed by id alone, read by their metadata.
    ///
    /// A Graph delta's, or a Gmail round's the store does not hold in that
    /// mailbox (new, or newly labelled); one no longer in it is left out.
    pub fn name_messages(
        &mut self,
        mailbox: &str,
        ids: &[String],
    ) -> Result<Vec<Named>, BridgeError> {
        let id = self.mailbox_id(mailbox)?;
        match self.session.kind {
            MailKind::Gmail(_) => {
                let read = Gmail {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .envelopes(ids)?;
                Ok(read
                    .into_iter()
                    .filter_map(|(handle, envelope)| {
                        let envelope = envelope.filter(|envelope| envelope.belongs(&id))?;
                        envelope.named(&handle, &Scope::default())
                    })
                    .collect())
            }
            MailKind::Graph(_) => Msgraph {
                client: self.client,
                token: self.session.auth().secret(),
            }
            .name_messages(ids),
            MailKind::Imap(_) | MailKind::Jmap(_) => {
                Err("Only a Graph or Gmail listing names messages by id alone".into())
            }
        }
    }

    /// A chunk's floor.
    pub fn floor(&mut self, mailbox: &str, mut floor: Floor) -> Result<FloorReply, BridgeError> {
        match self.session.kind {
            MailKind::Imap(_) => {
                self.session.imap(self.client)?.floor(mailbox, &mut floor)?;
                Ok(floor.reply())
            }
            MailKind::Jmap(_) => {
                let id = self.mailbox_id(mailbox)?;
                let url = self.session.jmap_url()?;
                Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                }
                .floor(&id, &mut floor)?;
                Ok(floor.reply())
            }
            MailKind::Graph(_) => {
                let id = self.mailbox_id(mailbox)?;
                Msgraph {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .floor(&id, &mut floor)?;
                Ok(floor.reply())
            }
            MailKind::Gmail(_) => {
                // NOTE: the mailbox is resolved first, as on every HTTP
                // backend, so an unknown one is refused as such.
                self.mailbox_id(mailbox)?;
                Err("A Gmail account is stepped through, never floored".into())
            }
        }
    }

    /// One step of the account; IMAP steps from the store.
    pub fn step(&mut self, step: &MailStep) -> Result<MailStepReply, BridgeError> {
        match self.session.kind {
            MailKind::Jmap(_) => self.jmap_step(step),
            MailKind::Graph(_) => self.graph_step(step),
            MailKind::Gmail(_) => self.gmail_step(step),
            MailKind::Imap(_) => Err("An IMAP account steps from the store".into()),
        }
    }

    /// One step of a Gmail account ([`Gmail::step`]), each message with the
    /// mailboxes its labels place it in.
    fn gmail_step(&mut self, step: &MailStep) -> Result<MailStepReply, BridgeError> {
        let label = match step.mailbox.is_empty() {
            true => None,
            false => Some(self.mailbox_id(step.mailbox)?),
        };
        if self.session.listing().ids.is_empty() {
            let listed = self.http_roster()?;
            self.session.listing().remember(listed);
        }
        let names: HashMap<String, String> = self
            .session
            .listing()
            .ids
            .iter()
            .map(|(name, id)| (id.clone(), name.clone()))
            .collect();

        let token = self.session.auth().secret().to_string();
        let stepped = Gmail {
            client: self.client,
            token: &token,
        }
        .step(label.as_deref(), step.request.count, step.dir)?;

        let mut items = Vec::with_capacity(stepped.messages.len());
        for fetched in stepped.messages {
            let mailboxes = fetched
                .labels
                .iter()
                .filter(|label| belongs(&fetched.labels, label))
                .filter_map(|label| names.get(label).cloned())
                .collect();
            let named = Named::new(fetched.id.clone(), fetched.flags.clone(), fetched.summary);
            items.push(NamedStepItem {
                flags: named.flags,
                handle: named.handle,
                link_id: named.link_id,
                mailboxes,
                path: fetched.path.to_string_lossy().into_owned(),
                received: fetched.received,
                size: fetched.size,
                sort_key: named.sort_key,
                summary: named.summary,
            });
        }
        Ok(MailStepReply::Named {
            anchors: None,
            by_reception: true,
            exhausted: stepped.exhausted,
            history_id: stepped.history_id,
            items,
        })
    }

    /// One step of a JMAP account ([`Jmap::step`]), each message with the
    /// mailboxes its `mailboxIds` file it in, by name.
    ///
    /// The trash and the junk are left out of an account step, the roster
    /// read afresh for the roles naming them.
    fn jmap_step(&mut self, step: &MailStep) -> Result<MailStepReply, BridgeError> {
        let request = step.request;
        let listed = self.http_roster()?;
        // NOTE: an account step naming its mailboxes (the filter's ticks)
        // leaves every other out, bringing nothing none of them would file.
        let excluded = listed
            .iter()
            .filter(|(_, listed)| {
                listed.role == "trash"
                    || listed.role == "junk"
                    || (!request.mailboxes.is_empty() && !request.mailboxes.contains(&listed.name))
            })
            .map(|(id, _)| id.clone())
            .collect();
        let names: HashMap<String, String> = listed
            .iter()
            .map(|(id, listed)| (id.clone(), listed.name.clone()))
            .collect();
        self.session.listing().remember(listed);
        let only = match step.mailbox.is_empty() {
            true => None,
            false => Some(self.mailbox_id(step.mailbox)?),
        };

        let url = self.session.jmap_url()?;
        let asked = StepRequest {
            mailbox: only.as_deref(),
            excluded,
            sent_at: false,
            anchors: &request.anchors,
            frontier: request.frontier.as_deref(),
            count: request.count,
        };
        let stepped = Jmap {
            client: self.client,
            session_url: url,
            auth: self.session.auth(),
        }
        .step(asked, step.dir)?;

        let mut items = Vec::with_capacity(stepped.items.len());
        for item in stepped.items {
            let mailboxes = item
                .mailboxes
                .iter()
                .filter_map(|id| names.get(id).cloned())
                .collect();
            items.push(NamedStepItem {
                flags: item.named.flags,
                handle: item.named.handle,
                link_id: item.named.link_id,
                mailboxes,
                path: item.path.to_string_lossy().into_owned(),
                received: item.received,
                size: item.size,
                sort_key: item.named.sort_key,
                summary: item.named.summary,
            });
        }
        Ok(MailStepReply::Named {
            anchors: Some(stepped.anchors),
            by_reception: stepped.by_reception,
            exhausted: stepped.exhausted,
            history_id: stepped.state,
            items,
        })
    }

    /// One step of a Graph account ([`Msgraph::step`]) below the frontier.
    ///
    /// Over the `mailboxes` named, or `mailbox` alone for a filtered view
    /// (its own frontier then); a held message's body is never read again.
    fn graph_step(&mut self, step: &MailStep) -> Result<MailStepReply, BridgeError> {
        let request = step.request;
        let names: Vec<String> = match step.mailbox.is_empty() {
            true => request.mailboxes.clone(),
            false => vec![step.mailbox.to_string()],
        };
        let mut folders = Vec::with_capacity(names.len());
        let mut named = HashMap::with_capacity(names.len());
        for name in &names {
            let id = self.mailbox_id(name)?;
            named.insert(id.clone(), name.clone());
            folders.push(id);
        }

        let ask = StepAsk {
            folders: &folders,
            alone: !step.mailbox.is_empty(),
            frontier: request.frontier.as_deref(),
            bound: request.bound.as_deref(),
            count: request.count,
        };
        let token = self.session.auth().secret().to_string();
        let (stepped, fetched) = Msgraph {
            client: self.client,
            token: &token,
        }
        .step(&ask, step.dir)?;
        let mut files: HashMap<String, GraphFetched> = fetched
            .into_iter()
            .map(|fetched| (fetched.id.clone(), fetched))
            .collect();

        let items = stepped
            .messages
            .into_iter()
            .map(|message| {
                let file = files.remove(&message.id);
                GraphStepItem {
                    mailboxes: vec![named.get(&message.folder).cloned()],
                    path: file
                        .as_ref()
                        .map(|file| file.path.to_string_lossy().into_owned()),
                    received: message.received,
                    size: file.map_or(message.size, |file| Some(file.size as u64)),
                    sort_key: message.date,
                    handle: message.id,
                }
            })
            .collect();
        Ok(MailStepReply::Graph {
            exhausted: stepped.exhausted,
            items,
        })
    }

    /// Some messages read whole into files, and the ids gone.
    ///
    /// Gmail and Graph in batches ([`Gmail::sources`], [`Msgraph::sources`]),
    /// IMAP by streamed `UID FETCH` ([`imap::ImapSession::fetch_sources`]),
    /// JMAP blob by blob after one `Email/get` ([`Jmap::sources`]).
    pub fn fetch_sources(&mut self, fetch: &MessageFetch) -> Result<MessageSources, BridgeError> {
        let ids = fetch.ids;
        match self.session.kind {
            MailKind::Jmap(_) => {
                let url = self.session.jmap_url()?;
                let read = Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                }
                .sources(ids, fetch.dir)?;
                let items = read
                    .fetched
                    .into_iter()
                    .map(|read| MessageSource::Plain {
                        id: read.id,
                        path: read.path.to_string_lossy().into_owned(),
                        size: read.size as u64,
                    })
                    .collect();
                Ok(MessageSources {
                    gone: read.gone,
                    items,
                })
            }
            MailKind::Gmail(_) => {
                // NOTE: one message on a session of no pool run is the
                // reader's, someone waiting on it: it skips the fill's queue.
                self.client.urgent = self.session.alone() && ids.len() == 1;
                let token = self.session.auth().secret().to_string();
                let read = Gmail {
                    client: self.client,
                    token: &token,
                }
                .sources(ids, fetch.dir)?;
                let items = read
                    .fetched
                    .into_iter()
                    .map(|fetched| MessageSource::Labelled {
                        id: fetched.id,
                        labels: fetched.labels,
                        path: fetched.path.to_string_lossy().into_owned(),
                        received: fetched.received,
                        size: fetched.size,
                    })
                    .collect();
                Ok(MessageSources {
                    gone: read.gone,
                    items,
                })
            }
            MailKind::Graph(_) => {
                let token = self.session.auth().secret().to_string();
                let read = Msgraph {
                    client: self.client,
                    token: &token,
                }
                .sources(ids, fetch.dir)?;
                let items = read
                    .fetched
                    .into_iter()
                    .map(|fetched| MessageSource::Plain {
                        id: fetched.id,
                        path: fetched.path.to_string_lossy().into_owned(),
                        size: fetched.size as u64,
                    })
                    .collect();
                Ok(MessageSources {
                    gone: read.gone,
                    items,
                })
            }
            MailKind::Imap(_) => {
                let read =
                    self.session
                        .imap(self.client)?
                        .fetch_sources(fetch.mailbox, ids, fetch.dir)?;
                let items = read
                    .fetched
                    .into_iter()
                    .map(|source| MessageSource::Plain {
                        id: source.id,
                        path: source.path.to_string_lossy().into_owned(),
                        size: source.size,
                    })
                    .collect();
                Ok(MessageSources {
                    gone: read.gone,
                    items,
                })
            }
        }
    }

    /// Writes one marker.
    pub fn set_flag(&mut self, write: &FlagWrite) -> Result<(), BridgeError> {
        let FlagWrite {
            mailbox,
            id,
            flag,
            add,
        } = *write;
        match self.session.kind {
            MailKind::Jmap(_) => {
                let url = self.session.jmap_url()?;
                Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                }
                .set_keyword(id, flag, add)
            }
            // NOTE: Graph and Gmail keep no answered or deleted marker, so
            // clearing one is done; only setting one is refused. A placement
            // pushed with no agreed set states all four, those two cleared.
            MailKind::Graph(_) | MailKind::Gmail(_)
                if !add && matches!(flag, "\\Answered" | "\\Deleted") =>
            {
                Ok(())
            }
            MailKind::Graph(_) => Msgraph {
                client: self.client,
                token: self.session.auth().secret(),
            }
            .set_flag(id, flag, add),
            MailKind::Gmail(_) => {
                Gmail {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .set_flag(id, flag, add)?;
                // NOTE: dropped rather than patched, so a later read in the
                // same run asks Gmail what the change left.
                self.session.gmail_run().forget(id);
                Ok(())
            }
            MailKind::Imap(_) => self
                .session
                .imap(self.client)?
                .store_flag(mailbox, id, flag, add),
        }
    }

    /// Relocates one message.
    pub fn relocate(&mut self, transfer: &MessageTransfer) -> Result<(), BridgeError> {
        let MessageTransfer {
            mailbox,
            id,
            target,
        } = *transfer;
        match self.session.kind {
            MailKind::Imap(_) => self
                .session
                .imap(self.client)?
                .relocate(mailbox, id, target),
            MailKind::Jmap(_) => {
                let (from, to) = self.mailbox_ids(mailbox, target)?;
                let url = self.session.jmap_url()?;
                Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                }
                .relocate_message(id, &from, &to)
            }
            MailKind::Graph(_) => {
                let (_, to) = self.mailbox_ids(mailbox, target)?;
                Msgraph {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .relocate_message(id, &to)
            }
            MailKind::Gmail(_) => {
                let (from, to) = self.mailbox_ids(mailbox, target)?;
                Gmail {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .relocate_message(id, &from, &to)?;
                // NOTE: dropped rather than patched, so a later read in the
                // same run asks Gmail what the change left.
                self.session.gmail_run().forget(id);
                Ok(())
            }
        }
    }

    /// Copies one message.
    pub fn copy(&mut self, transfer: &MessageTransfer) -> Result<(), BridgeError> {
        let MessageTransfer {
            mailbox,
            id,
            target,
        } = *transfer;
        match self.session.kind {
            MailKind::Imap(_) => self.session.imap(self.client)?.copy(mailbox, id, target),
            MailKind::Jmap(_) => {
                let to = self.mailbox_id(target)?;
                let url = self.session.jmap_url()?;
                Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                }
                .copy_message(id, &to)
            }
            MailKind::Graph(_) => {
                let to = self.mailbox_id(target)?;
                Msgraph {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .copy_message(id, &to)
            }
            MailKind::Gmail(_) => {
                let to = self.mailbox_id(target)?;
                Gmail {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .copy_message(id, &to)?;
                self.session.gmail_run().forget(id);
                Ok(())
            }
        }
    }

    /// Deletes one message for good.
    pub fn destroy(&mut self, mailbox: &str, id: &str) -> Result<(), BridgeError> {
        match self.session.kind {
            MailKind::Jmap(_) => {
                let url = self.session.jmap_url()?;
                Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                }
                .destroy_message(id)
            }
            MailKind::Graph(_) => Msgraph {
                client: self.client,
                token: self.session.auth().secret(),
            }
            .destroy_message(id),
            MailKind::Gmail(_) => {
                Gmail {
                    client: self.client,
                    token: self.session.auth().secret(),
                }
                .destroy_message(id)?;
                self.session.gmail_run().forget(id);
                Ok(())
            }
            MailKind::Imap(_) => self.session.imap(self.client)?.destroy(mailbox, id),
        }
    }

    /// Appends one message; only an IMAP session files mail it was handed.
    pub fn append(&mut self, append: MessageAppend) -> Result<(), BridgeError> {
        match self.session.kind {
            MailKind::Imap(_) => {
                self.session
                    .imap(self.client)?
                    .append(append.mailbox, append.raw, append.flags)
            }
            MailKind::Jmap(_) | MailKind::Graph(_) | MailKind::Gmail(_) => {
                Err("Only an IMAP session appends a message".into())
            }
        }
    }

    /// Submits one stored message, over SMTP or the session's own API.
    ///
    /// Answers the `Message-ID` the provider filed its sent copy under when
    /// it stamped its own. The sent copy is a create staged when queued,
    /// landed by the provider's filing or pushed as an IMAP append.
    pub fn submit(&mut self, raw: &[u8]) -> Result<Option<String>, Refused> {
        match self.session.kind {
            MailKind::Jmap(_) => {
                // NOTE: the server files the copy in Sent once it accepts
                // the message.
                let url = self.session.jmap_url().map_err(Refused::transient)?;
                let mut jmap = Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                };
                if let Err(err) = jmap.send_message(raw) {
                    let unanswered = jmap.client.unanswered();
                    return Err(Refused::http(err, unanswered, |status| {
                        matches!(status, 413 | REFUSED)
                    }));
                }
                Ok(None)
            }
            // NOTE: Graph and Gmail submit through the session they read
            // from and file the sent copy themselves.
            MailKind::Graph(_) => {
                let mut graph = Msgraph {
                    client: self.client,
                    token: self.session.auth().secret(),
                };
                if let Err(err) = graph.send_message(raw) {
                    let unanswered = graph.client.unanswered();
                    return Err(Refused::http(err, unanswered, refused_by_api));
                }
                Ok(None)
            }
            MailKind::Gmail(_) => {
                // NOTE: Gmail replaces the Message-ID of what it sends, so
                // the sent copy is read back for it. The message has left
                // either way: a failed read only leaves the staged copy
                // unmatched.
                let mut gmail = Gmail {
                    client: self.client,
                    token: self.session.auth().secret(),
                };
                let id = match gmail.send_message(raw) {
                    Ok(id) => id,
                    Err(err) => {
                        let unanswered = gmail.client.unanswered();
                        return Err(Refused::http(err, unanswered, refused_by_api));
                    }
                };
                let filed = match gmail.envelope(&id) {
                    Ok(envelope) => envelope.and_then(|envelope| envelope.summary.message_id),
                    Err(err) => {
                        log::warn!("sent copy {id} unread: {err}");
                        None
                    }
                };
                Ok(filed)
            }
            MailKind::Imap(_) => {
                let submit_url = &self.session.account.submit_url;
                if submit_url.is_empty() {
                    return Err(Refused::transient(
                        "This account has no server to send through",
                    ));
                }

                let composed = compose::envelope(raw).map_err(Refused::transient)?;
                let submit = parse_url(submit_url).map_err(Refused::transient)?;
                smtp::send(self.client, &submit, &self.session.auth(), &composed).map_err(
                    |err| Refused {
                        permanent: err.is_permanent(),
                        uncertain: err.is_uncertain(),
                        message: err.to_string(),
                    },
                )?;
                Ok(None)
            }
        }
    }

    /// The roster of an HTTP session, each mailbox with the id it is
    /// addressed by.
    fn http_roster(&mut self) -> Result<Vec<(String, Mailbox)>, BridgeError> {
        match self.session.kind {
            MailKind::Jmap(_) => {
                let url = self.session.jmap_url()?;
                Jmap {
                    client: self.client,
                    session_url: url,
                    auth: self.session.auth(),
                }
                .list_mailbox_roster()
            }
            MailKind::Graph(_) => Msgraph {
                client: self.client,
                token: self.session.auth().secret(),
            }
            .list_mailboxes(),
            MailKind::Gmail(_) => Gmail {
                client: self.client,
                token: self.session.auth().secret(),
            }
            .list_mailboxes(),
            MailKind::Imap(_) => Err("An IMAP session has no HTTP roster".into()),
        }
    }

    /// The id an HTTP session addresses a mailbox by, the roster read on
    /// demand ([`MailListing::resolve`]).
    fn mailbox_id(&mut self, mailbox: &str) -> Result<String, BridgeError> {
        // NOTE: the listing is lifted out while the roster is read, which
        // needs the session's credentials, and put back whatever came of it.
        let mut listing = mem::take(self.session.listing());
        let id = listing.resolve(mailbox, || self.http_roster());
        *self.session.listing() = listing;
        id
    }

    /// The ids of the mailbox a message leaves and the one it goes to,
    /// in that order ([`Self::mailbox_id`]).
    fn mailbox_ids(&mut self, from: &str, to: &str) -> Result<(String, String), BridgeError> {
        Ok((self.mailbox_id(from)?, self.mailbox_id(to)?))
    }
}

/// Whether Graph or Gmail refused a submission for good, by its status.
fn refused_by_api(status: u16) -> bool {
    matches!(status, 400 | 403 | 404 | 413 | 422)
}

/// One submission that did not happen, and whether it ever could.
///
/// The queue reads this: a message refused is parked with what was said,
/// one that may have gone is parked for the user to send again or not,
/// anything else stays queued for the next drain.
pub struct Refused {
    /// What went wrong, as the user reads it.
    pub message: String,
    /// The server refused it for good.
    pub permanent: bool,
    /// The whole message went over and no verdict came back.
    pub uncertain: bool,
}

impl Refused {
    /// A failure that says nothing about the message, which keeps its place
    /// in the queue.
    pub fn transient(err: impl ToString) -> Self {
        Self {
            message: err.to_string(),
            permanent: false,
            uncertain: false,
        }
    }

    /// A failed HTTP send: refused for good when `refused` says so of its
    /// status; uncertain when it went whole and no answer came
    /// (`unanswered`) or a server error did, which a gateway may give after
    /// the work was done; else transient.
    fn http(err: BridgeError, unanswered: bool, refused: fn(u16) -> bool) -> Self {
        let permanent = err.status.is_some_and(refused);
        let uncertain = !permanent && uncertain(err.status, unanswered);
        Self {
            message: err.to_string(),
            permanent,
            uncertain,
        }
    }
}

/// Whether an HTTP send that failed with `status` (none when no answer
/// was read) may have been carried out.
fn uncertain(status: Option<u16>, unanswered: bool) -> bool {
    match status {
        None => unanswered,
        Some(status) => matches!(status, 500 | 502 | 503 | 504),
    }
}

#[cfg(test)]
mod tests {
    use crate::mail::{Mailbox, session::*};

    /// A send with no answer, or a server error a gateway may give after
    /// the work, may have gone; a refusal before the work did not.
    #[test]
    fn a_send_is_uncertain_without_a_verdict() {
        assert!(uncertain(None, true), "written whole, never answered");
        assert!(!uncertain(None, false), "failed before it was written");
        assert!(uncertain(Some(503), false));
        assert!(uncertain(Some(504), false));
        assert!(!uncertain(Some(429), false), "throttled before any work");
        assert!(!uncertain(Some(401), true));
    }

    fn roster(names: &[&str]) -> Vec<(String, Mailbox)> {
        names
            .iter()
            .map(|name| {
                (
                    format!("id-{name}"),
                    Mailbox {
                        name: name.to_string(),
                        role: String::new(),
                    },
                )
            })
            .collect()
    }

    /// A session that never listed the mailboxes (one widening a mailbox, a
    /// fill worker's) reads the roster rather than refuse a mailbox there.
    #[test]
    fn a_fresh_session_reads_the_roster_on_its_first_lookup() {
        let mut listing = MailListing::default();
        let mut listed = 0;
        let id = listing.resolve("Archive", || {
            listed += 1;
            Ok(roster(&["Inbox", "Archive"]))
        });
        assert_eq!(id.unwrap(), "id-Archive");
        assert_eq!(listed, 1);

        let id = listing.resolve("Inbox", || {
            listed += 1;
            Ok(roster(&["Inbox", "Archive"]))
        });
        assert_eq!(id.unwrap(), "id-Inbox");
        assert_eq!(listed, 1, "the roster read once serves every mailbox");
    }

    #[test]
    fn a_mailbox_the_roster_does_not_hold_is_refused() {
        let mut listing = MailListing::default();
        listing.remember(roster(&["Inbox"]));
        let refused = listing.resolve("Gone", || Ok(roster(&["Inbox"])));
        assert_eq!(refused.unwrap_err().message, "No mailbox named `Gone`");
    }

    #[test]
    fn a_mailbox_made_since_the_roster_is_found_by_reading_it_again() {
        let mut listing = MailListing::default();
        listing.remember(roster(&["Inbox"]));
        let id = listing.resolve("Projets", || Ok(roster(&["Inbox", "Projets"])));
        assert_eq!(id.unwrap(), "id-Projets");
    }

    #[test]
    fn a_roster_that_fails_fails_the_lookup() {
        let mut listing = MailListing::default();
        let failed = listing.resolve("Inbox", || Err("offline".into()));
        assert_eq!(failed.unwrap_err().message, "offline");
        assert!(listing.ids.is_empty());
    }
}
