---
cairn: delta
change: attachments-as-files
---

Folds into `spec/mail.md`.

## ADDED Requirements

### Requirement: A stored body records its attachments as files
When a message's body is stored, by an open or by the body step, the app SHALL record each part the bridge lists as an attachment as a file holding no body (pimdir STORAGE §14.3): keyed `part:`, the message's link id, `#` and the part's IMAP section (RFC 3501 §6.4.5), at `Meta`, with its name, media type (none when the part states none), decoded size and section in `file_summary`, in the account's attachments collection, and SHALL record an `attachment` reference of origin `auto` from the message to it in the same transaction. The attachments collection SHALL be one per account, of kind `application/octet-stream`, synced by no source, created when first needed, and dropped with the account. A part already recorded SHALL be left as it is. A stand-in SHALL go once no reference names it, which the store does when the message's last row goes.

#### Scenario: Opening a message with an invoice
- GIVEN a message whose second part is `invoice.pdf`
- WHEN it is opened for the first time
- THEN a file `part:<link id>#2` holding no body is recorded, referenced by the message

#### Scenario: The same body stored twice
- GIVEN a message whose attachments are recorded
- WHEN its body is stored again
- THEN nothing new is recorded

### Requirement: The reader opens and saves an attachment
The reader SHALL list a message's attachments from the store (`list_attachments`), whether or not its body is held. Tapping one SHALL offer to open it or save it to a folder. Its bytes SHALL be a saved copy's body when one exists, else the part read from the message's body by its section (`messagePart`), else the message SHALL be fetched and stored first, as an open does; with no network and no body, the reader SHALL say the message is not on the phone. Opening SHALL hand a read-only `content://` copy to the app the phone picks for the media type. Saving SHALL offer the device's folders, collections of kind `application/octet-stream` on the device account, and a new one by name; it SHALL store the bytes as a blob, deduplicated by hash, and place the attachment's key in the folder with that body, sharing its public id, the stand-in staying. A folder already holding it SHALL be left as it is.

#### Scenario: Saving the same invoice from two messages
- GIVEN two messages attaching the same bytes
- WHEN both attachments are saved to one folder
- THEN the folder holds two files and the store one blob

#### Scenario: A message whose body was released
- GIVEN a message with recorded attachments and no body held
- WHEN an attachment is opened online
- THEN the message is fetched and stored, and the part opens

## MODIFIED Requirements

## REMOVED Requirements
