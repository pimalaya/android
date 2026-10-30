package org.pimalaya.account;

import org.pimalaya.R;
import org.pimalaya.client.ServiceConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The three kinds of data the app manages, and the services serving them.
 *
 * <p>A domain is an axis of one account, not an account: splitting would make
 * an expired token three repairs. JMAP (RFC 8620) is one session serving the
 * domains its capabilities name ({@link #servedByJmap}).
 */
public enum PimDomain {
    MAIL("mail", R.string.domain_mail, R.drawable.ic_domain_mail, "urn:ietf:params:jmap:mail"),
    CONTACTS(
            "contacts",
            R.string.domain_contacts,
            R.drawable.ic_domain_contacts,
            "urn:ietf:params:jmap:contacts"),
    CALENDAR(
            "calendar",
            R.string.domain_calendar,
            R.drawable.ic_domain_calendar,
            "urn:ietf:params:jmap:calendars");

    /** The capability JMAP mail is sent with (RFC 8621 section 1.3.2). */
    static final String JMAP_SUBMISSION = "urn:ietf:params:jmap:submission";

    /** The stored spelling; the enum name is not persisted. */
    public final String id;

    /** The label a picker shows. */
    public final int label;

    /** The glyph beside that label, the one the domain bar draws. */
    public final int icon;

    /** The JMAP capability URN the domain is read with. */
    final String jmapCapability;

    PimDomain(String id, int label, int icon, String jmapCapability) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.jmapCapability = jmapCapability;
    }

    /**
     * What an account's settings call the collections of this domain it
     * syncs (cairn/changes/one-filter-panel).
     */
    public int syncLabel() {
        switch (this) {
            case MAIL:
                return R.string.sync_mailboxes;
            case CALENDAR:
                return R.string.sync_calendars;
            default:
                return R.string.sync_books;
        }
    }

    /** The domain a stored id names, contacts when it names none. */
    public static PimDomain byId(String id) {
        for (PimDomain domain : values()) {
            if (domain.id.equals(id)) {
                return domain;
            }
        }
        // NOTE: an account stored before accounts had a domain is a contacts
        // account, because contacts is all the app could do then. Defaulting
        // rather than dropping is what keeps those accounts working.
        return CONTACTS;
    }

    /**
     * The domains one discovered service can serve.
     *
     * <p>Most services serve exactly one, and JMAP serves all three from a
     * single session. Anything the app has no reader for (generic WebDAV,
     * ManageSieve, POP3) serves none and is never offered.
     */
    static Set<PimDomain> servedBy(ServiceConfig config) {
        return servedBy(config.service());
    }

    /** The domains one discovered service name can serve. */
    static Set<PimDomain> servedBy(String service) {
        Set<PimDomain> domains = new LinkedHashSet<>();
        switch (service) {
            // NOTE: no SMTP. It sends mail rather than reading it, so it rides
            // under the mail connection as where that one submits, and a
            // domain served by SMTP alone would be one nothing can read.
            case "imap":
            case "msgraph":
            case "gmail":
                domains.add(MAIL);
                break;
            case "carddav":
                domains.add(CONTACTS);
                break;
            case "caldav":
            case "msgraphCalendar":
            case "gcal":
                domains.add(CALENDAR);
                break;
            // NOTE: every domain with a capability to be read by, which a
            // session may or may not advertise: that is only known once
            // signed in, and the probe drops what it does not serve. The
            // rule this follows is the mapping never leads the reader: a
            // domain with no client to read it produced an account that
            // connected, saved, and was then never read.
            case "jmap":
                for (PimDomain domain : values()) {
                    if (domain.jmapCapability != null) {
                        domains.add(domain);
                    }
                }
                break;
            default:
                break;
        }
        return domains;
    }

    /**
     * The domains a JMAP session serves, by its advertised capability URNs.
     *
     * <p>Mail only with submission beside it: a mail account that cannot answer
     * is not what connecting mail asks for.
     */
    public static Set<PimDomain> servedByJmap(Set<String> capabilities) {
        Set<PimDomain> served = new LinkedHashSet<>();
        for (PimDomain domain : servedBy("jmap")) {
            if (capabilities.contains(domain.jmapCapability)
                    && (domain != MAIL || capabilities.contains(JMAP_SUBMISSION))) {
                served.add(domain);
            }
        }
        return served;
    }

    /**
     * Where a discovered service ranks for this domain, lower first.
     *
     * <p>JMAP first for mail and contacts; CalDAV over JMAP for calendars,
     * whose JMAP support is known only once signed in (the probe then drops the
     * domain, no fallback); the DAVs over the rest.
     */
    public int rank(String service) {
        if (service == null) {
            return 2;
        }
        switch (service) {
            case "jmap":
                return this == CALENDAR ? 1 : 0;
            case "carddav":
            case "caldav":
                return this == CALENDAR ? 0 : 1;
            default:
                return 2;
        }
    }

    /**
     * The discovered configs that can serve this domain, in discovery order.
     */
    public static List<ServiceConfig> configsFor(PimDomain domain, List<ServiceConfig> configs) {
        List<ServiceConfig> serving = new ArrayList<>();
        for (ServiceConfig config : configs) {
            if (servedBy(config).contains(domain)) {
                serving.add(config);
            }
        }
        return serving;
    }

    /**
     * The domains a discovery run found, in declaration order (stable picker).
     */
    static List<PimDomain> found(List<ServiceConfig> configs) {
        List<PimDomain> found = new ArrayList<>();
        for (PimDomain domain : values()) {
            if (!configsFor(domain, configs).isEmpty()) {
                found.add(domain);
            }
        }
        return found;
    }
}
