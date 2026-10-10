---
cairn: delta
change: files-tab
---

Folds into `spec/mail.md`, after the requirements attachments-as-files adds (a files capability can be split out once remotes land).

## ADDED Requirements

### Requirement: Files is a fourth tab
The bottom bar SHALL offer Files after mail, contacts and calendars, in the same list design: a large title, a meta line counting the files shown, a search over the file name and the sender of its message, chips, the filter page and an extended add button creating a folder. The list SHALL show one card per file collection the filter lets through: the device's folders by name, then each account's attachments, headed with the account. A folder with no file SHALL show a placeholder row. One chip group SHALL narrow to folders or to attachments, another to images, PDFs or documents by media type. An attachment row SHALL name the sender of the message it came from.

#### Scenario: An attachment received from Alice
- GIVEN a message from Alice whose body is stored, attaching `report.pdf`
- WHEN the Files tab is opened
- THEN `report.pdf` is listed under the account's attachments, from Alice
- AND the PDFs chip keeps it, the Images chip leaves it out

### Requirement: A file can be opened, shared, saved, exported and traced
Tapping a file SHALL offer to open it, share it, save it to a folder and export it to a document the user picks; *Show the message* when a message references it, opening that message in the reader; and delete when it is a folder's copy, which releases its body once no file holds it. A stand-in SHALL NOT be deletable by itself. A stand-in's bytes SHALL be read from its message as the reader reads an attachment.

#### Scenario: Deleting a saved copy
- GIVEN an attachment saved to a folder
- WHEN the folder's copy is deleted
- THEN the attachment stays listed under its account, and the body goes when no other file holds it

### Requirement: Folders are created, renamed, deleted and imported into
A folder SHALL be a file collection of the device account, created from the add button under a name no other folder holds, renamed under the same rule, and deleted with every file in it after asking. Tapping a folder's header or placeholder SHALL offer to import a file into it through the system's document picker, the file keyed `file:` and 128 random bits, named as its provider names it.

#### Scenario: Importing a document
- GIVEN a folder Taxes
- WHEN a PDF is imported into it from the phone's storage
- THEN Taxes lists it under its name, with its body stored once

### Requirement: The Files filter lists attachments apart from folders
The filter page of Files SHALL list the device's folders under *On this device* and each account that is on with its attachments collection, named *Attachments*, never as a folder. Hiding one SHALL hide its files from the tab.

#### Scenario: Hiding an account's attachments
- GIVEN two accounts with attachments
- WHEN one account is unticked on the Files filter page
- THEN the tab shows the other account's attachments and the folders alone

## MODIFIED Requirements

## REMOVED Requirements
