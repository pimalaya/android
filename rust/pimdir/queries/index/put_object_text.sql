-- A body's text by field (SEARCH §6), keyed on its object row by its hash
-- rather than a returned id, so a refresh writes a body in one round: the
-- insert_object_text of the app's own, docid = object.id.
INSERT INTO object_text(docid, title, people, body, attachment, place, org, note,
                        "from", "to", cc, bcc, email, organizer, attendee)
SELECT o.id, :title, :people, :body, :attachment, :place, :org, :note,
       :from, :to, :cc, :bcc, :email, :organizer, :attendee
FROM object o WHERE o.hash = :hash;
