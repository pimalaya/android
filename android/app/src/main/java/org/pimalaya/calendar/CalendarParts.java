package org.pimalaya.calendar;

import org.pimalaya.client.Clients;
import org.pimalaya.pimdir.PimdirDb;

/** The calendar screens' ground: the store, the bridge, the event store. */
public record CalendarParts(PimdirDb pimdir, Clients clients, EventStore events) {}
