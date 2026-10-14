/**
 * Calendars: calendars and events, their sync with servers and the phone's
 * calendar provider, and the agenda.
 *
 * <p>{@link EventStore} keeps calendars as pimdir collections and events as
 * the iCalendar text the server sent. {@link CalendarEngine} syncs a calendar
 * with its server, and {@link CalendarStager} stages an edit for the next
 * pass.
 *
 * <p>{@link CalendarPhonePass} syncs a calendar with the phone's
 * {@code CalendarContract} rows ({@link CalendarRemote}, {@link CalendarRows}),
 * mapped by {@link CalendarMapping} and pinned on the JVM
 * (docs/calendar-mapping.md).
 *
 * <p>{@link CalendarList} is the agenda, its occurrences expanded by the
 * bridge as it loads; {@link EventView} shows and edits one entry.
 * {@link Zones} places a civil time at an instant through the platform's
 * time-zone database.
 */
package org.pimalaya.calendar;
