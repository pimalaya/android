---
cairn: log
change: rows-and-week-filter
landed: 2026-10-07
---

# One row shape, and a week that filters

Capabilities moved: mail (modified: a row's trailing marks), calendar (modified: the agenda offers the week), offline-store (added: the three lists share one row; modified: the contacts list selects like mail, its origin sentence moving into the shared row).

**Rows.** `item_contact.xml` and `item_event.xml` take the shape of `item_message.xml`: a 14dp lead, a 42dp disc topped by 12dp, a column with a hairline (hidden on a card's first row through `CardSections.opensCard`), then 12dp-padded lines. The contact's warning moves from a trailing slot onto the name's line. The agenda's leading start column becomes the time ending the first line. The contact's origin (`ContactsList.originOf`) names the first card an account holds, so a contact also kept on the device no longer reads "This application".

**Mail.** The unread dot moves from the subject's marks to after the time. The bottom bar's badge sits 14dp in from its indicator's end, over the envelope's corner.

**Week.** The weekday labels sat left: a vertical `LinearLayout` lays a child out at full width by default, so the label filled its cell. They now centre. A day pressed now filters rather than scrolls (`CalendarList.render`): the agenda shows that day alone, and pressing it again shows everything from today on. The expansion window starts at the week's first day, so the days already gone this week can be picked too.

**Open.** How to see a whole week or month at once is undecided: today the unfiltered agenda is everything ahead, and no other week can be reached from the card.
