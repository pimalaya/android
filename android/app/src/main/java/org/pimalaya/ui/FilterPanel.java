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
import android.widget.Button;
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
 * surface, and folds it back as Cancel does: one row per section naming its
 * value, a tap opening that section's checklist under it and closing any
 * other. Ticks edit a {@link Draft}; Apply keeps it and Cancel drops it, both
 * folding the panel. Folded, the criteria on stand as pills under the field,
 * a tap on one removing it at once. The list draws the panel on each render,
 * between {@link #begin} and {@link #end}.
 */
public final class FilterPanel {
    /** A checkbox's state. */
    public enum Box {
        ON,
        PARTIAL,
        OFF,
    }

    /**
     * The list's filters as the panel edits them: a draft taken on opening,
     * kept by Apply, dropped by Cancel.
     */
    public interface Draft {
        /** The panel opens: the draft starts as the filters applied. */
        void open();

        /** Apply: the draft becomes the filters, the list follows. */
        void apply();

        /** Cancel: the draft is dropped. */
        void cancel();

        /** Reset: the draft goes back to the defaults, applied by Apply. */
        void reset();
    }

    private final Host host;
    private final ImageButton button;
    private final LinearLayout panel;
    private final View pillsRow;
    private final LinearLayout pills;

    /** The list's render, run on every change the panel makes itself. */
    private final Runnable redraw;

    private final Draft draft;

    private boolean unfolded;

    /** The panel's height running, null when it stands still. */
    private ValueAnimator folding;

    /** The key of the section open, null for none. */
    private String open;

    /** Each section's list and chevron, by key, as last drawn. */
    private final Map<String, View> lists = new HashMap<>();

    private final Map<String, ImageView> chevrons = new HashMap<>();

    /**
     * The panel of {@code header}'s list, {@code redraw} its render, {@code
     * draft} the filters it edits.
     */
    public FilterPanel(Host host, ListHeader header, Runnable redraw, Draft draft) {
        this.host = host;
        this.redraw = redraw;
        this.draft = draft;
        button = header.filterButton();
        panel = header.filterPanel();
        pills = header.chips();
        pillsRow = header.chipsRow();
        // NOTE: the button stays while the panel is open, and closes it then
        // as Cancel does.
        button.setOnClickListener(
                view -> {
                    if (unfolded) {
                        fold(draft::cancel);
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

    /** A section of any number of choices, none on narrowing nothing. */
    public void checks(
            String key, int name, String[] keys, int[] labels, Set<String> on, Runnable changed) {
        String[] texts = new String[labels.length];
        for (int index = 0; index < labels.length; index++) {
            texts[index] = host.getString(labels[index]);
        }
        checks(key, name, keys, texts, on, changed);
    }

    /** A section of choices as {@link #checks}, labelled by text. */
    public void checks(
            String key,
            int name,
            String[] keys,
            String[] labels,
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
                        ? host.getString(R.string.filter_all)
                        : String.join(", ", onLabels),
                !onLabels.isEmpty(),
                list -> {
                    for (int index = 0; index < keys.length; index++) {
                        String choice = keys[index];
                        boolean ticked = on.contains(choice);
                        check(
                                list,
                                labels[index],
                                ticked ? Box.ON : Box.OFF,
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
     * account, then each collection's id and name), {@code hidden} holding
     * the ones unticked; an account's box ticks or clears them all. Its value
     * and its one pill name what shows: all, the one collection, the one
     * account whole, else how many of how many.
     */
    public void collections(
            String key,
            int name,
            Map<String, Map<String, String>> byAccount,
            Set<String> hidden,
            Runnable changed) {
        List<String> shown = new ArrayList<>();
        Map<String, String> names = new LinkedHashMap<>();
        for (Map<String, String> collections : byAccount.values()) {
            names.putAll(collections);
        }
        if (names.isEmpty()) {
            return;
        }
        for (String id : names.keySet()) {
            if (!hidden.contains(id)) {
                shown.add(id);
            }
        }
        String value = host.getString(R.string.filter_all);
        if (!hidden.isEmpty()) {
            value = host.getString(R.string.filter_count_of, shown.size(), names.size());
            if (shown.size() == 1) {
                value = names.get(shown.get(0));
            }
            for (Map.Entry<String, Map<String, String>> account : byAccount.entrySet()) {
                Set<String> ids = account.getValue().keySet();
                if (!shown.isEmpty() && ids.containsAll(shown) && shown.containsAll(ids)) {
                    value = account.getKey();
                }
            }
        }
        section(
                key,
                name,
                value,
                !hidden.isEmpty(),
                list -> {
                    for (Map.Entry<String, Map<String, String>> account : byAccount.entrySet()) {
                        Set<String> ids = account.getValue().keySet();
                        int off = 0;
                        for (String id : ids) {
                            off += hidden.contains(id) ? 1 : 0;
                        }
                        Box box = off == 0 ? Box.ON : off == ids.size() ? Box.OFF : Box.PARTIAL;
                        check(
                                list,
                                account.getKey(),
                                box,
                                true,
                                0,
                                () -> {
                                    if (box == Box.OFF) {
                                        hidden.removeAll(ids);
                                    } else {
                                        hidden.addAll(ids);
                                    }
                                    changed.run();
                                });
                        for (Map.Entry<String, String> collection :
                                account.getValue().entrySet()) {
                            String id = collection.getKey();
                            boolean unticked = hidden.contains(id);
                            check(
                                    list,
                                    collection.getValue(),
                                    unticked ? Box.OFF : Box.ON,
                                    false,
                                    host.dp(24),
                                    () -> {
                                        if (!hidden.remove(id)) {
                                            hidden.add(id);
                                        }
                                        changed.run();
                                    });
                        }
                    }
                });
        if (!hidden.isEmpty()) {
            pill(
                    value,
                    () -> {
                        hidden.clear();
                        changed.run();
                    });
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
     * Ends a draw: the panel shown while unfolded, closed by Reset, Cancel
     * and Apply; the pills and the button while folded, the button accented
     * while {@code active}, the filters applied being off their defaults.
     */
    public void end(boolean active) {
        panel.addView(divider());
        LinearLayout actions = new LinearLayout(host);
        actions.setOrientation(LinearLayout.HORIZONTAL);
        actions.setGravity(Gravity.CENTER_VERTICAL);
        actions.setPadding(host.dp(8), host.dp(8), host.dp(12), host.dp(10));
        actions.addView(
                textButton(
                        R.string.filter_reset,
                        () -> {
                            draft.reset();
                            redraw.run();
                        }));
        actions.addView(new View(host), new LinearLayout.LayoutParams(0, 0, 1f));
        actions.addView(textButton(android.R.string.cancel, () -> fold(draft::cancel)));
        // NOTE: built from PrimaryButton alone, the theme's button style
        // under it in a layout is missing: its centring and minimum sizes
        // are set here.
        Button apply = new Button(host, null, 0, R.style.PrimaryButton);
        apply.setText(R.string.filter_apply);
        apply.setTextColor(host.accentContrast());
        apply.setGravity(Gravity.CENTER);
        apply.setMinHeight(0);
        apply.setMinimumHeight(0);
        apply.setMinWidth(0);
        apply.setMinimumWidth(0);
        apply.setPadding(host.dp(20), 0, host.dp(20), 0);
        apply.setOnClickListener(view -> fold(draft::apply));
        LinearLayout.LayoutParams applyParams =
                new LinearLayout.LayoutParams(wrap(), host.dp(40));
        applyParams.setMarginStart(host.dp(4));
        actions.addView(apply, applyParams);
        panel.addView(actions);

        panel.setVisibility(unfolded ? View.VISIBLE : View.GONE);
        pillsRow.setVisibility(!unfolded && pills.getChildCount() > 0 ? View.VISIBLE : View.GONE);
        button.setImageTintList(
                ColorStateList.valueOf(
                        host.resolveColor(
                                active
                                        ? android.R.attr.colorAccent
                                        : android.R.attr.textColorPrimary)));
    }

    /** Opens the panel on a draft of the filters, its height growing. */
    private void unfold() {
        if (folding != null) {
            return;
        }
        draft.open();
        unfolded = true;
        redraw.run();
        folding = slide(panel, 0, heightOf(panel), () -> folding = null);
    }

    /** Runs {@code close} (Apply or Cancel), then folds the panel away. */
    private void fold(Runnable close) {
        if (folding != null) {
            return;
        }
        close.run();
        redraw.run();
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

    /** A borderless button of the panel's foot, in the accent. */
    private Button textButton(int label, Runnable run) {
        Button button = new Button(host, null, android.R.attr.borderlessButtonStyle);
        button.setText(label);
        button.setAllCaps(false);
        button.setTextColor(host.resolveColor(android.R.attr.colorAccent));
        button.setOnClickListener(view -> run.run());
        return button;
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
