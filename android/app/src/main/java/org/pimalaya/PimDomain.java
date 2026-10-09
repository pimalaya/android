package org.pimalaya;

import org.pimalaya.client.ServiceConfig;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The three kinds of data the app manages, and which discovered services
 * serve them.
 *
 * <p>A domain is an axis of an account, not an account. One address is one
 * identity that covers some of these, each with its own server
 * ({@link AccountConnection}) and signing in with one of the account's
 * credentials ({@link AccountCredential}); the connection flow runs once per
 * domain, and all of them land in the one account. Splitting instead would
 * fragment an identity at the front door: every cross-domain question
 * afterwards has to put it back together, an expired token becomes three
 * repairs, and connecting calendars later means a second account rather than a
 * step.
 *
 * <p>JMAP is what settles it. RFC 8620 has one session resource behind one
 * authentication, and each account in it advertises which domains it serves
 * through capability URNs. Three accounts there would be three sessions to the
 * same URL with the same token, so it is offered under every domain it has a
 * capability for, and once signed in the session's capabilities decide which of
 * the ticked domains the one account syncs ({@link #servedByJmap}).
 */
enum PimDomain {
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
    final String id;

    /** The label a picker shows. */
    final int label;

    /** The glyph beside that label, the one the domain bar draws. */
    final int icon;

    /** The JMAP capability URN the domain is read with. */
    final String jmapCapability;

    PimDomain(String id, int label, int icon, String jmapCapability) {
        this.id = id;
        this.label = label;
        this.icon = icon;
        this.jmapCapability = jmapCapability;
    }

    /** The domain a stored id names, contacts when it names none. */
    static PimDomain byId(String id) {
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
        return servedBy(config.service);
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
     * The domains a JMAP session serves, by the capability URNs it advertises
     * with an account to use them in: mail only with submission beside it,
     * since a mail account that cannot answer is not what connecting mail
     * asks for.
     */
    static Set<PimDomain> servedByJmap(Set<String> capabilities) {
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
     * Where a discovered service ranks for this domain, lower first, by what
     * the app can do over it here: JMAP first for mail, which it reads and
     * sends, and for contacts; CalDAV over JMAP for calendars, since whether a
     * JMAP session serves calendars is known only once signed in, and the
     * probe then drops the domain rather than falling back to CalDAV; the DAVs
     * over the rest.
     */
    int rank(String service) {
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

    /** The discovered configs that can serve this domain, in discovery order. */
    static List<ServiceConfig> configsFor(PimDomain domain, List<ServiceConfig> configs) {
        List<ServiceConfig> serving = new ArrayList<>();
        for (ServiceConfig config : configs) {
            if (servedBy(config).contains(domain)) {
                serving.add(config);
            }
        }
        return serving;
    }

    /**
     * The domains a discovery run turned up anything for, in the order this
     * enum declares them so the picker never reorders itself between runs.
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
