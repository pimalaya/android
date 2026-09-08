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

use io_smtp::{
    coroutine::{SmtpCoroutine, SmtpCoroutineState, SmtpYield},
    message::SmtpMessageSend,
    rfc5321::{
        SmtpDomain, SmtpEhloDomain, SmtpForwardPath, SmtpLocalPart, SmtpMailbox, SmtpReversePath,
        ehlo::SmtpEhlo, greeting::SmtpGreetingGet, quit::SmtpQuit,
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
    fn submit(&mut self, composed: &Composed) -> Result<(), BridgeError> {
        let sender = mailbox(&composed.sender)?;
        let mut recipients = Vec::with_capacity(composed.recipients.len());
        for recipient in &composed.recipients {
            recipients.push(SmtpForwardPath::from(mailbox(recipient)?));
        }

        self.run(SmtpMessageSend::new(
            SmtpReversePath::from(sender),
            recipients,
            composed.message.clone(),
        ))?;

        // NOTE: QUIT rather than dropping the socket. A server that is
        // never told the session ended keeps it open until its own
        // timeout, and the transport pools by origin, so the next send
        // of the same run would meet a connection the server has half
        // forgotten.
        self.run(SmtpQuit::new()).map(|_| ())
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
) -> Result<(), BridgeError> {
    let mut session = SmtpSession::new(client, url);
    session.connect(credentials)?;
    session.submit(composed)
}
