//! Payloads threaded across the JNI boundary.

use core::fmt;

use serde::{Deserialize, Serialize};

/// One failed bridge operation, serialized as the error reply the
/// Java client parses: the message every layer displays, plus the
/// HTTP status when the failure was an HTTP round. Java branches on
/// the status (412 retries unguarded, 404 converges a removal, 401
/// refreshes the token), so it crosses as its own field instead of
/// riding the message prose.
#[derive(Debug, Serialize)]
pub struct BridgeError {
    /// Human-readable failure message.
    #[serde(rename = "error")]
    pub message: String,
    /// Status of the failed HTTP round, absent on non-HTTP failures.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub status: Option<u16>,
}

impl fmt::Display for BridgeError {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        self.message.fmt(f)
    }
}

impl From<String> for BridgeError {
    fn from(message: String) -> Self {
        Self {
            message,
            status: None,
        }
    }
}

impl From<&str> for BridgeError {
    fn from(message: &str) -> Self {
        message.to_string().into()
    }
}

#[cfg(test)]
mod tests {
    use super::BridgeError;

    #[test]
    fn bridge_error_serializes_the_wire_shape() {
        let plain: BridgeError = "Invalid URL".into();
        assert_eq!(
            serde_json::to_string(&plain).unwrap(),
            r#"{"error":"Invalid URL"}"#,
        );

        let http = BridgeError {
            message: "WebDAV server returned HTTP 412".into(),
            status: Some(412),
        };
        assert_eq!(
            serde_json::to_string(&http).unwrap(),
            r#"{"error":"WebDAV server returned HTTP 412","status":412}"#,
        );
    }
}

/// Borrowed account credentials threaded through one connection. An
/// empty login means the password field carries an OAuth 2.0 access
/// token, authenticated as Bearer instead of Basic.
pub struct Credentials<'a> {
    /// Account login, empty when the password carries a Bearer token.
    pub login: &'a str,
    /// Account password, or an OAuth 2.0 access token when login is empty.
    pub password: &'a str,
}

/// One CardDAV addressbook surfaced to the Java client.
#[derive(Serialize)]
pub struct Addressbook {
    /// Last non-empty path segment of the collection URL.
    pub id: String,
    /// Human-readable name (display name, falling back to the id).
    pub name: String,
    /// Absolute collection URL, the target of every card operation.
    pub url: String,
    /// Free-form description, when the server exposes one.
    pub description: Option<String>,
    /// Display colour (`#RRGGBB`), when the server exposes one.
    pub color: Option<String>,
}

/// One CalDAV calendar surfaced to the Java client.
///
/// The same five fields as [`Addressbook`], and kept a separate type
/// rather than shared: the two are the same shape today by coincidence
/// of both being WebDAV collections, and a calendar grows a default
/// time zone and a component set that an addressbook never will.
#[derive(Serialize)]
pub struct Calendar {
    /// Last non-empty path segment of the collection URL.
    pub id: String,
    /// Human-readable name (display name, falling back to the id).
    pub name: String,
    /// Absolute collection URL, the target of every event operation.
    pub url: String,
    /// Free-form description, when the server exposes one.
    pub description: Option<String>,
    /// Display colour (`#RRGGBB`), when the server exposes one.
    pub color: Option<String>,
}

/// One calendar item surfaced to the Java client, raw.
///
/// The iCalendar text is passed through unparsed: the expansion of a
/// recurring event depends on the window being rendered, so decoding
/// happens where that window is known rather than here.
#[derive(Serialize)]
pub struct Event {
    /// Display identifier (resource name with any `.ics` stripped).
    pub id: String,
    /// Entity tag guarding concurrent updates, when the server sent one.
    pub etag: Option<String>,
    /// Raw iCalendar text, one VCALENDAR.
    pub ical: String,
}

/// One message's envelope spine, surfaced to the Java client.
///
/// The spine only: no body and no MIME structure, just the one bit of it
/// a row renders ([`Message::has_attachment`]). A merged mail list draws
/// exactly these fields, and fetching bodies for every message of every
/// mailbox to draw a list would be the wrong trade.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Message {
    /// The mailbox the message was listed from.
    pub mailbox: String,
    /// What the backend addresses the message by, within its mailbox:
    /// the IMAP UID as text, the opaque Email id on JMAP. A string
    /// rather than a number because only one of the two is one.
    pub id: String,
    /// Decoded `Subject`, empty when the message carries none.
    pub subject: String,
    /// The first `From` display name, empty when the sender sent none.
    /// Kept apart from the address rather than folded into one label,
    /// because a row shows the name while the avatar beside it is
    /// derived from the address, which is the half that never changes.
    pub from: String,
    /// The first `From` address itself, empty when the envelope carries
    /// no sender at all.
    pub from_address: String,
    /// The envelope `Date`, still RFC 5322 text.
    pub date: String,
    /// Whether the message carries `\Seen` (JMAP `$seen`).
    pub seen: bool,
    /// Whether the message carries `\Answered` (JMAP `$answered`).
    pub answered: bool,
    /// Whether the message carries `\Flagged` (JMAP `$flagged`).
    pub flagged: bool,
    /// Whether any MIME part is dispositioned as an attachment.
    pub has_attachment: bool,
}

