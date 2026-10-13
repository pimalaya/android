//! # JMAP mail
//!
//! The mail verbs of the [`Jmap`] backend: the RFC 8621 mailbox roster,
//! listing pages, keyword and mailbox writes, and the section 7 submission.
//!
//! The session and [`JmapCalls`] are [`crate::client::jmap`]'s; the account
//! step and the pool run its deltas share are [`crate::mail::jmap_step`]'s.

use std::{collections::BTreeMap, path::Path};

use io_jmap::{
    coroutine::{JmapCoroutine, JmapCoroutineState, JmapYield},
    rfc8620::{
        blob_download::JmapBlobDownload, blob_upload::JmapBlobUpload, changes::*,
        error::JmapMethodError, filter::JmapFilter, session::JmapSession,
    },
    rfc8621::{
        JMAP_MAIL_CAPABILITY,
        email::{
            JMAP_KEYWORD_ANSWERED, JMAP_KEYWORD_DRAFT, JMAP_KEYWORD_FLAGGED, JMAP_KEYWORD_SEEN,
            JmapEmail, JmapEmailAddress, JmapEmailProperty,
            changes::{JmapEmailChanges, JmapEmailChangesError, JmapEmailChangesOptions},
            get::*,
            import::*,
            query::*,
            set::*,
        },
        email_submission::{
            JMAP_SUBMISSION_CAPABILITY, JmapEmailAddressWithParameters,
            JmapEmailSubmissionSetItemError, JmapEnvelope, set::*,
        },
        identity::{JmapIdentity, get::*},
        mailbox::{JmapMailbox, JmapMailboxRole, get::*},
    },
};
use io_pimdir::summary::{
    PimdirAddress,
    mail::{PimdirMailSummary, decode},
};
use secrecy::SecretString;
use url::Url;

use crate::{
    client::{
        Host,
        jmap::{Jmap, JmapCalls, max_objects_in_get, page_size, refused, sessions},
        trust::CrossOrigin,
    },
    error::{BridgeError, converged, coroutine_error},
    mail::{
        Mailbox,
        compose::{self, Composed},
        jmap_step::{
            JmapDelta, JmapRun, JmapStepped, Sources, Start, StepItem, StepQuery, StepRequest,
            received,
        },
        listing::{
            Floor, Listing, MailPage, MailRequest, Named, RECEIVED_MARGIN_DAYS, Scope, flags, utc,
        },
    },
};

/// The `Email` properties a listing names a member by.
///
/// Annex A's summary, the markers, and the mailboxes a delta checks the
/// member is still in. The date is `sentAt` (the `Date` header), never
/// `receivedAt`.
const SUMMARY_PROPERTIES: [JmapEmailProperty; 12] = [
    JmapEmailProperty::Id,
    JmapEmailProperty::MailboxIds,
    JmapEmailProperty::Keywords,
    JmapEmailProperty::Size,
    JmapEmailProperty::MessageId,
    JmapEmailProperty::InReplyTo,
    JmapEmailProperty::From,
    JmapEmailProperty::To,
    JmapEmailProperty::Cc,
    JmapEmailProperty::Subject,
    JmapEmailProperty::SentAt,
    JmapEmailProperty::HasAttachment,
];

