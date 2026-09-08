//! Message composition: the composer's fields to the RFC 5322 bytes a
//! submission hands over.
//!
//! Built here rather than in Java for the reason every other document in
//! this app is: the wire format is the library layer's business, and a
//! second implementation of header encoding on the other side of the JNI
//! boundary would be a second place for it to be wrong.
//!
//! Plain text only, and deliberately: a composer that offers no
//! formatting has nothing to express in HTML, and a `multipart/alternative`
//! carrying the same text twice is a larger message saying the same
//! thing. Attachments are the reason that will change.

use std::fmt::Write as _;

use serde::Deserialize;

use crate::types::BridgeError;

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
}

/// One composed message: its bytes, and the envelope they are handed
/// over with.
///
/// The envelope is not derived from the headers by the reader of this
/// struct, because it must not be: a blind copy is a recipient the
/// headers deliberately do not name, and deriving would either lose it
/// or disclose it.
pub struct Composed {
    /// The complete RFC 5322 message, headers and body.
    pub message: Vec<u8>,
    /// Who it is from, for `MAIL FROM`.
    pub sender: String,
    /// Everyone it goes to, for one `RCPT TO` each.
    pub recipients: Vec<String>,
}

/// Composes one draft into the message and the envelope it is sent with.
pub fn compose(draft: &Draft) -> Result<Composed, BridgeError> {
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
    headers.push_str(&header("From", &mailbox(&draft.from_name, &sender)));
    if !to.is_empty() {
        headers.push_str(&header("To", &to.join(", ")));
    }
    if !cc.is_empty() {
        headers.push_str(&header("Cc", &cc.join(", ")));
    }
    headers.push_str(&header("Subject", &draft.subject));
    headers.push_str("MIME-Version: 1.0\r\n");
    headers.push_str("Content-Type: text/plain; charset=utf-8\r\n");
    headers.push_str("Content-Transfer-Encoding: quoted-printable\r\n");

    let mut message = headers.into_bytes();
    message.extend_from_slice(b"\r\n");
    message.extend_from_slice(quoted_printable(&draft.body).as_bytes());

    let mut recipients = to;
    recipients.extend(cc);
    recipients.extend(bcc);

    Ok(Composed {
        message,
        sender,
        recipients,
    })
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
fn base64(bytes: &[u8]) -> String {
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
        }
    }

    fn text(draft: &Draft) -> String {
        String::from_utf8(compose(draft).unwrap().message).unwrap()
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
        let composed = compose(&draft()).unwrap();
        let message = String::from_utf8(composed.message).unwrap();

        assert!(message.contains("Date: Mon, 5 Jan 2026 09:00:00 +0000\r\n"));
        assert!(message.contains("Message-ID: <abc@pimalaya>\r\n"));
        assert!(message.contains("From: ada@example.org\r\n"));
        assert!(message.contains("To: bob@example.com\r\n"));
        assert!(message.contains("Subject: Hello\r\n"));
        assert!(message.ends_with("\r\n\r\nHi there\r\n"));

        assert_eq!(composed.sender, "ada@example.org");
        assert_eq!(composed.recipients, ["bob@example.com"]);
    }

    #[test]
    fn a_blind_copy_reaches_the_envelope_and_no_header() {
        let mut draft = draft();
        draft.cc = "cc@example.com".into();
        draft.bcc = "hidden@example.com, second@example.com".into();

        let composed = compose(&draft).unwrap();
        let message = String::from_utf8(composed.message).unwrap();

        assert!(message.contains("Cc: cc@example.com\r\n"));
        assert!(
            !message.contains("hidden@example.com"),
            "a blind copy must not be named in the headers"
        );
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
                    decoded.push_str(&String::from_utf8(decode_base64(encoded)).unwrap())
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

    /// The inverse of [`base64`], for the round trip one test needs.
    fn decode_base64(encoded: &str) -> Vec<u8> {
        const ALPHABET: &[u8; 64] =
            b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

        let mut bytes = Vec::new();
        let mut bits = 0u32;
        let mut held = 0;

        for character in encoded.bytes().filter(|byte| *byte != b'=') {
            let sextet = ALPHABET
                .iter()
                .position(|entry| *entry == character)
                .unwrap();
            bits = (bits << 6) | sextet as u32;
            held += 6;
            if held >= 8 {
                held -= 8;
                bytes.push((bits >> held) as u8);
            }
        }
        bytes
    }
}
