package org.pimalaya.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import org.pimalaya.R;

/**
 * A list's filters, inline under its search field (cairn/changes/
 * one-filter-panel).
 *
 * <p>The field's filter button unfolds the panel under the field, the two one
 * surface, and folds it back: one row per section naming its value, a tap
 * opening that section's checklist under it and closing any other. A tick
 * applies at once. The criteria off their defaults stand as pills under the
 * field, open or folded, a tap on one restoring its default: panel and pills
 * show the one state. The list draws the panel on each render,
 * between {@link #begin} and {@link #end}.
 */
public final class FilterPanel {
    /** A checkbox's state. */
    public enum Box {
        ON,
        PARTIAL,
        OFF,
    }

    private final Host host;
    private final ImageButton button;
    private final LinearLayout panel;
    private final View pillsRow;
    private final LinearLayout pills;

    /** The list's render, run on every change the panel makes itself. */
    private final Runnable redraw;

    private boolean unfolded;

    /** The panel's height running, null when it stands still. */
    private ValueAnimator folding;

    /** The key of the section open, null for none. */
    private String open;

    /** Each section's list and chevron, by key, as last drawn. */
    private final Map<String, View> lists = new HashMap<>();

    private final Map<String, ImageView> chevrons = new HashMap<>();

    /** The panel of {@code header}'s list, {@code redraw} its render. */
    public FilterPanel(Host host, ListHeader header, Runnable redraw) {
        this.host = host;
        this.redraw = redraw;
        button = header.filterButton();
        panel = header.filterPanel();
        pills = header.chips();
        pillsRow = header.chipsRow();
        button.setOnClickListener(
                view -> {
                    if (unfolded) {
                        fold();
                    } else {
                        unfold();
                    }
                });
    }

    /** Starts a draw: the panel and the pills emptied. */
    public void begin() {
        panel.removeAllViews();
        pills.removeAllViews();
        lists.clear();
        chevrons.clear();
    }

    /**
     * One section's row, naming {@code value}, its checklist drawn by {@code
     * fill} while it is the one open; {@code set} when it is off its default.
     */
    public void section(
            String key, int name, CharSequence value, boolean set, Consumer<LinearLayout> fill) {
        // NOTE: the first one parts the panel from the search field above.
        panel.addView(divider());
        boolean opened = key.equals(open);

        TextView label = text(host.getString(name), 14, android.R.attr.textColorSecondary);
        TextView shown =
                text(
                        value,
                        15,
                        set ? android.R.attr.textColorPrimary : android.R.attr.textColorSecondary);
        if (set) {
            shown.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        ImageView chevron = new ImageView(host);
        chevron.setImageResource(opened ? R.drawable.ic_expand_less : R.drawable.ic_expand_more);
        chevron.setImageTintList(
                ColorStateList.valueOf(host.resolveColor(android.R.attr.textColorSecondary)));

        LinearLayout row = row(0);
        row.addView(label, new LinearLayout.LayoutParams(host.dp(96), wrap()));
        row.addView(shown, new LinearLayout.LayoutParams(0, wrap(), 1f));
        int icon = dimen(R.dimen.item_icon);
        row.addView(chevron, new LinearLayout.LayoutParams(icon, icon));
        // NOTE: the chevrons stand on the filter button's centre line, the
        // field above ending in it.
        View field = (View) button.getParent();
        int end = field.getPaddingEnd() + button.getLayoutParams().width / 2 - icon / 2;
        row.setPaddingRelative(row.getPaddingStart(), 0, end, 0);
        row.setOnClickListener(view -> toggle(key));
        panel.addView(row);

        // NOTE: every list is built, the closed ones gone, so opening one
        // runs its height rather than redrawing the panel.
        LinearLayout list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, 0, 0, host.dp(6));
        fill.accept(list);
        list.setVisibility(opened ? View.VISIBLE : View.GONE);
        panel.addView(list);
        lists.put(key, list);
        chevrons.put(key, chevron);
    }