/// JMAP mail verbs: the mailbox roster, listing pages, steps and writes.
impl<H: Host> Jmap<'_, H> {
    /// The account's mailboxes, each named by its path ([`mailbox_path`]).
    pub fn list_mailbox_roster(&mut self) -> Result<Vec<(String, Mailbox)>, BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();

        Ok(self
            .list_mailboxes(&session, &auth, &api_url)?
            .into_iter()
            .map(|(id, path, role)| (id, Mailbox { name: path, role }))
            .collect())
    }

    /// One page of a mailbox's listing (pimdir SYNC §4, §5).
    ///
    /// A round pages `Email/query` by `receivedAt`, its position the cursor,
    /// the state read first the checkpoint. A delta projects ([`project`]) the
    /// pool run's one replay; one too old is refused, for a round to open.
    pub fn list_page(
        &mut self,
        run: &JmapRun,
        mailbox_id: &str,
        request: &MailRequest,
    ) -> Result<MailPage, BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();
        let scope = &request.scope;

        let (position, band) = match &request.listing {
            Listing::Delta { checkpoint } => {
                let delta = run.delta(checkpoint, || {
                    self.mail_changes(&session, &auth, &api_url, checkpoint)
                })?;
                return Ok(match delta {
                    Some(delta) => project(&delta, mailbox_id, scope),
                    None => MailPage::rejected(),
                });
            }
            Listing::Round { cursor, band } => match cursor.as_deref() {
                None => (0, *band),
                Some(cursor) => match cursor.parse::<u64>() {
                    Ok(position) => (position, *band),
                    Err(_) => return Ok(MailPage::rejected()),
                },
            },
        };

        // NOTE: before the query, so what moves while the round lists is
        // the next delta's.
        let checkpoint = match (position, band) {
            (0, false) => Some(self.email_state(&session, &auth, &api_url)?),
            _ => None,
        };

        let limit = page_size(&session);
        let filter = JmapEmailFilter {
            in_mailbox: Some(mailbox_id.to_string()),
            after: scope
                .received_since(RECEIVED_MARGIN_DAYS)
                .map(|since| since.strftime("%Y-%m-%dT%H:%M:%SZ").to_string()),
            before: scope
                .received_until(RECEIVED_MARGIN_DAYS)
                .map(|until| until.strftime("%Y-%m-%dT%H:%M:%SZ").to_string()),
            ..Default::default()
        };
        let opts = JmapEmailQueryOptions {
            filter: Some(JmapFilter::Condition(filter)),
            sort: Some(vec![JmapEmailComparator::received_at_desc()]),
            position: Some(position),
            limit: Some(limit),
            properties: Some(SUMMARY_PROPERTIES.to_vec()),
            ..Default::default()
        };
        let coroutine =
            JmapEmailQuery::new(&session, &auth, opts).map_err(|err| err.to_string())?;
        let out = self.run(&api_url, coroutine)?;

        let listed = out.emails.len() as u64;
        let reached = out.position + listed;
        let more = listed > 0 && out.total.is_none_or(|total| reached < total);
        let items = out
            .emails
            .into_iter()
            .filter_map(|email| named(email, scope))
            .collect();

        Ok(MailPage::round(
            items,
            more.then(|| reached.to_string()),
            checkpoint,
        ))
    }

    /// Takes a mailbox's newest emails below the floor's ceiling until its
    /// chunk is full.
    ///
    /// `Email/query` by `receivedAt`, narrowed two days above the ceiling,
    /// each email's `sentAt` (the `Date` header) taken a page at a time.
    pub fn floor(&mut self, mailbox_id: &str, floor: &mut Floor) -> Result<(), BridgeError> {
        let auth = self.auth.authorization();
        let session = self.session()?;
        let api_url = session.api_url.clone();
        let limit = (floor.count() as u64)
            .min(max_objects_in_get(&session))
            .max(1);
        let mut position = 0;

        loop {
            let filter = JmapEmailFilter {
                in_mailbox: Some(mailbox_id.to_string()),
                before: floor
                    .scope()
                    .received_until(RECEIVED_MARGIN_DAYS)
                    .map(|until| until.strftime("%Y-%m-%dT%H:%M:%SZ").to_string()),
                ..Default::default()
            };
            let opts = JmapEmailQueryOptions {
                filter: Some(JmapFilter::Condition(filter)),
                sort: Some(vec![JmapEmailComparator::received_at_desc()]),
                position: Some(position),
                limit: Some(limit),
                properties: Some(vec![JmapEmailProperty::Id, JmapEmailProperty::SentAt]),
                ..Default::default()
            };
            let coroutine =
                JmapEmailQuery::new(&session, &auth, opts).map_err(|err| err.to_string())?;
            let out = self.run(&api_url, coroutine)?;

            let listed = out.emails.len() as u64;
            for email in &out.emails {
                let date = email.sent_at.as_deref().and_then(utc);
                if floor.take(date.as_deref()) {
                    return Ok(());
                }
            }
            position = out.position + listed;
            if listed == 0 || out.total.is_some_and(|total| position >= total) {
                return Ok(());
            }
        }
    }

    /// The account's `Email` state, what a delta lists from.
    fn email_state(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
    ) -> Result<String, BridgeError> {
        let opts = JmapEmailGetOptions {
            properties: Some(vec![JmapEmailProperty::Id]),
            ..Default::default()
        };
        let coroutine =
            JmapEmailGet::new(session, auth, Vec::new(), opts).map_err(|err| err.to_string())?;
        Ok(self.run(api_url, coroutine)?.new_state)
    }

    /// What changed in the account since `state`, [`None`] when the server
    /// can no longer compute changes from it.
    ///
    /// Every `Email/changes` page, then the created and updated emails read
    /// with the summary properties, a page at a time.
    fn mail_changes(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
        state: &str,
    ) -> Result<Option<JmapDelta>, BridgeError> {
        let mut touched = Vec::new();
        let mut gone = Vec::new();
        let mut since = state.to_string();

        loop {
            let coroutine = JmapEmailChanges::new(
                session,
                auth,
                since.clone(),
                JmapEmailChangesOptions::default(),
            )
            .map_err(|err| err.to_string())?;
            let Some(out) = self.run_email_changes(api_url, coroutine)? else {
                return Ok(None);
            };
            touched.extend(out.created);
            touched.extend(out.updated);
            gone.extend(out.destroyed);
            since = out.new_state;
            if !out.has_more_changes {
                break;
            }
        }
        touched.sort();
        touched.dedup();

        let mut emails = Vec::new();
        let chunk = page_size(session).max(1) as usize;
        for ids in touched.chunks(chunk) {
            let opts = JmapEmailGetOptions {
                properties: Some(SUMMARY_PROPERTIES.to_vec()),
                ..Default::default()
            };
            let coroutine = JmapEmailGet::new(session, auth, ids.to_vec(), opts)
                .map_err(|err| err.to_string())?;
            let out = self.run(api_url, coroutine)?;
            gone.extend(out.not_found);
            emails.extend(out.emails);
        }

        Ok(Some(JmapDelta {
            next: since,
            emails,
            gone,
        }))
    }

    /// Drives one `Email/changes`, surfacing a state the server can no
    /// longer compute changes from as [`None`].
    fn run_email_changes(
        &mut self,
        api_url: &Url,
        mut coroutine: JmapEmailChanges,
    ) -> Result<Option<JmapChangesOutput>, BridgeError> {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                JmapCoroutineState::Complete(Ok(out)) => return Ok(Some(out)),
                JmapCoroutineState::Complete(Err(JmapEmailChangesError::Changes(
                    JmapChangesError::Method(JmapMethodError::CannotCalculateChanges { .. }),
                ))) => return Ok(None),
                JmapCoroutineState::Complete(Err(err)) => {
                    sessions().forget(api_url);
                    return Err(coroutine_error(&err));
                }
                JmapCoroutineState::Yielded(JmapYield::WantsRead) => {
                    arg = Some(self.client.http_read(api_url.as_str())?);
                }
                JmapCoroutineState::Yielded(JmapYield::WantsWrite(bytes)) => {
                    self.client.http_write(api_url.as_str(), &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// The account's mailboxes as `(id, path, role)` triples.
    ///
    /// The path ([`mailbox_path`]), as the store keys a collection by name and
    /// JMAP lets mailboxes under different parents share one. The role, RFC
    /// 6154's counterpart, tells a delete which mailbox it moves into.
    fn list_mailboxes(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
    ) -> Result<Vec<(String, String, String)>, BridgeError> {
        let opts = JmapMailboxGetOptions::default();
        let coroutine = JmapMailboxGet::new(session, auth, opts).map_err(|err| err.to_string())?;
        let out = self.run(api_url, coroutine)?;

        let named: BTreeMap<String, JmapMailbox> = out
            .mailboxes
            .into_iter()
            .filter_map(|mailbox| mailbox.id.clone().map(|id| (id, mailbox)))
            .collect();

        Ok(named
            .iter()
            .map(|(id, mailbox)| {
                // NOTE: RFC 8621's roles are pimdir's vocabulary already.
                let role = match mailbox.role {
                    Some(JmapMailboxRole::Inbox) => "inbox",
                    Some(JmapMailboxRole::Sent) => "sent",
                    Some(JmapMailboxRole::Drafts) => "drafts",
                    Some(JmapMailboxRole::Junk) => "junk",
                    Some(JmapMailboxRole::Trash) => "trash",
                    Some(JmapMailboxRole::Archive) => "archive",
                    _ => "",
                };
                (id.clone(), mailbox_path(&named, mailbox), role.into())
            })
            .collect())
    }

    /// One step of the account ([`JmapCalls::step`]).
    ///
    /// Sorted by `sentAt` where the mail capability lists it among its
    /// `emailQuerySortOptions`, by `receivedAt` otherwise.
    pub fn step(
        &mut self,
        mut request: StepRequest<'_>,
        dir: &Path,
    ) -> Result<JmapStepped, BridgeError> {
        let mut calls = self.calls()?;
        let account = calls.session.primary_account_id_for(JMAP_MAIL_CAPABILITY);
        request.sent_at = calls
            .session
            .mail_capability(&account)
            .email_query_sort_options
            .iter()
            .any(|sort| sort == "sentAt");
        let stepped = calls.step(&request, dir)?;

        let mut items = Vec::with_capacity(stepped.brought.len());
        for brought in stepped.brought {
            let mailboxes = brought
                .email
                .mailbox_ids
                .iter()
                .flatten()
                .filter(|(_, filed)| **filed)
                .map(|(id, _)| id.clone())
                .collect();
            let received = received(&brought.email);
            let Some(named) = named(brought.email, &Scope::default()) else {
                continue;
            };
            items.push(StepItem {
                named,
                mailboxes,
                path: brought.path,
                size: brought.size,
                received,
            });
        }
        Ok(JmapStepped {
            state: stepped.state,
            items,
            exhausted: stepped.exhausted,
            anchors: stepped.anchors,
            by_reception: stepped.by_reception,
        })
    }

    /// Some messages read whole into files of `dir` ([`JmapCalls::sources`]).
    ///
    /// Their blobs are named by one `Email/get` per `maxObjectsInGet` ids,
    /// never one per message.
    pub fn sources(&mut self, ids: &[String], dir: &Path) -> Result<Sources, BridgeError> {
        let mut calls = self.calls()?;
        let chunk = max_objects_in_get(&calls.session) as usize;
        calls.sources(ids, chunk, dir)
    }

    /// Adds or removes one keyword on one message.
    ///
    /// The IMAP marker crosses as the keyword RFC 8621 section 4.1.1
    /// maps it to, so the caller says `\Seen` whichever backend answers.
    pub fn set_keyword(&mut self, id: &str, flag: &str, add: bool) -> Result<(), BridgeError> {
        let keyword = keyword_of(flag)?;
        let mut calls = self.calls()?;

        let mut args = JmapEmailSetArgs::default();
        if add {
            args.set_keyword(id, keyword);
        } else {
            args.unset_keyword(id, keyword);
        }

        converged(calls.run_email_set(args, id))?;
        Ok(())
    }

    /// Relocates one message from the mailbox `from` to the mailbox `to`
    /// (mailbox ids), as [`relocation_args`] says.
    pub fn relocate_message(&mut self, id: &str, from: &str, to: &str) -> Result<(), BridgeError> {
        let args = relocation_args(id, from, to);
        converged(self.write_message(id, args))?;
        Ok(())
    }

    /// Files one message in the mailbox `to` (an id) too; a message gone
    /// fails with 404, for the caller to refuse.
    pub fn copy_message(&mut self, id: &str, to: &str) -> Result<(), BridgeError> {
        let args = copy_args(id, to);
        self.write_message(id, args)
    }

    /// Destroys one message for good.
    pub fn destroy_message(&mut self, id: &str) -> Result<(), BridgeError> {
        let args = destroy_args(id);
        converged(self.write_message(id, args))?;
        Ok(())
    }

    /// Runs one message's `Email/set`.
    fn write_message(&mut self, id: &str, args: JmapEmailSetArgs) -> Result<(), BridgeError> {
        self.calls()?.run_email_set(args, id)
    }
}

/// JMAP writes: one `Email/set` over the calls of a verb.
impl<H: Host> JmapCalls<'_, '_, H> {
    /// Runs one `Email/set`, its per-object refusal reported as an error.
    ///
    /// The method succeeds while refusing the one change it was given, so a
    /// bare `Ok` would claim a write nobody made. A message gone fails with
    /// 404: a removal, move or keyword converges ([`converged`]), a copy not.
    fn run_email_set(&mut self, args: JmapEmailSetArgs, id: &str) -> Result<(), BridgeError> {
        let coroutine =
            JmapEmailSet::new(&self.session, &self.auth, args).map_err(|err| err.to_string())?;
        let out = self.jmap.run(&self.session.api_url, coroutine)?;

        email_set_refusal(id, out.not_updated.get(id).or(out.not_destroyed.get(id)))
    }
}

/// One message's `Email/set` refusal as an error, if any: a message the
/// server no longer finds as the 404 Gmail and Graph answer ([`converged`]).
fn email_set_refusal(id: &str, refusal: Option<&JmapEmailSetItemError>) -> Result<(), BridgeError> {
    match refusal {
        None => Ok(()),
        Some(JmapEmailSetItemError::NotFound { .. }) => Err(BridgeError {
            message: format!("No message {id} on the server"),
            status: Some(404),
        }),
        Some(refused) => Err(format!("The server refused the change: {refused}").into()),
    }
}

/// The `Email/set` trading one message's membership of `from` for `to`,
/// any other mailbox holding it left alone (RFC 8621 section 4.1.1).
fn relocation_args(id: &str, from: &str, to: &str) -> JmapEmailSetArgs {
    let mut args = JmapEmailSetArgs::default();
    args.add_to_mailbox(id, to).remove_from_mailbox(id, from);
    args
}

/// The `Email/set` filing one message in the mailbox `to` too: a copy is a
/// membership, one object with one id in both.
fn copy_args(id: &str, to: &str) -> JmapEmailSetArgs {
    let mut args = JmapEmailSetArgs::default();
    args.add_to_mailbox(id, to);
    args
}

/// The `Email/set` destroying one message for good.
fn destroy_args(id: &str) -> JmapEmailSetArgs {
    let mut args = JmapEmailSetArgs::default();
    args.destroy(id);
    args
}

/// The JMAP keyword an IMAP marker maps to (RFC 8621 section 4.1.1).
///
/// The three the section names, and no more: `\Deleted` and `\Recent`
/// have no counterpart, JMAP expressing the first as a move and the
/// second not at all.
fn keyword_of(flag: &str) -> Result<&'static str, BridgeError> {
    match flag {
        "\\Seen" => Ok(JMAP_KEYWORD_SEEN),
        "\\Answered" => Ok(JMAP_KEYWORD_ANSWERED),
        "\\Flagged" => Ok(JMAP_KEYWORD_FLAGGED),
        other => Err(format!("No JMAP keyword for the marker `{other}`").into()),
    }
}

/// JMAP submission: the RFC 8621 section 7 send of one stored message.
impl<H: Host> Jmap<'_, H> {
    /// Sends one stored message over the session ([`JmapCalls::send`]).
    ///
    /// A session advertising no submission is refused for good before
    /// anything is written: no later pass changes what the server offers.
    pub fn send_message(&mut self, raw: &[u8]) -> Result<(), BridgeError> {
        let mut calls = self.calls()?;
        if !calls
            .session
            .capabilities
            .contains_key(JMAP_SUBMISSION_CAPABILITY)
        {
            return Err(refused("This server does not offer sending over JMAP"));
        }
        let max_upload = calls.session.core_capability().max_size_upload;

        calls.send(raw, max_upload)
    }
}

