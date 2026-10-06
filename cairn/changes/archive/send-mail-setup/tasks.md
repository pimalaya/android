---
cairn: change
id: send-mail-setup
status: landed
---

# Tasks

## The standard setup

- [x] Give DomainSetup a submission pick beside its selected option, so the mail section carries two answers rather than one.
- [x] Draw a *send mail* switch under the mail switch in the standard setup, on when submissionUrl() found an endpoint, off and unswitchable when it did not, with the note naming the advanced setup.
- [x] Carry the pick into commitConnections() rather than reading setup.selected.submitUrl, so switching it off stores no submit endpoint.
- [x] Strings, English and French: the switch, its out-of-reach note.

## The advanced setup

- [x] List every discovered SMTP configuration as its own rows under a switched-on mail section, labelled SMTP and detailed with the host, with a none row and a manual entry row.
- [x] Keep the rows out of the other two domains: submission is mail's alone.
- [x] Prompt manual entry for a host or host:port and build smtps://host:port, port 465 by default, refusing nothing else.
- [x] Skip a discovered SMTP configuration whose security is not implicit TLS, logging it as endpointUrl() does.
- [x] Strings, English and French: the SMTP rows, the none row, the manual prompt.

## The repair

- [x] Add a mail section to AccountSettings for an account covering mail, showing where it submits and offering the advanced setup's rows.
- [x] Commit a changed submit endpoint through AccountEntry and SecureStore, leaving the credential alone.
- [x] Strings, English and French.

## The queue

- [x] Enqueue a submit action in one transaction: blob written, ensure_collection, store_object, pin_object, enqueue_action, under the shared staging lock.
- [x] Overlay the account's pending submit actions where MailStore draws the outbox, and show a parked one as failed.
- [x] Drain one account's pending actions in append order, and acknowledge a handed-over message with cancel_action rather than claiming it first: a claim deletes the row, and deleting it before the server accepted the message would lose the message.
- [x] Park a refusal the server calls permanent with its error and carry on to the rows behind it; bump_attempts on an environment failure and stop the drain there.
- [x] Discard a pending message by cancel_action, releasing its pin.
- [x] Retire ensureOutbox, isOutbox and the roster-replace exemption; MailStore.OUTBOX stays as the collection the queue rows address.
- [x] Migrate an outbox holding messages at upgrade: the rows are enqueued as the submissions they were and dropped, before the first roster replace would cascade them away.

## Proof

- [x] Unit tests: the enqueue pins and the cancel releases, a parked row leaves the drain, an unknown kind is skipped untouched, an outbox of items moves onto the queue.
- [x] Unit tests: a 5yz reply parks and a 4yz one retries, whichever step of the send earned it.
- [ ] Not written: the onboarding's own URL building, which needs a host activity to reach. The rows are checked by reading, not by a test.
- [x] `nix develop --command gradle -p android :app:assembleDebug :app:testDebugUnitTest --console=plain` green.
- [x] `cargo fmt` on rust/ if the bridge moved.

## Fold

- [x] Fold delta.md into cairn/spec/onboarding.md and cairn/spec/mail.md.
- [x] Write cairn/log/2026-09-12-send-mail-setup.md.
- [x] Roll the user-facing half into CHANGELOG.md.
