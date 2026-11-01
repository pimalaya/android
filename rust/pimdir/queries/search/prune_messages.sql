-- Fork of PRUNE_MESSAGES (sha256 016fb613977616b0) for SQLite 3.18: the
-- RETURNING (3.35) as a read of the rows the delete takes, before it, run in
-- one transaction. Within one thread, after unparent_messages, the message
-- rows no live mail placement holds and no row names as its parent (§9).
SELECT link_id FROM message
WHERE thread = :thread
  AND NOT EXISTS (SELECT 1 FROM placement p JOIN store.collections c ON c.id = p.collection
                  WHERE p.link_id = message.link_id AND c.kind = 'message/rfc822')
  AND NOT EXISTS (SELECT 1 FROM message r WHERE r.parent = message.link_id);
DELETE FROM message
WHERE thread = :thread
  AND NOT EXISTS (SELECT 1 FROM placement p JOIN store.collections c ON c.id = p.collection
                  WHERE p.link_id = message.link_id AND c.kind = 'message/rfc822')
  AND NOT EXISTS (SELECT 1 FROM message r WHERE r.parent = message.link_id);
