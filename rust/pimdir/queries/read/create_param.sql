-- Fork of CREATE_PARAM (sha256 6869c3b971d73271) for SQLite 3.18:
-- no STRICT (3.37). `value` takes no declared type, so no affinity converts
-- what is bound (`ANY` would read as NUMERIC outside a STRICT table and turn
-- a handle like '007' into 7); the other two keep their type by CHECK.
--
-- The list parameters of the canonical statements (§4.4), one table per
-- connection: a caller fills a statement's slots with bind_param inside the
-- statement's transaction, after clear_param, and the statement joins them.
-- `position` keeps a value bound twice twice, which release_pins counts.
CREATE TEMP TABLE IF NOT EXISTS param (
    slot     TEXT    NOT NULL CHECK (typeof(slot) = 'text'),
    position INTEGER NOT NULL CHECK (typeof(position) = 'integer'),
    value,
    PRIMARY KEY (slot, position)
);
