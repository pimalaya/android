//! # Composition
//!
//! A composer's draft as the RFC 5322 bytes an outbox holds, and a stored
//! message as the envelope and bytes a submission hands over.
//!
//! Built here, not in Java: the wire format is the library layer's
//! business, and header encoding on both sides of JNI would be two places
//! to be wrong. The twin of [`crate::mail::read`], which reads the bytes.
//!
//! Plain text only, deliberately: a composer offering no formatting has
//! nothing for HTML to say, and a `multipart/alternative` repeating the
//! text is a larger message saying the same. Attachments will change that.

use std::fmt::Write as _;

use mail_parser::{Address, MessageParser};
use serde::Deserialize;

use crate::{
    error::BridgeError,
    mail::{base64, base64_len},
};

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
    /// The recipients, comma separated as the composer's field spells them.
    pub to: String,
    /// The carbon copies, comma separated; empty when there are none.
    #[serde(default)]
    pub cc: String,
    /// The blind carbon copies, which reach the envelope and no header.
    #[serde(default)]
    pub bcc: String,
    /// The subject, as typed.
    #[serde(default)]
    pub subject: String,
    /// The text, as typed.
    #[serde(default)]
    pub body: String,
    /// Now, as an RFC 5322 date.
    ///
    /// Passed in rather than read here, so composing stays a pure function
    /// of its inputs.
    pub date: String,
    /// The `Message-ID` to stamp it with, angle brackets included.
    pub message_id: String,
    /// The `Message-ID` replied to, angle brackets included, or empty.
    #[serde(default)]
    pub in_reply_to: String,
    /// The thread this reply continues, oldest first, as space separated
    /// msg-ids (RFC 5322 section 3.6.4); empty outside a reply.
    #[serde(default)]
    pub references: String,
}

/// One message about to be handed over: its bytes and its envelope.
///
/// The bytes carry no `Bcc`, as nobody else may learn those recipients;
/// the envelope carries them, the only way a blind copy is neither lost
/// nor disclosed.
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
/// It keeps its `Bcc` header, as RFC 5322 section 3.6.3 allows a message
/// prepared for sending, so the outbox holds one self-contained document;
/// [`envelope`] strips it at submission.
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
/// The step RFC 5322 section 3.6.3 asks of the sender: blind recipients
/// become `RCPT TO` and leave the document, so no copy received names them.
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
/// On the bytes rather than by recomposing, as re-encoding would touch the
/// other headers too and the sender's copy would stop matching the sent one.
fn without_bcc(raw: &[u8]) -> Vec<u8> {
    let mut kept = Vec::with_capacity(raw.len());
    let mut read = 0;
    let mut dropping = false;

    for line in raw.split_inclusive(|byte| *byte == b'\n') {
        read += line.len();

        let text = line.strip_suffix(b"\n").unwrap_or(line);
        let text = text.strip_suffix(b"\r").unwrap_or(text);

        // NOTE: past the blank line, a line starting with `Bcc:` is body.
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

/// One header line: the name, the value encoded where it has to be, and
/// the folds that keep it inside [`MAX_LINE`].
fn header(name: &str, value: &str) -> String {
    let mut line = String::from(name);
    line.push(':');

    let mut width = line.len();
    for word in encode_words(value) {
        // NOTE: the fold's space belongs to the value (RFC 5322 section
        // 2.2.3), so it is written before the word either way, the CRLF
        // going before it.
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
/// Word by word, so one non-ASCII name among ASCII ones leaves the rest
/// readable, as section 5 asks and a client with no decoder still shows.
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

        // NOTE: adjacent words needing encoding share one run, the space
        // inside it, as a decoder drops the whitespace between two encoded
        // words (RFC 2047 section 6.2).
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
        // NOTE: measured encoded, as the cap is, and per character, as a
        // word cutting one in half would decode to nothing legible.
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

            // NOTE: the soft break's `=` must fit within the 76 too.
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
        // NOTE: unquoted, as an encoded word must not sit in a quoted
        // string (RFC 2047 section 5) and a literal name is plain ASCII.
        false => format!("{name} <{address}>"),
    }
}

/// The addresses of one comma-separated field, blanks dropped.
///
/// The composer's fields are bare addresses: display names are the address
/// book's business, and accepting `Ada <ada@example.org>` would mean
/// parsing RFC 5322's address grammar.
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
    use mail_parser::MessageParser;

    use crate::mail::{compose::*, unbase64};

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

        // NOTE: the outbox holds the blind recipients (RFC 5322 section
        // 3.6.3), as nothing else remembers them until the message goes out.
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

    /// What `header` writes, folded and encoded, a reader decodes back.
    #[test]
    fn an_encoded_header_decodes_back_to_what_was_written() {
        for original in [
            "Réunion générale",
            "Re: déjeuner tomorrow",
            "déjeuner à côté données réunion générale prévue",
            "Ada Lovelace",
            "Ada Løvelace",
        ] {
            let written = format!("{}\r\n", header("Subject", original));
            let parsed = MessageParser::default().parse(written.as_bytes());
            let subject = parsed.as_ref().and_then(|parsed| parsed.subject());

            assert_eq!(subject, Some(original), "round trip of {original:?}");
        }
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
        assert!(message.contains("Subject: Re: =?UTF-8?B?ZMOpamV1bmVy?= tomorrow\r\n"));
    }

    /// A split run carries its spaces inside the encoding, as a decoder
    /// drops those between encoded words (RFC 2047 section 6.2).
    #[test]
    fn a_run_too_long_for_one_encoded_word_splits_and_decodes_back_whole() {
        let mut draft = draft();
        draft.subject = "déjeuner à côté données réunion générale prévue".into();

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
}
