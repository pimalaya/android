//! # Reading
//!
//! A raw message resolved for a reader: its headers, the body shown, its
//! attachments by IMAP section, and what the store files of it.
//!
//! No backend takes part: reading works on the bytes whichever server sent
//! them, so a message the store holds reads exactly as one just fetched.
//! The twin of [`crate::mail::compose`], which builds the bytes.

use std::{borrow::Cow, io::Write};

use io_pimdir::{hash::PimdirHashAlgo, summary::derive};
use mail_parser::{
    Address, Message, MessageParser, MessagePart, MessagePartId, MimeHeaders, PartType,
    decoders::{base64::base64_decode, quoted_printable::quoted_printable_decode},
};
use serde::Serialize;

use crate::{
    error::BridgeError,
    mail::{MAX_DEPTH, MessageAttachment, MessageBody, derivable},
    offline::{summary::SummaryJson, wire::sorted},
};

/// One raw RFC 5322 message as the reader shows it.
///
/// HTML wins over text when the message carries both, being the alternative
/// the sender laid out; the reader sandboxes it, which makes that safe.
pub fn parse(raw: &[u8]) -> Result<MessageBody, BridgeError> {
    let parsed = MessageParser::default()
        .parse(raw)
        .ok_or_else(|| BridgeError::from("Could not read the message"))?;

    let sender = parsed.from().and_then(|address| address.first());

    // NOTE: the part itself, as `body_html` renders a text-only message
    // into HTML, which the reader would sandbox in a web view for nothing.
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
        attachment_mark: match io_pimdir::summary::mail::derive(derivable(raw)).summary {
            Some(io_pimdir::summary::PimdirSummary::Mail(summary)) => {
                summary.attachment.unwrap_or(false)
            }
            _ => false,
        },
    })
}

/// The parts a message carries as attachments, in document order.
///
/// Each carries its IMAP section, the key pimdir names its stand-in by.
/// Exactly the parts making the attachment mark: those carrying
/// `Content-Disposition: attachment` (STORAGE Annex A.1, section 14.3).
fn attachments(parsed: &Message<'_>) -> Vec<MessageAttachment> {
    sections(parsed)
        .into_iter()
        .filter_map(|(id, section)| {
            let part = parsed.parts.get(id as usize)?;
            let attached = part
                .content_disposition()
                .is_some_and(|disposition| disposition.ctype().eq_ignore_ascii_case("attachment"));
            attached.then(|| MessageAttachment {
                name: part.attachment_name().unwrap_or_default().to_string(),
                mime: part
                    .content_type()
                    .map(|content| match content.subtype() {
                        Some(subtype) => format!("{}/{subtype}", content.ctype()),
                        None => content.ctype().to_string(),
                    })
                    .unwrap_or_default()
                    .to_lowercase(),
                size: decoded(parsed, part).len() as u64,
                part: section,
            })
        })
        .collect()
}

/// Writes the decoded bytes of one part, by IMAP section, to `out`, and
/// answers how many.
///
/// How an attachment opens from the body the store holds; the part is
/// written as the parser holds it, never copied first.
pub fn write_part(raw: &[u8], section: &str, out: &mut impl Write) -> Result<usize, BridgeError> {
    let parsed = MessageParser::default()
        .parse(raw)
        .ok_or_else(|| BridgeError::from("Could not read the message"))?;
    let id = sections(&parsed)
        .into_iter()
        .find_map(|(id, held)| (held == section).then_some(id))
        .ok_or_else(|| BridgeError::from(format!("No part {section} in the message")))?;
    let bytes = decoded(&parsed, &parsed.parts[id as usize]);
    out.write_all(&bytes)
        .map_err(|err| BridgeError::from(err.to_string()))?;
    Ok(bytes.len())
}

