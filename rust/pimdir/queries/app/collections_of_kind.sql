-- An account's collections of one kind, by id.
SELECT id FROM collections WHERE account = :account AND kind = :kind;
