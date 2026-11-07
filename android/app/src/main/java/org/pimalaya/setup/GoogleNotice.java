package org.pimalaya.setup;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.LayerDrawable;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.Set;
import org.pimalaya.R;
import org.pimalaya.account.Oauth;
import org.pimalaya.ui.Host;

/**
 * What a Google grant reads and how the app uses it, agreed to by a tap
 * before Google's consent page opens.
 *
 * <p>The disclosure of Google's Workspace API user data policy ("Transparent
 * and accurate notice and control"): in the app's normal flow, right before
 * the consent request, never taken as agreed by backing out or tapping away,
 * never dismissing itself. Drawn as the canvas's GoogleNotice frame.
 */
final class GoogleNotice {
    /** The app's privacy policy, its Google section and Limited Use. */
    static final String PRIVACY_URL = "https://pimalaya.org/android/privacy/";

    private GoogleNotice() {}

    /**
     * Shows the notice for the scopes asked: {@code agreed} runs on Agree,
     * {@code declined} on Not now, back, a tap outside or the dialog closing
     * any other way, never both.
     */
    static void show(Host host, Set<String> asked, Runnable agreed, Runnable declined) {
        boolean[] accepted = {false};
        AlertDialog dialog =
                new AlertDialog.Builder(host)
                        .setTitle(R.string.google_notice_title)
                        .setView(body(host, asked))
                        .setPositiveButton(
                                R.string.google_notice_agree,
                                (shown, which) -> {
                                    accepted[0] = true;
                                    agreed.run();
                                })
                        .setNegativeButton(R.string.google_notice_decline, null)
                        .setOnDismissListener(
                                shown -> {
                                    if (!accepted[0]) {
                                        declined.run();
                                    }
                                })
                        .show();
        // NOTE: Agree is the one filled button, Not now stays text. The pill
        // is drawn at its own height, centred: the dialog button's full
        // height stays the touch target, and a pill that tall reads heavy.
        Button agree = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        LayerDrawable pill =
                new LayerDrawable(new Drawable[] {host.getDrawable(R.drawable.button_pill)});
        pill.setLayerHeight(
                0, host.getResources().getDimensionPixelSize(R.dimen.notice_agree));
        pill.setLayerGravity(0, Gravity.CENTER_VERTICAL | Gravity.FILL_HORIZONTAL);
        agree.setBackground(pill);
        agree.setTextColor(host.accentContrast());
        agree.setPadding(
                host.dp(24), agree.getPaddingTop(), host.dp(24), agree.getPaddingBottom());
    }

    /** The intro, a row per asked service, the card, then the fine print. */
    private static View body(Host host, Set<String> asked) {
        boolean mail = asked.contains(Oauth.GOOGLE_MAIL_SCOPE);
        boolean list = asked.contains(Oauth.GOOGLE_CALENDAR_LIST_SCOPE);
        boolean calendar = list || asked.contains(Oauth.GOOGLE_EVENTS_SCOPE);
        boolean contacts = asked.contains(Oauth.GOOGLE_PEOPLE_SCOPE);

        LinearLayout column = new LinearLayout(host);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setPadding(host.dp(24), host.dp(4), host.dp(24), 0);
        column.addView(
                label(host, host.getString(R.string.google_notice_intro), 14, false, false),
                spaced(0, host.dp(8)));
        if (mail) {
            column.addView(
                    row(host, R.drawable.ic_domain_mail, R.string.google_notice_mail_name,
                            R.string.google_notice_mail));
        }
        if (calendar) {
            column.addView(
                    row(host, R.drawable.ic_domain_calendar,
                            R.string.google_notice_calendar_name,
                            R.string.google_notice_calendar));
        }
        if (asked.contains(Oauth.GOOGLE_TASKS_SCOPE)) {
            column.addView(
                    row(host, R.drawable.ic_task_done, R.string.google_notice_tasks_name,
                            R.string.google_notice_tasks));
        }
        if (contacts) {
            column.addView(
                    row(host, R.drawable.ic_domain_contacts,
                            R.string.google_notice_contacts_name,
                            R.string.google_notice_contacts));
        }
        column.addView(card(host), spaced(host.dp(12), 0));
        // NOTE: the sign-in check reads an address only off the mailbox or
        // the calendar list (rust/src/account/identity.rs).
        column.addView(fine(host, mail || list, calendar || contacts), spaced(host.dp(12), 0));

        ScrollView scroll = new ScrollView(host);
        scroll.addView(column);
        return scroll;
    }

