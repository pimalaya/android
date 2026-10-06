//! SMTP submission: one message handed over, run over io-smtp's sans-io
//! coroutines and the same Java transport every other protocol uses.
//!
//! Stateless per call like the IMAP side, and for the same reason: a
//! send is a whole session (greeting, EHLO, authentication, envelope,
//! data) and there is nothing between two of them worth keeping open.
//!
//! Implicit TLS only, which is what the connection flow offers: this
//! client has no STARTTLS step, and a `starttls` endpoint driven as if
//! it were implicit would hand a message over in the clear rather than
//! fail.

use core::fmt::{Display, Formatter, Result as FmtResult};

use io_smtp::{
    coroutine::{SmtpCoroutine, SmtpCoroutineState, SmtpYield},
    message::{SmtpMessageSend, SmtpMessageSendError, SmtpMessageSendOptions},
    rfc5321::{
        SmtpDomain, SmtpEhloDomain, SmtpForwardPath, SmtpLocalPart, SmtpMailbox, SmtpReversePath,
        data::SmtpDataError, ehlo::SmtpEhlo, greeting::SmtpGreetingGet, mail::SmtpMailError,
        quit::SmtpQuit, rcpt::SmtpRcptError,
    },
    sasl::auth_plain::{SmtpAuthPlain, SmtpAuthPlainOptions},
};
use secrecy::SecretString;
use url::Url;

use crate::{
    client::Client,
    mail::Composed,
    types::{BridgeError, Credentials},
};

/// What the app calls itself in `EHLO`.
///
/// A client on a phone has no name a server could resolve, and RFC 5321
/// section 4.1.4 tells a server not to refuse a message over it anyway.
/// A literal name says what this is rather than claiming a domain that
/// does not exist.
const EHLO_DOMAIN: &str = "pimalaya.android";

/// One SMTP session: the shared JNI client and the URL its socket is
/// keyed by.
pub struct SmtpSession<'a, 'b, 'local> {
    client: &'a mut Client<'b, 'local>,
    url: String,
}

impl<'a, 'b, 'local> SmtpSession<'a, 'b, 'local> {
    pub fn new(client: &'a mut Client<'b, 'local>, url: &Url) -> Self {
        Self {
            client,
            url: url.to_string(),
        }
    }

    /// Drives a coroutine to completion, servicing every read and write
    /// yield through the Java transport's stream for this URL.
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

    /// Consumes the greeting, says hello, then authenticates.
    ///
    /// AUTHENTICATE PLAIN only, as on the IMAP side and for the same
    /// reason: every provider this app onboards accepts it, and a
    /// bearer-token account would need XOAUTH2, which is not wired here
    /// either.
    fn connect(&mut self, credentials: &Credentials) -> Result<(), BridgeError> {
        self.run(SmtpGreetingGet::new())?;
        self.run(SmtpEhlo::new(domain()))?;

        let password = SecretString::from(credentials.password.to_string());
        self.run(SmtpAuthPlain::new(
            None::<&str>,
            credentials.login,
            &password,
            domain(),
            SmtpAuthPlainOptions::default(),
        ))?;

        Ok(())
    }

    /// Hands one composed message over, then closes the session.
    ///
    /// The envelope is the composition's, not the headers': a blind copy
    /// is a recipient the headers deliberately do not name, so reading
    /// the recipients back off the message would drop it.
    ///
    /// The loop is written out here rather than run through [`Self::run`]
    /// because this is the one exchange whose failure has to survive
    /// typed. The queue has two answers for a failed action, park and
    /// retry, and a string cannot be asked which one it is.
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

        loop {
            match send.resume(arg.as_deref()) {
                SmtpCoroutineState::Complete(Ok(_)) => break,
                SmtpCoroutineState::Complete(Err(err)) => return Err(refusal(err)),
                SmtpCoroutineState::Yielded(SmtpYield::WantsRead) => {
                    let read = self.client.read(&self.url);
                    arg = Some(read.map_err(SmtpSendError::transient)?);
                }
                SmtpCoroutineState::Yielded(SmtpYield::WantsWrite(bytes)) => {
                    self.client
                        .write(&self.url, &bytes)
                        .map_err(SmtpSendError::transient)?;
                    arg = None;
                }
            }
        }

