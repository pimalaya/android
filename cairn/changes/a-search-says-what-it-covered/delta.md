---
cairn: delta
change: a-search-says-what-it-covered
---

Folds into spec/mail.md: the ADDED requirement beside "The mail search reads the index", the MODIFIED one replacing it.

## ADDED Requirements

### Requirement: A mail search says what it did not cover
While a search is shown, the mail list SHALL end on a line saying what the search could not see, read from io-pimdir's search coverage (pimdir SEARCH §8.2) per shown account over the mailboxes the filter lets through, and SHALL show no line when the coverage is complete for every one of them. The coverage of an account SHALL be the temporal axis of its mailboxes' coverage rows over the range the query's date terms narrow to, a mailbox the server's rounds never closed one over counting as filling in, and, for an account its steps bring (Gmail, JMAP), whose rounds name what the store holds over the whole bound, the frontier its steps reached counted as one more coverage floor; with the local axis: the messages in scope whose body the search did not read, counted only under a term reading a body, and the messages the index has not folded in yet. The line SHALL name the account whose floor is the latest and that floor's date, or how many shown accounts fall short with that date when several do; it SHALL say *Still syncing* while a shown mailbox is still filling in, say a floor that is the account's own bound as mail the phone does not keep rather than mail not on the phone yet, and count the messages searched without their body. The app SHALL compute no axis of its own: the bridge answers the coverage and its roll-up beside the hits.

#### Scenario: A mailbox below its floor
- GIVEN a Work inbox whose rounds cover mail since 1 March
- WHEN *roadmap* is searched
- THEN the list ends on *Mail before 1 March on Work is not on the phone yet*

#### Scenario: Two accounts short
- GIVEN Work covered since 1 March and Home since 1 May, and a third account covered whole
- WHEN *roadmap* is searched
- THEN the list ends on *Mail before 1 May on 2 accounts is not on the phone yet*

#### Scenario: A first sync filling in
- GIVEN an inbox whose first round has not closed
- WHEN a word is searched
- THEN the list ends on *Still syncing*

#### Scenario: Bodies not on the phone
- GIVEN an inbox covered whole holding 12 messages without their body
- WHEN a word is searched
- THEN the list ends on *12 messages searched by subject and sender only*, and a search of `from:` alone shows no line

#### Scenario: A complete search
- GIVEN an inbox covered whole, every body on the phone, the index caught up
- WHEN a word is searched
- THEN no line ends the list

#### Scenario: A Gmail account stepped back to May
- GIVEN a Gmail account whose rounds cover its whole bound and whose steps reached 1 May
- WHEN a word is searched
- THEN the list ends on *Mail before 1 May on Gmail is not on the phone yet*

## MODIFIED Requirements

### Requirement: The mail search reads the index
The mail list's search SHALL read its words as a query of pimdir SEARCH §8, answered by the index over every field of every stored message, the body's text included, ordered by date or by name (no relevance order), with the sender and subject match it had before kept beside the index's hits; a query the language refuses SHALL say why on the search field, the sender and subject match still answering. The index SHALL answer the hits with what they could not see per shown account and its roll-up (A mail search says what it did not cover), in the same run.

#### Scenario: A word of a body
- GIVEN a stored message whose body alone holds `roadmap`
- WHEN the user searches `roadmap`
- THEN the message is listed

#### Scenario: A refused query
- GIVEN a message whose subject is `Re: weekly`
- WHEN the user searches `re: weekly`
- THEN the search field says the field `re` is not one of the language's, and the message is listed by its subject

## REMOVED Requirements
