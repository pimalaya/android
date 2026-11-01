-- Fork of INSERT_OBJECT (sha256 4374b989b46020e9) for SQLite 3.18: the
-- RETURNING (3.35) as a read of the row by its hash after the insert, run in
-- one transaction.
INSERT INTO object(hash, status, attachment, tel, adr, bday, photo)
VALUES(:hash, :status, :attachment, :tel, :adr, :bday, :photo);
SELECT id FROM object WHERE hash = :hash;
