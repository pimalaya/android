//! # SMTP
//!
//! SMTP submission: one message handed over through io-smtp's sans-io
//! coroutines, how an IMAP account's session sends ([`super::session`]).
//!
//! Unlike IMAP, nothing is held between two sends: a send is a whole
//! session (greeting, EHLO, authentication, envelope, data), opened and
//! closed per call.
//!
//! Implicit TLS behind `smtps://`, STARTTLS behind `smtp://` (RFC 3207):
//! the plain socket carries the greeting, `EHLO` and `STARTTLS` and nothing
//! else, the transport upgrading it before anything is authenticated.

use core::fmt::{Display, Formatter, Result as FmtResult};

use io_smtp::{
    coroutine::{SmtpCoroutine, SmtpCoroutineState, SmtpYield},
    message::{SmtpMessageSend, SmtpMessageSendError, SmtpMessageSendOptions},
    rfc3207::starttls::SmtpStartTls,
    rfc5321::{
        SmtpEhloDomain, SmtpForwardPath, SmtpLocalPart, SmtpMailbox, SmtpReversePath,
        SmtpSyntaxError, data::SmtpDataError, ehlo::SmtpEhlo, greeting::SmtpGreetingGet,
        mail::SmtpMailError, quit::SmtpQuit, rcpt::SmtpRcptError,
    },
    sasl::{
        auth_plain::{SmtpAuthPlain, SmtpAuthPlainOptions},
        auth_xoauth2::{SmtpAuthXoauth2, SmtpAuthXoauth2Options},
    },
};
use secrecy::SecretString;
use url::Url;

use crate::{
    client::{Auth, Client, Host, url_user},
    error::BridgeError,
    mail::compose::Composed,
};

/// What the app calls itself in `EHLO`.
///
/// A phone has no name a server could resolve, and RFC 5321 section 4.1.4
/// tells a server not to refuse a message over it; a literal name says
/// what this is rather than claiming a domain that does not exist.
const EHLO_DOMAIN: &str = "pimalaya.android";

/// One SMTP session: the call's client and the URL its socket is keyed by.
pub struct SmtpSession<'a, H> {
    client: &'a mut Client<H>,
    url: String,
}

impl<'a, H: Host> SmtpSession<'a, H> {
    /// A session over `client` to the server at `url`, not yet connected.
    pub fn new(client: &'a mut Client<H>, url: &Url) -> Self {
        Self {
            client,
            url: url.to_string(),
        }
    }

