package org.pimalaya.setup;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.method.LinkMovementMethod;
import android.text.style.URLSpan;
import android.widget.TextView;
import java.util.Set;
import org.pimalaya.R;
import org.pimalaya.account.Oauth;

/**
 * What a Google grant reads and how the app uses it, agreed to by a tap
 * before Google's consent page opens.
 *
 * <p>The disclosure of Google's Workspace API user data policy ("Transparent
 * and accurate notice and control"): in the app's normal flow, right before
 * the consent request, never taken as agreed by backing out or tapping away,
 * never dismissing itself.
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
    static void show(Activity host, Set<String> asked, Runnable agreed, Runnable declined) {
        boolean[] accepted = {false};
        AlertDialog dialog =
                new AlertDialog.Builder(host)
                        .setTitle(R.string.google_notice_title)
                        .setMessage(text(host, asked))
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
        TextView message = dialog.findViewById(android.R.id.message);
        if (message != null) {
            message.setMovementMethod(LinkMovementMethod.getInstance());
        }
    }

    /** The notice's text: what each asked scope reads, then how it is used. */
    static CharSequence text(Context context, Set<String> asked) {
        boolean mail = asked.contains(Oauth.GOOGLE_MAIL_SCOPE);
        boolean list = asked.contains(Oauth.GOOGLE_CALENDAR_LIST_SCOPE);
        boolean calendar = list || asked.contains(Oauth.GOOGLE_EVENTS_SCOPE);
        boolean contacts = asked.contains(Oauth.GOOGLE_PEOPLE_SCOPE);

        SpannableStringBuilder text =
                new SpannableStringBuilder(context.getString(R.string.google_notice_intro));
        text.append('\n');
        if (mail) {
            bullet(text, context.getString(R.string.google_notice_mail));
        }
        if (calendar) {
            bullet(text, context.getString(R.string.google_notice_calendar));
        }
        if (asked.contains(Oauth.GOOGLE_TASKS_SCOPE)) {
            bullet(text, context.getString(R.string.google_notice_tasks));
        }
        if (contacts) {
            bullet(text, context.getString(R.string.google_notice_contacts));
        }
        // NOTE: the sign-in check reads an address only off the mailbox or the
        // calendar list (rust/src/account/identity.rs).
        if (mail || list) {
            text.append('\n').append(context.getString(R.string.google_notice_identity));
        }
        text.append("\n\n").append(context.getString(R.string.google_notice_use));
        if (calendar || contacts) {
            text.append("\n\n").append(context.getString(R.string.google_notice_mirror));
        }

        text.append("\n\n");
        int start = text.length();
        text.append(context.getString(R.string.google_notice_privacy));
        text.setSpan(
                new URLSpan(PRIVACY_URL), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    private static void bullet(SpannableStringBuilder text, String line) {
        text.append("\n• ").append(line);
    }
}
