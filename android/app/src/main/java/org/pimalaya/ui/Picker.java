package org.pimalaya.ui;

import android.app.AlertDialog;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/** The dialog a new item picks its target collection in. */
public final class Picker {
    private Picker() {}

    /**
     * Opens {@code direct}, else says {@code none}, else lets the user pick.
     *
     * <p>The pick is among {@code offered}, {@code preselected} checked, then
     * opened.
     */
    public static <T> void choose(
            Host host,
            int title,
            int none,
            T direct,
            List<T> offered,
            int preselected,
            Function<T, CharSequence> label,
            Consumer<T> open) {
        if (direct != null) {
            open.accept(direct);
            return;
        }
        if (offered.isEmpty()) {
            host.toast(host.getString(none));
            return;
        }

        CharSequence[] labels = new CharSequence[offered.size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = label.apply(offered.get(index));
        }
        int[] picked = {preselected};
        new AlertDialog.Builder(host)
                .setTitle(title)
                .setSingleChoiceItems(labels, preselected, (dialog, which) -> picked[0] = which)
                .setPositiveButton(
                        android.R.string.ok,
                        (dialog, which) -> {
                            if (picked[0] >= 0) {
                                open.accept(offered.get(picked[0]));
                            }
                        })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }
}
