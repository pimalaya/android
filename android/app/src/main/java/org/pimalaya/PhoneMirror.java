package org.pimalaya;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * A phone app the store's items show in, and the permissions it takes.
 *
 * <p>Asked together: a setup continuing with several mirrors switched on
 * asks their permissions in one prompt ({@link MainActivity#askMirrors}).
 */
enum PhoneMirror {
    CONTACTS(
            PimDomain.CONTACTS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS),
    CALENDAR(
            PimDomain.CALENDAR,
            Manifest.permission.READ_CALENDAR,
            Manifest.permission.WRITE_CALENDAR);

    /** The domain whose collections it shows. */
    final PimDomain domain;

    private final String[] permissions;

    PhoneMirror(PimDomain domain, String... permissions) {
        this.domain = domain;
        this.permissions = permissions;
    }

    /** The mirror showing the domain's collections, null for mail. */
    static PhoneMirror of(PimDomain domain) {
        for (PhoneMirror mirror : values()) {
            if (mirror.domain == domain) {
                return mirror;
            }
        }
        return null;
    }

    /** Whether every permission the mirror takes is granted. */
    boolean granted(Context context) {
        return granted(
                permission ->
                        context.checkSelfPermission(permission)
                                == PackageManager.PERMISSION_GRANTED);
    }

    private boolean granted(Predicate<String> has) {
        for (String permission : permissions) {
            if (!has.test(permission)) {
                return false;
            }
        }
        return true;
    }

    /** The permissions to ask for the wanted mirrors, those already granted left out. */
    static String[] missing(Set<PhoneMirror> wanted, Predicate<String> has) {
        List<String> missing = new ArrayList<>();
        for (PhoneMirror mirror : wanted) {
            // NOTE: a mirror half granted asks its whole set again, so the
            // prompt never names a permission without its pair.
            if (!mirror.granted(has)) {
                for (String permission : mirror.permissions) {
                    missing.add(permission);
                }
            }
        }
        return missing.toArray(new String[0]);
    }

    /** The wanted mirrors whose every permission is granted. */
    static Set<PhoneMirror> granted(Set<PhoneMirror> wanted, Predicate<String> has) {
        Set<PhoneMirror> granted = EnumSet.noneOf(PhoneMirror.class);
        for (PhoneMirror mirror : wanted) {
            if (mirror.granted(has)) {
                granted.add(mirror);
            }
        }
        return granted;
    }
}
