//! # Pimalaya Android
//!
//! The Rust half of Pimalaya Android, loaded by the app's `:client` module
//! as libpimalaya.so: every protocol, document format and sync decision,
//! driven over JNI by a Java side that owns the sockets and the store.
//!
//! ## Boundary
//!
//! Java owns everything with a platform behind it: TCP and TLS, the SQLite
//! pimdir store, the scheduler, the phone's contacts and calendars. This
//! crate performs no I/O: every byte goes out through a [`client::Host`],
//! and [`ffi`] holds the only implementation over JNI and every export.
//!
//! An export decodes its JNI strings, builds an account and the params of
//! one operation, calls it, and encodes the reply as JSON: a value, or
//! `{"error", "status"?}` ([`error::BridgeError`]), whose HTTP status is
//! what Java branches on (401 renews a token, 412 retries, 404 converges).
//!
//! ## Layout
//!
//! The three domains are [`contact`], [`calendar`] and [`mail`]. Each owns
//! its types, a router ([`contact::Contacts`], [`calendar::Calendars`],
//! [`mail::session::Mail`]) dispatching an operation on the account's
//! backend, and one file per backend named after the io- crate it drives.
//!
//! Four modules cut across them. [`account`] tells backends apart and
//! holds onboarding (discovery, OAuth); [`client`] the transport policy
//! (throttling, trust) and the protocol plumbing several domains share;
//! [`offline`] the io-pimdir engine; [`ffi`] the JNI surface.
//!
//! ## Backends
//!
//! An account is a base URL: a CardDAV or CalDAV context root, or a
//! sentinel scheme (`msgraph://`, `jmap://`, `google://`) standing for an
//! API with no root to name, opaque to Java ([`account::Backend`]).
//!
//! A backend is a struct holding the client and the credential every verb
//! needs (`Webdav`, `Msgraph`, `Jmap`, `Gpeople`, `Gcal`, `Gmail`). One
//! serving several domains lives in [`client`], and each domain adds its
//! verbs in its own file; IMAP and SMTP are sessions in [`mail`].
//!
//! ## Calls and sessions
//!
//! A native call builds a [`client::Client`], runs one operation and drops
//! it; the transport pools the sockets underneath. Mail keeps a session
//! across calls instead ([`mail::session`]), handed to Java as a handle,
//! holding the protocol state an IMAP conversation needs.
//!
//! ## Offline engine
//!
//! The `offline*` exports run io-pimdir's sync engine through
//! [`offline::Driver`]: every yield goes to the Java `OfflineDriver` as
//! one JSON envelope ([`offline::wire`]), storage yields served by the
//! store and remote ones by the domain exports above.
//!
//! ## JSON contract
//!
//! Replies keep the bytes Java always saw. Those once built with `json!`
//! came out in alphabetical key order (serde_json sorts its maps), so their
//! typed successors declare fields in that order, the one Java compares
//! printed models in; types typed from the start keep their own.
//!
//! A typed input models what the Java producer sends, and nothing more.
//!
//! ## Tests
//!
//! Tests drive the real backend structs through the real client over
//! `client::fake::FakeServer`, a host answering HTTP from the test's
//! model of the provider; IMAP tests script the bytes of a server. The app
//! side's JVM tests load this library too.

// NOTE: a cdylib whose every module is private; its docs are read with
// `cargo doc --document-private-items`, where these links resolve.
#![allow(rustdoc::private_intra_doc_links)]

mod account;
mod calendar;
mod client;
mod contact;
mod error;
mod ffi;
mod logger;
mod mail;
mod offline;