/// The `Email` properties a step reads: the summary's, the reception, and
/// the blob the source is downloaded from.
const STEP_PROPERTIES: [JmapEmailProperty; 14] = [
    JmapEmailProperty::Id,
    JmapEmailProperty::MailboxIds,
    JmapEmailProperty::Keywords,
    JmapEmailProperty::Size,
    JmapEmailProperty::MessageId,
    JmapEmailProperty::InReplyTo,
    JmapEmailProperty::From,
    JmapEmailProperty::To,
    JmapEmailProperty::Cc,
    JmapEmailProperty::Subject,
    JmapEmailProperty::SentAt,
    JmapEmailProperty::HasAttachment,
    JmapEmailProperty::ReceivedAt,
    JmapEmailProperty::BlobId,
];

/// JMAP step reads: what a step and a read of sources ask of the server.
impl<H: Host> JmapCalls<'_, '_, H> {
    /// The account's `Email` state, the checkpoint a step lands with.
    pub fn email_state(&mut self) -> Result<String, BridgeError> {
        let opts = JmapEmailGetOptions {
            properties: Some(vec![JmapEmailProperty::Id]),
            ..Default::default()
        };
        let coroutine = JmapEmailGet::new(&self.session, &self.auth, Vec::new(), opts)
            .map_err(|err| err.to_string())?;
        Ok(self.jmap.run(&self.session.api_url, coroutine)?.new_state)
    }

