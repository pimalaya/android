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
        SmtpDomain, SmtpEhloDomain, SmtpForwardPath, SmtpLocalPart, SmtpMailbox, SmtpReversePath,
        data::SmtpDataError, ehlo::SmtpEhlo, greeting::SmtpGreetingGet, mail::SmtpMailError,
        quit::SmtpQuit, rcpt::SmtpRcptError,
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
        match *auth {
            Auth::Bearer(_) => {
                let user = url_user(&self.url)?;
                self.run(SmtpAuthXoauth2::new(
                    &user,
                    &password,
                    domain(),
                    SmtpAuthXoauth2Options::default(),
                ))?;
            }
            Auth::Basic { login, .. } => {
                self.run(SmtpAuthPlain::new(
                    None::<&str>,
                    login,
                    &password,
                    domain(),
                    SmtpAuthPlainOptions::default(),
                ))?;
            }
        }

        Ok(())
    }

    /// Hands one composed message over, then closes the session.
    ///
    /// The envelope is the composition's, as headers name no blind copy. The
    /// loop is spelled out, not [`Self::run`], so the failure stays typed: the
    /// queue parks or retries, and a string cannot say which.
    fn submit(&mut self, composed: &Composed) -> Result<(), SmtpSendError> {
        let sender = mailbox(&composed.sender).map_err(SmtpSendError::transient)?;

        let mut recipients = Vec::with_capacity(composed.recipients.len());
        for recipient in &composed.recipients {
            let recipient = mailbox(recipient).map_err(SmtpSendError::transient)?;
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
}

/// Why one submission failed, in the only terms the outbox cares about.
///
/// It picks the queue's answer: a message the server refused is parked
/// with what it said, anything else stays pending for the next drain.
#[derive(Debug)]
pub enum SmtpSendError {
    /// The server refused this message, and would refuse it again.
    Refused(String),
    /// Anything else: no network, TLS, authentication, a 4yz reply.
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

    fn transient(err: impl Display) -> Self {
        Self::Transient(err.to_string())
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
            Self::Refused(message) | Self::Transient(message) | Self::Uncertain(message) => {
                formatter.write_str(message)
            }
        }
    }
}

/// One send failure as the queue reads it.
///
/// A 5yz reply is the server's final word, parked; a 4yz asks for the
/// message later (RFC 5321 section 4.2.1), pending. No reply code is no
/// verdict: a retry before the message was `handed_over`, uncertain after.
fn refusal(err: SmtpMessageSendError, handed_over: bool) -> SmtpSendError {
    let code = match &err {
        SmtpMessageSendError::MailFrom(SmtpMailError::Rejected { code, .. })
        | SmtpMessageSendError::RcptTo(SmtpRcptError::Rejected { code, .. })
        | SmtpMessageSendError::Data(SmtpDataError::CommandRejected { code, .. })
        | SmtpMessageSendError::Data(SmtpDataError::BodyRejected { code, .. }) => *code,
        _ => 0,
    };

    if (500..600).contains(&code) {
        SmtpSendError::Refused(err.to_string())
    } else if code == 0 {
        SmtpSendError::lost(err, handed_over)
    } else {
        SmtpSendError::Transient(err.to_string())
    }
}

/// Whether a write carries the end of a message's data, its final `.`
/// line (RFC 5321 section 4.1.1.4).
fn handed(bytes: &[u8]) -> bool {
    bytes.ends_with(b"\r\n.\r\n")
}

/// The `EHLO` name, built per use as the type borrows and two coroutines
/// want one.
fn domain() -> SmtpEhloDomain<'static> {
    SmtpEhloDomain::from(SmtpDomain(EHLO_DOMAIN.into()))
}

/// One address as the envelope spells it.
fn mailbox(address: &str) -> Result<SmtpMailbox<'static>, BridgeError> {
    let (local, domain) = address
        .rsplit_once('@')
        .ok_or_else(|| BridgeError::from(format!("`{address}` is not an email address")))?;

    Ok(SmtpMailbox {
        local_part: SmtpLocalPart(local.to_string().into()),
        domain: SmtpEhloDomain::from(SmtpDomain(domain.to_string().into())),
    })
}

/// Connects, authenticates, submits one message and closes.
pub fn send<H: Host>(
    client: &mut Client<H>,
    url: &Url,
    auth: &Auth,
    composed: &Composed,
) -> Result<(), SmtpSendError> {
    let mut session = SmtpSession::new(client, url);
    session.connect(auth).map_err(SmtpSendError::transient)?;
    session.submit(composed)
}

#[cfg(test)]
mod tests {
    use io_smtp::{
        message::SmtpMessageSendError,
        rfc5321::{data::SmtpDataError, mail::SmtpMailError, rcpt::SmtpRcptError},
        send::SmtpCommandSendError,
    };

    use crate::mail::smtp::{handed, refusal};

    #[test]
    fn a_5yz_reply_is_the_servers_last_word() {
        let err = SmtpMessageSendError::RcptTo(SmtpRcptError::Rejected {
            code: 550,
            message: "No such recipient".into(),
        });

        assert!(refusal(err, false).is_permanent(), "expected a refusal");
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
}
