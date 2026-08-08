package org.pimalaya.client;

/** A CalDAV calendar listed from the server. */
public final class Calendar {
    public final String id;
    public final String name;

    /** Absolute collection URL, the target of every event operation. */
    public final String url;

    /** Free-form description, or null. */
    public final String description;

    /** Display colour, or null. */
    public final String color;

    public Calendar(String id, String name, String url, String description, String color) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.description = description;
        this.color = color;
    }
}
