---
cairn: delta
change: a-message-is-filed-somewhere
---

Folds into `spec/mail.md`: the ADDED requirement beside "An account's mail comes in steps", the MODIFIED one replacing "A Gmail account keeps its step's messages under the hood".

## ADDED Requirements

### Requirement: A stepped message is filed somewhere
Every message a Gmail step brings SHALL be filed in one mailbox at least: the bridge SHALL hand it over with a set of mailboxes that cannot be empty, All Mail always among them beside each mailbox its labels make it a member of. A mailbox name a step hands over that resolves to no collection the store holds for the account (a label made since the store last read the mailboxes) SHALL be logged and counted, the message still filed in the mailboxes that do resolve. Only a Gmail account SHALL step through the account; every other account steps from the store, and the bridge SHALL refuse an account step for it.

#### Scenario: A label the store does not hold yet
- GIVEN a Gmail message labelled with the inbox and a label made since the store last read the account's mailboxes
- WHEN a step brings it
- THEN it lands in the inbox and All Mail, and the unknown label is logged and counted

## MODIFIED Requirements

### Requirement: A Gmail account keeps its step's messages under the hood
A Gmail account SHALL hold a hidden collection standing for its account-wide step, Gmail's All Mail, every message a step brings landing in it beside the mailboxes its labels name, a message filed under no mailbox label (archived) landing in it alone, with no body. Every message a Gmail step hands over SHALL name All Mail among its mailboxes (A stepped message is filed somewhere). It SHALL be among the mailboxes a step asks the store whether it holds a message in, so no step reads a message twice, and SHALL NOT be listed: not on the filter page, not under a chip, not in a list or a search. A step over the trash or the junk alone SHALL ask over that mailbox alone, what lies there having been taken out of every other, and SHALL file nothing in All Mail.

#### Scenario: An archived message
- GIVEN a Gmail message filed under no mailbox label
- WHEN a step brings it
- THEN it is stored, no mailbox shows it, and the next step does not read it again

## REMOVED Requirements

None. The Graph and JMAP account-wide steps the bridge still carried (a Graph keyset walk below the frontier, a JMAP anchored `Email/query`) were reached by no requirement: both accounts step from the store ("An account's mail comes in steps"), so their removal takes nothing out of the spec.
