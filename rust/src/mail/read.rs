//! # Reading
//!
//! A raw message resolved for a reader: its headers, the body shown, its
//! attachments by IMAP section, and what the store files of it.
//!
//! No backend takes part: reading works on the bytes whichever server sent
//! them, so a message the store holds reads exactly as one just fetched.
//! The twin of [`crate::mail::compose`], which builds the bytes.

use std::{borrow::Cow, io::Write};

use io_pimdir::{
    hash,
    summary::{
        derive,
        file::PimdirFileSummary,
        mail::{attachments, first_occurrences},
    },
};
use mime::{
    header::marker::{CC, DATE, FROM, SUBJECT, TO},
    tree::{cst::MimeCst, entity::MimeEntity, parse::MimeParseOptions},
    value::address::MimeAddressList,
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
///
/// An empty input is refused, as no message: the parser reads any other
/// bytes as one, a header block or not.
pub fn parse(raw: &[u8]) -> Result<MessageBody, BridgeError> {
    if raw.is_empty() {
        return Err("Could not read the message".into());
    }
    let once = first_occurrences(raw);
    let cst = tree(&once, None);
    let root = &cst.root;
    let lists = root.body_lists();
    let parts = attachments::parts(raw);

    let from = root.header::<FROM>();
    let sender = from.as_ref().and_then(MimeAddressList::first);

    // NOTE: the HTML list holds the text part of a text-only message (RFC
    // 8621 section 4.1.4), which the reader would sandbox in a web view for
    // nothing.
    let html = lists
        .html
        .first()
        .filter(|part| part.content_type().is("text", "html"))
        .and_then(|part| part.text())
        .map(Cow::into_owned);
    let (kind, body) = match html {
        Some(html) => ("html", html),
        None => match lists.text.first().and_then(|part| part.text()) {
            Some(text) => ("plain", text.into_owned()),
            None => ("", String::new()),
        },
    };

    Ok(MessageBody {
        subject: root.header::<SUBJECT>().unwrap_or_default(),
        from: sender
            .and_then(|mailbox| mailbox.name.as_deref())
            .map(str::trim)
            .filter(|name| !name.is_empty())
            .unwrap_or_default()
            .to_string(),
        from_address: sender
            .and_then(|mailbox| mailbox.address.clone())
            .unwrap_or_default(),
        to: header_addresses(root.header::<TO>()),
        cc: header_addresses(root.header::<CC>()),
        date: root
            .header::<DATE>()
            .map(|date| date.to_rfc3339())
            .unwrap_or_default(),
        kind: kind.to_string(),
        body,
        attachments: listed(parts.attachments),
        attachment_mark: parts.attachment,
    })
}

/// The tree of a message as STORAGE Annex A.1 reads its entities, from
/// `once`, the message stating once each field an entity is read by
/// ([`first_occurrences`]): mime-rs reads those from their last occurrence.
///
/// A multipart nothing closes is closed by the end of its entity: where the
/// message's own is, the line break the message ends with is the missing
/// delimiter's, and the tree is read without it. `entities` bounds the
/// parse, [`None`] for mime-rs's own bound.
fn tree(once: &[u8], entities: Option<usize>) -> MimeCst<'_> {
    let opts = || MimeParseOptions {
        max_entities: entities,
        ..Default::default()
    };
    let cst = MimeCst::parse_with(once, opts());
    let unclosed = cst
        .root
        .body
        .multipart()
        .is_some_and(|multipart| multipart.close.is_empty());
    let cut = once
        .strip_suffix(b"\n")
        .map(|rest| rest.strip_suffix(b"\r").unwrap_or(rest));
    match cut {
        Some(cut) if unclosed => MimeCst::parse_with(cut, opts()),
        _ => cst,
    }
}