    /// One step query's emails, as the server sorted them; [`None`] when
    /// its anchor is not in the results (`anchorNotFound`).
    pub fn step_query(&mut self, query: &StepQuery) -> Result<Option<Vec<JmapEmail>>, BridgeError> {
        let sort = match query.received {
            true => JmapEmailComparator::received_at_desc(),
            false => JmapEmailComparator::sent_at_desc(),
        };
        let (position, anchor) = match &query.start {
            Start::Top => (None, None),
            Start::After(id) => (None, Some(id.clone())),
            Start::At(position) => (Some(*position), None),
        };
        let opts = JmapEmailQueryOptions {
            filter: Some(JmapFilter::Condition(query.filter.clone())),
            sort: Some(vec![sort]),
            position,
            anchor_offset: anchor.as_ref().map(|_| 1),
            anchor,
            limit: Some(query.limit),
            calculate_total: false,
            collapse_threads: false,
            properties: Some(STEP_PROPERTIES.to_vec()),
        };
        let coroutine =
            JmapEmailQuery::new(&self.session, &self.auth, opts).map_err(|err| err.to_string())?;
        let api_url = self.session.api_url.clone();
        match self.jmap.try_run(&api_url, coroutine)? {
            Ok(out) => Ok(Some(out.emails)),
            Err(JmapEmailQueryError::QueryMethod(JmapMethodError::AnchorNotFound { .. })) => {
                Ok(None)
            }
            Err(err) => {
                sessions().forget(&api_url);
                Err(coroutine_error(&err))
            }
        }
    }

