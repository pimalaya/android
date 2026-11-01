-- A bodiless placement's summary text (SEARCH §6), by its key:
-- insert_summary_text keyed on the placement's id, docid = placement.id.
INSERT INTO summary_text(docid, title, people, "from", "to", cc, bcc, email, organizer, attendee)
SELECT p.id, :title, :people, :from, :to, :cc, :bcc, :email, :organizer, :attendee
FROM placement p WHERE p.collection = :collection AND p.seq = :seq;