/// The parts a message carries as attachments, in document order, each with
/// its IMAP section, the key pimdir names its stand-in by.
///
/// From io-pimdir's walk of the parts, so the reader lists exactly the
/// stand-ins the store records: the parts making the attachment mark
/// (STORAGE Annex A.1, §14.3).
fn listed(files: Vec<PimdirFileSummary>) -> Vec<MessageAttachment> {
    files
        .into_iter()
        .filter_map(|file| {
            Some(MessageAttachment {
                name: file.name,
                mime: file.media_type.unwrap_or_default(),
                size: file.size.unwrap_or_default(),
                part: file.part?,
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
    // NOTE: no message, as [`parse`] refuses it.
    if raw.is_empty() {
        return Err("Could not read the message".into());
    }
    // NOTE: no entity bound, as io-pimdir's walk bounds the entities it
    // reads and mime-rs leaves whole a multipart holding one too many.
    let once = first_occurrences(raw);
    let cst = tree(&once, Some(usize::MAX));
    let part = sections(&cst.root)
        .into_iter()
        .find_map(|(part, held)| (held == section).then_some(part))
        .ok_or_else(|| BridgeError::from(format!("No part {section} in the message")))?;
    let bytes = decoded(part);
    out.write_all(&bytes)
        .map_err(|err| BridgeError::from(err.to_string()))?;
    Ok(bytes.len())
}

/// One part's bytes as its sender wrote them, only the
/// `Content-Transfer-Encoding` undone: no charset conversion, which would
/// change a text file's bytes.
///
/// A nested message the parse split is its bytes again, as RFC 2046
/// section 5.2.1 leaves such a part unencoded.
fn decoded<'e>(part: &'e MimeEntity<'_>) -> Cow<'e, [u8]> {
    part.decoded()
        .or_else(|| {
            part.body
                .message()
                .map(|message| Cow::Owned(message.to_bytes()))
        })
        .unwrap_or_default()
}

/// The IMAP section (RFC 3501 section 6.4.5) of every part, in document
/// order.
///
/// A single-part body is `1`, a multipart's children count from 1 under it;
/// a nested message is one part. Walked on a stack, at most [`MAX_DEPTH`].
fn sections<'t, 'a>(root: &'t MimeEntity<'a>) -> Vec<(&'t MimeEntity<'a>, String)> {
    let Some(multipart) = root.body.multipart() else {
        return vec![(root, String::from("1"))];
    };

    // NOTE: each level holds the children it numbers, how many of them
    // are numbered, and the prefix they are numbered under.
    let mut sections = Vec::new();
    let mut levels = vec![(multipart.parts.iter(), 0, String::new())];
    while let Some((children, numbered, prefix)) = levels.last_mut() {
        let Some(part) = children.next() else {
            levels.pop();
            continue;
        };
        *numbered += 1;
        let section = format!("{prefix}{numbered}");
        sections.push((&part.entity, section.clone()));
        if levels.len() < MAX_DEPTH
            && let Some(nested) = part.entity.body.multipart()
        {
            levels.push((nested.parts.iter(), 0, format!("{section}.")));
        }
    }
    sections
}

/// What the store files of one message, read once.
pub fn describe(raw: &[u8]) -> Description {
    let hash = hash::hash(raw);
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
    /// Its content hash under the store's algorithm (BLAKE3, STORAGE §5, the
    /// app's `PimdirHash`).
    pub hash: String,
    /// Its size in octets.
    pub size: usize,
    /// The summary io-pimdir derives from it ([`derivable`]), or null.
    #[serde(serialize_with = "sorted")]
    pub summary: Option<SummaryJson>,
}

/// A header's addresses as one line, the way a header reads them.
fn header_addresses(header: Option<MimeAddressList>) -> String {
    let Some(header) = header else {
        return String::new();
    };

    header
        .mailboxes()
        .map(|mailbox| {
            let address = mailbox.address.as_deref().unwrap_or_default();
            match mailbox.name.as_deref().map(str::trim) {
                Some(name) if !name.is_empty() => format!("{name} <{address}>"),
                _ => address.to_string(),
            }
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
    fn a_multipart_cut_off_reads_what_arrived() {
        let raw = b"Subject: Broken\r\n\
                    Date: yesterday-ish\r\n\
                    Content-Type: multipart/mixed; boundary=\"gone\"\r\n\
                    \r\n\
                    --gone\r\n\
                    Content-Type: text/plain\r\n\
                    \r\n\
                    cut off\r\n";

        let message = parse(raw).unwrap();

        assert_eq!(message.subject, "Broken");
        assert_eq!(message.date, "");
        // NOTE: the end of the message closes the multipart, the line break
        // it ends with being the missing delimiter's (STORAGE Annex A.1).
        assert_eq!(message.body, "cut off");
        assert!(message.attachments.is_empty());
        assert_eq!(describe(raw).attachments.map(|found| found.len()), Some(0));
    }

    #[test]
    fn an_empty_input_is_refused_and_a_headerless_one_read() {
        assert_eq!(
            parse(b"").err().unwrap().to_string(),
            "Could not read the message"
        );
        assert!(describe(b"").attachments.is_none());

        let message = parse(b"\r\njust a body\r\n").unwrap();
        assert_eq!(message.kind, "plain");
        assert_eq!(message.body, "just a body\r\n");
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
            io_pimdir::hash::hash(&raw).0.as_str(),
            "the store's algorithm"
        );
        assert_eq!(described["size"], raw.len());
        assert!(described["summary"].to_string().contains("deep"));
        assert_eq!(described["attachments"][0]["part"], "1.1.1");
    }

    /// The attachments the reader lists and opens are the stand-ins
    /// io-pimdir's summary vectors state (spec/vectors/summaries.json): the
    /// same sections, names, types and sizes, a part opened by its section
    /// measuring what its stand-in states (STORAGE §14.3, Annex A.1).
    #[test]
    fn the_reader_lists_and_opens_the_attachments_the_summary_vectors_state() {
        let spec = crate::offline::tests::io_pimdir_spec_dir();
        let text = std::fs::read_to_string(spec.join("vectors/summaries.json")).unwrap();
        let vectors: serde_json::Value = serde_json::from_str(&text).unwrap();
        assert_eq!(vectors["v"], 3, "summaries.json at another version");

        let mut failures = Vec::new();
        let mut run = 0;
        for case in vectors["cases"].as_array().unwrap() {
            let Some(stated) = case.get("attachments").and_then(|found| found.as_array()) else {
                continue;
            };
            let fixture = case["fixture"].as_str().unwrap();
            let raw = std::fs::read(spec.join("vectors").join(fixture)).unwrap();
            run += 1;

            let listed: Vec<serde_json::Value> = on_java_stack({
                let raw = raw.clone();
                move || parse(&raw).unwrap().attachments
            })
            .into_iter()
            .map(|found| {
                serde_json::json!({
                    "part": found.part,
                    "name": found.name,
                    "media_type": Some(found.mime).filter(|mime| !mime.is_empty()),
                    "size": found.size,
                })
            })
            .collect();
            if &listed != stated {
                failures.push(format!("{fixture}: listed {listed:?}, stated {stated:?}"));
                continue;
            }
            for found in stated {
                let section = found["part"].as_str().unwrap();
                let opened = part(&raw, section).map(|bytes| bytes.len() as u64).ok();
                if opened != found["size"].as_u64() {
                    failures.push(format!("{fixture}: part {section} opens {opened:?} octets"));
                }
            }
        }
        assert!(run >= 40, "{run} cases state their attachments");
        assert!(failures.is_empty(), "{}", failures.join("\n"));
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