    /**
     * Opens {@code key}'s list and closes the one open, or closes it when it
     * is the one open, both running their height.
     */
    private void toggle(String key) {
        if (folding != null) {
            return;
        }
        String was = open;
        open = key.equals(was) ? null : key;
        if (was != null) {
            View closing = lists.get(was);
            chevrons.get(was).setImageResource(R.drawable.ic_expand_more);
            slide(closing, closing.getHeight(), 0, () -> closing.setVisibility(View.GONE));
        }
        if (open != null) {
            View opening = lists.get(open);
            chevrons.get(open).setImageResource(R.drawable.ic_expand_less);
            opening.setVisibility(View.VISIBLE);
            slide(opening, 0, heightOf(opening), null);
        }
    }

    /**
     * A section of one choice among {@code keys}, the first being the
     * default {@code on} holds when empty; a pill while another is on.
     */
    public void choice(
            String key, int name, String[] keys, int[] labels, Set<String> on, Runnable changed) {
        String picked = on.isEmpty() ? keys[0] : on.iterator().next();
        int index = Math.max(0, java.util.Arrays.asList(keys).indexOf(picked));
        String label = host.getString(labels[index]);
        section(
                key,
                name,
                label,
                index != 0,
                list -> {
                    for (int each = 0; each < keys.length; each++) {
                        String choice = keys[each];
                        boolean first = each == 0;
                        radio(
                                list,
                                host.getString(labels[each]),
                                null,
                                choice.equals(picked),
                                () -> {
                                    on.clear();
                                    if (!first) {
                                        on.add(choice);
                                    }
                                    changed.run();
                                });
                    }
                });
        if (index != 0) {
            pill(
                    label,
                    () -> {
                        on.clear();
                        changed.run();
                    });
        }
    }

    /**
     * A section of any number of choices, none ticked meaning any; its value
     * names the ones ticked, each standing as a pill.
     */
    public void checks(
            String key, int name, String[] keys, int[] labels, Set<String> on, Runnable changed) {
        String[] texts = new String[labels.length];
        for (int index = 0; index < labels.length; index++) {
            texts[index] = host.getString(labels[index]);
        }
        checks(key, name, keys, texts, null, on, changed);
    }

    /**
     * A section of choices as {@link #checks}, labelled by text, {@code
     * counts} trailing the rows, or null.
     */
    public void checks(
            String key,
            int name,
            String[] keys,
            String[] labels,
            String[] counts,
            Set<String> on,
            Runnable changed) {
        List<String> onLabels = new ArrayList<>();
        for (int index = 0; index < keys.length; index++) {
            if (on.contains(keys[index])) {
                onLabels.add(labels[index]);
            }
        }
        section(
                key,
                name,
                onLabels.isEmpty()
                        ? host.getString(R.string.filter_any)
                        : String.join(", ", onLabels),
                !onLabels.isEmpty(),
                list -> {
                    for (int index = 0; index < keys.length; index++) {
                        String choice = keys[index];
                        check(
                                list,
                                labels[index],
                                counts == null ? null : counts[index],
                                on.contains(choice) ? Box.ON : Box.OFF,
                                false,
                                0,
                                () -> {
                                    if (!on.remove(choice)) {
                                        on.add(choice);
                                    }
                                    changed.run();
                                });
                    }
                });
        for (int index = 0; index < keys.length; index++) {
            String choice = keys[index];
            if (on.contains(choice)) {
                pill(
                        labels[index],
                        () -> {
                            on.remove(choice);
                            changed.run();
                        });
            }
        }
    }