        // NOTE: QUIT rather than dropping the socket. A server that is
        // never told the session ended keeps it open until its own
        // timeout, and the transport pools by origin, so the next send
        // of the same run would meet a connection the server has half
        // forgotten.
        self.run(SmtpQuit::new())
            .map(|_| ())
            .map_err(SmtpSendError::transient)
    }
}

/// Why one submission failed, in the only terms the outbox cares about.
///
/// The queue has two answers for a failed action, and this is what picks
/// between them: a message the server refused is parked carrying what it
/// said, and everything else stays pending for the drain that follows.
#[derive(Debug)]
pub enum SmtpSendError {
    /// The server refused this message, and would refuse it again.
    Refused(String),
    /// Anything else: no network, TLS, authentication, a 4yz reply.
    Transient(String),
}

impl SmtpSendError {
    /// Whether another attempt would only earn a second refusal.
    pub fn is_permanent(&self) -> bool {
        matches!(self, Self::Refused(_))
    }

    fn transient(err: impl Display) -> Self {
        Self::Transient(err.to_string())
    }
}

impl Display for SmtpSendError {
    fn fmt(&self, formatter: &mut Formatter<'_>) -> FmtResult {
        match self {
            Self::Refused(message) | Self::Transient(message) => formatter.write_str(message),
        }
    }
}

/// One send failure as the queue reads it.
///
/// A 5yz reply is the server's final word on this message and a 4yz one
/// asks for the same message later (RFC 5321 section 4.2.1), which is
/// exactly the difference between parking the action and leaving it
/// pending. Anything with no reply code at all never reached a verdict,
/// so it is the environment's failure and retries.
fn refusal(err: SmtpMessageSendError) -> SmtpSendError {
    let code = match &err {
        SmtpMessageSendError::MailFrom(SmtpMailError::Rejected { code, .. })
        | SmtpMessageSendError::RcptTo(SmtpRcptError::Rejected { code, .. })
        | SmtpMessageSendError::Data(SmtpDataError::CommandRejected { code, .. })
        | SmtpMessageSendError::Data(SmtpDataError::BodyRejected { code, .. }) => *code,
        _ => 0,
    };

    if (500..600).contains(&code) {
        SmtpSendError::Refused(err.to_string())
    } else {
        SmtpSendError::Transient(err.to_string())
    }
}

/// The `EHLO` name, built each time it is needed: the type borrows, and
/// two coroutines want one.
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
pub fn send(
    client: &mut Client<'_, '_>,
    url: &Url,
    credentials: &Credentials,
    composed: &Composed,
) -> Result<(), SmtpSendError> {
    let mut session = SmtpSession::new(client, url);
    session
        .connect(credentials)
        .map_err(SmtpSendError::transient)?;
    session.submit(composed)
}

#[cfg(test)]
mod tests {
    use io_smtp::{
        message::SmtpMessageSendError,
        rfc5321::{data::SmtpDataError, mail::SmtpMailError, rcpt::SmtpRcptError},
    };

    use super::refusal;

    #[test]
    fn a_5yz_reply_is_the_servers_last_word() {
        let err = SmtpMessageSendError::RcptTo(SmtpRcptError::Rejected {
            code: 550,
            message: "No such recipient".into(),
        });

        assert!(refusal(err).is_permanent(), "expected a refusal");
    }

    #[test]
    fn a_4yz_reply_asks_for_the_same_message_later() {
        let err = SmtpMessageSendError::MailFrom(SmtpMailError::Rejected {
            code: 450,
            message: "Mailbox busy".into(),
        });

        assert!(
            !refusal(err).is_permanent(),
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
            refusal(err).is_permanent(),
            "the verdict is the reply code, whichever step earned it"
        );
    }
}