    /// Some emails by id, with their `blobId`; one gone left out.
    pub fn email_blobs(&mut self, ids: Vec<String>) -> Result<Vec<JmapEmail>, BridgeError> {
        let opts = JmapEmailGetOptions {
            properties: Some(vec![JmapEmailProperty::Id, JmapEmailProperty::BlobId]),
            ..Default::default()
        };
        let coroutine = JmapEmailGet::new(&self.session, &self.auth, ids, opts)
            .map_err(|err| err.to_string())?;
        Ok(self.jmap.run(&self.session.api_url, coroutine)?.emails)
    }

    /// One message's blob, downloaded from the session's download URL.
    pub fn download_blob(&mut self, blob: &str) -> Result<Vec<u8>, BridgeError> {
        let url = download_url(&self.session, blob)?;
        let auth = &self.auth;
        let out = self
            .jmap
            .run_redirect(&url, CrossOrigin::Anonymous, |target| {
                JmapBlobDownload::new(auth, target)
            })?;
        Ok(out.data)
    }
}

/// JMAP submission requests (RFC 8621 sections 4.8, 6.1, 7.5, RFC 8620 6.1).
impl<H: Host> JmapCalls<'_, '_, H> {
    /// Sends one stored message over the session (RFC 8621 section 7).
    ///
    /// Imported into Drafts without `Bcc` ([`compose::envelope`]), submitted as
    /// [`identity_for`] to an envelope also naming the blind recipients, moved
    /// to Sent. What no later pass changes is refused for good ([`refused`]).
    fn send(&mut self, raw: &[u8], max_upload: Option<u64>) -> Result<(), BridgeError> {
        let Composed {
            message,
            sender,
            recipients,
        } = compose::envelope(raw).map_err(|err| refused(err.message))?;

        let identities = self.identities()?;
        let identity = identity_for(&identities, &sender)
            .ok_or_else(|| refused(format!("No identity of this account sends as {sender}")))?;

        let mailboxes =
            self.jmap
                .list_mailboxes(&self.session, &self.auth, &self.session.api_url)?;
        let role = |wanted: &str| {
            mailboxes
                .iter()
                .find(|(_, _, role)| role == wanted)
                .map(|(id, _, _)| id.clone())
        };
        let drafts = role("drafts")
            .ok_or_else(|| refused("This account has no Drafts mailbox to send from"))?;
        let sent = role("sent");

        if let Some(max) = max_upload.filter(|max| message.len() as u64 > *max) {
            return Err(refused(format!(
                "The message is larger than the {max} bytes the server takes"
            )));
        }

        let blob = self.upload_message(message)?;
        let imported = self.import_draft(&blob, &drafts)?;
        if let Some(err) = imported.not_created.into_values().next() {
            let message = format!("The server refused the message: {err}");
            return Err(match err {
                JmapEmailImportItemError::Unknown => message.into(),
                _ => refused(message),
            });
        }
        let email = imported
            .created
            .into_values()
            .next()
            .and_then(|email| email.id)
            .ok_or("The server imported the message under no id")?;

        let address = |email: &str| JmapEmailAddressWithParameters {
            email: email.to_string(),
            parameters: None,
        };
        let create = JmapEmailSubmissionCreate {
            identity_id: identity.id.clone(),
            email_id: email.clone(),
            envelope: Some(JmapEnvelope {
                mail_from: address(&sender),
                rcpt_to: recipients.iter().map(|to| address(to)).collect(),
            }),
        };
        let filed = match &sent {
            Some(sent) => JmapEmailPatch::default()
                .remove_from_mailbox(&drafts)
                .add_to_mailbox(sent)
                .unset_keyword(JMAP_KEYWORD_DRAFT),
            None => JmapEmailPatch::default(),
        };
        let args = JmapEmailSubmissionSetArgs {
            create: BTreeMap::from([("s0".to_string(), create)]),
            on_success_update_email: sent
                .is_some()
                .then(|| BTreeMap::from([("#s0".to_string(), filed)])),
            // NOTE: with no Sent, a sent message is destroyed, as on IMAP.
            on_success_destroy_email: sent.is_none().then(|| vec!["#s0".to_string()]),
        };

        // NOTE: a submission never answered keeps its draft: the server may
        // have sent and moved it already.
        let out = self.submit(args)?;
        if out.created.contains_key("s0") {
            return Ok(());
        }

        // NOTE: never sent, so the draft imported for it is litter; one the
        // destroy misses costs a row in Drafts and nothing else.
        if let Err(err) = self.destroy_draft(&email) {
            log::warn!("draft {email} left behind: {err}");
        }
        let Some(err) = out.not_created.into_values().next() else {
            return Err("The server answered the submission with nothing".into());
        };
        let message = format!("The server refused to send the message: {err}");
        Err(match err {
            JmapEmailSubmissionSetItemError::RateLimit { .. }
            | JmapEmailSubmissionSetItemError::NotFound { .. }
            | JmapEmailSubmissionSetItemError::Unknown => message.into(),
            _ => refused(message),
        })
    }

