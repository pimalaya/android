package org.pimalaya.setup;

import android.util.TypedValue;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.util.ArrayList;
import java.util.List;
import org.pimalaya.R;
import org.pimalaya.calendar.WeekStart;
import org.pimalaya.ui.Host;
import org.pimalaya.ui.Panels;
import org.pimalaya.ui.Sections;

/**
 * The app's own settings, raised from the drawer: what belongs to no
 * account, each choice applying when made.
 */
public final class AppSettings {
    private final Host host;

    /** The screen under the page, given back when it closes. */
    private int cameFrom;

    public AppSettings(Host host) {
        this.host = host;
    }

    /** Wires the page's back arrow. */
    public void setUp() {
        host.findViewById(R.id.settings_back).setOnClickListener(view -> leave());
    }

    /** Opens the page over the drawer. */
    public void open() {
        cameFrom = host.screen;
        render();
        host.openOverlay(Panels.SETTINGS);
    }

    /** Closes the page onto the drawer it was opened over. */
    public void leave() {
        host.closeOverlay(Panels.SETTINGS);
        host.screen = cameFrom;
        host.applyChrome(cameFrom);
    }

    /** Builds the cards, as the settings stand. */
    private void render() {
        LinearLayout content = host.findViewById(R.id.settings_content);
        Sections sections = new Sections(host, content);
        sections.clear();
        label(content, R.string.settings_calendar_label);

        int first = WeekStart.of(host);
        int current = -1;
        for (int index = 0; index < WeekStart.DAYS.length; index++) {
            if (WeekStart.DAYS[index] == first) {
                current = index;
            }
        }
        int shown = current;
        String[] days = host.getResources().getStringArray(R.array.week_start_days);
        List<View> rows = new ArrayList<>();
        rows.add(
                sections.row(
                        host.getString(R.string.week_start),
                        shown < 0 ? null : days[shown],
                        chevron(),
                        () ->
                                AccountSettings.choose(
                                        host,
                                        R.string.week_start,
                                        R.string.week_start_note,
                                        R.array.week_start_days,
                                        shown,
                                        picked -> {
                                            WeekStart.pick(host, WeekStart.DAYS[picked]);
                                            render();
                                        })));
        sections.section(0, 0, rows, null, false);
    }

    /** A label over the next card, as the account settings draw theirs. */
    private void label(LinearLayout content, int text) {
        TextView label = new TextView(host);
        label.setText(text);
        label.setTextColor(host.resolveColor(android.R.attr.textColorSecondary));
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        label.setTypeface(null, android.graphics.Typeface.BOLD);
        label.setAllCaps(true);
        label.setLetterSpacing(0.02f);
        int gutter = host.getResources().getDimensionPixelSize(R.dimen.card_gutter);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMarginStart(gutter + host.dp(4));
        params.setMarginEnd(gutter + host.dp(4));
        params.topMargin = host.dp(20);
        content.addView(label, params);
    }

    /** The trailing chevron of a row opening a choice. */
    private View chevron() {
        ImageView glyph = new ImageView(host);
        glyph.setImageResource(R.drawable.ic_chevron_right);
        glyph.setImageTintList(
                android.content.res.ColorStateList.valueOf(
                        host.resolveColor(android.R.attr.textColorSecondary)));
        return glyph;
    }
}
