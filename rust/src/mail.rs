//! Message composition and reading: the composer's fields to the RFC
//! 5322 bytes a submission hands over, and a stored message back to what
//! a reader draws.
//!
//! Built here rather than in Java for the reason every other document in
//! this app is: the wire format is the library layer's business, and a
//! second implementation of header encoding on the other side of the JNI
//! boundary would be a second place for it to be wrong.
//!
//! Neither half speaks a protocol, which is why they share a module the
//! backends have no part in: reading resolves the MIME tree of bytes
//! whichever server sent them, so a message the store already holds is
//! read exactly as one just fetched is.
//!
//! Composition is plain text only, and deliberately: a composer that
//! offers no formatting has nothing to express in HTML, and a
//! `multipart/alternative` carrying the same text twice is a larger
//! message saying the same thing. Attachments are the reason that will
//! change.

use std::{collections::HashMap, fmt::Write as _};

use mail_parser::{Address, Message, MessageParser, MessagePartId, MimeHeaders, PartType};
use serde::Deserialize;

use crate::types::{BridgeError, MessageAttachment, MessageBody};

/// How long a header line may run before it folds (RFC 5322 section
/// 2.1.1 recommends 78, and requires no more than 998).
const MAX_LINE: usize = 78;

/// The most bytes one encoded word may carry, base64 included, so the
/// line it sits on stays under [`MAX_LINE`] (RFC 2047 section 2 caps an
/// encoded word at 75 anyway).
const MAX_ENCODED_WORD: usize = 45;

/// What the composer hands over: the envelope, the headers a reader
/// sees, and the text.
#[derive(Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct Draft {
    /// The sender, as the address alone.
    pub from: String,
    /// The sender's display name, empty when there is none.
    #[serde(default)]
    pub from_name: String,
    /// The recipients, comma separated as the composer's field spells
    /// them.
    pub to: String,
    /// The carbon copies, comma separated; empty when there are none.
    #[serde(default)]
    pub cc: String,
    /// The blind carbon copies, which reach the envelope and no header.
    #[serde(default)]
    pub bcc: String,
    #[serde(default)]
    pub subject: String,
    #[serde(default)]
    pub body: String,
    /// Now, as an RFC 5322 date. Passed in rather than read here, so
    /// this stays a pure function of its inputs, as the calendar side's
    /// stamp is.
    pub date: String,
    /// The `Message-ID` to stamp it with, angle brackets included.
    pub message_id: String,
    /// The `Message-ID` of the message this replies to, angle brackets
    /// included; empty when it replies to none.
    #[serde(default)]
    pub in_reply_to: String,
    /// The thread this reply continues, oldest first, as space separated
    /// msg-ids (RFC 5322 section 3.6.4); empty outside a reply.
    #[serde(default)]
    pub references: String,
}

/// One message about to be handed over: the bytes a server receives, and
/// the envelope it is handed over with.
///
/// The bytes carry no `Bcc`, because the recipients that header names are
/// the ones nobody else may learn about; the envelope carries them, which
/// is the only way a blind copy is neither lost nor disclosed.
pub struct Composed {
    /// The complete RFC 5322 message, headers and body.
    pub message: Vec<u8>,
    /// Who it is from, for `MAIL FROM`.
    pub sender: String,
    /// Everyone it goes to, for one `RCPT TO` each.
    pub recipients: Vec<String>,
}