    /// The identities the account may send as.
    fn identities(&mut self) -> Result<Vec<JmapIdentity>, BridgeError> {
        let opts = JmapIdentityGetOptions::default();
        let coroutine =
            JmapIdentityGet::new(&self.session, &self.auth, opts).map_err(|err| err.to_string())?;
        Ok(self.jmap.run(&self.session.api_url, coroutine)?.identities)
    }

    /// Uploads one message, answering its blob id.
    fn upload_message(&mut self, message: Vec<u8>) -> Result<String, BridgeError> {
        let account = self.session.primary_account_id_for(JMAP_MAIL_CAPABILITY);
        let url = self
            .session
            .resolve_upload_url(&account)
            .map_err(|err| format!("Invalid upload URL: {err}"))?;
        let auth = &self.auth;
        let out = self
            .jmap
            .run_redirect(&url, CrossOrigin::Refuse, |target| {
                JmapBlobUpload::new(auth, target, "message/rfc822", message.clone())
            })?;
        Ok(out.blob_id)
    }

    /// Imports the blob into the mailbox `drafts` as a draft, seen.
    fn import_draft(
        &mut self,
        blob_id: &str,
        drafts: &str,
    ) -> Result<JmapEmailImportOutput, BridgeError> {
        let import = JmapEmailImportArgs {
            blob_id: blob_id.to_string(),
            mailbox_ids: BTreeMap::from([(drafts.to_string(), true)]),
            keywords: Some(BTreeMap::from([
                (JMAP_KEYWORD_DRAFT.to_string(), true),
                (JMAP_KEYWORD_SEEN.to_string(), true),
            ])),
            received_at: None,
        };
        let emails = BTreeMap::from([("m0".to_string(), import)]);
        let coroutine = JmapEmailImport::new(&self.session, &self.auth, emails)
            .map_err(|err| err.to_string())?;
        self.jmap.run(&self.session.api_url, coroutine)
    }

