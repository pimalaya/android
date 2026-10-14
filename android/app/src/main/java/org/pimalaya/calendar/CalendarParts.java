package org.pimalaya.calendar;

import org.pimalaya.client.Clients;
import org.pimalaya.pimdir.PimdirDb;

/** What the calendar screens work over: the store, the bridge and its calendar side. */
public record CalendarParts(PimdirDb pimdir, Clients clients, EventStore events) {}