    /// Runs a coroutine to completion over the client's stream for the URL.
    fn run<C, T, E>(&mut self, mut coroutine: C) -> Result<T, BridgeError>
    where
        C: SmtpCoroutine<Yield = SmtpYield, Return = Result<T, E>>,
        E: core::fmt::Display,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(arg.as_deref()) {
                SmtpCoroutineState::Complete(Ok(value)) => return Ok(value),
                SmtpCoroutineState::Complete(Err(err)) => return Err(err.to_string().into()),
                SmtpCoroutineState::Yielded(SmtpYield::WantsRead) => {
                    arg = Some(self.client.read(&self.url)?);
                }
                SmtpCoroutineState::Yielded(SmtpYield::WantsWrite(bytes)) => {
                    self.client.write(&self.url, &bytes)?;
                    arg = None;
                }
            }
        }
    }

    /// Consumes the greeting, says hello, upgrades an `smtp://` session to
    /// TLS, then authenticates.
    ///
    /// AUTH PLAIN for a password, as on the IMAP side, and XOAUTH2 for a
    /// token as the user the URL names.
    fn connect(&mut self, auth: &Auth) -> Result<(), BridgeError> {
        self.run(SmtpGreetingGet::new())?;
        self.run(SmtpEhlo::new(domain()))?;

        if self.url.starts_with("smtp://") {
            // NOTE: RFC 3207 section 6 forbids anything past the 220, so
            // more is an injection, refused before it crosses the upgrade.
            let trailing = self.run(SmtpStartTls::new())?;
            if !trailing.is_empty() {
                return Err("SMTP server sent data past its STARTTLS reply".into());
            }
            self.client.starttls(&self.url)?;
            // NOTE: the session restarts with the upgrade (RFC 3207
            // section 4.2), so the server is asked again what it offers.
            self.run(SmtpEhlo::new(domain()))?;
        }

        let password = SecretString::from(auth.secret().to_string());
        let signed = match *auth {
            Auth::Bearer(_) => {
                let user = url_user(&self.url)?;
                self.run(SmtpAuthXoauth2::new(
                    &user,
                    &password,
                    domain(),
                    SmtpAuthXoauth2Options::default(),
                ))
                .map(|_| ())
            }
            Auth::Basic { login, .. } => self
                .run(SmtpAuthPlain::new(
                    None::<&str>,
                    login,
                    &password,
                    domain(),
                    SmtpAuthPlainOptions::default(),
                ))
                .map(|_| ()),
        };

        // NOTE: the credential refused, the 401 of the HTTP backends as IMAP's.
        signed.map_err(|err| BridgeError {
            message: err.message,
            status: Some(401),
            ..Default::default()
        })
    }

    /// Hands one composed message over, then closes the session.
    ///
    /// The envelope is the composition's, as headers name no blind copy. The
    /// loop is spelled out, not [`Self::run`], so the failure stays typed: the
    /// queue parks or retries, and a string cannot say which.
    fn submit(&mut self, composed: &Composed) -> Result<(), SmtpSendError> {
        // NOTE: no later try spells an address better, so the row is parked.
        let unspelled = |err: BridgeError| SmtpSendError::Refused(err.message);
        let sender = mailbox(&composed.sender).map_err(unspelled)?;

        let mut recipients = Vec::with_capacity(composed.recipients.len());
        for recipient in &composed.recipients {
            let recipient = mailbox(recipient).map_err(unspelled)?;
            recipients.push(SmtpForwardPath::from(recipient));
        }

        let mut send = SmtpMessageSend::new(
            SmtpReversePath::from(sender),
            recipients,
            composed.message.clone(),
            SmtpMessageSendOptions::default(),
        );
        let mut arg: Option<Vec<u8>> = None;
        // NOTE: set once the data's final `.` is on its way: past it, a
        // failure with no reply cannot tell a message taken from one unseen.
        let mut handed_over = false;

        loop {
            match send.resume(arg.as_deref()) {
                SmtpCoroutineState::Complete(Ok(_)) => break,
                SmtpCoroutineState::Complete(Err(err)) => return Err(refusal(err, handed_over)),
                SmtpCoroutineState::Yielded(SmtpYield::WantsRead) => {
                    let read = self.client.read(&self.url);
                    arg = Some(read.map_err(|err| SmtpSendError::lost(err, handed_over))?);
                }
                SmtpCoroutineState::Yielded(SmtpYield::WantsWrite(bytes)) => {
                    handed_over |= handed(&bytes);
                    self.client
                        .write(&self.url, &bytes)
                        .map_err(|err| SmtpSendError::lost(err, handed_over))?;
                    arg = None;
                }
            }
        }

        // NOTE: QUIT, as the transport pools by origin and the next send
        // would meet a connection the server half forgot. The message was
        // accepted: a failed QUIT is no failed send, which would resend it.
        if let Err(err) = self.run(SmtpQuit::new()) {
            log::debug!("quit after an accepted message failed: {err}");
        }
        Ok(())
    }

    /// Ends a session a send failed on, so the next send opens its own:
    /// left open, the transport would hand that send a connection waiting
    /// for a command, never a greeting.
    ///
    /// `QUIT` first where the server answered (RFC 5321 section 4.1.1.10),
    /// none where the message may have gone with no verdict read, whose
    /// connection is in no known state.
    fn abandon(&mut self, err: &SmtpSendError) {
        if !err.is_uncertain()
            && let Err(quit) = self.run(SmtpQuit::new())
        {
            log::debug!("quit after a failed send failed: {quit}");
        }
        self.client.reset(&self.url);
    }
}

