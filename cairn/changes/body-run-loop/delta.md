---
cairn: delta
change: body-run-loop
---

Folds into `spec/mail.md`.

## ADDED Requirements

### Requirement: A body gone is given up until a new listing
A message the server answers gone when its body is asked for (a 404 on Gmail or Graph, a UID with no size on IMAP, no email for its id on JMAP) SHALL be told apart from a body not handed over: the bridge SHALL name it, it SHALL NOT count as a failed attempt, it SHALL be owed no more, and no run SHALL plan it until its mailbox is listed anew, the next listing taking it away. Its row SHALL say *Not downloaded*.

#### Scenario: A message deleted for good after the listing
- GIVEN an owed Gmail message deleted for good after the history replay that listed it
- WHEN the body step asks for it
- THEN it is asked once, owed no more, and no later run asks for it until its mailbox is listed again

## MODIFIED Requirements

### Requirement: A body that fails is tried again in the run
A body the server does not hand over SHALL be put back at the end of its account's plan and tried again later in the same run, on a reopened session, at most 3 times a run. A message whose body failed every attempt SHALL say so in its row (*Download failed*), and no run SHALL plan it again until its mailbox is listed anew or the app starts again, so a run never starts for it alone. A download failing as a whole SHALL put its messages back the same way, an expired token renewed when the session reopens; an account whose download fails as a whole twice in a row SHALL leave the run.

#### Scenario: One body refused once
- GIVEN a body step whose server fails one message's read once
- WHEN the run goes on
- THEN that message is read again after the others and stored

#### Scenario: A body refused every time
- GIVEN a body step whose server fails one message's read every time
- WHEN its 3 attempts are spent and the step is started again before any listing
- THEN nothing is planned and nothing is asked of the server, until its mailbox is listed again

#### Scenario: A token expiring mid-run
- GIVEN an account whose token expires while its bodies download
- WHEN the next chunk is read
- THEN the token is renewed and the run goes on

### Requirement: Bodies download behind the list
For each account that is on, the app SHALL raise to `Full`, through the engine's upgrade, every listed message dated on or after its mailbox's frontier in every mailbox but the junk and the trash, every listed message of a mailbox kept whole, and every owed message (Arrivals download whatever their date), after each pass, step and fill step while the app is open: newest first, the mailboxes the mail filter shows before the account's others, the owed ones last, at most 24 bodies an account a step. A step's own messages SHALL be stored from the files it read, never read again. A message with no date SHALL be downloaded only under a frontier of all mail or in a mailbox kept whole, and a pending create never. Whether a message is taken SHALL be read again against the current frontier as its chunk downloads. A body the store already holds under the same link id SHALL be linked rather than fetched where the link id names the message across mailboxes (A link id names a message across mailboxes only where the account lists them as one). The body SHALL be stored as the bytes the server sent, its base moved to it, its summary and sort key kept, its attachment mark restated from its parts. The step SHALL run only while the app is in the foreground, no other sync runs, and on Wi-Fi; it SHALL never be periodic work, and SHALL stop when the user starts a sync or leaves the app. It SHALL start again only on a real trigger (a pass, a step, a fill step, the app returning to the foreground, Wi-Fi coming back), never on a capability change of a Wi-Fi network that stays up. A body that fails SHALL be tried again in the run (A body that fails is tried again in the run).

#### Scenario: Within the frontier only
- GIVEN an account whose frontier is 1 September, holding a header from yesterday and one from March
- WHEN the body step runs
- THEN yesterday's body is downloaded and stored, the March one stays a summary

#### Scenario: Junk and trash
- GIVEN a message from yesterday in the junk, its mailbox not kept whole
- WHEN the body step runs
- THEN its body is not downloaded

#### Scenario: A metered network
- GIVEN an account on mobile data
- WHEN a pass would end
- THEN no body is downloaded until Wi-Fi is back

#### Scenario: The same message under two labels
- GIVEN a Gmail message under two labels, its body downloaded in one
- WHEN the body step reaches the other
- THEN the body is linked from the store and not read again

#### Scenario: Wi-Fi staying up
- GIVEN the app open on Wi-Fi, the body step done
- WHEN Wi-Fi reports a new signal strength
- THEN no run starts

## REMOVED Requirements
