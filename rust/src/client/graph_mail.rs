//! Microsoft Graph mail: mail folders as mailboxes and messages read as
//! the MIME Graph keeps of them, the Graph half of what `syncMail` does
//! over IMAP and JMAP.
//!
//! A folder has one message delta link, made with no filter: its first
//! pass names every message of the folder by id (1,000 a page), and every
//! delta after it reports a change whatever the message's date, so it
//! serves any scope and a widening relists nothing. Mail itself is listed
//! by band: a first chunk, a scroll widening and the fill list only the
//! band they lack by the plain `/messages` list on `sentDateTime`, every
//! member named by the summary `$select`. A message is read as its RFC
//! 5322 source, so the store and the parser stay the ones the other
//! backends use.

use io_http::rfc6750::bearer::HttpAuthBearer;
use io_msgraph::v1::{
    rest::{
        batch::{MSGRAPH_BATCH_MAX_REQUESTS, MsgraphBatch, MsgraphBatchRequest},
        users::{
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
                copy::MsgraphMessageCopy,
                delta::{
                    MsgraphMessageDelta, MsgraphMessagesDelta, MsgraphMessagesDeltaParams,
                    MsgraphMessagesDeltaResponse,
                },
                get_raw::MsgraphMessageGetRaw,
                list::{
                    MsgraphMessagesList, MsgraphMessagesListParams, MsgraphMessagesListResponse,
                },
                r#move::MsgraphMessageMove,
                update::MsgraphMessageUpdate,
            },
            send_mail::MsgraphMailSendMime,
        },
    },
    send::{MSGRAPH_API_BASE, MsgraphNoResponse, MsgraphSend},
};

use io_pimdir::summary::{
    PimdirAddress,
    mail::{PimdirMailSummary, decode},
};
use serde::{Deserialize, Serialize};
use url::Url;