/// Why one submission failed, in the only terms the outbox cares about.
///
/// It picks the queue's answer: a message the server refused is parked
/// with what it said, anything else stays pending for the next drain.
#[derive(Debug)]
pub enum SmtpSendError {
    /// The server refused this message, and would refuse it again, or its
    /// envelope holds an address SMTP cannot spell.
    Refused(String),
    /// The server refused the credential, the message kept for later.
    SignIn(String),
    /// Anything else: no network, TLS, a 4yz reply.
    Transient(String),
    /// The whole message went over and no verdict came back: sending it
    /// again may deliver it twice.
    Uncertain(String),
}

impl SmtpSendError {
    /// Whether another attempt would only earn a second refusal.
    pub fn is_permanent(&self) -> bool {
        matches!(self, Self::Refused(_))
    }

    /// Whether the message may have been taken although the send failed.
    pub fn is_uncertain(&self) -> bool {
        matches!(self, Self::Uncertain(_))
    }

    /// Whether the server refused the credential.
    pub fn is_sign_in(&self) -> bool {
        matches!(self, Self::SignIn(_))
    }

    /// A session that did not open: the credential refused, or transient.
    fn connecting(err: BridgeError) -> Self {
        match err.status {
            Some(401) => Self::SignIn(err.message),
            _ => Self::Transient(err.message),
        }
    }

    /// A failure with no reply, before the message was handed over
    /// (transient) or after (uncertain).
    fn lost(err: impl Display, handed_over: bool) -> Self {
        match handed_over {
            true => Self::Uncertain(err.to_string()),
            false => Self::Transient(err.to_string()),
        }
    }
}

impl Display for SmtpSendError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> FmtResult {
        match self {
            Self::Refused(message)
            | Self::SignIn(message)
            | Self::Transient(message)
            | Self::Uncertain(message) => formatter.write_str(message),
        }
    }
}

/// One send failure as the queue reads it.
///
/// A 5yz reply is the server's final word, parked, unless it names a
/// sending limit ([`names_a_sending_limit`]), which the account outlives;
/// a 4yz asks for the message later (RFC 5321 section 4.2.1), pending. No
/// reply code is no verdict: a retry before the message was
/// `handed_over`, uncertain after.
fn refusal(err: SmtpMessageSendError, handed_over: bool) -> SmtpSendError {
    let code = match &err {
        SmtpMessageSendError::MailFrom(SmtpMailError::Rejected { code, .. })
        | SmtpMessageSendError::RcptTo {
            error: SmtpRcptError::Rejected { code, .. },
            ..
        }
        | SmtpMessageSendError::Data(SmtpDataError::CommandRejected { code, .. })
        | SmtpMessageSendError::Data(SmtpDataError::BodyRejected { code, .. }) => *code,
        // NOTE: one recipient asked to wait keeps the message waiting.
        SmtpMessageSendError::NoRecipient(refusals) => {
            let codes = refusals.iter().map(|refusal| refusal.code);
            codes.min().unwrap_or(0)
        }
        SmtpMessageSendError::MailFrom(SmtpMailError::Send(_))
        | SmtpMessageSendError::RcptTo {
            error: SmtpRcptError::Send(_),
            ..
        }
        | SmtpMessageSendError::Data(_) => 0,
    };

    if (500..600).contains(&code) && names_a_sending_limit(&err.to_string()) {
        SmtpSendError::Transient(err.to_string())
    } else if (500..600).contains(&code) {
        SmtpSendError::Refused(err.to_string())
    } else if code == 0 {
        SmtpSendError::lost(err, handed_over)
    } else {
        SmtpSendError::Transient(err.to_string())
    }
}

