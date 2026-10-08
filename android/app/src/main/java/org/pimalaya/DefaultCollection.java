package org.pimalaya;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;

/**
 * Which calendar or address book of an account a new event or contact goes to.
 *
 * <p>The one its source names (pimdir's {@code default} role, which the bridge
 * sets from JMAP {@code isDefault}, Graph's default calendar and contacts
 * folder, Google's primary calendar and {@code myContacts}); else the account's
 * only writable one of the kind; else the one the user set as default on the
 * filter page. The last is kept here, never written to pimdir: the store's role
 * is what a source states.
 */
final class DefaultCollection {
    private static final String PREFS = "default-collections";

    private DefaultCollection() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    private static String key(String account, String kind) {
        return kind + "\u0000" + account;
    }

    /**
     * The default among one account's collections of one kind, null when
     * none is: {@code chosen} is the user's choice, null for none.
     */
    static PimdirCollections.Stored of(List<PimdirCollections.Stored> collections, String chosen) {
        List<PimdirCollections.Stored> writable = writable(collections);
        for (PimdirCollections.Stored collection : writable) {
            if (collection.isDefault()) {
                return collection;
            }
        }
        if (writable.size() == 1) {
            return writable.get(0);
        }
        for (PimdirCollections.Stored collection : writable) {
            if (collection.id.equals(chosen)) {
                return collection;
            }
        }
        return null;
    }

    /** The default among one account's collections of one kind, null when none is. */
    static PimdirCollections.Stored of(
            Context context, String account, String kind, List<PimdirCollections.Stored> all) {
        List<PimdirCollections.Stored> own = new ArrayList<>();
        for (PimdirCollections.Stored collection : all) {
            if (collection.accountEmail.equals(account)) {
                own.add(collection);
            }
        }
        return of(own, prefs(context).getString(key(account, kind), null));
    }

    /**
     * Whether the user may set the collection as default: writable, among
     * several writable ones, and of an account whose source names none.
     */
    static boolean choosable(
            PimdirCollections.Stored collection, List<PimdirCollections.Stored> own) {
        if (!collection.writable) {
            return false;
        }
        List<PimdirCollections.Stored> writable = writable(own);
        for (PimdirCollections.Stored candidate : writable) {
            if (candidate.isDefault()) {
                return false;
            }
        }
        return writable.size() > 1;
    }

    /** Keeps the user's default for one account's kind. */
    static void choose(Context context, String account, String kind, String collection) {
        prefs(context).edit().putString(key(account, kind), collection).apply();
    }

    /** Drops the user's defaults of an account that is gone. */
    static void forget(Context context, String account) {
        SharedPreferences.Editor editor = prefs(context).edit();
        for (String kind : new String[] {PimdirSummary.CALENDAR, PimdirSummary.CONTACT}) {
            editor.remove(key(account, kind));
        }
        editor.apply();
    }

    /**
     * Where a new item of a kind goes: straight to the default when the list
     * shows one account and it has one, else a pick among the writable
     * collections of the accounts shown, the first default preselected.
     */
    static final class Choice {
        /** Where it goes without asking, null when the user is asked. */
        final PimdirCollections.Stored direct;

        /** What the user is offered, writable only. */
        final List<PimdirCollections.Stored> offered;

        /** The offered collection preselected, -1 for none. */
        final int preselected;

        private Choice(
                PimdirCollections.Stored direct,
                List<PimdirCollections.Stored> offered,
                int preselected) {
            this.direct = direct;
            this.offered = offered;
            this.preselected = preselected;
        }
    }

    /**
     * The choice for a new item among {@code collections}, every account's of
     * one kind: the accounts in view are those of which {@code shown} lets a
     * collection through, else, when it lets none, every account that is on.
     * A single writable collection on offer is taken without asking.
     */
    static Choice choice(
            Context context,
            String kind,
            List<PimdirCollections.Stored> collections,
            BiPredicate<String, String> shown) {
        Set<String> inView = new LinkedHashSet<>();
        for (PimdirCollections.Stored collection : collections) {
            if (shown.test(collection.accountEmail, collection.id)) {
                inView.add(collection.accountEmail);
            }
        }
        if (inView.isEmpty()) {
            for (PimdirCollections.Stored collection : collections) {
                if (AccountActivation.enabled(context, collection.accountEmail)) {
                    inView.add(collection.accountEmail);
                }
            }
        }

        List<PimdirCollections.Stored> offered = new ArrayList<>();
        PimdirCollections.Stored preselected = null;
        for (String account : inView) {
            PimdirCollections.Stored found = of(context, account, kind, collections);
            if (inView.size() == 1 && found != null) {
                return new Choice(found, List.of(), -1);
            }
            if (preselected == null) {
                preselected = found;
            }
            for (PimdirCollections.Stored collection : writable(collections)) {
                if (collection.accountEmail.equals(account)) {
                    offered.add(collection);
                }
            }
        }
        if (offered.size() == 1) {
            return new Choice(offered.get(0), List.of(), -1);
        }
        return new Choice(null, offered, offered.indexOf(preselected));
    }

    /** The collections the user may write into. */
    static List<PimdirCollections.Stored> writable(List<PimdirCollections.Stored> collections) {
        List<PimdirCollections.Stored> writable = new ArrayList<>();
        for (PimdirCollections.Stored collection : collections) {
            if (collection.writable) {
                writable.add(collection);
            }
        }
        return writable;
    }
}
