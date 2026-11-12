-- Whether this source binds the server handle :handle live, with a base, in
-- a collection of its account other than :collection and :hidden (Gmail's All
-- Mail, which holds every message a step brought): on a source listed
-- account-wide, one handle names one message in every mailbox, so what a
-- removal from one mailbox must leave in place. A Message-ID names none: two
-- messages may share it, and one message may sit under two keys. Seeks
-- bindings_by_handle once per collection of the account.
SELECT 1 FROM bindings b
JOIN items i ON i.id = b.item
WHERE b.collection IN (SELECT id FROM collections
                       WHERE substr(id, 1, length(:account)) = :account
                         AND id <> :collection AND id <> :hidden)
  AND b.source = :source AND b.handle = :handle
  AND b.base_present = 1 AND substr(b.handle, 1, 1) <> char(1)
  AND i.deleted = 0 AND i.retained_at IS NULL
LIMIT 1;