/// One message read whole: the headers a reader sees and the one body
/// part they read, surfaced to the Java client.
///
/// The MIME tree is resolved here rather than crossing the bridge,
/// because picking the part to show is a decision about the message
/// (the richest alternative wins, HTML over text) and not about the
/// screen showing it. What crosses is one body and the list of what
/// hangs off it.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageBody {
    /// Decoded `Subject`, empty when the message carries none.
    pub subject: String,
    /// The first `From` display name, empty when there is none.
    pub from: String,
    /// The first `From` address itself.
    pub from_address: String,
    /// Every `To` address, comma separated, as a header reads.
    pub to: String,
    /// Every `Cc` address, comma separated.
    pub cc: String,
    /// When the message was sent, RFC 3339, empty when it carries no
    /// readable date. Normalised rather than raw, since one of the two
    /// backends never had an RFC 5322 header to hand over; the Java
    /// side reads both spellings through the one parser it already has.
    pub date: String,
    /// What `body` holds: `html`, `plain`, or empty for neither.
    pub kind: String,
    /// The body itself, decoded to text.
    pub body: String,
    /// What the message carries beside its body.
    pub attachments: Vec<MessageAttachment>,
}

/// One attachment of a message, named and measured but not carried.
///
/// The bytes stay on the server: the reader shows attachments as badges
/// saying what is there, and downloading one is a separate act that has
/// somewhere to put it.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageAttachment {
    /// The file name, or an empty string when the part names none.
    pub name: String,
    /// The media type, lowercased.
    pub mime: String,
    /// The size in octets, zero when the source does not report one.
    pub size: u64,
}

/// Incremental changes of one collection since a sync cursor: the
/// changed cards (spine-only or full, per backend), the removed
/// resource names, the next cursor to checkpoint, and whether the
/// round listed the complete member set.
#[derive(Serialize)]
pub struct CardDelta {
    /// Cards created or updated since the cursor.
    pub changed: Vec<Card>,
    /// Resource names removed since the cursor.
    pub vanished: Vec<String>,
    /// The next cursor, when the backend issued one.
    pub token: Option<String>,
    /// True when the round listed the complete member set (an initial
    /// round, or an expired cursor re-run as one).
    pub complete: bool,
}

/// One change of a batched push round, handed down by the Java driver.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct PushChange {
    /// Opaque correlation key the outcome echoes back (the engine
    /// handle in practice).
    #[serde(rename = "ref")]
    pub reference: String,
    /// One of create, update, books (membership patch) or destroy.
    pub op: String,
    /// The card id addressed by update, books and destroy.
    pub id: Option<String>,
    /// The full vCard of create and update.
    pub vcard: Option<String>,
    /// The state last synced with the server, trimming update patches
    /// to the fields the edit changed.
    pub base_vcard: Option<String>,
    /// Book ids a books op adds the card to.
    #[serde(default)]
    pub add: Vec<String>,
    /// Book ids a books op removes the card from.
    #[serde(default)]
    pub remove: Vec<String>,
}

/// One change's outcome of a batched push round.
#[derive(Default, Serialize)]
pub struct PushOutcome {
    /// The correlation key of the change this outcome answers.
    #[serde(rename = "ref")]
    pub reference: String,
    /// Whether the server took the change; a rejected change is
    /// reconciled by the next sync instead of failing the round.
    pub accepted: bool,
    /// The server-assigned id of an accepted create.
    #[serde(skip_serializing_if = "Option::is_none")]
    pub id: Option<String>,
    /// The revision of the accepted write, when the backend has one
    /// (the Graph changeKey; JMAP revisions only exist fetch-side).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub etag: Option<String>,
    /// What the server objected, on a rejected change (driver-side
    /// logging only).
    #[serde(skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// One vCard surfaced to the Java client.
#[derive(Serialize)]
pub struct Card {
    /// Display identifier (resource name with any `.vcf` stripped).
    pub id: String,
    /// Resource name exactly as the server returned it, the addressing
    /// key of updates and deletes (servers need not suffix `.vcf`).
    pub uri: String,
    /// Entity tag guarding concurrent updates, when the server sent one.
    pub etag: Option<String>,
    /// Raw vCard text.
    pub vcard: String,
    /// Ids of the addressbooks the card is a member of, on the
    /// backends whose cards are account-level with m:n memberships
    /// (JMAP AddressBook ids, Google contact group ids). Empty on the
    /// backends whose cards live in the one collection they were
    /// listed from (CardDAV, Graph).
    #[serde(skip_serializing_if = "Vec::is_empty")]
    pub books: Vec<String>,
}
