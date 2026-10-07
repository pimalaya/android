//! Microsoft Graph mail: mail folders as mailboxes and messages read as
//! the MIME Graph keeps of them, the Graph half of what `syncMail` does
//! over IMAP and JMAP.
//!
//! A folder is listed by its message delta, which answers newest first
//! (by reception) and ends with the delta link the next pass resumes
//! from: a round is that delta's pages, 1,000 messages each, every member
//! named by the summary `$select` reads, and every pass after it one
//! request. A message is read as its RFC 5322 source, so the store and the
//! parser stay the ones the other backends use.

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::v1::{
    rest::users::{
        mail_folders::{
            MsgraphMailFolder,
            child_folders::MsgraphMailChildFoldersList,
            get::MsgraphMailFolderGet,
            list::{
                MsgraphMailFoldersList, MsgraphMailFoldersListParams,
                MsgraphMailFoldersListResponse,
            },
        },
        messages::{
            MsgraphFlagStatus, MsgraphFollowupFlag, MsgraphMessage, MsgraphRecipient,
            delta::{MsgraphMessagesDelta, MsgraphMessagesDeltaParams},
            get_raw::MsgraphMessageGetRaw,
            list::{MsgraphMessagesList, MsgraphMessagesListParams, MsgraphMessagesListResponse},
            r#move::MsgraphMessageMove,
            update::MsgraphMessageUpdate,
        },
        send_mail::MsgraphMailSendMime,
    },
    send::MsgraphSend,
};

use io_pimdir::summary::{
    PimdirAddress,
    mail::{PimdirMailSummary, decode},
};

use crate::{
    client::{
        Client,
        graph::parse_graph_url,
        listing::{
            Floor, GRAPH_PAGE, Listing, MailPage, MailRequest, Named, RECEIVED_MARGIN_DAYS, Scope,
            flags, utc,
        },
    },
    types::{BridgeError, Mailbox},
};

/// The well-known name Graph gives the trash (`Deleted Items`).
const TRASH: &str = "deleteditems";

/// The other well-known folders a pass is ordered by, with the role each
/// is in pimdir's vocabulary.
const WELL_KNOWN: [(&str, &str); 4] = [
    ("inbox", "inbox"),
    ("sentitems", "sent"),
    ("drafts", "drafts"),
    ("junkemail", "junk"),
];

/// The `$select` of a folder's message delta: what Annex A's summary and
/// addresses read, and the markers.
///
/// The date is `sentDateTime`, Graph's name for the `Date` header, which
/// is what pimdir STORAGE Annex A.1 stores and sorts mail by; the
/// reception time is no part of a summary, and only narrows the listing.
const MESSAGE_SELECT: &str = "id,subject,from,toRecipients,ccRecipients,sentDateTime,isRead,\
flag,hasAttachments,internetMessageId";

impl<'a, 'local> Client<'a, 'local> {
    /// Reads the inbox's folder, so a session that cannot authenticate
    /// fails when it is opened rather than on the first verb that uses it.
    pub fn graph_mail_check(&mut self, token: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMailFolderGet::new(&auth, "me", "inbox").map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;
        Ok(())
    }

    /// The account's mail folders as `(id, mailbox)` pairs, the name
    /// being the folder's path, its ancestors' names and its own joined
    /// by `/`, the way IMAP hands the app a hierarchical name.
    ///
    /// Graph v1.0 tells no folder's role, so each role is asked for by its
    /// well-known name and recognised by id: the trash, which a delete
    /// needs, and the inbox, the sent items, the drafts and the junk, which
    /// order a pass. Only the trash is required; a well-known folder the
    /// mailbox does not answer for leaves its role unsaid.
    pub fn list_graph_mailboxes(
        &mut self,
        token: &str,
    ) -> Result<Vec<(String, Mailbox)>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let coroutine =
            MsgraphMailFolderGet::new(&auth, "me", TRASH).map_err(|err| err.to_string())?;
        let mut roles = vec![(self.run_msgraph(coroutine)?.id, "trash")];
        for (known, role) in WELL_KNOWN {
            let coroutine =
                MsgraphMailFolderGet::new(&auth, "me", known).map_err(|err| err.to_string())?;
            match self.run_msgraph(coroutine) {
                Ok(folder) => roles.push((folder.id, role)),
                Err(err) => log::debug!("no well-known folder {known}: {err}"),
            }
        }