/// Enhanced status codes (RFC 3463) naming a sending rate or quota alone:
/// Gmail's daily limit, Microsoft's recipient and submission limits.
const SENDING_LIMIT_CODES: [&str; 5] = ["5.4.5", "5.1.90", "5.7.232", "5.7.233", "5.7.236"];

/// Wording of a sending rate or quota refusal under an ambiguous code.
const SENDING_LIMIT_WORDS: [&str; 5] = [
    "submissionquotaexceeded",
    "submission quota",
    "sending limit exceeded",
    "rate limited",
    "daily limit for message recipients",
];

/// Whether a refusal's text names a sending rate or quota: the account's
/// state, not the message's, which a later drain may send.
fn names_a_sending_limit(text: &str) -> bool {
    let lower = text.to_ascii_lowercase();
    text.split_ascii_whitespace()
        .any(|word| SENDING_LIMIT_CODES.contains(&word))
        || SENDING_LIMIT_WORDS
            .iter()
            .any(|words| lower.contains(words))
}

/// Whether a write carries the end of a message's data, its final `.`
/// line (RFC 5321 section 4.1.1.4).
fn handed(bytes: &[u8]) -> bool {
    bytes.ends_with(b"\r\n.\r\n")
}

/// The `EHLO` name, built per use as the type borrows and two coroutines
/// want one.
fn domain() -> SmtpEhloDomain<'static> {
    SmtpEhloDomain::try_from(EHLO_DOMAIN).expect("a valid EHLO name")
}

/// One address as the envelope spells it, a bare local part needing
/// quotes quoted, refused when it is none SMTP takes.
fn mailbox(address: &str) -> Result<SmtpMailbox<'_>, BridgeError> {
    let refused = |err: SmtpSyntaxError| {
        BridgeError::from(format!("`{address}` is not an email address: {err}"))
    };

    if let Ok(mailbox) = SmtpMailbox::try_from(address) {
        return Ok(mailbox);
    }

    let (local, domain) = address
        .rsplit_once('@')
        .filter(|(local, _)| !local.is_empty())
        .ok_or_else(|| refused(SmtpSyntaxError::Mailbox))?;

    Ok(SmtpMailbox {
        local_part: SmtpLocalPart::quote(local).map_err(refused)?,
        domain: SmtpEhloDomain::try_from(domain).map_err(refused)?,
    })
}

/// Connects and authenticates, then closes, sending nothing: whether the
/// server takes the credential, as the setup asks before saving it.
pub fn check<H: Host>(client: &mut Client<H>, url: &Url, auth: &Auth) -> Result<(), BridgeError> {
    let mut session = SmtpSession::new(client, url);
    session.connect(auth)?;
    // NOTE: as after a send, so the pooled connection is not left half
    // open; the credential was taken, which is all this asks.
    if let Err(err) = session.run(SmtpQuit::new()) {
        log::debug!("quit after a sign-in check failed: {err}");
    }
    Ok(())
}

/// Connects, authenticates, submits one message and closes.
pub fn send<H: Host>(
    client: &mut Client<H>,
    url: &Url,
    auth: &Auth,
    composed: &Composed,
) -> Result<(), SmtpSendError> {
    let mut session = SmtpSession::new(client, url);
    let sent = session
        .connect(auth)
        .map_err(SmtpSendError::connecting)
        .and_then(|()| session.submit(composed));
    if let Err(err) = &sent {
        session.abandon(err);
    }
    sent
}

#[cfg(test)]
mod tests {
    use io_smtp::{
        message::{SmtpMessageSendError, SmtpMessageSendRefusal},
        rfc5321::{SmtpForwardPath, data::SmtpDataError, mail::SmtpMailError, rcpt::SmtpRcptError},
        send::SmtpCommandSendError,
    };

    use std::collections::VecDeque;

    use url::Url;

