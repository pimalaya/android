---
cairn: log
change: the-bridge-follows-the-io-releases
date: 2026-11-13
---

# The bridge follows the io releases of 2026-11-13

The Rust bridge moves onto the family libraries released on 2026-11-13, each a breaking release closing lines of the family audit of that day: io-http 0.7, io-oauth 0.6, io-imap 0.9, io-smtp 0.8, io-jmap 0.8, io-webdav 0.8, io-gcal 0.4, io-gtasks 0.1 (its first release), io-gmail 0.7, io-gpeople 0.7, io-msgraph 0.8 and io-pim-discovery 0.12. The bridge pumps the coroutines itself, so it met every changed yield and return. Only rust/ and this paperwork moved: no Java, no Gradle file.

## The manifest

rust/Cargo.toml asks each of the twelve at its release. io-gtasks leaves its git source (f4c4aed) for crates.io 0.1. The `[patch.crates-io]` section is gone: its one pin, io-msgraph at 92c28d5, is an ancestor of v0.8.0. `cargo update` named the twelve alone and moved io-sasl with them (0.1.1 to 0.2.0, through io-imap and io-smtp). `cargo tree -i` shows one io-http (0.7.0), one io-sasl (0.2.0) and one io-oauth (0.6.0). io-pimdir 0.12, not released that day, pulls none of them.

## What each library needed

- **io-gcal 0.4** encodes the ids of its request paths itself. The bridge's `calendar_path`, which percent-encoded a calendar id before handing it over, is gone: kept, a subscribed calendar's `#` went out as `%2523` and named another resource. An event id is encoded too now, which the helper never did. `from_ical` may answer `StashFull`: it fails the write with the library's sentence, as its other refusals do.
- **io-smtp 0.8** validates the envelope types. `mailbox` reads an address in its wire form (`SmtpMailbox::try_from`), and one whose bare local part needs quotes is quoted (`SmtpLocalPart::quote`); the `EHLO` name goes through `SmtpEhloDomain::try_from`. An address that is none, a line break in it included, is refused before any command, and parks the row (`SmtpSendError::Refused`) where a missing `@` left it pending and stopped the drain: no later attempt spells it better, and a server answered such an address with a 5yz, which parked it. `RcptTo { forward_path, error }` is read for its reply code and its sentence, which names the recipient, is the parked row's error. `NoRecipient`, which only `skip_refused_recipients` yields and the bridge does not set, is for good when no recipient asked to wait.
- **io-imap 0.9** hands back the responses it skipped for not decoding. See the next section. `NO` and `BAD` carry an `ImapStatusError`, read as text as before. COPY and MOVE answer the `COPYUID` pairs, which the bridge drops: see what is left. The bridge runs no watcher, so nothing resumes.
- **io-jmap 0.8** answers a request refused with a problem of the JMAP error namespace as `JmapSendError::Request { status, error }`, where it was `HttpStatus`: `http_status` reads the status of both, so a 401 answered that way still renews the token and a 4xx is still a refusal. `JmapBlobUpload::new` returns a `Result`, so the redirect runner's constructor is fallible. The catch-all of the set errors is `Other { .. }`; `Email/import` names `AlreadyExists`, which waits as it did while it read as unknown (the email held may be an earlier attempt's, sent or not). `EmailSubmission/set` hands the answer of its implicit `Email/set`: a sent message whose draft was not filed is logged as a warning, the send standing. The bridge opens no event source.
- **io-oauth 0.6**: the PKCE verifier's error is the library's sentence (a length outside 43 to 128 refused too; the app makes 64). `validate` takes an expected issuer and is handed none: the bridge compares the `iss` itself, parsed, because the issuer it holds went through a `Url`, which writes a bare origin with its slash, and the library compares strings: `https://api.fastmail.com`, as a redirect names it, against `https://api.fastmail.com/` would refuse the sign-in. The bridge uses none of the listener functions.
- **io-pim-discovery 0.12** checks the issuer and the scheme of every URL where it fetches OAuth metadata, more strictly than the bridge did (no loopback exception, every endpoint): the bridge's `trusted_metadata` was a duplicate and is gone. A cleartext issuer is refused once, before the two well-known tries. Every config carries a `trust`, set as the library's own client sets it (cairn/spec/onboarding.md). The DAV and JMAP resolves answer whether an SRV record named their origin, which is that trust.
- **io-gpeople 0.7**: `from_vcard` answers the person and the properties over 8 KiB the stash left out, logged as a warning on a write. `merge_unprojected` is not adopted: see what is left.
- **io-msgraph 0.8**: recipients are a `MsgraphField`, read as none when Graph sent no list. Listings are `MsgraphPage` (two test literals take `count`).
- **io-gmail 0.7, io-webdav 0.8, io-gtasks 0.1, io-http 0.7**: no production line. The bridge runs no `GmailHistoryPoll` (it replays the history itself), builds no `GmailSend` by hand, and keeps the id a WebDAV create or move answers, now the percent-encoded segment a listing answers too. A multistatus that is not whole fails in the library. A weak ETag is kept whole where it lost its closing quote, so a server sending weak tags has every member read as changed once.