        let params = MsgraphMailFoldersListParams {
            top: Some(100),
            ..Default::default()
        };

        let coroutine =
            MsgraphMailFoldersList::new(&auth, "me", &params).map_err(|err| err.to_string())?;
        let first = self.run_msgraph(coroutine)?;
        let mut pending: Vec<(String, MsgraphMailFolder)> = self
            .graph_folder_pages(&auth, first)?
            .into_iter()
            .map(|folder| (folder.display_name.clone(), folder))
            .collect();

        let mut mailboxes = Vec::new();
        while let Some((path, folder)) = pending.pop() {
            if folder.child_folder_count.unwrap_or(0) > 0 {
                let coroutine = MsgraphMailChildFoldersList::new(&auth, "me", &folder.id, &params)
                    .map_err(|err| err.to_string())?;
                let first = self.run_msgraph(coroutine)?;
                for child in self.graph_folder_pages(&auth, first)? {
                    pending.push((format!("{path}/{}", child.display_name), child));
                }
            }

            let role = roles
                .iter()
                .find(|(id, _)| *id == folder.id)
                .map_or("", |(_, role)| *role);
            mailboxes.push((
                folder.id,
                Mailbox {
                    name: path,
                    role: role.into(),
                },
            ));
        }

        Ok(mailboxes)
    }

    /// Every folder of a listing, following its next links.
    fn graph_folder_pages(
        &mut self,
        auth: &HttpAuthBearer,
        mut page: MsgraphMailFoldersListResponse,
    ) -> Result<Vec<MsgraphMailFolder>, BridgeError> {
        let mut folders = Vec::new();

        loop {
            folders.extend(page.value);
            match page.next_link {
                Some(next) => {
                    let url = parse_graph_url(&next)?;
                    page = self.run_msgraph(MsgraphSend::<MsgraphMailFoldersListResponse>::get(
                        auth, url,
                    ))?;
                }
                None => return Ok(folders),
            }
        }
    }

    /// One page of a folder's listing (pimdir SYNC §4, §5).
    ///
    /// A round is the folder's message delta, filtered on the reception
    /// date two days below the scope's floor, 1,000 messages a page with
    /// the summary `$select`; the next link is the resume cursor and the
    /// delta link, on the last page, the checkpoint. A delta follows the
    /// checkpoint's link to its new delta link as one page. An expired
    /// link (410) is refused for the engine to restart, which opens a
    /// round where a delta was asked. A member whose `Date` falls out of
    /// the scope is left out; a removal applies whatever the date.
    pub fn list_graph_page(
        &mut self,
        token: &str,
        folder_id: &str,
        request: &MailRequest,
    ) -> Result<MailPage, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let scope = &request.scope;

        let (link, delta) = match &request.listing {
            Listing::Delta { checkpoint } => (Some(checkpoint.as_str()), true),
            Listing::Round { cursor, .. } => (cursor.as_deref(), false),
        };

        let mut items = Vec::new();
        let mut vanished = Vec::new();
        let mut next = link.map(String::from);
        loop {
            let coroutine = match next.as_deref() {
                Some(link) => MsgraphMessagesDelta::from_link(&auth, link)
                    .map_err(|err| err.to_string())?
                    .max_page_size(GRAPH_PAGE),
                None => {
                    let filter = scope.received_since(RECEIVED_MARGIN_DAYS).map(|since| {
                        format!(
                            "receivedDateTime ge {}",
                            since.strftime("%Y-%m-%dT%H:%M:%SZ")
                        )
                    });
                    let params = MsgraphMessagesDeltaParams {
                        select: Some(MESSAGE_SELECT),
                        filter: filter.as_deref(),
                        max_page_size: Some(GRAPH_PAGE),
                    };
                    MsgraphMessagesDelta::with_params(&auth, "me", Some(folder_id), &params)
                        .map_err(|err| err.to_string())?
                }
            };
            let page = match self.run_msgraph(coroutine) {
                Ok(page) => page,
                Err(err) if err.status == Some(410) && next.is_some() => {
                    return Ok(MailPage::rejected());
                }
                Err(err) => return Err(err),
            };

            for row in page.value {
                if row.removed.is_some() {
                    vanished.push(row.message.id);
                } else {
                    named(row.message, scope, &mut items);
                }
            }

            match (page.next_link, page.delta_link) {
                // NOTE: a delta is one page however many Graph cuts it
                // into; a round lands each of Graph's pages as its own.
                (Some(link), _) if delta => next = Some(link),
                (Some(link), _) => {
                    let mut page = MailPage::round(items, Some(link), None);
                    page.vanished = vanished;
                    return Ok(page);
                }
                (None, Some(link)) if delta => return Ok(MailPage::delta(items, vanished, link)),
                (None, link) => {
                    let mut page = MailPage::round(items, None, link);
                    page.vanished = vanished;
                    return Ok(page);
                }
            }
        }
    }

    /// Takes a folder's newest messages below the floor's ceiling until its
    /// chunk is full: a plain listing ordered by `sentDateTime` (the `Date`
    /// header, which Graph filters on exactly), filtered below the ceiling,
    /// `sentDateTime` alone selected, as many a page as the chunk asks for.
    pub fn graph_floor(
        &mut self,
        token: &str,
        folder_id: &str,
        floor: &mut Floor,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let filter = floor
            .scope()
            .until
            .as_deref()
            .map(|until| format!("sentDateTime lt {until}"));
        let top = floor.count().min(GRAPH_PAGE as usize) as u32;
        let params = MsgraphMessagesListParams {
            top: Some(top),
            select: Some("id,sentDateTime"),
            filter: filter.as_deref(),
            orderby: Some("sentDateTime desc"),
            ..Default::default()
        };

        let coroutine = MsgraphMessagesList::new(&auth, "me", Some(folder_id), &params)
            .map_err(|err| err.to_string())?;
        let mut page = self.run_msgraph(coroutine)?;
        loop {
            for message in &page.value {
                let date = message.sent_date_time.as_deref().and_then(utc);
                if floor.take(date.as_deref()) {
                    return Ok(());
                }
            }
            let Some(next) = page.next_link.take() else {
                return Ok(());
            };
            let url = parse_graph_url(&next)?;
            page = self.run_msgraph(MsgraphSend::<MsgraphMessagesListResponse>::get(&auth, url))?;
        }
    }

    /// Reads one message whole, as the RFC 5322 bytes Graph serves.
    pub fn fetch_graph_source(&mut self, token: &str, id: &str) -> Result<Vec<u8>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMessageGetRaw::new(&auth, "me", id).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)
    }

    /// Adds or removes one marker on one message.
    ///
    /// `\Seen` is `isRead` and `\Flagged` the follow-up flag. Graph keeps
    /// no answered marker and no deleted one, so the other two are
    /// refused the way the JMAP side refuses what it cannot express.
    pub fn set_graph_flag(
        &mut self,
        token: &str,
        id: &str,
        flag: &str,
        add: bool,
    ) -> Result<(), BridgeError> {
        let mut patch = MsgraphMessage::default();
        match flag {
            "\\Seen" => patch.is_read = Some(add),
            "\\Flagged" => {
                let status = if add {
                    MsgraphFlagStatus::Flagged
                } else {
                    MsgraphFlagStatus::NotFlagged
                };
                patch.flag = Some(MsgraphFollowupFlag {
                    flag_status: Some(status),
                });
            }
            other => return Err(format!("No Graph property for the marker `{other}`").into()),
        }

        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMessageUpdate::new(&auth, "me", id, &patch).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;
        Ok(())
    }

    /// Moves one message into `Deleted Items`, naming the mailbox it
    /// landed in.
    ///
    /// The name is the bare display name: the trash is a top-level
    /// folder, so its path is its name.
    pub fn delete_graph_message(&mut self, token: &str, id: &str) -> Result<String, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let coroutine =
            MsgraphMessageMove::new(&auth, "me", id, TRASH).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;

        let coroutine =
            MsgraphMailFolderGet::new(&auth, "me", TRASH).map_err(|err| err.to_string())?;
        Ok(self.run_msgraph(coroutine)?.display_name)
    }

    /// Sends one RFC 5322 message, Graph filing the copy in `Sent Items`
    /// itself.
    pub fn send_graph_message(&mut self, token: &str, raw: &[u8]) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMailSendMime::new(&auth, "me", raw).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;
        Ok(())
    }
}

