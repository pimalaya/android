-- Fork of SET_PERFORMER (sha256 54c382d6d6b17c87) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- Records the source the user chose to perform an intent capability for an
-- account, applying a `set-performer` action (§15.6). The conflict target is
-- the expression index, so NULL is the account of a single-account store.
UPDATE performers SET source = :source
WHERE ifnull(account, '') = ifnull(:account, '') AND capability = :capability;
INSERT INTO performers(account, capability, source)
SELECT :account, :capability, :source
WHERE NOT EXISTS (SELECT 1 FROM performers
                  WHERE ifnull(account, '') = ifnull(:account, '')
                    AND capability = :capability);
