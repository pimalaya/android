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
 * same URL with the same token, so it is offered under every domain and the
 * ticked domains decide what the one account syncs.
 */
enum PimDomain {
    MAIL("mail", R.string.domain_mail),
    CONTACTS("contacts", R.string.domain_contacts),
    CALENDAR("calendar", R.string.domain_calendar);

    /** The stored spelling; the enum name is not persisted. */
    final String id;

    /** The label a picker shows. */
    final int label;

    PimDomain(String id, int label) {
        this.id = id;
        this.label = label;
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
            // NOTE: no SMTP. It is a mail service and discovery reports it, but
            // this app only reads mail, so offering it would put an option on
            // the screen that connects to nothing. It belongs here the day
            // sending does.
            case "imap":
                domains.add(MAIL);
                break;
            case "carddav":
                domains.add(CONTACTS);
                break;
            case "caldav":
                domains.add(CALENDAR);
                break;
            // NOTE: all three, and only because all three have a reader now.
            // One JMAP session has always served them; offering a domain
            // without the client to read it produced an account that
            // connected, saved, and was then never read, which is worse than
            // an absent option and is why SMTP is not here either. The rule
            // this line follows is the mapping never leads the reader.
            case "jmap":
                domains.add(MAIL);
                domains.add(CONTACTS);
                domains.add(CALENDAR);
                break;
            default:
                break;
        }
        return domains;
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