    use crate::{
        client::{
            Auth, Client, Host,
            fake::{FakeRequest, FakeResponse, FakeServer},
        },
        error::BridgeError,
        mail::{
            compose::Composed,
            smtp::{
                EHLO_DOMAIN, SmtpSendError, SmtpSession, domain, handed, mailbox, refusal, send,
            },
        },
    };

    /// The server refusing one recipient with `code`.
    fn rcpt_refused(address: &'static str, code: u16) -> SmtpMessageSendError {
        SmtpMessageSendError::RcptTo {
            forward_path: SmtpForwardPath::from(mailbox(address).unwrap()),
            error: SmtpRcptError::Rejected {
                code,
                message: "No such recipient".into(),
            },
        }
    }

    #[test]
    fn a_refused_credential_keeps_the_message_and_signs_in_again() {
        let refused = SmtpSendError::connecting(BridgeError {
            message: "535 5.7.8 Authentication failed".into(),
            status: Some(401),
            ..Default::default()
        });
        assert!(refused.is_sign_in());
        assert!(!refused.is_permanent() && !refused.is_uncertain());

        let lost = SmtpSendError::connecting("Connection reset".into());
        assert!(!lost.is_sign_in());
    }

    #[test]
    fn a_5yz_reply_is_the_servers_last_word() {
        let refused = refusal(rcpt_refused("bob@example.org", 550), false);

        assert!(refused.is_permanent(), "expected a refusal");
    }

    /// The parked row says which recipient the server would not take.
    #[test]
    fn a_refused_recipient_is_named() {
        let refused = refusal(rcpt_refused("bob@example.org", 550), false);

        assert_eq!(
            refused.to_string(),
            "Recipient <bob@example.org>: SMTP RCPT TO failed: rejected 550 No such recipient"
        );
    }

    /// Every recipient refused: for good only when none asked to wait.
    #[test]
    fn recipients_all_refused_wait_when_one_may_come_back() {
        let refusals = |codes: &[u16]| {
            let refusals = codes.iter().map(|code| SmtpMessageSendRefusal {
                forward_path: SmtpForwardPath::from(mailbox("bob@example.org").unwrap()),
                code: *code,
                message: "No".into(),
            });
            SmtpMessageSendError::NoRecipient(refusals.collect())
        };

        assert!(refusal(refusals(&[550, 551]), false).is_permanent());
        assert!(!refusal(refusals(&[550, 450]), false).is_permanent());
        assert!(!refusal(refusals(&[550, 450]), false).is_uncertain());
    }