    /// Runs one `EmailSubmission/set`.
    fn submit(
        &mut self,
        args: JmapEmailSubmissionSetArgs,
    ) -> Result<JmapEmailSubmissionSetOutput, BridgeError> {
        let coroutine = JmapEmailSubmissionSet::new(&self.session, &self.auth, args)
            .map_err(|err| err.to_string())?;
        self.jmap.run(&self.session.api_url, coroutine)
    }

    /// Destroys one draft; one gone already is destroyed ([`converged`]).
    fn destroy_draft(&mut self, id: &str) -> Result<(), BridgeError> {
        converged(self.run_email_set(destroy_args(id), id))?;
        Ok(())
    }
}

/// The identity a message from `sender` goes out as, compared without case.
///
/// The one with that address, else the one whose address is `*` at its
/// domain, which RFC 8621 section 6 lets send as any address there.
fn identity_for<'i>(identities: &'i [JmapIdentity], sender: &str) -> Option<&'i JmapIdentity> {
    let (_, domain) = sender.rsplit_once('@')?;
    let wildcard = format!("*@{domain}");

    identities
        .iter()
        .find(|identity| identity.email.eq_ignore_ascii_case(sender))
        .or_else(|| {
            identities
                .iter()
                .find(|identity| identity.email.eq_ignore_ascii_case(&wildcard))
        })
}