/// Composes one draft into the RFC 5322 message an outbox holds.
///
/// The message a submission hands over is [`envelope`]'s job, not this
/// one's: what comes out here carries a `Bcc` header, which section 3.6.3
/// provides for a message prepared for sending precisely so that the
/// blind recipients survive until whoever sends it strips them. It is
/// what lets the outbox hold one self-contained document rather than a
/// message and a list beside it.
pub fn compose(draft: &Draft) -> Result<Vec<u8>, BridgeError> {
    let sender = addresses(&draft.from)
        .into_iter()
        .next()
        .ok_or_else(|| BridgeError::from("The message has no sender"))?;

    let to = addresses(&draft.to);
    let cc = addresses(&draft.cc);
    let bcc = addresses(&draft.bcc);
    if to.is_empty() && cc.is_empty() && bcc.is_empty() {
        return Err("The message has no recipient".into());
    }

    let mut headers = String::new();
    let _ = write!(headers, "Date: {}\r\n", draft.date);
    let _ = write!(headers, "Message-ID: {}\r\n", draft.message_id);
    if !draft.in_reply_to.is_empty() {
        let _ = write!(headers, "In-Reply-To: {}\r\n", draft.in_reply_to);
    }
    // NOTE: one msg-id per line, folded, so a long thread never runs a
    // line past what section 2.1.1 allows.
    let references: Vec<&str> = draft.references.split_whitespace().collect();
    if !references.is_empty() {
        let _ = write!(headers, "References: {}\r\n", references.join("\r\n "));
    }
    headers.push_str(&header("From", &mailbox(&draft.from_name, &sender)));
    if !to.is_empty() {
        headers.push_str(&header("To", &to.join(", ")));
    }
    if !cc.is_empty() {
        headers.push_str(&header("Cc", &cc.join(", ")));
    }
    if !bcc.is_empty() {
        headers.push_str(&header("Bcc", &bcc.join(", ")));
    }
    headers.push_str(&header("Subject", &draft.subject));
    headers.push_str("MIME-Version: 1.0\r\n");
    headers.push_str("Content-Type: text/plain; charset=utf-8\r\n");
    headers.push_str("Content-Transfer-Encoding: quoted-printable\r\n");

    let mut message = headers.into_bytes();
    message.extend_from_slice(b"\r\n");
    message.extend_from_slice(quoted_printable(&draft.body).as_bytes());

    Ok(message)
}

/// One stored message as it is handed over: the envelope its address
/// headers name, and the bytes with the `Bcc` header taken back out.
///
/// The reverse of [`compose`], and the step RFC 5322 section 3.6.3 asks
/// of whoever sends the message: the blind recipients become `RCPT TO`
/// commands and leave the document, so no copy any recipient receives
/// names them.
pub fn envelope(raw: &[u8]) -> Result<Composed, BridgeError> {
    let parsed = MessageParser::default()
        .parse(raw)
        .ok_or_else(|| BridgeError::from("Could not read the message"))?;

    let sender = parsed
        .from()
        .and_then(|address| address.first())
        .and_then(|address| address.address.as_deref())
        .ok_or_else(|| BridgeError::from("The message has no sender"))?
        .to_string();

    let mut recipients = Vec::new();
    for header in [parsed.to(), parsed.cc(), parsed.bcc()] {
        recipients.extend(envelope_addresses(header));
    }
    if recipients.is_empty() {
        return Err("The message has no recipient".into());
    }

    Ok(Composed {
        message: without_bcc(raw),
        sender,
        recipients,
    })
}

/// The bare addresses of one address header, for the envelope.
fn envelope_addresses(header: Option<&Address>) -> Vec<String> {
    let Some(header) = header else {
        return Vec::new();
    };

    header
        .clone()
        .into_list()
        .into_iter()
        .filter_map(|address| address.address.map(|address| address.into_owned()))
        .filter(|address| !address.is_empty())
        .collect()
}

/// The message with its `Bcc` header and that header's folded
/// continuation lines removed, the body untouched.
///
/// Done on the bytes rather than by recomposing, because what goes out
/// has to be what was stored: re-encoding a message to drop one header
/// would re-encode the other headers with it, and the copy the sender
/// keeps would stop matching the copy the recipients got.
fn without_bcc(raw: &[u8]) -> Vec<u8> {
    let mut kept = Vec::with_capacity(raw.len());
    let mut read = 0;
    let mut dropping = false;

    for line in raw.split_inclusive(|byte| *byte == b'\n') {
        read += line.len();

        let text = line.strip_suffix(b"\n").unwrap_or(line);
        let text = text.strip_suffix(b"\r").unwrap_or(text);

        // The blank line ends the header block; everything after it is
        // body, where a line starting with `Bcc:` is just text.
        if text.is_empty() {
            kept.extend_from_slice(&raw[read - line.len()..]);
            return kept;
        }

        let folded = matches!(text.first(), Some(b' ' | b'\t'));
        if !folded {
            dropping = text.len() >= 4 && text[..4].eq_ignore_ascii_case(b"bcc:");
        }
        if !dropping {
            kept.extend_from_slice(line);
        }
    }

    kept
}

