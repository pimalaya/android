-- Fork of OPEN_ROUND (sha256 f823909086897f88) for SQLite 3.18:
-- the upsert (3.24) as an update of the row, then its insert where none is
-- there, run in one transaction.
--
-- Opens a round over the scope :since and :until (NULL bounds open), in the
-- write of its first page (SYNC.md §5), :band 1 when it lists only the band
-- the coverage lacks. It draws the next round id, so a round restarted after
-- the source refused its cursor stamps afresh and the stamps of the abandoned
-- listing name nothing; the cursor and the checkpoint of an earlier attempt
-- go with it. The checkpoint and the coverage stay until the round closes.
UPDATE sources SET
    round = round + 1,
    round_since = :since, round_until = :until,
    round_cursor = NULL, round_checkpoint = NULL,
    round_started_at = strftime('%Y-%m-%dT%H:%M:%fZ','now'), round_band = :band
WHERE collection = :collection AND source = :source;
INSERT INTO sources(collection, source, round, round_since, round_until, round_started_at, round_band)
SELECT :collection, :source, 1, :since, :until, strftime('%Y-%m-%dT%H:%M:%fZ','now'), :band
WHERE NOT EXISTS (SELECT 1 FROM sources WHERE collection = :collection AND source = :source);
