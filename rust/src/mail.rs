//! # Mail
//!
//! The mail domain: mailboxes and messages over IMAP and SMTP, JMAP,
//! Microsoft Graph and Gmail, plus the types and byte helpers they share.
//!
//! [`session::Mail`] routes every verb to the backend of a session held
//! across calls ([`session`]); one file per backend ([`imap`], [`smtp`],
//! [`jmap`], [`msgraph`], [`gmail`]), the account-wide walks of Gmail and
//! JMAP in [`gmail_step`] and [`jmap_step`].
//!
//! Message bytes are the protocol-free part: [`compose`] builds them,
//! [`read`] resolves them for a reader, [`markdown`] renders a rich draft, and
//! [`listing`] is the page every backend answers an `enumerate` yield with.

pub mod compose;
pub mod gmail;
pub mod gmail_step;
pub mod imap;
mod jmap;
pub mod jmap_step;
pub mod listing;
pub mod markdown;
pub mod msgraph;
pub mod read;
pub mod session;
pub mod smtp;

use std::path::{Path, PathBuf};

use mail_parser::{MessageParser, MessagePartId, PartType};
use serde::Serialize;

/// A message's `Message-ID`, its angle brackets taken off, read from its
/// header alone; [`None`] when it carries none.
pub fn message_id(raw: &[u8]) -> Option<String> {
    MessageParser::default()
        .parse_headers(raw)?
        .message_id()
        .filter(|id| !id.is_empty())
        .map(str::to_string)
}

/// How deep a multipart tree is walked; parts nested deeper go unnumbered.
///
/// RFC 2046 sets no limit and hostile mail nests thousands of levels, which
/// a recursive walk on a 1 MiB Java thread overflows (a SIGSEGV no panic
/// guard catches).
pub const MAX_DEPTH: usize = 64;

/// The bytes of a raw message io-pimdir may derive from.
///
/// Its header alone when the tree nests deeper than [`MAX_DEPTH`], as the
/// library walks it recursively; the header still names and summarises it.
/// Counting `multipart` first spares the parse for a shallow message.
pub fn derivable(raw: &[u8]) -> &[u8] {
    let mentions = raw
        .windows(9)
        .filter(|window| window.eq_ignore_ascii_case(b"multipart"))
        .count();
    if mentions < MAX_DEPTH || depth(raw) < MAX_DEPTH {
        return raw;
    }
    let end = raw
        .windows(4)
        .position(|window| window == b"\r\n\r\n")
        .map(|at| at + 4)
        .or_else(|| {
            raw.windows(2)
                .position(|window| window == b"\n\n")
                .map(|at| at + 2)
        })
        .unwrap_or(raw.len());
    &raw[..end]
}

/// How many multipart levels a raw message nests, walked with a stack
/// of its own.
fn depth(raw: &[u8]) -> usize {
    let Some(parsed) = MessageParser::default().parse(raw) else {
        return 0;
    };
    let mut deepest = 0;
    let mut pending: Vec<(MessagePartId, usize)> = vec![(0, 0)];
    while let Some((id, level)) = pending.pop() {
        if let Some(PartType::Multipart(children)) =
            parsed.parts.get(id as usize).map(|part| &part.body)
        {
            deepest = deepest.max(level + 1);
            pending.extend(children.iter().map(|child| (*child, level + 1)));
        }
    }
    deepest
}

/// How many characters base64 turns `bytes` into, padding included.
pub fn base64_len(bytes: usize) -> usize {
    bytes.div_ceil(3) * 4
}

/// The standard base64 alphabet (RFC 4648 section 4), padded.
///
/// Also how a fetched message crosses the JNI boundary: a Java string is
/// UTF-8 and a message is bytes, so Java decodes it back before storing it.
pub fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    let mut encoded = String::with_capacity(base64_len(bytes.len()));
    for group in bytes.chunks(3) {
        let bits = (u32::from(group[0]) << 16)
            | (u32::from(group.get(1).copied().unwrap_or(0)) << 8)
            | u32::from(group.get(2).copied().unwrap_or(0));

        for index in 0..4 {
            // NOTE: a group of n bytes encodes n + 1 characters; the
            // padding says how many of the last three bytes were real.
            if index <= group.len() {
                let sextet = (bits >> (18 - 6 * index)) & 0b11_1111;
                encoded.push(char::from(ALPHABET[sextet as usize]));
            } else {
                encoded.push('=');
            }
        }
    }
    encoded
}