    /** One service: its glyph on a washed disc, its name over its uses. */
    private static View row(Host host, int glyph, int name, int uses) {
        LinearLayout row = new LinearLayout(host);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, host.dp(8), 0, host.dp(8));

        int size = host.getResources().getDimensionPixelSize(R.dimen.notice_disc);
        int inset = (size - host.getResources().getDimensionPixelSize(R.dimen.notice_glyph)) / 2;
        ImageView disc = icon(host, glyph);
        disc.setBackgroundResource(R.drawable.tag_wash);
        disc.setPadding(inset, inset, inset, inset);
        row.addView(disc, new LinearLayout.LayoutParams(size, size));

        LinearLayout.LayoutParams words =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        words.setMarginStart(host.dp(14));
        row.addView(named(host, name, uses), words);
        return row;
    }

    /** Where the data stays: the phone's glyph beside the promise. */
    private static View card(Host host) {
        LinearLayout card = new LinearLayout(host);
        card.setBackgroundResource(R.drawable.card_background);
        card.setPadding(host.dp(16), host.dp(14), host.dp(16), host.dp(14));

        int size = host.getResources().getDimensionPixelSize(R.dimen.notice_glyph);
        LinearLayout.LayoutParams phone = new LinearLayout.LayoutParams(size, size);
        phone.topMargin = host.dp(1);
        card.addView(icon(host, R.drawable.ic_phone_android), phone);

        LinearLayout.LayoutParams words =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        words.setMarginStart(host.dp(12));
        card.addView(named(host, R.string.google_notice_stays, R.string.google_notice_use), words);
        return card;
    }

    /** The other uses, as they apply, and the privacy policy's link. */
    private static View fine(Host host, boolean identity, boolean mirror) {
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (identity) {
            text.append(host.getString(R.string.google_notice_identity)).append(' ');
        }
        if (mirror) {
            text.append(host.getString(R.string.google_notice_mirror)).append(' ');
        }
        int start = text.length();
        text.append(host.getString(R.string.google_notice_privacy));
        text.setSpan(
                new URLSpan(PRIVACY_URL), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);

        TextView fine = label(host, text, 12.5f, false, false);
        fine.setLinkTextColor(host.getColor(R.color.accent_ink));
        fine.setMovementMethod(LinkMovementMethod.getInstance());
        return fine;
    }

    /** A name in medium weight over a line in the secondary tone. */
    private static View named(Host host, int name, int line) {
        LinearLayout words = new LinearLayout(host);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(label(host, host.getString(name), 15, true, true));
        words.addView(label(host, host.getString(line), 13, false, false));
        return words;
    }

    private static TextView label(
            Host host, CharSequence text, float size, boolean medium, boolean primary) {
        TextView label = new TextView(host);
        label.setText(text);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, size);
        label.setTextColor(
                host.resolveColor(
                        primary
                                ? android.R.attr.textColorPrimary
                                : android.R.attr.textColorSecondary));
        if (medium) {
            label.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }
        return label;
    }

    /** A glyph in the accent's ink, decor to a screen reader. */
    private static ImageView icon(Host host, int glyph) {
        ImageView icon = new ImageView(host);
        icon.setImageResource(glyph);
        icon.setImageTintList(ColorStateList.valueOf(host.getColor(R.color.accent_ink)));
        icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
        icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        return icon;
    }

    private static LinearLayout.LayoutParams spaced(int top, int bottom) {
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT);
        params.topMargin = top;
        params.bottomMargin = bottom;
        return params;
    }
}