use crate::{
    client::{
        Client,
        graph::{graph_url, parse_graph_url, relative},
        listing::{Floor, GRAPH_PAGE, Listing, MailPage, MailRequest, Named, Scope, flags, utc},
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

/// The `$select` of a band's `/messages` page and of a message read by
/// id: what Annex A's summary and addresses read, and the markers.
///
/// The date is `sentDateTime`, Graph's name for the `Date` header, which
/// is what pimdir STORAGE Annex A.1 stores and sorts mail by; the
/// reception time is no part of a summary.
const MESSAGE_SELECT: &str = "id,subject,from,toRecipients,ccRecipients,sentDateTime,isRead,\
flag,hasAttachments,internetMessageId";

/// The `$expand` riding along [`MESSAGE_SELECT`]: the MAPI
/// `PidTagMessageSize` (`Integer 0x0E08`), the size Exchange states for
/// the message, as IMAP's `RFC822.SIZE` does.
const MESSAGE_EXPAND: &str = "singleValueExtendedProperties($filter=id eq 'Integer 0x0E08')";

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

    /// One page of a folder's listing (pimdir SYNC §4, §5), over the live
    /// Graph ([`list_folder`]).
    pub fn list_graph_page(
        &mut self,
        token: &str,
        folder_id: &str,
        request: &MailRequest,
    ) -> Result<MailPage, BridgeError> {
        let mut folder = LiveFolder {
            client: self,
            auth: HttpAuthBearer::new(token),
            folder: folder_id,
        };
        list_folder(&mut folder, request)
    }

    /// Names the messages a delta listed by id alone, as the summary
    /// `$select` reads them ([`name_messages`]); a message gone since the
    /// delta is left out.
    pub fn name_graph_messages(
        &mut self,
        token: &str,
        folder_id: &str,
        ids: &[String],
    ) -> Result<Vec<Named>, BridgeError> {
        let mut folder = LiveFolder {
            client: self,
            auth: HttpAuthBearer::new(token),
            folder: folder_id,
        };
        name_messages(&mut folder, ids)
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

    /// Moves one message into the folder `to` (a folder id).
    pub fn relocate_graph_message(
        &mut self,
        token: &str,
        id: &str,
        to: &str,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMessageMove::new(&auth, "me", id, to).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;
        Ok(())
    }

    /// Copies one message into the folder `to` (a folder id).
    pub fn copy_graph_message(
        &mut self,
        token: &str,
        id: &str,
        to: &str,
    ) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let coroutine =
            MsgraphMessageCopy::new(&auth, "me", id, to).map_err(|err| err.to_string())?;
        self.run_msgraph(coroutine)?;
        Ok(())
    }

    /// Deletes one message for good, past `Recoverable Items`
    /// (`permanentDelete`), which io-msgraph has no coroutine for.
    pub fn destroy_graph_message(&mut self, token: &str, id: &str) -> Result<(), BridgeError> {
        let auth = HttpAuthBearer::new(token);
        let send = MsgraphSend::<MsgraphNoResponse>::with_method(
            &auth,
            "POST",
            permanent_delete_url(id)?,
            None,
            Vec::new(),
        );
        self.run_msgraph(send)?;
        Ok(())
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

/// What a delta link is kept under as a checkpoint: a link made with no
/// filter, which reports a change whatever the message's date.
///
/// A checkpoint without it is a link made under a reception filter
/// (before the delta was unfiltered), bound to that filter: it is refused,
/// and the round it opens makes the unfiltered one.
const DELTA_CHECKPOINT: &str = "graph-delta:";

/// What a round's cursor starts with while it lists a band by
/// `/messages`, the rest the band's [`Ceiling`] as JSON.
const BAND_CURSOR: &str = "band:";

/// What a round's cursor starts with while it walks the unfiltered
/// delta's first pass, the rest Graph's next link.
const DELTA_CURSOR: &str = "delta:";

/// The `$select` of the unfiltered delta: the markers, and the `Date`
/// that tells a member in scope from one out of it, which is all a
/// change needs where the store already names the message. The delta
/// link keeps it: every later delta answers with these alone.
const DELTA_SELECT: &str = "id,sentDateTime,isRead,flag";

/// The Graph requests a folder's listing is made of: one page of the
/// plain `/messages` list over a band of `sentDateTime`, one page of the
/// folder's unfiltered message delta, and the summaries of messages read
/// by id. The listing ([`list_folder`]) is one piece of logic over them,
/// the live Graph or a fake one.
pub(crate) trait GraphFolder {
    /// The newest messages of the band first, `top` at most, with the
    /// summary `$select`; `more` when Graph holds more past them.
    fn messages(&mut self, band: &Band, top: u32) -> Result<MessagesPage, BridgeError>;

    /// One page of the unfiltered delta: its first, or the one `link`
    /// names (a next link or a delta link). [`None`] when Graph expired
    /// the link (410).
    fn delta(
        &mut self,
        link: Option<&str>,
    ) -> Result<Option<MsgraphMessagesDeltaResponse>, BridgeError>;

    /// The messages of `ids` read with the summary `$select`, those gone
    /// left out.
    fn read(&mut self, ids: &[String]) -> Result<Vec<MsgraphMessage>, BridgeError>;
}

/// One page of the plain `/messages` list.
pub(crate) struct MessagesPage {
    pub value: Vec<MsgraphMessage>,
    pub more: bool,
}

/// A band of `sentDateTime`, Graph's name for the `Date` header, which it
/// filters on exactly: from `since` on, below `until` (or up to it,
/// `inclusive`, for a page resuming among messages of the same second).
#[derive(Clone, Debug, Default, Eq, PartialEq)]
pub(crate) struct Band {
    pub since: Option<String>,
    pub until: Option<String>,
    pub inclusive: bool,
    /// How many messages the page skips (`$skip`), 0 for none.
    pub skip: u32,
}

impl Band {
    /// The `$filter` of the band, [`None`] for an open one.
    pub fn filter(&self) -> Option<String> {
        let since = self
            .since
            .as_deref()
            .map(|since| format!("sentDateTime ge {since}"));
        let until = self.until.as_deref().map(|until| match self.inclusive {
            true => format!("sentDateTime le {until}"),
            false => format!("sentDateTime lt {until}"),
        });
        match (since, until) {
            (Some(since), Some(until)) => Some(format!("{since} and {until}")),
            (since, until) => since.or(until),
        }
    }
}

/// Where a band's listing resumes: below the oldest `Date` the pages
/// before reached, that second included so messages of it a page cut off
/// are not lost, the ones already listed at it named in `seen`.
///
/// Not Graph's next link: that one pages by `$skip`, and a message
/// removed from the pages before shifts the rest up by one, so the first
/// of the next page would never be listed.
#[derive(Debug, Default, Deserialize, Serialize, Eq, PartialEq)]
struct Ceiling {
    #[serde(default, skip_serializing_if = "Option::is_none")]
    until: Option<String>,
    #[serde(default, skip_serializing_if = "core::ops::Not::not")]
    inclusive: bool,
    #[serde(default, skip_serializing_if = "Vec::is_empty")]
    seen: Vec<String>,
    /// How many of that second's messages a page skips, past a page of
    /// them all listed already.
    #[serde(default, skip_serializing_if = "is_zero")]
    skip: u32,
}

fn is_zero(skip: &u32) -> bool {
    *skip == 0
}

/// One page of a folder's listing (pimdir SYNC §4, §5).
///
/// The folder has one delta link, made with no filter, so it reports a
/// change to any message whatever its date and serves every scope: the
/// connector is bound to none (`scope_bound` false), and a widening lists
/// the band it lacks alone.
///
/// - A delta follows the checkpoint's link to its new delta link, as one
///   page: a removal applies whatever the date, a change to a message
///   dated out of the scope is dropped, and one in scope is listed by id
///   and markers alone, for the caller to name ([`name_messages`]) when
///   the store binds it not. An expired link (410), or one made under a
///   filter before, is refused for the engine to open a round.
/// - A round over a scope the store does not cover yet (a mailbox's first
///   chunk), and every band round, lists the band by `/messages`
///   ([`band_page`]), each page named by the summary `$select`, and hands
///   no checkpoint: the first chunk lands without waiting on the delta.
/// - A round over a scope the store covers (`covered`: the round a pass
///   opens once the first chunk has landed and no delta link exists yet,
///   or after a refused one) walks the delta's first pass ([`delta_page`]):
///   every message of the folder by id, the ones in scope listed, a page
///   a round page, the last carrying the delta link as the checkpoint. It
///   is what the folder holds as it ends, so what changed or went since
///   the band was listed is read there: a member it does not list is
///   absent from the scope.
pub(crate) fn list_folder<G: GraphFolder>(
    graph: &mut G,
    request: &MailRequest,
) -> Result<MailPage, BridgeError> {
    let scope = &request.scope;

    match &request.listing {
        Listing::Delta { checkpoint } => {
            let Some(link) = checkpoint.strip_prefix(DELTA_CHECKPOINT) else {
                return Ok(MailPage::rejected());
            };
            let mut items = Vec::new();
            let mut vanished = Vec::new();
            let mut next = link.to_string();
            loop {
                let Some(page) = graph.delta(Some(&next))? else {
                    return Ok(MailPage::rejected());
                };
                changes(page.value, scope, &mut items, &mut vanished);
                // NOTE: a delta is one page however many Graph cuts it
                // into.
                match (page.next_link, page.delta_link) {
                    (Some(link), _) => next = link,
                    (None, Some(link)) => {
                        let checkpoint = format!("{DELTA_CHECKPOINT}{link}");
                        return Ok(MailPage::delta(items, vanished, checkpoint));
                    }
                    (None, None) => return Err("Graph delta page with no link".into()),
                }
            }
        }
        Listing::Round { cursor: None, band } if !band && request.covered => {
            delta_page(graph, None, scope)
        }
        Listing::Round { cursor: None, .. } => {
            let ceiling = Ceiling {
                until: scope.until.clone(),
                ..Default::default()
            };
            band_page(graph, scope, ceiling)
        }
        Listing::Round {
            cursor: Some(cursor),
            ..
        } => {
            if let Some(link) = cursor.strip_prefix(DELTA_CURSOR) {
                return delta_page(graph, Some(link), scope);
            }
            match cursor
                .strip_prefix(BAND_CURSOR)
                .and_then(|ceiling| serde_json::from_str::<Ceiling>(ceiling).ok())
            {
                Some(ceiling) => band_page(graph, scope, ceiling),
                // NOTE: a cursor from before (a filtered delta's next
                // link) restarts the round.
                None => Ok(MailPage::rejected()),
            }
        }
    }
}

/// One page of a band listed by `/messages`, newest `Date` first, below
/// the ceiling; the next page resumes below the oldest `Date` it reached.
/// No checkpoint: a band round keeps the one the source has, and a first
/// chunk lands before any delta link exists.
fn band_page<G: GraphFolder>(
    graph: &mut G,
    scope: &Scope,
    ceiling: Ceiling,
) -> Result<MailPage, BridgeError> {
    let band = Band {
        since: scope.since.clone(),
        until: ceiling.until.clone(),
        inclusive: ceiling.inclusive,
        skip: ceiling.skip,
    };
    let page = graph.messages(&band, GRAPH_PAGE)?;

    let mut items = Vec::with_capacity(page.value.len());
    let mut fresh = 0;
    let mut oldest: Option<String> = None;
    let mut at_oldest: Vec<String> = Vec::new();
    for message in page.value {
        if ceiling.seen.contains(&message.id) {
            continue;
        }
        fresh += 1;
        if let Some(date) = message.sent_date_time.as_deref().and_then(utc) {
            match oldest.as_deref().map(|oldest| date.as_str().cmp(oldest)) {
                None | Some(core::cmp::Ordering::Less) => {
                    oldest = Some(date);
                    at_oldest = vec![message.id.clone()];
                }
                Some(core::cmp::Ordering::Equal) => at_oldest.push(message.id.clone()),
                Some(core::cmp::Ordering::Greater) => (),
            }
        }
        named(message, scope, &mut items);
    }

    if !page.more {
        return Ok(MailPage::round(items, None, None));
    }
    let next = match oldest {
        Some(oldest) if fresh > 0 => {
            // NOTE: the ones listed at that second on the pages before
            // stay listed, when the ceiling did not move.
            let mut seen = at_oldest;
            if ceiling.inclusive && ceiling.until.as_deref() == Some(oldest.as_str()) {
                seen.extend(ceiling.seen);
            }
            Ceiling {
                until: Some(oldest),
                inclusive: true,
                seen,
                skip: 0,
            }
        }
        // NOTE: a whole page of one second's messages listed already: that
        // second holds more than a page, so the next page skips the ones
        // listed, the one place a band pages by `$skip`.
        _ if ceiling.inclusive && fresh == 0 && ceiling.skip == 0 => Ceiling {
            skip: ceiling.seen.len() as u32,
            ..ceiling
        },
        // NOTE: Graph did not answer that second's messages in the same
        // order twice; the band goes on below it, and the delta's first
        // pass names what this misses.
        _ if ceiling.inclusive && fresh == 0 => Ceiling {
            until: ceiling.until,
            ..Default::default()
        },
        // NOTE: a page of undated messages alone, which no ceiling
        // resumes below; the delta's first pass names the rest.
        _ => return Ok(MailPage::round(items, None, None)),
    };
    let cursor = serde_json::to_string(&next).map_err(|err| err.to_string())?;
    Ok(MailPage::round(
        items,
        Some(format!("{BAND_CURSOR}{cursor}")),
        None,
    ))
}

/// One page of the unfiltered delta's first pass, as a round page: the
/// members in scope listed by id and markers, the next link the cursor,
/// the delta link on the last page the checkpoint. A next link Graph
/// expired restarts the round.
fn delta_page<G: GraphFolder>(
    graph: &mut G,
    link: Option<&str>,
    scope: &Scope,
) -> Result<MailPage, BridgeError> {
    let Some(page) = graph.delta(link)? else {
        if link.is_some() {
            return Ok(MailPage::rejected());
        }
        return Err("Graph refused a new message delta".into());
    };

    let mut items = Vec::new();
    let mut vanished = Vec::new();
    changes(page.value, scope, &mut items, &mut vanished);
    let mut reply = match (page.next_link, page.delta_link) {
        (Some(next), _) => MailPage::round(items, Some(format!("{DELTA_CURSOR}{next}")), None),
        (None, Some(link)) => {
            MailPage::round(items, None, Some(format!("{DELTA_CHECKPOINT}{link}")))
        }
        (None, None) => return Err("Graph delta page with no link".into()),
    };
    reply.vanished = vanished;
    Ok(reply)
}

/// What one delta page says of the folder: a removal goes whatever the
/// date, a message dated out of the scope is dropped, and one in scope
/// is listed by id and markers alone.
fn changes(
    rows: Vec<MsgraphMessageDelta>,
    scope: &Scope,
    items: &mut Vec<Named>,
    vanished: &mut Vec<String>,
) {
    for row in rows {
        if row.removed.is_some() {
            vanished.push(row.message.id);
            continue;
        }
        let date = row.message.sent_date_time.as_deref().and_then(utc);
        if !scope.contains(date.as_deref()) {
            continue;
        }
        let marks = marks(&row.message);
        items.push(Named::unnamed(row.message.id, marks));
    }
}

/// The messages of `ids` named by the summary `$select` reads, those gone
/// since they were listed left out.
pub(crate) fn name_messages<G: GraphFolder>(
    graph: &mut G,
    ids: &[String],
) -> Result<Vec<Named>, BridgeError> {
    let read = graph.read(ids)?;
    let mut items = Vec::with_capacity(read.len());
    for message in read {
        named(message, &Scope::default(), &mut items);
    }
    Ok(items)
}

/// The live Graph of one folder.
struct LiveFolder<'c, 'a, 'local> {
    client: &'c mut Client<'a, 'local>,
    auth: HttpAuthBearer,
    folder: &'c str,
}

impl GraphFolder for LiveFolder<'_, '_, '_> {
    fn messages(&mut self, band: &Band, top: u32) -> Result<MessagesPage, BridgeError> {
        let filter = band.filter();
        let params = MsgraphMessagesListParams {
            top: Some(top),
            skip: (band.skip > 0).then_some(band.skip),
            select: Some(MESSAGE_SELECT),
            expand: Some(MESSAGE_EXPAND),
            filter: filter.as_deref(),
            orderby: Some("sentDateTime desc"),
            ..Default::default()
        };
        let coroutine = MsgraphMessagesList::new(&self.auth, "me", Some(self.folder), &params)
            .map_err(|err| err.to_string())?;
        let page = self.client.run_msgraph(coroutine)?;
        Ok(MessagesPage {
            more: page.next_link.is_some(),
            value: page.value,
        })
    }

    fn delta(
        &mut self,
        link: Option<&str>,
    ) -> Result<Option<MsgraphMessagesDeltaResponse>, BridgeError> {
        let coroutine = match link {
            Some(link) => MsgraphMessagesDelta::from_link(&self.auth, link)
                .map_err(|err| err.to_string())?
                .max_page_size(GRAPH_PAGE),
            None => {
                let params = MsgraphMessagesDeltaParams {
                    select: Some(DELTA_SELECT),
                    filter: None,
                    expand: None,
                    max_page_size: Some(GRAPH_PAGE),
                };
                MsgraphMessagesDelta::with_params(&self.auth, "me", Some(self.folder), &params)
                    .map_err(|err| err.to_string())?
            }
        };
        match self.client.run_msgraph(coroutine) {
            Ok(page) => Ok(Some(page)),
            Err(err) if err.status == Some(410) && link.is_some() => Ok(None),
            Err(err) => Err(err),
        }
    }

    /// `$batch` calls of 20 GETs; a GET the batch could not serve
    /// (throttled inside it, say) is sent again on its own, where the
    /// transport rides out the throttling.
    fn read(&mut self, ids: &[String]) -> Result<Vec<MsgraphMessage>, BridgeError> {
        let mut read = Vec::with_capacity(ids.len());
        let mut again = Vec::new();
        for chunk in ids.chunks(MSGRAPH_BATCH_MAX_REQUESTS) {
            let requests: Vec<MsgraphBatchRequest> = chunk
                .iter()
                .enumerate()
                .map(|(index, id)| {
                    Ok(MsgraphBatchRequest {
                        id: index.to_string(),
                        method: String::from("GET"),
                        url: relative(&message_url(id)?),
                        ..Default::default()
                    })
                })
                .collect::<Result<_, BridgeError>>()?;
            let coroutine =
                MsgraphBatch::new(&self.auth, &requests).map_err(|err| err.to_string())?;
            let replies = self.client.run_msgraph(coroutine)?;
            for reply in replies.responses {
                let Some(id) = reply
                    .id
                    .parse::<usize>()
                    .ok()
                    .and_then(|index| chunk.get(index))
                else {
                    continue;
                };
                match reply.status {
                    404 => (),
                    200..300 => match reply.parse::<MsgraphMessage>() {
                        Ok(message) => read.push(message),
                        Err(_) => again.push(id.clone()),
                    },
                    _ => again.push(id.clone()),
                }
            }
        }

        for id in again {
            match self.client.run_msgraph(MsgraphSend::<MsgraphMessage>::get(
                &self.auth,
                message_url(&id)?,
            )) {
                Ok(message) => read.push(message),
                Err(err) if err.status == Some(404) => (),
                Err(err) => return Err(err),
            }
        }
        Ok(read)
    }
}

/// The address one message is read at by id: the summary `$select` and
/// its `$expand`, percent-encoded.
fn message_url(id: &str) -> Result<Url, BridgeError> {
    let mut url = graph_url(&format!("me/messages/{id}"))?;
    url.query_pairs_mut()
        .append_pair("$select", MESSAGE_SELECT)
        .append_pair("$expand", MESSAGE_EXPAND);
    Ok(url)
}

/// The markers of one Graph message, named the IMAP way.
fn marks(message: &MsgraphMessage) -> Vec<String> {
    let flagged = message
        .flag
        .as_ref()
        .and_then(|flag| flag.flag_status.as_ref())
        .is_some_and(|status| *status == MsgraphFlagStatus::Flagged);
    flags(message.is_read.unwrap_or(false), false, flagged)
}

/// One Graph message, named by the summary its `$select` read, when its
/// `Date` falls in the scope.
fn named(message: MsgraphMessage, scope: &Scope, items: &mut Vec<Named>) {
    let summary = graph_summary(&message);
    if !scope.contains(summary.date.as_deref()) {
        return;
    }
    let flags = marks(&message);
    items.push(Named::new(message.id, flags, summary));
}

/// The Annex A summary of one Graph message, read off its `$select`.
///
/// The date is the `Date` header (`sentDateTime`), never the reception;
/// the attachment mark is Graph's own `hasAttachments`; the size is the
/// MAPI one [`MESSAGE_EXPAND`] asks for, the only property it expands.
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
        size: message
            .single_value_extended_properties
            .first()
            .and_then(|size| size.value.trim().parse().ok()),
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

/// The `permanentDelete` action on one message (Graph v1.0).
fn permanent_delete_url(id: &str) -> Result<Url, BridgeError> {
    parse_graph_url(&format!(
        "{MSGRAPH_API_BASE}me/messages/{id}/permanentDelete"
    ))
}

#[cfg(test)]
#[path = "graph_mail_tests.rs"]
mod sync_tests;

#[cfg(test)]
mod tests {
    use io_msgraph::v1::rest::users::{
        contacts::MsgraphSingleValueExtendedProperty,
        messages::{MsgraphEmailAddress, MsgraphMessage, MsgraphRecipient},
    };

    use super::{graph_summary, message_url, permanent_delete_url};
    use crate::client::graph::relative;

    #[test]
    fn a_permanent_delete_posts_the_action() {
        assert_eq!(
            permanent_delete_url("AAMk-1=").unwrap().as_str(),
            "https://graph.microsoft.com/v1.0/me/messages/AAMk-1=/permanentDelete"
        );
    }

    #[test]
    fn a_read_by_id_expands_the_size_percent_encoded() {
        let url = message_url("AAMk-1=").unwrap();
        let expand = url
            .query_pairs()
            .find(|(key, _)| key == "$expand")
            .map(|(_, value)| value.into_owned());
        assert_eq!(
            expand.as_deref(),
            Some("singleValueExtendedProperties($filter=id eq 'Integer 0x0E08')")
        );

        // NOTE: a batch names it relative to the API version, no raw
        // space left in it.
        let batched = relative(&url);
        assert!(batched.starts_with("/me/messages/AAMk-1=?%24select="));
        assert!(!batched.contains(' '), "got: {batched}");
    }

    #[test]
    fn the_size_is_the_expanded_mapi_size() {
        let sized = |value: &str| MsgraphMessage {
            id: "m1".into(),
            single_value_extended_properties: vec![MsgraphSingleValueExtendedProperty {
                id: "Integer 0xe08".into(),
                value: value.into(),
            }],
            ..Default::default()
        };
        assert_eq!(graph_summary(&sized("48213")).size, Some(48213));
        assert_eq!(graph_summary(&sized("large")).size, None, "unparsable");
        assert_eq!(graph_summary(&sized("-1")).size, None, "negative");

        let bare = MsgraphMessage {
            id: "m2".into(),
            ..Default::default()
        };
        assert_eq!(graph_summary(&bare).size, None, "not expanded");
    }

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
