package org.pimalaya;

import java.util.function.Predicate;

/** What a permission prompt's answer leaves to the user. */
final class PermissionAnswer {
    private PermissionAnswer() {}

    /**
     * Whether only the system settings can grant what was asked: a
     * permission is denied and Android would show no rationale for it, which
     * after a denial means it will not prompt again (or just did not).
     */
    static boolean blocked(String[] asked, Predicate<String> has, Predicate<String> rationale) {
        for (String permission : asked) {
            if (!has.test(permission) && !rationale.test(permission)) {
                return true;
            }
        }
        return false;
    }
}
