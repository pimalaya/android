-- Fork of LINK_MAIL_FROM (sha256 31daac7256b51485) for SQLite 3.18:
-- `ON CONFLICT DO NOTHING` (3.24) as an insert of the references not
-- recorded yet, the rows distinct on their key; any other constraint still
-- raises, where `INSERT OR IGNORE` would swallow it.
--
-- The sender rule from the other end (§14.2): the references
-- link_senders_of records, for the mail from any `email` of one contact, so
-- a contact added today, or given an address, has its history. :link_id
-- names the contact, NULL every contact, on link_senders_of's range. An
-- address removed removes nothing.
INSERT INTO item_reference(from_kind, from_link_id, to_kind, to_link_id, role, origin, created_at)
SELECT DISTINCT 'message/rfc822', mi.link_id, 'text/vcard', ci.link_id, 'sender', 'auto',
       strftime('%Y-%m-%dT%H:%M:%fZ', 'now')
FROM items ci
JOIN collections cc ON cc.id = ci.collection AND cc.kind = 'text/vcard'
JOIN item_address c ON c.item = ci.id AND c.role = 'email'
JOIN item_address m ON m.address = c.address AND m.role = 'from'
JOIN items mi ON mi.id = m.item AND mi.deleted = 0
JOIN collections mc ON mc.id = mi.collection AND mc.kind = 'message/rfc822'
WHERE ci.link_id BETWEEN coalesce(:link_id, '') AND coalesce(:link_id, x'') AND ci.deleted = 0
  AND NOT (mi.link_id GLOB 'alt:*' OR mi.link_id GLOB 'dup:*' OR mi.link_id GLOB 'hash:*') AND NOT (ci.link_id GLOB 'alt:*' OR ci.link_id GLOB 'dup:*' OR ci.link_id GLOB 'hash:*')
  AND NOT EXISTS (SELECT 1 FROM item_reference r
                  WHERE r.from_kind = 'message/rfc822' AND r.from_link_id = mi.link_id
                    AND r.to_kind = 'text/vcard' AND r.to_link_id = ci.link_id
                    AND r.role = 'sender');
