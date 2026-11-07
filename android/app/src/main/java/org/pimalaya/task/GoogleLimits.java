package org.pimalaya.task;

import android.app.AlertDialog;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.BulletSpan;
import android.util.TypedValue;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.pimalaya.R;
import org.pimalaya.ui.Host;

/** What Google Tasks keeps, and what stays on this phone alone. */
public final class GoogleLimits {
    private GoogleLimits() {}

    /** Shows the limits as a bulleted list under one OK. */
    public static void show(Host host) {
        int color = host.resolveColor(android.R.attr.textColorSecondary);
        LinearLayout list = new LinearLayout(host);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(host.dp(28), host.dp(4), host.dp(24), 0);
        for (String point : host.getResources().getStringArray(R.array.google_tasks_limits)) {
            SpannableString text = new SpannableString(point);
            text.setSpan(
                    new BulletSpan(host.dp(12), color),
                    0,
                    text.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            TextView line = new TextView(host);
            line.setText(text);
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            line.setTextColor(color);
            LinearLayout.LayoutParams params =
                    new LinearLayout.LayoutParams(
                            LinearLayout.LayoutParams.MATCH_PARENT,
                            LinearLayout.LayoutParams.WRAP_CONTENT);
            params.bottomMargin = host.dp(10);
            list.addView(line, params);
        }
        ScrollView scroll = new ScrollView(host);
        scroll.addView(list);
        new AlertDialog.Builder(host)
                .setTitle(R.string.google_tasks_limits_title)
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }
}
