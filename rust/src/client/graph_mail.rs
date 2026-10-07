//! Microsoft Graph mail: mail folders as mailboxes and messages read as
//! the MIME Graph keeps of them, the Graph half of what `syncMail` does
//! over IMAP and JMAP.
//!
//! Shaped like the JMAP side: a folder answers its newest messages whole
//! every pass, since the message delta is not wired, and a message is
//! read as its RFC 5322 source so the store and the parser stay the ones
//! the other backends use.

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
            MsgraphFlagStatus, MsgraphFollowupFlag, MsgraphMessage,
            get_raw::MsgraphMessageGetRaw,
            list::{MsgraphMessagesList, MsgraphMessagesListParams},
            r#move::MsgraphMessageMove,
            update::MsgraphMessageUpdate,
        },
        send_mail::MsgraphMailSendMime,
    },
    send::MsgraphSend,
};

use crate::{
    client::{Client, graph::parse_graph_url},
    types::{BridgeError, Mailbox, Message},
};

/// The well-known name Graph gives the trash (`Deleted Items`).
const TRASH: &str = "deleteditems";

/// The `$select` of a folder listing: the envelope spine and markers.
///
/// The date is `sentDateTime`, Graph's name for the `Date` header, which
/// is what pimdir STORAGE Annex A.1 stores and sorts mail by; the
/// reception time is no part of a summary.
const MESSAGE_SELECT: &str = "id,subject,from,sentDateTime,isRead,flag,hasAttachments";

/// The order of a folder listing: newest by the same date the store
/// sorts on, so the window is the newest messages the list shows.
const MESSAGE_ORDER: &str = "sentDateTime desc";

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
    /// Graph v1.0 tells no folder's role, so the trash is asked for by
    /// its well-known name and recognised by id.
    pub fn list_graph_mailboxes(
        &mut self,
        token: &str,
    ) -> Result<Vec<(String, Mailbox)>, BridgeError> {
        let auth = HttpAuthBearer::new(token);

        let coroutine =
            MsgraphMailFolderGet::new(&auth, "me", TRASH).map_err(|err| err.to_string())?;
        let trash = self.run_msgraph(coroutine)?.id;

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

            let role = if folder.id == trash { "trash" } else { "" };
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

    /// One folder's newest `limit` messages, whole.
    pub fn query_graph_mailbox(
        &mut self,
        token: &str,
        folder_id: &str,
        mailbox_path: &str,
        limit: u32,
    ) -> Result<Vec<Message>, BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let params = MsgraphMessagesListParams {
            top: Some(limit),
            select: Some(MESSAGE_SELECT),
            orderby: Some(MESSAGE_ORDER),
            ..Default::default()
        };

        let coroutine = MsgraphMessagesList::new(&auth, "me", Some(folder_id), &params)
            .map_err(|err| err.to_string())?;
        let page = self.run_msgraph(coroutine)?;

        Ok(page
            .value
            .into_iter()
            .map(|message| graph_message(mailbox_path, message))
            .collect())
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

/// One Graph message to the JNI-facing shape.
fn graph_message(mailbox: &str, message: MsgraphMessage) -> Message {
    let sender = message.from.map(|from| from.email_address);
    let from = sender
        .as_ref()
        .and_then(|sender| sender.name.clone())
        .filter(|name| !name.trim().is_empty())
        .unwrap_or_default();
    let from_address = sender.and_then(|sender| sender.address).unwrap_or_default();
    let flagged = message
        .flag
        .and_then(|flag| flag.flag_status)
        .is_some_and(|status| status == MsgraphFlagStatus::Flagged);

    Message {
        mailbox: mailbox.to_string(),
        id: message.id,
        subject: message.subject.unwrap_or_default(),
        from,
        from_address,
        // NOTE: Annex A.1 leaves the date `NULL` when there is none, which
        // the wire carries as an empty string, as for the other backends.
        date: message.sent_date_time.unwrap_or_default(),
        seen: message.is_read.unwrap_or(false),
        answered: false,
        flagged,
        has_attachment: message.has_attachments.unwrap_or(false),
    }
}

#[cfg(test)]
mod tests {
    use io_msgraph::v1::rest::users::messages::MsgraphMessage;

    use super::graph_message;

    #[test]
    fn the_date_is_the_sent_date_not_the_reception() {
        let message = MsgraphMessage {
            id: "m1".into(),
            sent_date_time: Some("2026-10-07T08:00:00Z".into()),
            received_date_time: Some("2026-10-07T08:05:00Z".into()),
            ..Default::default()
        };
        assert_eq!(graph_message("INBOX", message).date, "2026-10-07T08:00:00Z");

        // A message with no `Date` has none, rather than the reception
        // time standing in for it (Annex A.1: `NULL`).
        let undated = MsgraphMessage {
            id: "m2".into(),
            received_date_time: Some("2026-10-07T08:05:00Z".into()),
            ..Default::default()
        };
        assert_eq!(graph_message("INBOX", undated).date, "");
    }
}