/// One header value as a reader sees it: RFC 2047 encoded words decoded,
/// everything else left as it came.
///
/// The counterpart of [`header`], what proves its encoded words read back.
///
/// A listing no longer needs it: every connector names a message through
/// io-pimdir's Annex A derivation, which decodes the header fields it
/// reads. It stays as the round-trip witness of [`header`], decoding a
/// synthetic header through the parser a reader uses.
#[cfg(test)]
pub fn decode_header(raw: &str) -> String {
    // A value carrying no encoded word is returned untouched, which is
    // most of them: it spares the parse, and it guarantees that a header
    // needing nothing done to it comes back byte for byte.
    if !raw.contains("=?") {
        return raw.into();
    }

    // NOTE: a line break would end the synthetic header and let whatever
    // follows read as one of its own. An ENVELOPE value is unfolded
    // already, so this never fires; it is here because the cost of being
    // wrong about that is a header injected from a message.
    let value: String = raw
        .chars()
        .map(|char| match char {
            '\r' | '\n' => ' ',
            other => other,
        })
        .collect();

    let synthetic = format!("Subject: {value}\r\n\r\n");
    MessageParser::default()
        .parse(synthetic.as_bytes())
        .and_then(|parsed| parsed.subject().map(str::to_string))
        .unwrap_or_else(|| raw.into())
}

/// One raw RFC 5322 message as the reader shows it.
///
/// HTML wins over text when the message carries both, because that is
/// the alternative the sender laid out; the reader sandboxes it, which
/// is what makes preferring it safe.
pub fn parse(raw: &[u8]) -> Result<MessageBody, BridgeError> {
    let parsed = MessageParser::default()
        .parse(raw)
        .ok_or_else(|| BridgeError::from("Could not read the message"))?;

    let sender = parsed.from().and_then(|address| address.first());

    // NOTE: the part itself rather than `body_html`, which renders a
    // text-only message into HTML rather than saying it has none: a
    // message with nothing but text would otherwise reach the reader as
    // markup and be sandboxed in a web view for no reason.
    let html = parsed.html_part(0).and_then(|part| match &part.body {
        PartType::Html(html) => Some(html.to_string()),
        _ => None,
    });
    let (kind, body) = match html {
        Some(html) => ("html", html),
        None => match parsed.body_text(0) {
            Some(text) => ("plain", text.into_owned()),
            None => ("", String::new()),
        },
    };

    Ok(MessageBody {
        subject: parsed.subject().unwrap_or_default().to_string(),
        from: sender
            .and_then(|address| address.name.as_deref())
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .unwrap_or_default()
            .to_string(),
        from_address: sender
            .and_then(|address| address.address.as_deref())
            .unwrap_or_default()
            .to_string(),
        to: header_addresses(parsed.to()),
        cc: header_addresses(parsed.cc()),
        date: parsed
            .date()
            .map(|date| date.to_rfc3339())
            .unwrap_or_default(),
        kind: kind.to_string(),
        body,
        attachments: attachments(&parsed),
        attachment_mark: match io_pimdir::summary::mail::derive(raw).summary {
            Some(io_pimdir::summary::PimdirSummary::Mail(summary)) => {
                summary.attachment.unwrap_or(false)
            }
            _ => false,
        },
    })
}

/// The parts a message carries beside its body, each with its IMAP
/// section, the key pimdir names its stand-in by (STORAGE Annex A.7).
fn attachments(parsed: &Message<'_>) -> Vec<MessageAttachment> {
    let sections = sections(parsed);
    parsed
        .attachments
        .iter()
        .filter_map(|id| {
            let part = parsed.parts.get(*id as usize)?;
            Some(MessageAttachment {
                name: part.attachment_name().unwrap_or_default().to_string(),
                mime: part
                    .content_type()
                    .map(|content| match content.subtype() {
                        Some(subtype) => format!("{}/{subtype}", content.ctype()),
                        None => content.ctype().to_string(),
                    })
                    .unwrap_or_default()
                    .to_lowercase(),
                size: part.contents().len() as u64,
                part: sections.get(id).cloned()?,
            })
        })
        .collect()
}

/// The decoded bytes of one part of a raw message, by its IMAP section:
/// an attachment opened from the body the store holds.
pub fn part(raw: &[u8], section: &str) -> Result<Vec<u8>, BridgeError> {
    let parsed = MessageParser::default()
        .parse(raw)
        .ok_or_else(|| BridgeError::from("Could not read the message"))?;
    let id = sections(&parsed)
        .into_iter()
        .find_map(|(id, held)| (held == section).then_some(id))
        .ok_or_else(|| BridgeError::from(format!("No part {section} in the message")))?;
    Ok(parsed.parts[id as usize].contents().to_vec())
}

