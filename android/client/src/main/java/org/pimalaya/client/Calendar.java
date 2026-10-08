package org.pimalaya.client;

/** A calendar listed from the server. */
public final class Calendar {
    /** The role a source gives the calendar it writes to when none is named. */
    public static final String DEFAULT = "default";

    public final String id;
    public final String name;

    /** Absolute collection URL, the target of every event operation. */
    public final String url;

    /** Free-form description, or null. */
    public final String description;

    /** Display colour, or null. */
    public final String color;

    /**
     * What the source says the calendar is for, in pimdir's role vocabulary:
     * {@link #DEFAULT} for its default calendar (JMAP {@code isDefault},
     * Graph's default calendar, Google's primary one), empty otherwise.
     */
    public final String role;

    /** Whether the user may write events into it; true where the source says nothing. */
    public final boolean writable;

    public Calendar(String id, String name, String url, String description, String color) {
        this(id, name, url, description, color, "", true);
    }

    public Calendar(
            String id,
            String name,
            String url,
            String description,
            String color,
            String role,
            boolean writable) {
        this.id = id;
        this.name = name;
        this.url = url;
        this.description = description;
        this.color = color;
        this.role = role;
        this.writable = writable;
    }
}
