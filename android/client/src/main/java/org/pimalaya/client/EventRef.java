package org.pimalaya.client;

/** One member of an enumerated calendar: its resource name and ETag. */
public final class EventRef {
    public final String id;

    /** Entity tag guarding concurrent updates, or null. */
    public final String etag;

    public EventRef(String id, String etag) {
        this.id = id;
        this.etag = etag;
    }
}
