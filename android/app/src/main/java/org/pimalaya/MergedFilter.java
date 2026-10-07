package org.pimalaya;

import android.view.Gravity;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The merged view's two-axis filter: which accounts and which
 * collections take part in the list (docs/merged-view.md, extended
 * across accounts). One instance is shared by the three domains, since
 * the account axis is common to all of them and a user who hides an
 * account means it everywhere.
 *
 * <p>State is kept as the <em>hidden</em> sets rather than the visible
 * ones, so the empty filter is the everything-on default and an account
 * or a collection added later shows up without having to be enrolled.
 */
final class MergedFilter {
    /** Account emails whose items are kept out of the lists. */
    private final Set<String> hiddenAccounts = new HashSet<>();

    /** Collection ids whose items are kept out of the lists. */
    private final Set<String> hiddenCollections = new HashSet<>();

    /** Whether an item of this account and collection takes part. */
    boolean accepts(String account, String collection) {
        return !hiddenAccounts.contains(account) && !hiddenCollections.contains(collection);
    }

    /** Whether the account takes part at all, whatever its collections. */
    boolean showsAccount(String account) {
        return !hiddenAccounts.contains(account);
    }

    /** Whether anything is hidden, which the bar icon reflects. */
    boolean isActive() {
        return !hiddenAccounts.isEmpty() || !hiddenCollections.isEmpty();
    }

    /** One axis of the sheet: a heading over its checkable rows. */
    static final class Axis {
        final String title;
        final List<String> ids = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        private final Set<String> hidden;

        private Axis(String title, Set<String> hidden) {
            this.title = title;
            this.hidden = hidden;
        }

        void add(String id, String label) {
            ids.add(id);
            labels.add(label);
        }
    }

    Axis accountAxis(String title) {
        return new Axis(title, hiddenAccounts);
    }

    Axis collectionAxis(String title) {
        return new Axis(title, hiddenCollections);
    }

    /**
     * Shows the filter sheet over {@code host} and calls {@code onChange}
     * whenever a box flips, so the list re-renders live rather than on
     * dismissal: the point of the sheet is watching the merged view
     * narrow.
     */
    void show(MainActivity host, List<Axis> axes, Runnable onChange) {
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = host.getResources().getDimensionPixelSize(R.dimen.item_padding);
        content.setPadding(padding, padding, padding, padding);

        for (Axis axis : axes) {
            if (axis.ids.isEmpty()) {
                continue;
            }
            content.addView(heading(host, axis.title, padding));
            for (int index = 0; index < axis.ids.size(); index++) {
                String id = axis.ids.get(index);
                CheckBox box = new CheckBox(host);
                box.setText(axis.labels.get(index));
                box.setTextSize(16);
                box.setMinHeight(
                        host.getResources().getDimensionPixelSize(R.dimen.item_height));
                box.setChecked(!axis.hidden.contains(id));
                box.setOnCheckedChangeListener(
                        (view, checked) -> {
                            if (checked) {
                                axis.hidden.remove(id);
                            } else {
                                axis.hidden.add(id);
                            }
                            onChange.run();
                        });
                content.addView(box);
            }
        }

        ScrollView scroll = new ScrollView(host);
        scroll.addView(content);

        new android.app.AlertDialog.Builder(host)
                .setTitle(R.string.filter_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(
                        R.string.filter_reset,
                        (dialog, which) -> {
                            hiddenAccounts.clear();
                            hiddenCollections.clear();
                            onChange.run();
                        })
                .show();
    }

    private static View heading(MainActivity host, String title, int padding) {
        TextView view = new TextView(host);
        view.setText(title);
        view.setAllCaps(true);
        view.setTextSize(12);
        view.setGravity(Gravity.CENTER_VERTICAL);
        view.setPadding(0, padding, 0, padding / 2);
        view.setTextColor(host.ui.resolveColor(android.R.attr.textColorSecondary));
        return view;
    }
}
