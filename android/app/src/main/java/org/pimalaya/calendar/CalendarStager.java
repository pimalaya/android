package org.pimalaya.calendar;

import org.pimalaya.client.Clients;
import org.pimalaya.phone.PhoneQueue;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.Stager;

/** The calendar driver that only stages: event writes, and the phone queue they reach. */
public final class CalendarStager extends Stager {
    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

    public CalendarStager(PimdirDb pimdir, Clients clients) {
        super(pimdir, clients);
        this.pimdir = pimdir;
    }

    @Override
    protected void staged(String collection) {
        staged(pimdir, collection);
    }

    /**
     * A write to a calendar reaches the phone's calendar within a second
     * ({@link PhoneQueue}); a write to the phone collection is a phone
     * pass's own, pushed by that pass.
     */
    static void staged(PimdirDb pimdir, String collection) {
        if (!PimdirStorage.isPhoneCollection(collection)) {
            PhoneQueue.calendarWritten(pimdir.context(), collection);
        }
    }
}