/// The IMAP section of every part of a message (RFC 3501 section
/// 6.4.5), by its index in the parser's flat list: a single-part body is
/// `1`, the children of a multipart count from 1 under it. A nested
/// message is one part, its own parts left to its own parse.
fn sections(parsed: &Message<'_>) -> HashMap<MessagePartId, String> {
    let mut sections = HashMap::new();
    match parsed.parts.first().map(|root| &root.body) {
        Some(PartType::Multipart(children)) => number(parsed, children, "", &mut sections),
        Some(_) => {
            sections.insert(0, String::from("1"));
        }
        None => {}
    }
    sections
}

/// Numbers the children of one multipart under `prefix`, recursing into
/// the multiparts among them.
fn number(
    parsed: &Message<'_>,
    children: &[MessagePartId],
    prefix: &str,
    sections: &mut HashMap<MessagePartId, String>,
) {
    for (index, id) in children.iter().enumerate() {
        let section = format!("{prefix}{}", index + 1);
        if let Some(PartType::Multipart(nested)) =
            parsed.parts.get(*id as usize).map(|part| &part.body)
        {
            number(parsed, nested, &format!("{section}."), sections);
        }
        sections.insert(*id, section);
    }
}

/// A header's addresses as one line, the way a header reads them.
fn header_addresses(header: Option<&Address>) -> String {
    let Some(header) = header else {
        return String::new();
    };

    header
        .clone()
        .into_list()
        .iter()
        .map(|address| match address.name.as_deref().map(str::trim) {
            Some(name) if !name.is_empty() => {
                format!(
                    "{name} <{}>",
                    address.address.as_deref().unwrap_or_default()
                )
            }
            _ => address.address.as_deref().unwrap_or_default().to_string(),
        })
        .collect::<Vec<_>>()
        .join(", ")
}

/// One header line: the name, the value encoded where it has to be, and
/// the folds that keep it inside [`MAX_LINE`].
fn header(name: &str, value: &str) -> String {
    let mut line = String::from(name);
    line.push(':');

    let mut width = line.len();
    for word in encode_words(value) {
        // NOTE: folding is a CRLF plus at least one space, and the space
        // is part of the value being folded rather than added to it
        // (RFC 5322 section 2.2.3), so it is written before the word
        // either way and the break goes before it.
        if width + 1 + word.len() > MAX_LINE {
            line.push_str("\r\n");
            width = 0;
        }
        line.push(' ');
        line.push_str(&word);
        width += 1 + word.len();
    }

    line.push_str("\r\n");
    line
}

/// The value split into the words a header folds between, each one
/// already RFC 2047 encoded where it carries anything outside US-ASCII.
///
/// Word by word rather than whole: a header holding one non-ASCII name
/// among ASCII ones encodes that name and leaves the rest readable,
/// which is what section 5 asks for and what a client with no decoder
/// then still shows most of.
fn encode_words(value: &str) -> Vec<String> {
    let mut words = Vec::new();
    let mut pending = String::new();

    for word in value.split_whitespace() {
        if word.is_ascii() {
            if !pending.is_empty() {
                words.extend(encoded_words(&pending));
                pending.clear();
            }
            words.push(word.to_string());
            continue;
        }

        // NOTE: adjacent words that both need encoding go into one run,
        // so the space between them is carried inside the encoding
        // rather than lost: RFC 2047 section 6.2 has a decoder drop the
        // whitespace between two encoded words.
        if !pending.is_empty() {
            pending.push(' ');
        }
        pending.push_str(word);
    }

    if !pending.is_empty() {
        words.extend(encoded_words(&pending));
    }
    words
}

/// One run of non-ASCII text as RFC 2047 base64 encoded words, split so
/// no word runs past [`MAX_ENCODED_WORD`] and no split lands inside a
/// character.
fn encoded_words(text: &str) -> Vec<String> {
    let mut words = Vec::new();
    let mut chunk = String::new();

    for character in text.chars() {
        // NOTE: measured on the encoded length, which is what the cap is
        // about, and on the character rather than the byte, since a word
        // that cut a character in half would decode to nothing legible.
        if base64_len(chunk.len() + character.len_utf8()) > MAX_ENCODED_WORD && !chunk.is_empty() {
            words.push(format!("=?UTF-8?B?{}?=", base64(chunk.as_bytes())));
            chunk.clear();
        }
        chunk.push(character);
    }

    if !chunk.is_empty() {
        words.push(format!("=?UTF-8?B?{}?=", base64(chunk.as_bytes())));
    }
    words
}

