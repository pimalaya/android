---
cairn: delta
id: one-connection-per-pass
---

# Delta

## ADDED Requirements

### Requirement: A pass opens its connections once
A sync pass SHALL open its connections when it starts and close them when it ends, and every verb it runs SHALL use them. It SHALL NOT open a connection per verb.

#### Scenario: A mail pass with changes to push
- GIVEN an account with three staged markers
- WHEN the mailbox is synced
- THEN one session carries the walk and all three writes

#### Scenario: A calendar pass
- GIVEN an account with three calendars
- WHEN the agenda is refreshed
- THEN one transport carries the calendar listing, every event listing and every write

#### Scenario: A fan-out
- GIVEN a push the contacts driver runs over several workers
- WHEN it runs
- THEN each worker has its own connection, none of them shared

### Requirement: A mail session outlives the call that opened it
The bridge SHALL hold an IMAP session across native calls, connected and authenticated once, addressed by a handle the caller keeps. Nothing on the bridge side SHALL hold a JNI reference between calls: the caller owns the transport and passes it back on every call.

#### Scenario: A second command on one session
- GIVEN an open session
- WHEN a second verb runs on it
- THEN it sends its command without a greeting or an authentication

#### Scenario: The session is closed
- GIVEN an open session
- WHEN the pass that opened it ends
- THEN the handle is freed and the sockets are closed

#### Scenario: A run of commands on one mailbox
- GIVEN several commands against the same mailbox
- WHEN they run on one session
- THEN the mailbox is selected once

### Requirement: A connection the server dropped is reopened once
A verb failing on a session or a transport the pass was holding SHALL reopen it and run once more, and SHALL report the failure only if that fails too. A server's idle timeout, a rebound NAT and a walk from wifi to cellular all end a connection under the app, so a held one is a hint and never a promise.

#### Scenario: An idle timeout
- GIVEN a session the server has since closed
- WHEN the next verb runs on it
- THEN the session is reopened and the verb succeeds

#### Scenario: The reopen fails too
- GIVEN no network at all
- WHEN a verb runs on a held session
- THEN the failure is reported rather than retried forever