    /**
     * A section of collections under their accounts ({@code byAccount}: an
     * account, then each collection's id and name), {@code on} holding the
     * ones ticked, none meaning any; an account's box ticks or clears them
     * all. Its value names the ones ticked, each standing as a pill.
     */
    public void collections(
            String key,
            int name,
            Map<String, Map<String, String>> byAccount,
            Set<String> on,
            Runnable changed) {
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, String> collections : byAccount.values()) {
            names.putAll(collections);
        }
        if (names.isEmpty()) {
            return;
        }
        List<String> onNames = new ArrayList<>();
        for (Map.Entry<String, String> collection : names.entrySet()) {
            if (on.contains(collection.getKey())) {
                onNames.add(collection.getValue());
            }
        }
        section(
                key,
                name,
                onNames.isEmpty()
                        ? host.getString(R.string.filter_any)
                        : String.join(", ", onNames),
                !onNames.isEmpty(),
                list -> {
                    for (Map.Entry<String, Map<String, String>> account : byAccount.entrySet()) {
                        Set<String> ids = account.getValue().keySet();
                        int ticked = 0;
                        for (String id : ids) {
                            ticked += on.contains(id) ? 1 : 0;
                        }
                        Box box =
                                ticked == 0
                                        ? Box.OFF
                                        : ticked == ids.size() ? Box.ON : Box.PARTIAL;
                        check(
                                list,
                                account.getKey(),
                                box,
                                true,
                                0,
                                () -> {
                                    if (box == Box.ON) {
                                        on.removeAll(ids);
                                    } else {
                                        on.addAll(ids);
                                    }
                                    changed.run();
                                });
                        for (Map.Entry<String, String> collection :
                                account.getValue().entrySet()) {
                            String id = collection.getKey();
                            check(
                                    list,
                                    collection.getValue(),
                                    on.contains(id) ? Box.ON : Box.OFF,
                                    false,
                                    host.dp(24),
                                    () -> {
                                        if (!on.remove(id)) {
                                            on.add(id);
                                        }
                                        changed.run();
                                    });
                        }
                    }
                });
        for (Map.Entry<String, String> collection : names.entrySet()) {
            String id = collection.getKey();
            if (on.contains(id)) {
                pill(
                        collection.getValue(),
                        () -> {
                            on.remove(id);
                            changed.run();
                        });
            }
        }
    }

    /** A radio row of an open section, {@code count} trailing it or null. */
    public void radio(
            LinearLayout into, CharSequence label, String count, boolean on, Runnable pick) {
        LinearLayout row = choice(on ? R.drawable.ic_radio_on : R.drawable.ic_radio_off, on, 0);
        row.addView(text(label, 16, android.R.attr.textColorPrimary), weighted());
        if (count != null) {
            row.addView(text(count, 14, android.R.attr.textColorSecondary));
        }
        row.setOnClickListener(view -> pick.run());
        into.addView(row);
    }

    /**
     * A checkbox row of an open section, indented by {@code indent}, bold
     * when it {@code heads} the rows under it.
     */
    public void check(
            LinearLayout into,
            CharSequence label,
            Box box,
            boolean heads,
            int indent,
            Runnable toggle) {
        check(into, label, null, box, heads, indent, toggle);
    }

    /** A checkbox row as {@link #check}, {@code count} trailing it or null. */
    public void check(
            LinearLayout into,
            CharSequence label,
            String count,
            Box box,
            boolean heads,
            int indent,
            Runnable toggle) {
        LinearLayout row =
                choice(
                        box == Box.ON
                                ? R.drawable.ic_check_box
                                : box == Box.PARTIAL
                                        ? R.drawable.ic_check_box_partial
                                        : R.drawable.ic_check_box_blank,
                        box != Box.OFF,
                        indent);
        TextView name = text(label, 16, android.R.attr.textColorPrimary);
        if (heads) {
            name.setTypeface(null, Typeface.BOLD);
        }
        row.addView(name, weighted());
        if (count != null) {
            row.addView(text(count, 14, android.R.attr.textColorSecondary));
        }
        row.setOnClickListener(view -> toggle.run());
        into.addView(row);
    }

    /** An accented action of an open section, under its choices. */
    public void action(LinearLayout into, CharSequence label, Runnable run) {
        TextView action = text(label, 14, android.R.attr.colorAccent);
        action.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        int start = dimen(R.dimen.item_padding) * 2 + dimen(R.dimen.item_icon);
        action.setPadding(start, host.dp(10), dimen(R.dimen.item_padding), host.dp(10));
        action.setBackgroundResource(
                host.ui.resolveAttr(android.R.attr.selectableItemBackground));
        action.setOnClickListener(view -> run.run());
        into.addView(action);
    }

    /** A pill standing for one criterion on, shown while folded. */
    public void pill(CharSequence label, Runnable remove) {
        Chips.removable(host, pills, label, remove);
    }

    /**
     * Ends a draw: the panel shown while unfolded, the pills whenever any
     * criterion is off its default, the button accented while {@code active}.
     */
    public void end(boolean active) {
        panel.setVisibility(unfolded ? View.VISIBLE : View.GONE);
        pillsRow.setVisibility(pills.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        button.setImageTintList(
                ColorStateList.valueOf(
                        host.resolveColor(
                                active
                                        ? android.R.attr.colorAccent
                                        : android.R.attr.textColorPrimary)));
    }

    /** Opens the panel, its height growing. */
    private void unfold() {
        if (folding != null) {
            return;
        }
        unfolded = true;
        redraw.run();
        folding = slide(panel, 0, heightOf(panel), () -> folding = null);
    }

    /** Folds the panel away, its height shrinking. */
    private void fold() {
        if (folding != null) {
            return;
        }
        folding =
                slide(
                        panel,
                        panel.getHeight(),
                        0,
                        () -> {
                            folding = null;
                            unfolded = false;
                            open = null;
                            redraw.run();
                        });
    }

    /** The height {@code view} takes at the panel's width. */
    private int heightOf(View view) {
        View box = (View) panel.getParent();
        view.measure(
                View.MeasureSpec.makeMeasureSpec(box.getWidth(), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return view.getMeasuredHeight();
    }

    /**
     * Runs {@code view}'s height from {@code from} to {@code to}, then {@code
     * done}, the height left to its content after.
     */
    private ValueAnimator slide(View view, int from, int to, Runnable done) {
        ViewGroup.LayoutParams params = view.getLayoutParams();
        ValueAnimator slide = ValueAnimator.ofInt(from, to);
        slide.setDuration(200);
        slide.setInterpolator(
                AnimationUtils.loadInterpolator(host, android.R.interpolator.fast_out_slow_in));
        slide.addUpdateListener(
                step -> {
                    params.height = (int) step.getAnimatedValue();
                    view.setLayoutParams(params);
                });
        slide.addListener(
                new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        // NOTE: done first, so a list closing is gone before
                        // its height is its content's again.
                        if (done != null) {
                            done.run();
                        }
                        params.height = ViewGroup.LayoutParams.WRAP_CONTENT;
                        view.setLayoutParams(params);
                    }
                });
        slide.start();
        return slide;
    }

    /** A row of an open section: its glyph, accented while on. */
    private LinearLayout choice(int glyph, boolean on, int indent) {
        ImageView box = new ImageView(host);
        box.setImageResource(glyph);
        box.setImageTintList(
                ColorStateList.valueOf(
                        host.resolveColor(
                                on
                                        ? android.R.attr.colorAccent
                                        : android.R.attr.textColorSecondary)));
        LinearLayout row = row(indent);
        int icon = dimen(R.dimen.item_icon);
        row.addView(box, new LinearLayout.LayoutParams(icon, icon));
        return row;
    }

    private LinearLayout row(int indent) {
        LinearLayout row = new LinearLayout(host);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dimen(R.dimen.item_height));
        int padding = dimen(R.dimen.item_padding);
        row.setPadding(padding + indent, 0, padding, 0);
        row.setBackgroundResource(host.ui.resolveAttr(android.R.attr.selectableItemBackground));
        return row;
    }

    private View divider() {
        View line = new View(host);
        line.setBackgroundColor(host.getColor(R.color.divider_light));
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
        params.setMarginStart(dimen(R.dimen.item_padding));
        line.setLayoutParams(params);
        return line;
    }

    private TextView text(CharSequence value, int sp, int color) {
        TextView text = new TextView(host);
        text.setText(value);
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (color != 0) {
            text.setTextColor(host.resolveColor(color));
        }
        text.setSingleLine(true);
        text.setEllipsize(TextUtils.TruncateAt.END);
        return text;
    }

    private LinearLayout.LayoutParams weighted() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, wrap(), 1f);
        params.setMarginStart(dimen(R.dimen.item_padding));
        params.setMarginEnd(host.dp(8));
        return params;
    }

    private static int wrap() {
        return LinearLayout.LayoutParams.WRAP_CONTENT;
    }

    private int dimen(int id) {
        return host.getResources().getDimensionPixelSize(id);
    }
}
