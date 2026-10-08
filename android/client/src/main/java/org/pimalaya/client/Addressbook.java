package org.pimalaya.client;

/** An addressbook listed from the server. */
public final class Addressbook {
    public final String id;
    public final String name;

    /** Absolute collection URL, the target of every card operation. */
    public final String url;

    /** Free-form description, or null. */
    public final String description;

    /** Display colour, or null. */
    public final String color;

    /**
     * What the source says the book is for, in pimdir's role vocabulary:
     * {@link Calendar#DEFAULT} for its default book (JMAP {@code isDefault},
     * Graph's default contacts folder, Google's {@code myContacts}), empty
     * otherwise.
     */
    public final String role;

    /** Whether the user may write cards into it; true where the source says nothing. */
    public final boolean writable;

    public Addressbook(String id, String name, String url, String description, String color) {
        this(id, name, url, description, color, "", true);
    }

    public Addressbook(
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
