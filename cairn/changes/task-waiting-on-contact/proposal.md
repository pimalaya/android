---
cairn: change
id: task-waiting-on-contact
status: active
created: 2026-10-30
---

# Waiting on: a contact, linked as attachments and files are

Deferred (user, 2026-10-30): written down to be done later, after a pimdir rule.

## Why

A Waiting For task names who it waits on in free text. The person is usually a contact, and the app already links items to each other (pimdir references, STORAGE §14.2, the Linked card of cairn/changes/item-links), but nothing ties a task to the contact it waits on: the row opens a text field, and the contact page cannot say what someone owes.

## What

### On the wire: unchanged

- `CONTACT` stays the name with `ALTREP="mailto:<address>"`, as pimdir GTD.md writes it and RFC 5545 3.8.4.2 allows. An address reads in every client, survives the contact being deleted or merged, and works across accounts; a vCard `UID` (`urn:uuid:`) in `ALTREP` would mean nothing outside the store and would change pimdir's convention.

### In the store: an automatic reference

- pimdir gains a rule, beside `link_senders_of`, `link_mail_from` and `link_invitations_to` (cairn/changes/automatic-references): a task whose `CONTACT` carries a `mailto:` address references every contact holding that address, kind `waits on`. It runs after a task is written and after a card is written, as `link_invitations_to` does. The rule is pimdir's to state (its GTD.md and GUIDE); the app runs the statement.

### In the app

- The Waiting on row picks a contact: a search over the contacts, a contact with several addresses asking which one, the pick writing its name and that address. Free text stays possible and links nothing until an address matches.
- The row opens the contact's page when the reference stands.
- The task page gains the Linked card the entry page carries, the mail a task came from becoming one of its rows (*Captured from*) in place of the separate From section.
- The contact page's Linked card lists the tasks waiting on the person, *Waits on* as the reason: what someone owes, for the weekly review.

## Out of scope

- A contact with no address: only the name is written, and only a link the person adds by hand (a `user` reference) ties it, which other clients do not see.

## Open

- The reference kind's name and wording, and whether a closed task keeps its reference listed.