/// One Graph message, named by the summary its `$select` read, when its
/// `Date` falls in the scope.
fn named(message: MsgraphMessage, scope: &Scope, items: &mut Vec<Named>) {
    let summary = graph_summary(&message);
    if !scope.contains(summary.date.as_deref()) {
        return;
    }
    let flagged = message
        .flag
        .and_then(|flag| flag.flag_status)
        .is_some_and(|status| status == MsgraphFlagStatus::Flagged);
    let flags = flags(message.is_read.unwrap_or(false), false, flagged);

    items.push(Named::new(message.id, flags, summary));
}

/// The Annex A summary of one Graph message, read off its `$select`.
///
/// The date is the `Date` header (`sentDateTime`), never the reception;
/// the attachment mark is Graph's own `hasAttachments`.
fn graph_summary(message: &MsgraphMessage) -> PimdirMailSummary {
    let from: Vec<PimdirAddress> = message.from.iter().filter_map(address).collect();
    let first = from.first().cloned();

    PimdirMailSummary {
        message_id: message
            .internet_message_id
            .as_deref()
            .map(|id| {
                id.trim()
                    .trim_start_matches('<')
                    .trim_end_matches('>')
                    .to_string()
            })
            .filter(|id| !id.is_empty()),
        in_reply_to: Vec::new(),
        subject: message.subject.as_deref().map(decode).unwrap_or_default(),
        sender: first.as_ref().map(|address| address.address.clone()),
        sender_name: first.and_then(|address| address.name),
        date: message.sent_date_time.as_deref().and_then(utc),
        size: None,
        attachment: message.has_attachments,
        from,
        to: message.to_recipients.iter().filter_map(address).collect(),
        cc: message.cc_recipients.iter().filter_map(address).collect(),
        bcc: Vec::new(),
    }
}

