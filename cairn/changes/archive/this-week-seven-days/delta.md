---
cairn: delta
change: this-week-seven-days
---

## ADDED Requirements

## MODIFIED Requirements

### Requirement: The agenda offers the week
The agenda's header SHALL carry the month and year of the shown week, its number and arrows to the weeks either side, over one card of that week's seven days, today in the accent. Pressing the number SHALL bring the card back to this week. Pressing a day SHALL narrow the agenda to that day, the day on a filled disc, and pressing it again SHALL widen it back to the shown week. With no day pressed, the agenda SHALL list the shown week's seven days alone, from the locale's first weekday, this week the same as any other.

#### Scenario: Only Friday
- GIVEN entries on Wednesday, Friday and Saturday, today being Tuesday
- WHEN Friday is pressed in the week
- THEN the agenda shows Friday's entries alone
- AND pressing Friday again shows all three days

#### Scenario: Next week
- GIVEN this week's card
- WHEN the next arrow is pressed
- THEN the card shows next week's days and number, and the agenda that week's entries alone

#### Scenario: This week
- GIVEN entries on this week's Monday, today and in two weeks, today being Wednesday
- WHEN the agenda shows this week with no day pressed
- THEN it lists Monday's and today's entries, and not the one in two weeks

## REMOVED Requirements