/// The path of a mailbox: its ancestors' names and its own joined by `/`,
/// the hierarchical name IMAP hands the app.
///
/// A parent the roster lacks ends the walk: a partial path still names the
/// mailbox, while dropping it for a missing ancestor would hide its mail.
fn mailbox_path(named: &BTreeMap<String, JmapMailbox>, mailbox: &JmapMailbox) -> String {
    let mut segments = vec![mailbox.name.clone().unwrap_or_default()];
    let mut parent = mailbox.parent_id.clone();

    // NOTE: bounded by the roster size, so a server answering with a
    // parent cycle cannot spin here.
    while let Some(id) = parent.take() {
        let Some(ancestor) = named.get(&id) else {
            break;
        };
        if segments.len() > named.len() {
            break;
        }
        segments.push(ancestor.name.clone().unwrap_or_default());
        parent = ancestor.parent_id.clone();
    }

    segments.reverse();
    segments.join("/")
}

/// Where one message's blob is downloaded from: the session's RFC 8620
/// section 6.2 template, for the mail account, offered as a message.
fn download_url(session: &JmapSession, blob: &str) -> Result<Url, BridgeError> {
    let account = session.primary_account_id_for(JMAP_MAIL_CAPABILITY);
    session
        .resolve_download_url(&account, blob, "message.eml", "message/rfc822")
        .map_err(|err| BridgeError::from(format!("Invalid download URL: {err}")))
}

/// One JMAP Email, named by its summary, when its `Date` falls in the scope.
///
/// Seen is a keyword here, mapped to the flag so the app keeps one notion
/// of seen.
fn named(email: JmapEmail, scope: &Scope) -> Option<Named> {
    let summary = email_summary(&email);
    if !scope.contains(summary.date.as_deref()) {
        return None;
    }
    let flags = flags(
        keyword(&email, JMAP_KEYWORD_SEEN),
        keyword(&email, JMAP_KEYWORD_ANSWERED),
        keyword(&email, JMAP_KEYWORD_FLAGGED),
    );
    Some(Named::new(email.id?, flags, summary))
}

/// One mailbox's delta page, projected from the account's changes.
///
/// An email still filed in the mailbox is a member, one moved out of it is
/// gone from it whatever its date, and what the changes destroyed is gone.
fn project(delta: &JmapDelta, mailbox_id: &str, scope: &Scope) -> MailPage {
    let mut items = Vec::new();
    let mut vanished = delta.gone.clone();
    for email in &delta.emails {
        let filed = email
            .mailbox_ids
            .as_ref()
            .is_some_and(|mailboxes| mailboxes.get(mailbox_id).copied().unwrap_or(false));
        match filed {
            true => items.extend(named(email.clone(), scope)),
            false => vanished.extend(email.id.clone()),
        }
    }
    MailPage::delta(items, vanished, delta.next.clone())
}

/// The Annex A summary of one JMAP Email, dated by `sentAt` in UTC.
fn email_summary(email: &JmapEmail) -> PimdirMailSummary {
    let list = |addresses: &Option<Vec<JmapEmailAddress>>| -> Vec<PimdirAddress> {
        addresses
            .iter()
            .flatten()
            .filter_map(|address| {
                let canonical = PimdirAddress::canonical(&address.email);
                (!canonical.is_empty()).then(|| PimdirAddress {
                    address: canonical,
                    name: Some(display_name(address)).filter(|name| !name.is_empty()),
                })
            })
            .collect()
    };
    let from = list(&email.from);
    let first = from.first().cloned();

    PimdirMailSummary {
        message_id: email
            .message_id
            .as_ref()
            .and_then(|ids| ids.first())
            .cloned(),
        in_reply_to: email.in_reply_to.clone().unwrap_or_default(),
        subject: email.subject.as_deref().map(decode).unwrap_or_default(),
        sender: first.as_ref().map(|address| address.address.clone()),
        sender_name: first.and_then(|address| address.name),
        date: email.sent_at.as_deref().and_then(utc),
        size: email.size,
        attachment: email.has_attachment,
        invitation: None,
        from,
        to: list(&email.to),
        cc: list(&email.cc),
        bcc: Vec::new(),
    }
}

/// Whether one keyword is set on an email.
fn keyword(email: &JmapEmail, keyword: &str) -> bool {
    email
        .keywords
        .as_ref()
        .and_then(|keywords| keywords.get(keyword).copied())
        .unwrap_or(false)
}

/// An email address's display name, empty when it carries none.
fn display_name(address: &JmapEmailAddress) -> String {
    match address.name.as_deref() {
        Some(name) if !name.trim().is_empty() => name.to_string(),
        _ => String::new(),
    }
}

#[cfg(test)]
mod tests;