    /// An address goes out as SMTP spells it, a bare local part that needs
    /// quotes quoted.
    #[test]
    fn an_address_is_spelled_for_the_envelope() {
        let spelled = |address: &str| mailbox(address).unwrap().to_string();

        assert_eq!(spelled("alice@example.org"), "alice@example.org");
        assert_eq!(spelled("jörg@bücher.example"), "jörg@bücher.example");
        assert_eq!(
            spelled(r#""john doe"@example.org"#),
            r#""john doe"@example.org"#
        );
        assert_eq!(spelled("john doe@example.org"), r#""john doe"@example.org"#);
    }

    /// A line break in an address would add a command to the session.
    #[test]
    fn an_address_adding_a_line_is_refused() {
        for address in [
            "alice@example.org>\r\nRCPT TO:<eve@example.org",
            "alice\r\nRSET@example.org",
            "alice@example.org\n",
            "alice",
            "alice@",
            "@example.org",
        ] {
            let err = mailbox(address).unwrap_err();
            assert!(
                err.message.contains("is not an email address"),
                "{address:?}: {}",
                err.message
            );
        }
    }

    /// An address SMTP cannot spell parks its message, nothing sent.
    #[test]
    fn an_envelope_smtp_cannot_spell_is_refused_unsent() {
        let unsent = |_: &FakeRequest| -> FakeResponse { panic!("nothing is sent") };
        let mut client = Client::new(FakeServer::new(unsent));
        let url = Url::parse("smtps://smtp.example.org").unwrap();
        let composed = Composed {
            message: b"Subject: x\r\n\r\n".to_vec(),
            sender: "jane@example.org".into(),
            recipients: vec!["bob@exa mple.org".into()],
        };

        let err = SmtpSession::new(&mut client, &url)
            .submit(&composed)
            .unwrap_err();

        assert!(err.is_permanent());
        assert_eq!(
            err.to_string(),
            "`bob@exa mple.org` is not an email address: Invalid SMTP domain"
        );
    }

    #[test]
    fn the_hello_name_is_one_smtp_takes() {
        assert_eq!(domain().to_string(), EHLO_DOMAIN);
    }

    #[test]
    fn a_4yz_reply_asks_for_the_same_message_later() {
        let err = SmtpMessageSendError::MailFrom(SmtpMailError::Rejected {
            code: 450,
            message: "Mailbox busy".into(),
        });

        assert!(
            !refusal(err, false).is_permanent(),
            "a temporary reply keeps the message queued"
        );
    }

    #[test]
    fn a_rejected_body_is_read_the_same_way_as_a_rejected_command() {
        let err = SmtpMessageSendError::Data(SmtpDataError::BodyRejected {
            code: 552,
            message: "Message too large".into(),
        });

        assert!(
            refusal(err, false).is_permanent(),
            "the verdict is the reply code, whichever step earned it"
        );
    }

    /// The connection ending after the final dot leaves no telling whether
    /// the server took the message; before it, nothing was handed over.
    #[test]
    fn a_reply_lost_after_the_data_is_uncertain() {
        let eof = || SmtpMessageSendError::Data(SmtpDataError::Send(SmtpCommandSendError::Eof));

        assert!(refusal(eof(), true).is_uncertain());
        assert!(!refusal(eof(), false).is_uncertain());
        assert!(!refusal(eof(), false).is_permanent());

        let busy = SmtpMessageSendError::Data(SmtpDataError::BodyRejected {
            code: 451,
            message: "Try later".into(),
        });
        assert!(
            !refusal(busy, true).is_uncertain(),
            "a 4yz reply says the message was not taken"
        );
    }

    #[test]
    fn the_final_dot_hands_the_message_over() {
        assert!(handed(b"Subject: x\r\n\r\n..hello\r\n.\r\n"));
        assert!(!handed(b"DATA\r\n"));
        assert!(!handed(b"RCPT TO:<a@b.c>\r\n"));
    }

    /// An SMTP server over one connection the transport keeps between two
    /// sends, as Java's does by origin: a greeting on each connection it
    /// opens, the end of stream once it answered `QUIT`, and a read timing
    /// out while it waits for a command nobody sends.
    #[derive(Default)]
    struct Scripted {
        /// Whether a connection is open, and whether the server ended it.
        open: bool,
        ended: bool,
        pending: VecDeque<u8>,
        /// Whether the client's lines are the message's data.
        data: bool,
        /// Every command, connections apart.
        commands: Vec<String>,
        /// How many connections were opened.
        connections: usize,
    }

    impl Scripted {
        fn answer(&mut self, line: &str) -> &'static str {
            if self.data {
                if line == "." {
                    self.data = false;
                    return "250 2.0.0 queued\r\n";
                }
                return "";
            }
            self.commands.push(line.to_string());
            let verb = line.split([' ', ':']).next().unwrap_or_default();
            match verb.to_ascii_uppercase().as_str() {
                "EHLO" => "250-smtp.example.org\r\n250 AUTH PLAIN\r\n",
                "AUTH" => "235 2.7.0 accepted\r\n",
                "MAIL" | "RSET" => "250 2.1.0 ok\r\n",
                "RCPT" if line.contains("refused@") => "550 5.1.1 no such user\r\n",
                "RCPT" if line.contains("limited@") => {
                    "550 5.4.5 Daily user sending limit exceeded\r\n"
                }
                "RCPT" => "250 2.1.5 ok\r\n",
                "DATA" => {
                    self.data = true;
                    "354 go ahead\r\n"
                }
                "QUIT" => {
                    self.ended = true;
                    "221 2.0.0 bye\r\n"
                }
                _ => "502 5.5.2 unscripted\r\n",
            }
        }
    }

    impl Host for Scripted {
        fn read(&mut self, _url: &str) -> Result<Vec<u8>, BridgeError> {
            if !self.open {
                self.open = true;
                self.ended = false;
                self.connections += 1;
                self.pending.extend(b"220 smtp.example.org ready\r\n");
            }
            if self.pending.is_empty() {
                if self.ended {
                    self.open = false;
                    return Ok(Vec::new());
                }
                return Err("Read timed out".into());
            }
            Ok(self.pending.drain(..).collect())
        }

        fn write(&mut self, _url: &str, bytes: &[u8]) -> Result<(), BridgeError> {
            let text = String::from_utf8_lossy(bytes).into_owned();
            for line in text.split_terminator("\r\n") {
                let reply = self.answer(line);
                self.pending.extend(reply.as_bytes());
            }
            Ok(())
        }

        fn begin(&mut self, _url: &str) -> bool {
            false
        }

        fn settle(&mut self, _url: &str) {}

        fn reset(&mut self, _url: &str) {
            self.open = false;
            self.data = false;
            self.pending.clear();
        }

        fn starttls(&mut self, _url: &str) -> Result<(), BridgeError> {
            Err("Unscripted".into())
        }

        fn serve(&mut self, _request: &str) -> Result<String, BridgeError> {
            Err("Unscripted".into())
        }
    }

    /// A message to `to`, from Jane.
    fn to(to: &str) -> Composed {
        Composed {
            message: format!("From: jane@example.org\r\nTo: {to}\r\nSubject: x\r\n\r\nhi\r\n")
                .into_bytes(),
            sender: "jane@example.org".into(),
            recipients: vec![to.into()],
        }
    }

    /// Two queued messages over one connection, the first refused at its
    /// `RCPT`: the second is sent all the same, as mail.md asks of one
    /// refusal among several.
    #[test]
    fn a_submission_refused_leaves_the_next_one_sent() {
        let mut client = Client::new(Scripted::default());
        let url = Url::parse("smtps://jane@smtp.example.org").unwrap();
        let auth = Auth::Basic {
            login: "jane",
            password: "secret",
        };

        let first = send(&mut client, &url, &auth, &to("refused@example.org"));
        let second = send(&mut client, &url, &auth, &to("bob@example.org"));

        assert!(first.is_err_and(|err| err.is_permanent()));
        assert_eq!(second.map_err(|err| err.to_string()), Ok(()));
        assert_eq!(
            client.host.commands.last().map(String::as_str),
            Some("QUIT")
        );
    }

    /// A 5yz naming a sending limit (Gmail's 5.4.5, Microsoft's 5.7.232 and
    /// their wording) keeps the message queued: the account may send again
    /// later, where any other 5yz is the server's last word.
    #[test]
    fn a_sending_limit_keeps_the_message_queued() {
        let mut client = Client::new(Scripted::default());
        let url = Url::parse("smtps://jane@smtp.example.org").unwrap();
        let auth = Auth::Basic {
            login: "jane",
            password: "secret",
        };

        let limited = send(&mut client, &url, &auth, &to("limited@example.org")).unwrap_err();

        assert!(!limited.is_permanent(), "{limited}");
        assert!(!limited.is_uncertain(), "{limited}");

        let microsoft = SmtpMessageSendError::Data(SmtpDataError::BodyRejected {
            code: 554,
            message: "5.7.232 Your message can't be sent because your trial tenant has \
                      exceeded its daily limit for message recipients"
                .into(),
        });
        assert!(!refusal(microsoft, true).is_permanent());
    }
}
