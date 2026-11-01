-- The columns index.db's index_meta declares, none without the table: what
-- tells an index this shape refuses from one it rebuilds (SEARCH §2).
SELECT name FROM pragma_table_info('index_meta');