/// One part's bytes as its sender wrote them, only the
/// `Content-Transfer-Encoding` undone.
///
/// A text part is read from the raw message, as the parser converts it to
/// UTF-8 from its charset, which would change a file's bytes.
fn decoded<'a>(parsed: &'a Message<'_>, part: &'a MessagePart<'_>) -> Cow<'a, [u8]> {
    if !matches!(part.body, PartType::Text(_) | PartType::Html(_)) {
        return Cow::Borrowed(part.contents());
    }
    let raw = parsed
        .raw_message
        .get(part.raw_body_offset() as usize..part.raw_end_offset() as usize)
        .unwrap_or_default();
    let encoding = part
        .content_transfer_encoding()
        .unwrap_or_default()
        .trim()
        .to_ascii_lowercase();
    match encoding.as_str() {
        "base64" => base64_decode(raw).map_or(Cow::Borrowed(raw), Cow::Owned),
        "quoted-printable" => quoted_printable_decode(raw).map_or(Cow::Borrowed(raw), Cow::Owned),
        _ => Cow::Borrowed(raw),
    }
}

/// The IMAP section (RFC 3501 section 6.4.5) of every part, in document
/// order, by index in the parser's flat list.
///
/// A single-part body is `1`, a multipart's children count from 1 under it;
/// a nested message is one part. Walked on a stack, at most [`MAX_DEPTH`].
fn sections(parsed: &Message<'_>) -> Vec<(MessagePartId, String)> {
    let mut sections = Vec::new();
    let root = match parsed.parts.first().map(|root| &root.body) {
        Some(PartType::Multipart(children)) => children,
        Some(_) => {
            sections.push((0, String::from("1")));
            return sections;
        }
        None => return sections,
    };

    // NOTE: each level holds the children it numbers, how many of them
    // are numbered, and the prefix they are numbered under.
    let mut levels: Vec<(&[MessagePartId], usize, String)> = vec![(root, 0, String::new())];
    while let Some((children, numbered, prefix)) = levels.last_mut() {
        let Some(id) = children.get(*numbered).copied() else {
            levels.pop();
            continue;
        };
        *numbered += 1;
        let section = format!("{prefix}{numbered}");
        sections.push((id, section.clone()));
        if levels.len() < MAX_DEPTH
            && let Some(PartType::Multipart(nested)) =
                parsed.parts.get(id as usize).map(|part| &part.body)
        {
            levels.push((nested, 0, format!("{section}.")));
        }
    }
    sections
}

/// What the store files of one message, read once.
pub fn describe(raw: &[u8]) -> Description {
    let hash = PimdirHashAlgo::Sha256_128.hash(raw);
    let summary = derive("message/rfc822", derivable(raw)).and_then(|derived| derived.summary);
    let attachments = parse(raw).ok().map(|message| message.attachments);
    Description {
        attachments,
        hash: hash.0,
        size: raw.len(),
        summary: summary.as_ref().map(SummaryJson::from),
    }
}

/// What the store files of one message ([`describe`]).
#[derive(Serialize)]
pub struct Description {
    /// Its attachments as [`parse`] lists them, null when it cannot parse.
    #[serde(serialize_with = "sorted")]
    pub attachments: Option<Vec<MessageAttachment>>,
    /// Its content hash under the store's algorithm (`sha256-128`, the
    /// app's `PimdirHash`).
    pub hash: String,
    /// Its size in octets.
    pub size: usize,
    /// The summary io-pimdir derives from it ([`derivable`]), or null.
    #[serde(serialize_with = "sorted")]
    pub summary: Option<SummaryJson>,
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

#[cfg(test)]
mod tests {
    use std::thread;

    use io_pimdir::summary::derive;
    use serde_json::to_value;

    use crate::{
        error::BridgeError,
        ffi::tests::assert_key_order,
        mail::{derivable, read::*, tests::nested},
    };

