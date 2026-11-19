---
cairn: delta
change: google-contact-refusals
---

# Delta

Folds into `spec/carddav-sync.md`.

## ADDED Requirements

None.

## MODIFIED Requirements

### Requirement: An add the server cannot take whole is held and reported
An add whose body differs from every copy the account holds SHALL NOT be sent as a bare membership patch, nor as a second card, nor over the server's copy. On Google, an add superseding a removal (a move carrying an edit) SHALL be delivered as an update of the card the server holds, guarded by that card's base, then the membership patch; a conflict answers the move refused, as a push refused as it stands is (A contact push refused as it stands is held): the pending add held under its refusal and reported every sync, never tried again by itself until the person retries or drops it, and the source's removal held until the move goes through or is dropped. Any other such add SHALL be held, staged, and reported. At the end of a sync, and again whenever the set changes, a "Not sent yet" dialog SHALL list each held add, each held move removal and each deletion made in the phone's own Contacts app that waits for a held change, by contact and book, and the contact's row SHALL carry the diverged marker, reopening that report. An add whose body another book stages as its own unsent edit SHALL NOT be reported while that book syncs with its server; one whose server sync is off never sends the edit, so the add SHALL be reported. The report SHALL be computed from the store (and the phone's deleted raw contacts) when shown, so it survives a restart and drops what was sent.

#### Scenario: A contact edited and moved in one save
- GIVEN a Google contact in Contacts
- WHEN the user renames it and moves it to Seed amis in one save, then syncs
- THEN Google's person is updated under its etag, then moved, and nothing is reported

#### Scenario: A move whose edit meets a remote edit
- GIVEN a Google contact renamed and moved on the phone while the web edited the same name
- WHEN the phone syncs
- THEN nothing overwrites Google's copy, the edit stays on the phone, the move is reported refused and the next sync asks Google nothing for it

#### Scenario: An add carried by a book whose sync is off
- GIVEN a Google contact ticked into Seed amis and renamed in both Contacts and Seed amis, with Contacts' server sync off
- WHEN Seed amis syncs
- THEN nothing is sent, and "Not sent yet" names the contact in Seed amis

## REMOVED Requirements

None.