/// The inverse of [`base64`], for bytes crossing from Java into the engine.
///
/// Padding is skipped; any other character outside the alphabet refuses the
/// whole input.
pub fn unbase64(encoded: &str) -> Result<Vec<u8>, String> {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    let mut bytes = Vec::with_capacity(encoded.len() / 4 * 3);
    let mut bits = 0u32;
    let mut held = 0;

    for character in encoded.bytes().filter(|byte| *byte != b'=') {
        let Some(sextet) = ALPHABET.iter().position(|entry| *entry == character) else {
            return Err(format!(
                "Invalid base64 character {:?}",
                char::from(character)
            ));
        };
        bits = (bits << 6) | sextet as u32;
        held += 6;
        if held >= 8 {
            held -= 8;
            bytes.push((bits >> held) as u8);
        }
    }
    Ok(bytes)
}

/// One mailbox the account holds, and what the server says it is for.
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct Mailbox {
    /// The name a collection is keyed by, hierarchical where the backend nests.
    pub name: String,
    /// The mailbox's pimdir role (`inbox`, `sent`, `trash`, ...), or empty.
    ///
    /// From RFC 6154 attributes, RFC 8621 roles, Gmail system labels or Graph
    /// well-known folders. The trash is stored as a delete picks a move or a
    /// marker by it with no network to ask; the others order a pass.
    pub role: String,
}

/// One message read whole: the headers a reader sees and the body shown.
///
/// The MIME tree is resolved here, not in Java: picking the part to show
/// (the richest alternative, HTML over text) is a decision about the
/// message, not the screen. One body crosses, with what hangs off it.
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
    /// readable date.
    ///
    /// Normalised, as not every backend has an RFC 5322 header to hand over.
    pub date: String,
    /// What `body` holds: `html`, `plain`, or empty for neither.
    pub kind: String,
    /// The body itself, decoded to text.
    pub body: String,
    /// What the message carries beside its body.
    pub attachments: Vec<MessageAttachment>,
    /// The attachment mark (pimdir STORAGE Annex A.1) from the walk of parts.
    ///
    /// It replaces the mark a listing read off the top-level `Content-Type`.
    pub attachment_mark: bool,
}

/// One attachment, named and measured but not carried.
///
/// Its bytes stay in the message, read by section ([`read::write_part`]).
#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct MessageAttachment {
    /// The file name, or an empty string when the part names none.
    pub name: String,
    /// The media type, lowercased, parameters dropped; empty when unstated.
    pub mime: String,
    /// The decoded size in octets.
    pub size: u64,
    /// The IMAP section of the part (RFC 3501 section 6.4.5), `2`, `1.2`.
    pub part: String,
}

/// The file a message is written to in `dir`, named by its id kept to
/// characters every filesystem takes.
pub fn source_path(dir: &Path, id: &str) -> PathBuf {
    let name: String = id
        .chars()
        .map(|char| match char {
            'a'..='z' | 'A'..='Z' | '0'..='9' | '-' | '_' => char,
            _ => '_',
        })
        .collect();
    dir.join(format!("{name}.eml"))
}

#[cfg(test)]
pub mod tests {
    use crate::mail::*;

    #[test]
    fn eight_bit_bytes_round_trip_through_base64() {
        let bytes: Vec<u8> = (0..=255).collect();

        assert_eq!(unbase64(&base64(&bytes)).unwrap(), bytes);
        assert!(unbase64("not base64!").is_err());
    }

    /// A message nesting `levels` multipart/mixed parts over an attachment.
    pub fn nested(levels: usize) -> Vec<u8> {
        let mut raw = String::from("From: alice@example.org\r\nSubject: deep\r\n");
        for level in 0..levels {
            raw.push_str(&format!(
                "Content-Type: multipart/mixed; boundary=\"b{level}\"\r\n\r\n--b{level}\r\n"
            ));
        }
        raw.push_str(
            "Content-Type: text/plain\r\n\
             Content-Disposition: attachment; filename=\"deep.txt\"\r\n\r\nbottom\r\n",
        );
        for level in (0..levels).rev() {
            raw.push_str(&format!("--b{level}--\r\n"));
        }
        raw.into_bytes()
    }

    #[test]
    fn a_hostile_mime_depth_is_derived_from_its_header() {
        let raw = nested(MAX_DEPTH);

        assert_eq!(depth(&raw), MAX_DEPTH);
        assert!(derivable(&raw).ends_with(b"\r\n\r\n"));
        assert!(derivable(&raw).len() < 200);
    }
}