    /// [`write_part`] into memory.
    fn part(raw: &[u8], section: &str) -> Result<Vec<u8>, BridgeError> {
        let mut out = Vec::new();
        write_part(raw, section, &mut out).map(|_| out)
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
    fn only_parts_disposed_as_attachments_are_listed() {
        let raw = b"From: alice@example.org\r\n\
                    Content-Type: multipart/related; boundary=\"sep\"\r\n\
                    \r\n\
                    --sep\r\n\
                    Content-Type: text/html\r\n\
                    \r\n\
                    <img src=\"cid:logo\">\r\n\
                    --sep\r\n\
                    Content-Type: image/png\r\n\
                    Content-Disposition: inline; filename=\"logo.png\"\r\n\
                    \r\n\
                    PNG\r\n\
                    --sep\r\n\
                    Content-Type: text/plain\r\n\
                    Content-Disposition: attachment; filename=\"notes.txt\"\r\n\
                    \r\n\
                    notes\r\n\
                    --sep--\r\n";

        let message = parse(raw).unwrap();

        assert_eq!(message.attachments.len(), 1);
        assert_eq!(message.attachments[0].name, "notes.txt");
        assert_eq!(message.attachments[0].part, "3");
    }

    #[test]
    fn a_text_attachment_keeps_its_bytes() {
        let mut raw = b"From: alice@example.org\r\n\
                    Content-Type: multipart/mixed; boundary=\"sep\"\r\n\
                    \r\n\
                    --sep\r\n\
                    Content-Type: text/plain\r\n\
                    \r\n\
                    body\r\n\
                    --sep\r\n\
                    Content-Type: text/plain; charset=iso-8859-1\r\n\
                    Content-Disposition: attachment; filename=\"latin.txt\"\r\n\
                    Content-Transfer-Encoding: 8bit\r\n\
                    \r\n\
                    caf"
        .to_vec();
        raw.extend_from_slice(b"\xe9\r\n--sep\r\n");
        raw.extend_from_slice(
            b"Content-Type: text/plain; charset=utf-8\r\n\
              Content-Disposition: attachment; filename=\"qp.txt\"\r\n\
              Content-Transfer-Encoding: quoted-printable\r\n\
              \r\n\
              caf=C3=A9\r\n\
              --sep--\r\n",
        );

        let message = parse(&raw).unwrap();

        assert_eq!(part(&raw, "2").unwrap(), b"caf\xe9");
        assert_eq!(message.attachments[0].size, 4);
        assert_eq!(part(&raw, "3").unwrap(), "café".as_bytes());
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
        // NOTE: no display name, so the row falls back to the address.
        assert_eq!(message.from, "");
        assert_eq!(message.from_address, "alice@example.org");
    }

    /// Runs `f` on a 1 MiB stack, an Android Java thread's, where calls run.
    fn on_java_stack<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
        thread::Builder::new()
            .stack_size(1024 * 1024)
            .spawn(f)
            .unwrap()
            .join()
            .unwrap()
    }

    #[test]
    fn a_hostile_mime_depth_reads_on_a_java_stack() {
        let raw = nested(10_000);

        let (message, opened, derived) = on_java_stack(move || {
            let message = parse(&raw).unwrap();
            let opened = part(&raw, "1.1.1").is_ok();
            let derived = derive("message/rfc822", derivable(&raw))
                .and_then(|derived| derived.summary)
                .is_some();
            (message, opened, derived)
        });

        assert_eq!(message.subject, "deep");
        assert!(message.attachments.is_empty());
        assert!(opened);
        assert!(derived);
    }

    #[test]
    fn a_message_is_described_by_one_read() {
        let raw = nested(3);

        let described = describe(&raw);
        assert_key_order(&described);
        let described = to_value(described).unwrap();

        assert_eq!(
            described["hash"],
            PimdirHashAlgo::Sha256_128.hash(&raw).0.as_str(),
            "the store's algorithm"
        );
        assert_eq!(described["size"], raw.len());
        assert!(described["summary"].to_string().contains("deep"));
        assert_eq!(described["attachments"][0]["part"], "1.1.1");
    }

    #[test]
    fn a_reasonable_mime_depth_is_numbered_whole() {
        let raw = nested(3);

        let message = parse(&raw).unwrap();

        assert_eq!(derivable(&raw), &raw[..]);
        assert_eq!(message.attachments.len(), 1);
        assert_eq!(message.attachments[0].part, "1.1.1");
        assert_eq!(part(&raw, "1.1.1").unwrap(), b"bottom");
    }
}