/// How many characters base64 turns `bytes` into, padding included.
fn base64_len(bytes: usize) -> usize {
    bytes.div_ceil(3) * 4
}

/// The standard base64 alphabet (RFC 4648 section 4), padded.
///
/// Also how a fetched message crosses the JNI boundary: a Java string is
/// UTF-8 and a message is bytes, so the reply carries the encoding and
/// the Java side decodes it back before storing it.
pub(crate) fn base64(bytes: &[u8]) -> String {
    const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    let mut encoded = String::with_capacity(base64_len(bytes.len()));
    for group in bytes.chunks(3) {
        let bits = (u32::from(group[0]) << 16)
            | (u32::from(group.get(1).copied().unwrap_or(0)) << 8)
            | u32::from(group.get(2).copied().unwrap_or(0));

        for index in 0..4 {
            // NOTE: a group of one encodes two characters and a group of
            // two encodes three; the rest is padding, which is what says
            // how many of the last three bytes were real.
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

/// The inverse of [`base64`]: how a body the Java side holds as bytes
/// crosses back into the engine. Padding is skipped; any other character
/// outside the alphabet refuses the whole input.
pub(crate) fn unbase64(encoded: &str) -> Result<Vec<u8>, String> {
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

/// The body as quoted-printable (RFC 2045 section 6.7): readable where
/// it is ASCII, encoded where it is not, and never a line past 76.
fn quoted_printable(body: &str) -> String {
    let mut encoded = String::with_capacity(body.len());
    let mut width = 0;

    for line in body.split('\n') {
        let line = line.strip_suffix('\r').unwrap_or(line);
        for byte in line.as_bytes() {
            let literal = matches!(byte, 32..=60 | 62..=126);
            let piece = match literal {
                true => String::from(char::from(*byte)),
                false => format!("={byte:02X}"),
            };

            // NOTE: a soft break is itself an `=` plus the CRLF, so it
            // has to fit too: the cap is 76 and the piece plus the `=`
            // is what is measured against it.
            if width + piece.len() > 75 {
                encoded.push_str("=\r\n");
                width = 0;
            }
            encoded.push_str(&piece);
            width += piece.len();
        }

        encoded.push_str("\r\n");
        width = 0;
    }
    encoded
}

/// One address with its display name, encoded where it needs to be.
fn mailbox(name: &str, address: &str) -> String {
    match name.is_empty() {
        true => address.to_string(),
        // NOTE: no quoting around the name. An encoded word must not be
        // inside a quoted string (RFC 2047 section 5), and a name that
        // stays literal is one the encoder left alone because it is
        // plain ASCII text, which needs none either.
        false => format!("{name} <{address}>"),
    }
}

/// The addresses of one comma-separated field, blanks dropped.
///
/// A bare list of addresses, which is what the composer's fields are:
/// display names are the address book's business, and a field that
/// accepted `Ada <ada@example.org>` would have to parse RFC 5322's
/// address grammar to tell the two apart.
fn addresses(field: &str) -> Vec<String> {
    field
        .split(',')
        .map(str::trim)
        .filter(|address| !address.is_empty())
        .map(str::to_string)
        .collect()
}

#[cfg(test)]
mod tests {
    use super::*;

    fn draft() -> Draft {
        Draft {
            from: "ada@example.org".into(),
            from_name: String::new(),
            to: "bob@example.com".into(),
            cc: String::new(),
            bcc: String::new(),
            subject: "Hello".into(),
            body: "Hi there".into(),
            date: "Mon, 5 Jan 2026 09:00:00 +0000".into(),
            message_id: "<abc@pimalaya>".into(),
            in_reply_to: String::new(),
            references: String::new(),
        }
    }

    fn text(draft: &Draft) -> String {
        String::from_utf8(compose(draft).unwrap()).unwrap()
    }

    /// The value of one header, unfolded back into one line.
    fn header_of(message: &str, name: &str) -> String {
        let mut value = String::new();
        let mut inside = false;

        for line in message.lines() {
            if line.starts_with(name) {
                inside = true;
                value.push_str(line.trim_start_matches(name).trim_start_matches(':').trim());
                continue;
            }
            if inside {
                if !line.starts_with(' ') && !line.starts_with('\t') {
                    break;
                }
                value.push(' ');
                value.push_str(line.trim());
            }
        }
        value
    }

    #[test]
    fn a_plain_draft_composes_the_headers_a_reader_needs() {
        let message = text(&draft());

        assert!(message.contains("Date: Mon, 5 Jan 2026 09:00:00 +0000\r\n"));
        assert!(message.contains("Message-ID: <abc@pimalaya>\r\n"));
        assert!(message.contains("From: ada@example.org\r\n"));
        assert!(message.contains("To: bob@example.com\r\n"));
        assert!(message.contains("Subject: Hello\r\n"));
        assert!(message.ends_with("\r\n\r\nHi there\r\n"));

        let composed = envelope(message.as_bytes()).unwrap();
        assert_eq!(composed.sender, "ada@example.org");
        assert_eq!(composed.recipients, ["bob@example.com"]);
    }

    #[test]
    fn a_reply_names_its_parent_and_its_thread() {
        let mut draft = draft();
        draft.in_reply_to = "<parent@example.com>".into();
        draft.references = "<root@example.com> <parent@example.com>".into();

        let message = text(&draft);
        assert!(message.contains("In-Reply-To: <parent@example.com>\r\n"));
        assert_eq!(
            header_of(&message, "References"),
            "<root@example.com> <parent@example.com>"
        );
        assert!(!text(&self::draft()).contains("In-Reply-To"));
    }

    #[test]
    fn a_blind_copy_waits_in_the_message_and_leaves_it_at_the_submission() {
        let mut draft = draft();
        draft.cc = "cc@example.com".into();
        draft.bcc = "hidden@example.com, second@example.com".into();

        // The outbox holds the blind recipients, per RFC 5322 §3.6.3:
        // nothing else remembers them until the message goes out.
        let queued = text(&draft);
        assert!(queued.contains("Bcc: hidden@example.com, second@example.com\r\n"));

        let composed = envelope(queued.as_bytes()).unwrap();
        let sent = String::from_utf8(composed.message).unwrap();

        assert!(sent.contains("Cc: cc@example.com\r\n"));
        assert!(
            !sent.contains("hidden@example.com"),
            "a blind copy must not be named in what the recipients receive"
        );
        assert!(sent.ends_with("\r\n\r\nHi there\r\n"), "the body survived");
        assert_eq!(
            composed.recipients,
            [
                "bob@example.com",
                "cc@example.com",
                "hidden@example.com",
                "second@example.com"
            ]
        );
    }

    #[test]
    fn a_folded_blind_copy_leaves_with_its_continuation_lines() {
        let mut draft = draft();
        draft.bcc = (0..12)
            .map(|index| format!("blind{index}@example.org"))
            .collect::<Vec<_>>()
            .join(", ");

        let queued = text(&draft);
        assert!(queued.contains("Bcc: "), "expected the header");
        assert!(queued.contains("\r\n "), "expected a fold to strip past");

        let composed = envelope(queued.as_bytes()).unwrap();
        let sent = String::from_utf8(composed.message).unwrap();

        assert!(
            !sent.contains("blind"),
            "a folded blind copy must not leave half a header behind"
        );
        assert!(sent.contains("To: bob@example.com\r\n"), "kept the rest");
        assert_eq!(composed.recipients.len(), 13);
    }

    /// The envelope path's whole point: what `header` writes, `decode_header`
    /// reads back. An IMAP `ENVELOPE` hands the wire text over verbatim, so
    /// this is the only thing standing between a sender's name and a row
    /// showing `=?UTF-8?B?...?=`.
    #[test]
    fn an_encoded_header_decodes_back_to_what_was_written() {
        for original in [
            "Réunion générale",
            "Re: déjeuner tomorrow",
            "déjeuner à côté données réunion générale prévue",
            "Ada Lovelace",
            "Ada Løvelace",
        ] {
            // `header` writes "Name: value\r\n", folded; the envelope carries
            // the value alone, unfolded.
            let written = header("Subject", original);
            let value = written
                .trim_start_matches("Subject:")
                .replace("\r\n ", " ")
                .trim()
                .to_string();

            assert_eq!(
                decode_header(&value),
                original,
                "round trip of {original:?}"
            );
        }
    }

    #[test]
    fn a_header_with_nothing_encoded_comes_back_byte_for_byte() {
        // The fast path, and what it guarantees: a value needing nothing
        // done to it must not be normalised on its way through a parser.
        for original in ["Standup at 9", "  spaced  out  ", "", "a ? b = c"] {
            assert_eq!(decode_header(original), original);
        }
    }

    #[test]
    fn a_header_that_only_looks_encoded_is_left_alone() {
        // `=?` with nothing decodable behind it is text, and text is what
        // it has to stay: the alternative is a subject that disappears.
        assert_eq!(decode_header("=?not really?="), "=?not really?=");
    }

    #[test]
    fn a_line_break_in_a_header_cannot_smuggle_another_one() {
        // An ENVELOPE value is unfolded, so this never arises in practice.
        // What it pins is that being wrong about that costs nothing: the
        // break folds into a space rather than ending the header.
        let decoded = decode_header("=?UTF-8?B?w6k=?=\r\nBcc: someone@example.org");
        assert!(decoded.starts_with('é'), "decoded: {decoded:?}");
        assert!(decoded.contains("Bcc: someone@example.org"), "kept as text");
    }

    #[test]
    fn a_body_line_that_reads_like_a_header_is_left_alone() {
        let mut draft = draft();
        draft.body = "Bcc: not a header, just text".into();

        let composed = envelope(text(&draft).as_bytes()).unwrap();
        let sent = String::from_utf8(composed.message).unwrap();

        assert!(sent.ends_with("\r\n\r\nBcc: not a header, just text\r\n"));
    }

    #[test]
    fn a_non_ascii_header_is_encoded_and_the_ascii_around_it_is_not() {
        let mut draft = draft();
        draft.subject = "Re: déjeuner tomorrow".into();
        draft.from_name = "Ada".into();

        let message = text(&draft);
        assert!(message.contains("From: Ada <ada@example.org>\r\n"));
        // "Re:" and "tomorrow" are plain ASCII and stay readable; the one
        // word that is not is the one that encodes.
        assert!(message.contains("Subject: Re: =?UTF-8?B?ZMOpamV1bmVy?= tomorrow\r\n"));
    }

    #[test]
    fn a_run_too_long_for_one_encoded_word_splits_and_decodes_back_whole() {
        let mut draft = draft();
        draft.subject = "déjeuner à côté données réunion générale prévue".into();

        // RFC 2047 §6.2 has a decoder drop the whitespace between two
        // encoded words, so a run that has to split must carry its own
        // spaces inside the encoding rather than between the words.
        let subject = header_of(&text(&draft), "Subject");
        let mut decoded = String::new();
        for word in subject.split(' ') {
            match word
                .strip_prefix("=?UTF-8?B?")
                .and_then(|w| w.strip_suffix("?="))
            {
                Some(encoded) => {
                    decoded.push_str(&String::from_utf8(unbase64(encoded).unwrap()).unwrap())
                }
                None => {
                    decoded.push(' ');
                    decoded.push_str(word);
                    decoded.push(' ');
                }
            }
        }
        assert!(subject.contains("?= =?UTF-8?B?"), "expected a split run");
        assert_eq!(decoded.trim(), draft.subject);
    }

    #[test]
    fn a_long_header_folds_and_no_line_runs_past_the_limit() {
        let mut draft = draft();
        draft.to = (0..12)
            .map(|index| format!("person{index}@example.org"))
            .collect::<Vec<_>>()
            .join(", ");

        let message = text(&draft);
        for line in message.lines() {
            assert!(line.len() <= MAX_LINE, "line too long: {line}");
        }
        // Folded, and every address survived the folding: a header that
        // wraps must still say what it said.
        assert!(message.contains("\r\n "), "expected a fold");
        assert_eq!(header_of(&message, "To"), draft.to);
    }

    #[test]
    fn a_body_is_quoted_printable_and_never_runs_past_seventy_six() {
        let mut draft = draft();
        draft.body = format!("café\n{}", "x".repeat(200));

        let message = text(&draft);
        assert!(message.contains("caf=C3=A9\r\n"));
        for line in message.lines() {
            assert!(line.len() <= 76, "line too long: {line}");
        }
    }

    #[test]
    fn a_draft_with_nobody_to_send_to_is_refused() {
        let mut draft = draft();
        draft.to = "  ,  ".into();

        assert!(compose(&draft).is_err());
    }

    #[test]
    fn eight_bit_bytes_round_trip_through_base64() {
        let bytes: Vec<u8> = (0..=255).collect();

        assert_eq!(unbase64(&base64(&bytes)).unwrap(), bytes);
        assert!(unbase64("not base64!").is_err());
    }

    #[test]
    fn html_wins_over_text_and_attachments_are_listed() {
        let raw = b"From: Alice <alice@example.org>\r\n\
                    To: Bob <bob@example.org>\r\n\
                    Subject: Hello\r\n\
                    Date: Sun, 9 Aug 2026 12:14:00 +0200\r\n\
                    MIME-Version: 1.0\r\n\
                    Content-Type: multipart/mixed; boundary=\"sep\"\r\n\
                    \r\n\
                    --sep\r\n\
                    Content-Type: multipart/alternative; boundary=\"alt\"\r\n\
                    \r\n\
                    --alt\r\n\
                    Content-Type: text/plain\r\n\
                    \r\n\
                    plain body\r\n\
                    --alt\r\n\
                    Content-Type: text/html\r\n\
                    \r\n\
                    <p>rich body</p>\r\n\
                    --alt--\r\n\
                    --sep\r\n\
                    Content-Type: application/pdf; name=\"invoice.pdf\"\r\n\
                    Content-Disposition: attachment; filename=\"invoice.pdf\"\r\n\
                    \r\n\
                    %PDF\r\n\
                    --sep--\r\n";

        let message = parse(raw).unwrap();

        assert_eq!(message.subject, "Hello");
        assert_eq!(message.from, "Alice");
        assert_eq!(message.from_address, "alice@example.org");
        assert_eq!(message.to, "Bob <bob@example.org>");
        assert_eq!(message.kind, "html");
        assert!(message.body.contains("rich body"));
        assert!(message.date.starts_with("2026-08-09T12:14:00"));

        assert_eq!(message.attachments.len(), 1);
        assert_eq!(message.attachments[0].name, "invoice.pdf");
        assert_eq!(message.attachments[0].mime, "application/pdf");
        assert_eq!(message.attachments[0].part, "2");
        assert_eq!(part(raw, "2").unwrap(), b"%PDF");
        assert_eq!(part(raw, "1.2").unwrap(), b"<p>rich body</p>");
        assert!(part(raw, "3").is_err());
    }

    #[test]
    fn a_single_part_attachment_is_section_one_and_decoded() {
        let raw = b"From: alice@example.org\r\n\
                    Content-Type: application/octet-stream\r\n\
                    Content-Disposition: attachment; filename*=UTF-8''r%C3%A9sum%C3%A9.bin\r\n\
                    Content-Transfer-Encoding: base64\r\n\
                    \r\n\
                    AAEC\r\n";

        let message = parse(raw).unwrap();

        assert_eq!(message.attachments.len(), 1);
        assert_eq!(message.attachments[0].name, "résumé.bin");
        assert_eq!(message.attachments[0].part, "1");
        assert_eq!(message.attachments[0].size, 3);
        assert_eq!(part(raw, "1").unwrap(), vec![0, 1, 2]);
    }

    #[test]
    fn a_part_stating_no_type_has_none() {
        let raw = b"From: alice@example.org\r\n\
                    Content-Type: multipart/mixed; boundary=\"sep\"\r\n\
                    \r\n\
                    --sep\r\n\
                    Content-Type: text/plain\r\n\
                    \r\n\
                    text\r\n\
                    --sep\r\n\
                    Content-Disposition: attachment; filename=\"notes\"\r\n\
                    \r\n\
                    notes\r\n\
                    --sep--\r\n";

        let message = parse(raw).unwrap();

        assert_eq!(message.attachments.len(), 1);
        assert_eq!(message.attachments[0].mime, "");
        assert_eq!(message.attachments[0].part, "2");
    }

    #[test]
    fn a_message_with_no_html_falls_back_to_its_text() {
        let raw = b"From: alice@example.org\r\n\
                    Subject: Plain\r\n\
                    \r\n\
                    just text\r\n";

        let message = parse(raw).unwrap();

        assert_eq!(message.kind, "plain");
        assert_eq!(message.body.trim(), "just text");
        // No display name in the header, so the row falls back to the
        // address rather than showing an empty sender.
        assert_eq!(message.from, "");
        assert_eq!(message.from_address, "alice@example.org");
    }
}
