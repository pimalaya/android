-- A schema read as one from elsewhere (STORAGE §18): no function it names is
-- trusted. SQLite before 3.31 has no such pragma and ignores it, its schema
-- functions running trusted there, the hardening unavailable.
PRAGMA trusted_schema = OFF;