/// One Graph recipient as Annex A.6 stores an address.
fn address(recipient: &MsgraphRecipient) -> Option<PimdirAddress> {
    let address = recipient.email_address.address.as_deref()?;
    let address = PimdirAddress::canonical(address);
    if address.is_empty() {
        return None;
    }
    let name = recipient
        .email_address
        .name
        .as_deref()
        .map(str::trim)
        .filter(|name| !name.is_empty())
        .map(String::from);
    Some(PimdirAddress { address, name })
}

#[cfg(test)]
mod tests {
    use io_msgraph::v1::rest::users::messages::{
        MsgraphEmailAddress, MsgraphMessage, MsgraphRecipient,
    };

    use super::graph_summary;

    #[test]
    fn the_date_is_the_sent_date_not_the_reception() {
        let message = MsgraphMessage {
            id: "m1".into(),
            sent_date_time: Some("2026-10-07T08:00:00Z".into()),
            received_date_time: Some("2026-10-07T08:05:00Z".into()),
            ..Default::default()
        };
        assert_eq!(
            graph_summary(&message).date.as_deref(),
            Some("2026-10-07T08:00:00Z")
        );

        // A message with no `Date` has none, rather than the reception
        // time standing in for it (Annex A.1: `NULL`).
        let undated = MsgraphMessage {
            id: "m2".into(),
            received_date_time: Some("2026-10-07T08:05:00Z".into()),
            ..Default::default()
        };
        assert_eq!(graph_summary(&undated).date, None);
    }

    #[test]
    fn the_summary_reads_the_select() {
        let recipient = |name: &str, address: &str| MsgraphRecipient {
            email_address: MsgraphEmailAddress {
                name: Some(name.into()),
                address: Some(address.into()),
            },
        };
        let message = MsgraphMessage {
            id: "m1".into(),
            subject: Some("Hello".into()),
            from: Some(recipient("Ana", "Ana@Example.org")),
            to_recipients: vec![recipient("Bo", "bo@example.org")],
            internet_message_id: Some("<m1@example.org>".into()),
            has_attachments: Some(true),
            ..Default::default()
        };

        let summary = graph_summary(&message);
        assert_eq!(summary.message_id.as_deref(), Some("m1@example.org"));
        assert_eq!(summary.sender.as_deref(), Some("ana@example.org"));
        assert_eq!(summary.sender_name.as_deref(), Some("Ana"));
        assert_eq!(summary.to.len(), 1);
        assert_eq!(summary.attachment, Some(true));
    }
}
