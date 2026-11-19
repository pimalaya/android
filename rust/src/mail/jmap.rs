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
        blob_download::JmapBlobDownload,
        blob_upload::JmapBlobUpload,
        changes::*,
        error::JmapMethodError,
        filter::JmapFilter,
        get::JmapUnparsed,
        query::{JmapQuery, JmapQueryOptions},
        send::JmapSendError,
        session::JmapSession,
    },
    rfc8621::{
        JMAP_MAIL_CAPABILITY,
        email::{
            JMAP_KEYWORD_ANSWERED, JMAP_KEYWORD_DRAFT, JMAP_KEYWORD_FLAGGED, JMAP_KEYWORD_SEEN,
            JmapEmail, JmapEmailProperty,
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
    PimdirSummary,
    mail::{PimdirMailSummary, derive_meta},
};
use secrecy::SecretString;
use url::Url;

use crate::{
    client::{
        Host,
        jmap::{Jmap, JmapCalls, max_objects_in_get, page_size, paged, refused, sessions},
        trust::CrossOrigin,
    },
    error::{BridgeError, converged, coroutine_error},
    mail::{
        Mailbox, SendFailure,
        compose::Composed,
        jmap_step::{JmapDelta, JmapRun, Sources},
        listing::{
            Floor, Listing, MailPage, MailRequest, Named, RECEIVED_MARGIN_DAYS, Scope, flags, utc,
        },
        path_label, path_segment,
    },
};

/// The `Email` properties a listing names a member by.
///
/// Annex A's summary, read off the raw header fields ([`email_summary`]),
/// the markers, and the mailboxes a delta checks the member is still in.
/// `sentAt` is the server's parse of `Date`, which a band pages by.
const SUMMARY_PROPERTIES: [JmapEmailProperty; 7] = [
    JmapEmailProperty::Id,
    JmapEmailProperty::MailboxIds,
    JmapEmailProperty::Keywords,
    JmapEmailProperty::Size,
    JmapEmailProperty::Headers,
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
            .map(|(id, path, role)| {
                let label = path_label(&path);
                (
                    id,
                    Mailbox {
                        name: path,
                        label,
                        role,
                    },
                )
            })
            .collect())
    }

    /// One page of a mailbox's listing (pimdir SYNC §4, §5).
    ///
    /// A round pages `Email/query` by `receivedAt`, its position the cursor,
    /// the state read first the checkpoint, from the scope's floor alone: a
    /// message arrives after its `Date`, so a ceiling on its arrival would
    /// miss one the band holds. A delta projects ([`project`]) the
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
        parsed(&out.unparsed, "Email/query")?;

        let listed = out.emails.len() as u64;
        let reached = out.position + listed;
        let more = listed > 0 && out.total.is_none_or(|total| reached < total);
        let items = out
            .emails
            .into_iter()
            .filter_map(|email| named(email, scope))
            .collect();

        // NOTE: `after` and `before` miss an undated message and one whose
        // `Date` runs ahead of its arrival (SYNC §4), so the last page of a
        // narrowed round hands the mailbox's every id, `Email/query`
        // filtered by the mailbox alone: a query per page of ids.
        let narrowed = scope.since.is_some() || scope.until.is_some();
        let enumerated = match narrowed && !more {
            true => self.mailbox_ids(&session, &auth, &api_url, mailbox_id, limit)?,
            false => None,
        };
        Ok(
            MailPage::round(items, more.then(|| reached.to_string()), checkpoint)
                .narrowed(narrowed, enumerated),
        )
    }

    /// Every email id a mailbox holds, `Email/query` filtered by the mailbox
    /// alone and paged by position, ids only (RFC 8620 §5.5).
    ///
    /// Read to the `total` the server states, else to an empty page
    /// ([`paged`]); [`None`] when it ends short of a stated total.
    fn mailbox_ids(
        &mut self,
        session: &JmapSession,
        auth: &SecretString,
        api_url: &Url,
        mailbox_id: &str,
        limit: u64,
    ) -> Result<Option<Vec<String>>, BridgeError> {
        let account = session.primary_account_id_for(JMAP_MAIL_CAPABILITY);
        let (ids, whole) = paged(|position| {
            let filter = JmapEmailFilter {
                in_mailbox: Some(mailbox_id.to_string()),
                ..Default::default()
            };
            let opts = JmapQueryOptions::<_, JmapEmailComparator> {
                filter: Some(JmapFilter::Condition(filter)),
                position: Some(position),
                limit: Some(limit),
                calculate_total: true,
                ..Default::default()
            };
            let coroutine = JmapQuery::new(
                account.to_string(),
                auth,
                api_url,
                "Email/query",
                vec![JMAP_MAIL_CAPABILITY.to_string()],
                opts,
            )
            .map_err(|err| err.to_string())?;
            let page = self.run(api_url, coroutine)?;
            Ok((page.ids, page.total, page.position))
        })?;

        if !whole {
            log::warn!("mailbox {mailbox_id} enumerated short of its total");
            return Ok(None);
        }
        Ok(Some(ids))
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
            parsed(&out.unparsed, "Email/query")?;

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
            parsed(&out.unparsed, "Email/get")?;
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
        parsed(&out.unparsed, "Mailbox/get")?;

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

    /// Takes one message out of the mailbox `from` (an id) alone, every
    /// other mailbox holding it kept (RFC 8621 section 4.1.1).
    pub fn detach_message(&mut self, id: &str, from: &str) -> Result<(), BridgeError> {
        let mut args = JmapEmailSetArgs::default();
        args.remove_from_mailbox(id, from);
        converged(self.write_message(id, args))?;
        Ok(())
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

/// Refuses an answer holding an object its server sent and io-jmap could
/// not read (io-jmap 0.10): left out, it would read as a message or a
/// mailbox gone, so the answer is not whole.
fn parsed(unparsed: &[JmapUnparsed], method: &str) -> Result<(), BridgeError> {
    let Some(first) = unparsed.first() else {
        return Ok(());
    };
    Err(format!(
        "JMAP {method} is not whole, objects unread: {} ({})",
        unparsed.len(),
        first.error
    )
    .into())
}

/// One message's `Email/set` refusal as an error, if any: a message the
/// server no longer finds as the 404 Gmail and Graph answer ([`converged`]).
fn email_set_refusal(id: &str, refusal: Option<&JmapEmailSetItemError>) -> Result<(), BridgeError> {
    match refusal {
        None => Ok(()),
        Some(JmapEmailSetItemError::NotFound { .. }) => Err(BridgeError {
            message: format!("No message {id} on the server"),
            status: Some(404),
            ..Default::default()
        }),
        Some(refusal) => Err(BridgeError {
            message: format!("The server refused the change: {refusal}"),
            refused: refuses_as_it_stands(refusal),
            ..Default::default()
        }),
    }
}

/// Whether an `Email/set` refusal names the change as it stands (pimdir
/// SYNC §4, RFC 8620 §5.3, RFC 8621 §4.6): a rule it breaks, which no
/// later attempt mends. A quota, a rate limit, a message about to go or
/// a type io-jmap does not name cannot tell.
fn refuses_as_it_stands(refusal: &JmapEmailSetItemError) -> bool {
    match refusal {
        JmapEmailSetItemError::TooManyKeywords { .. }
        | JmapEmailSetItemError::TooManyMailboxes { .. }
        | JmapEmailSetItemError::BlobNotFound { .. }
        | JmapEmailSetItemError::InvalidPatch { .. }
        | JmapEmailSetItemError::InvalidProperties { .. }
        | JmapEmailSetItemError::Singleton { .. }
        | JmapEmailSetItemError::Forbidden { .. }
        | JmapEmailSetItemError::TooLarge { .. } => true,
        JmapEmailSetItemError::NotFound { .. }
        | JmapEmailSetItemError::WillDestroy { .. }
        | JmapEmailSetItemError::OverQuota { .. }
        | JmapEmailSetItemError::RateLimit { .. }
        | JmapEmailSetItemError::Other { .. } => false,
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
    pub fn send_message(&mut self, composed: &Composed) -> Result<(), SendFailure> {
        let mut calls = self.calls()?;
        if !calls
            .session
            .capabilities
            .contains_key(JMAP_SUBMISSION_CAPABILITY)
        {
            return Err(SendFailure::unsent(refused(
                "This server does not offer sending over JMAP",
            )));
        }
        let max_upload = calls.session.core_capability().max_size_upload;

        calls.send(composed, max_upload)
    }
}

/// JMAP source reads: what a read of sources asks of the server.
impl<H: Host> JmapCalls<'_, '_, H> {
    /// Some emails by id, with their `blobId`; one gone left out.
    pub fn email_blobs(&mut self, ids: Vec<String>) -> Result<Vec<JmapEmail>, BridgeError> {
        let opts = JmapEmailGetOptions {
            properties: Some(vec![JmapEmailProperty::Id, JmapEmailProperty::BlobId]),
            ..Default::default()
        };
        let coroutine = JmapEmailGet::new(&self.session, &self.auth, ids, opts)
            .map_err(|err| err.to_string())?;
        let out = self.jmap.run(&self.session.api_url, coroutine)?;
        parsed(&out.unparsed, "Email/get")?;
        Ok(out.emails)
    }

    /// One message's blob, downloaded from the session's download URL.
    pub fn download_blob(&mut self, blob: &str) -> Result<Vec<u8>, BridgeError> {
        let url = download_url(&self.session, blob)?;
        let auth = &self.auth;
        let out = self
            .jmap
            .run_redirect(&url, CrossOrigin::Anonymous, |target| {
                Ok(JmapBlobDownload::new(auth, target))
            })?;
        Ok(out.data)
    }
}

/// JMAP submission requests (RFC 8621 sections 4.8, 6.1, 7.5, RFC 8620 6.1).
impl<H: Host> JmapCalls<'_, '_, H> {
    /// Sends one stored message over the session (RFC 8621 section 7).
    ///
    /// Imported into Drafts without `Bcc`, submitted as [`identity_for`] to
    /// the envelope its intent states ([`compose::stated`]), the blind
    /// recipients among them, moved to Sent. What no later pass changes is
    /// refused for good ([`refused`]). Only the submission's own request may
    /// have sent it ([`SendFailure::taken`]).
    fn send(&mut self, composed: &Composed, max_upload: Option<u64>) -> Result<(), SendFailure> {
        let Composed {
            message,
            sender,
            recipients,
        } = composed;

        let identities = self.identities()?;
        let identity = identity_for(&identities, sender)
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
            return Err(SendFailure::unsent(refused(format!(
                "The message is larger than the {max} bytes the server takes"
            ))));
        }

        let blob = self.upload_message(message.clone())?;
        let imported = self.import_draft(&blob, &drafts)?;
        if let Some(err) = imported.not_created.into_values().next() {
            let message = format!("The server refused the message: {err}");
            // NOTE: one already held may be an earlier try's, sent or not.
            return Err(SendFailure::unsent(match err {
                JmapEmailImportItemError::AlreadyExists { .. }
                | JmapEmailImportItemError::OverQuota { .. }
                | JmapEmailImportItemError::RateLimit { .. }
                | JmapEmailImportItemError::Other { .. } => message.into(),
                JmapEmailImportItemError::InvalidEmail { .. }
                | JmapEmailImportItemError::NotFound { .. }
                | JmapEmailImportItemError::InvalidProperties { .. }
                | JmapEmailImportItemError::Forbidden { .. }
                | JmapEmailImportItemError::TooLarge { .. } => refused(message),
            }));
        }
        let email = imported
            .created
            .into_values()
            .next()
            .and_then(|email| email.id)
            .ok_or_else(|| SendFailure::unsent("The server imported the message under no id"))?;

        let address = |email: &str| JmapEmailAddressWithParameters {
            email: email.to_string(),
            parameters: None,
        };
        let create = JmapEmailSubmissionCreate {
            identity_id: identity.id.clone(),
            email_id: email.clone(),
            envelope: Some(JmapEnvelope {
                mail_from: address(sender),
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
            // NOTE: sent all the same, its draft staying where it was.
            if let Some(reason) = unfiled(&out) {
                log::warn!("sent message {email} not filed out of Drafts: {reason}");
            }
            return Ok(());
        }

        // NOTE: never sent, so the draft imported for it is litter; one the
        // destroy misses costs a row in Drafts and nothing else.
        if let Err(err) = self.destroy_draft(&email) {
            log::warn!("draft {email} left behind: {err}");
        }
        let Some(err) = out.not_created.into_values().next() else {
            return Err(SendFailure::unsent(
                "The server answered the submission with nothing",
            ));
        };
        let message = format!("The server refused to send the message: {err}");
        Err(SendFailure::unsent(match err {
            JmapEmailSubmissionSetItemError::RateLimit { .. }
            | JmapEmailSubmissionSetItemError::NotFound { .. }
            | JmapEmailSubmissionSetItemError::Other { .. } => message.into(),
            _ => refused(message),
        }))
    }

    /// The identities the account may send as.
    pub fn identities(&mut self) -> Result<Vec<JmapIdentity>, BridgeError> {
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
    ///
    /// Past its request written whole, a failure with no status nor
    /// method error read back may have created the submission.
    fn submit(
        &mut self,
        args: JmapEmailSubmissionSetArgs,
    ) -> Result<JmapEmailSubmissionSetOutput, SendFailure> {
        let coroutine = JmapEmailSubmissionSet::new(&self.session, &self.auth, args)
            .map_err(|err| SendFailure::unsent(err.to_string()))?;
        let api_url = self.session.api_url.clone();
        let failed = match self.jmap.try_run(&api_url, coroutine) {
            Ok(Ok(out)) => return Ok(out),
            Ok(Err(err)) => {
                sessions().forget(&api_url);
                SendFailure {
                    taken: !matches!(
                        err,
                        JmapEmailSubmissionSetError::Account(_)
                            | JmapEmailSubmissionSetError::SerializeArgs(_)
                            | JmapEmailSubmissionSetError::Method(_)
                            | JmapEmailSubmissionSetError::Send(
                                JmapSendError::HttpStatus(_)
                                    | JmapSendError::Request { .. }
                                    | JmapSendError::SerializeRequest(_)
                            )
                    ),
                    error: coroutine_error(&err),
                }
            }
            Err(error) => SendFailure {
                taken: self.jmap.client.sent(),
                error,
            },
        };
        Err(failed)
    }

    /// Destroys one draft; one gone already is destroyed ([`converged`]).
    fn destroy_draft(&mut self, id: &str) -> Result<(), BridgeError> {
        converged(self.run_email_set(destroy_args(id), id))?;
        Ok(())
    }
}

/// Why the `Email/set` filing a submitted draft did not, when it said so.
fn unfiled(out: &JmapEmailSubmissionSetOutput) -> Option<String> {
    match out.email_set.as_ref()? {
        Err(err) => Some(err.to_string()),
        Ok(set) => {
            let mut refused = set.not_updated.values().chain(set.not_destroyed.values());
            refused.next().map(ToString::to_string)
        }
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
/// the hierarchical name IMAP hands the app, each name escaped
/// ([`path_segment`]) so a `/` in one never reads as a level.
///
/// A parent the roster lacks ends the walk: a partial path still names the
/// mailbox, while dropping it for a missing ancestor would hide its mail.
fn mailbox_path(named: &BTreeMap<String, JmapMailbox>, mailbox: &JmapMailbox) -> String {
    let segment = |mailbox: &JmapMailbox| path_segment(mailbox.name.as_deref().unwrap_or_default());
    let mut segments = vec![segment(mailbox)];
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
        segments.push(segment(ancestor));
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

/// The Annex A summary of one JMAP Email, read off its raw header fields.
///
/// Each field as the message carries it (RFC 8621 §4.1.3, the `headers`
/// property), joined into the header block a fetched body starts with and
/// read by the derivation a body is read with (pimdir STORAGE Annex A.1),
/// so the two agree byte for byte: never the server's parsed `sentAt`,
/// `messageId` or decoded `subject`. The size and the attachment mark are
/// the server's.
fn email_summary(email: &JmapEmail) -> PimdirMailSummary {
    let mut block = String::new();
    for field in email.headers.iter().flatten() {
        block.push_str(&field.name);
        block.push(':');
        block.push_str(&field.value);
        block.push_str("\r\n");
    }
    block.push_str("\r\n");

    match derive_meta(block.as_bytes(), email.size, email.has_attachment).summary {
        Some(PimdirSummary::Mail(summary)) => summary,
        _ => PimdirMailSummary {
            size: email.size,
            attachment: email.has_attachment,
            ..PimdirMailSummary::default()
        },
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

#[cfg(test)]
mod tests;
