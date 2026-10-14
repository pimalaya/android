package org.pimalaya.calendar;

import org.pimalaya.client.PimalayaClient;
import org.pimalaya.pimdir.PimdirDb;

/** What the calendar screens work over: the store, the bridge and its calendar side. */
public record CalendarParts(PimdirDb pimdir, PimalayaClient client, EventStore events) {}