## An IMAP answer that skipped a line

An untagged response io-imap cannot decode is skipped and handed back. Where what the answer lacks would read as gone, the bridge no longer takes it for whole (rust/src/mail/imap.rs, `whole`, `taken`, `unread`):

- `LIST`, `UID SEARCH` and `UID SORT` fail on any skipped response: the roster drops a mailbox it lacks, a round ends on what its search names.
- `EXAMINE` fails when it skipped one and states no `EXISTS`, which read as an empty mailbox.
- `UID FETCH`, of a page's header fields and of the sizes before a body read, fails when it skipped one and lacks a UID it asked for; answering every UID asked, it stands.
- A QRESYNC `SELECT` hands what it read and keeps the checkpoint it was asked from.
- An `ALERT` is logged as a warning.

The error reads `IMAP <command> is not whole, responses unread: <n>`. A round's page has no way to say "apply what I list, drop nothing": the engine has one (`capped`), the app's `MailPage` does not carry it.

## Tests

New, in rust/src:

- calendar/gcal/tests.rs `an_id_reaches_google_encoded_once`, in place of the helper's own test. Seen failing with the helper still called: `fr.french%2523holiday@...` for `fr.french%23holiday@...`.
- mail/smtp.rs `a_refused_recipient_is_named`, `recipients_all_refused_wait_when_one_may_come_back`, `an_address_is_spelled_for_the_envelope`, `an_address_adding_a_line_is_refused`, `an_envelope_smtp_cannot_spell_is_refused_unsent`, `the_hello_name_is_one_smtp_takes`.
- mail/imap/tests.rs `a_roster_that_skipped_a_line_is_refused`, `a_round_whose_search_skipped_a_line_is_refused`, `a_header_line_unread_is_not_a_message_gone`, `a_size_line_unread_is_not_a_message_gone`, `a_mailbox_opened_without_its_count_is_not_empty`, `a_delta_that_skipped_a_line_keeps_its_checkpoint`, over the scripted server answering `LIST`, a QRESYNC `SELECT` and lines no client decodes.
- client/trust/tests.rs `oauth_metadata_is_the_issuers_own`, `oauth_metadata_is_not_fetched_in_clear` (the bridge's own check being gone, the library's is held here) and `a_dav_listing_that_is_not_whole_is_refused`.
- error.rs, in `a_status_the_failure_carries_itself_still_crosses`: the status of `JmapSendError::Request`.

The gcal test alone was run against the earlier code. The others exercise types or fields the earlier libraries did not have, so the earlier code does not build beside them.

Changed, each for what a library correctly changed:

- mail/gmail/tests.rs: the fake Gmail reads a message id as one percent-decoded path segment (`read_id`). io-gmail 0.7 encodes ids, so `18b0/a1` goes out as `18b0%2Fa1`, where the fake read the two raw segments.
- mail/msgraph/tests.rs: `count: None` in two page literals, `MsgraphField::Set` around a recipient list.
- mail/smtp.rs: `a_5yz_reply_is_the_servers_last_word` builds the new `RcptTo` shape.
- client/trust/tests.rs: the three JMAP redirect closures answer `Ok(..)`.

## Evidence

Each as `nix develop --command <cmd>`, from the repository root, `PIMALAYA_IO_PIMDIR_SPEC` at the io-pimdir v0.12.0 checkout's spec/, on 2026-11-13:

- `cargo fmt --check` in rust/: clean.
- `cargo clippy --all-targets --locked -- -D warnings` in rust/, as .github/workflows/tests.yml runs it: clean.
- `cargo test --locked` in rust/: 678 passed, 1 ignored (662 before, sixteen added).
- `cargo ndk -t arm64-v8a -t armeabi-v7a -t x86_64 -t x86 build --release --locked` in rust/, the command of the app's `cargoNdkBuild` without its `-o`: the four libraries built.
- `gradle -p android :app:testDebugUnitTest :client:testDebugUnitTest --offline --console=plain`: client 14 tests passing; app 1,021 tests, 5 skipped and 1 failing, `AccountClientTest.theAuthorizationUrlCarriesTheExtras`, which resumes a session on the one-character verifier `v` (see what is left).

No live suite was run, nothing was installed on a device, and nothing connected to an account.

## Left, and for the owner

- **One JVM test pins a verifier io-oauth now refuses.** android/app/src/test/java/org/pimalaya/client/AccountClientTest.java, `theAuthorizationUrlCarriesTheExtras`, resumes a session with the verifier `v`, and RFC 7636 section 4.1 asks 43 to 128 characters: `Invalid PKCE code verifier length 1, expected 43 to 128`. The app itself makes 64. The test wants a verifier of 43 characters or more and its challenge (`v` 43 times gives `7w_YNF9DSfIdPf_pRjSq646_kPr-2-o9NAl16JGghdM`). It is Java, which this change does not touch, so the Tests workflow's last step fails until it moves.
- **A listing with unread lines fails where it could be applied.** A server sending a line the codec never decodes (a message whose keyword is no IMAP flag, a mailbox named in raw 8-bit) now fails that mailbox's round, or the account's roster, on every pass, where the line was silently left out. Nothing is dropped, but nothing lands either. Carrying the engine's `capped` on the app's `MailPage` (one boolean in android/client MailPage.java and its Rust mirror) would let such a page apply what it read and keep the rest; a roster needs the same, said its own way.
- **COPY and MOVE pairs.** `Native.relocateMessage` and the copy answer nothing, so the destination UID io-imap now pairs with its source is dropped. Handing it to the engine needs a reply on those two exports and their Java readers.
- **`GpeoplePerson::merge_unprojected`.** An update still replaces each masked field with the vCard's projection, erasing what a vCard does not show (further organizations, other relations, custom types), the client data of other clients aside, which the bridge merges by hand. Adopting it means reading the person before every update, where the bridge reads it only without an ETag or for the stash, and its own audit line calls it unverified against the live API.
- **The redirect's issuer.** Handing io-oauth the expected issuer needs it as the server spells it, which the Java side would have to keep beside the parsed one.
- **`trust`** crosses to Java and is not read.
- **A device check** of every backend: no live suite was run, nothing was installed, no account was reached.

## Capabilities moved

- mail (cairn/spec/mail.md): added "An IMAP listing that skipped a response is not whole"; modified "A message is submitted and a copy is kept" (an envelope address is checked and quoted, one that is none parks the row, a refused recipient is named) and "Mail signs in with a token" (the status of a JMAP request refused whole).
- onboarding (cairn/spec/onboarding.md): modified "OAuth servers are checked" (no loopback exception for an issuer and its metadata) and "A provider's own rules are discovered first" (the `trust` of a discovered service).
- carddav-sync (cairn/spec/carddav-sync.md): added "A DAV listing that is not whole is refused".
