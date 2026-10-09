package org.pimalaya;

import android.app.AlertDialog;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.util.Log;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.pimalaya.client.Account;
import org.pimalaya.client.PimalayaClient;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Deleted items: every item the store keeps after its deletion, across
 * mail, contacts and calendars, newest deletion first ({@link DeletedItems}).
 *
 * <p>Tapping a row whose body is stored restores it into a collection of its
 * kind the user picks, its last one preselected. A row held as summary only
 * says it is not stored on this device, and one a source still binds says it
 * waits for the server; neither offers anything. Above the list, what the
 * retained items hold and the button that frees it, confirmed first.
 */
final class DeletedItemsPage {
    /** Rows shown at first and added by each "Show more". */
    private static final int SHOWN = 100;

    private final MainActivity host;
    private final Adapter adapter = new Adapter();

    private List<DeletedItems.Row> rows = new ArrayList<>();
    private int shown = SHOWN;

    /** The screen the page was raised over, restored on the way out. */
    private int cameFrom = MainActivity.PANEL_MAIL;

    DeletedItemsPage(MainActivity host) {
        this.host = host;
    }

    void setUp() {
        host.findViewById(R.id.deleted_back).setOnClickListener(view -> leave());
        host.findViewById(R.id.deleted_free).setOnClickListener(view -> confirmFree());
        ListView list = host.findViewById(R.id.deleted_list);
        list.setAdapter(adapter);
        list.setOnItemClickListener(
                (parent, view, position, id) -> {
                    if (position >= Math.min(shown, rows.size())) {
                        shown += SHOWN;
                        adapter.notifyDataSetChanged();
                    } else {
                        restore(rows.get(position));
                    }
                });
    }

    /** Opens the page over the drawer. */
    void open() {
        cameFrom = host.screen;
        rows = new ArrayList<>();
        shown = SHOWN;
        adapter.notifyDataSetChanged();
        ((TextView) host.findViewById(R.id.deleted_summary)).setText("");
        host.openOverlay(MainActivity.PANEL_DELETED);
        load();
    }

    /** Closes the page onto the drawer it was opened over. */
    void leave() {
        host.closeOverlay(MainActivity.PANEL_DELETED);
        host.screen = cameFrom;
        host.applyChrome(cameFrom);
    }

    private DeletedItems store() {
        return new DeletedItems(host.pimdir, host);
    }

    /** Reads the rows and the bytes off the main thread, then renders them. */
    private void load() {
        host.findViewById(R.id.deleted_progress).setVisibility(View.VISIBLE);
        host.findViewById(R.id.deleted_empty).setVisibility(View.GONE);
        host.io.execute(
                () -> {
                    List<DeletedItems.Row> listed;
                    long bytes;
                    try {
                        DeletedItems store = store();
                        listed = store.list();
                        bytes = store.retainedBytes();
                    } catch (Exception error) {
                        Log.w("pimalaya", "deleted items unreadable", error);
                        listed = new ArrayList<>();
                        bytes = 0;
                    }
                    List<DeletedItems.Row> read = listed;
                    long held = bytes;
                    host.postAlive(() -> render(read, held));
                });
    }

    private void render(List<DeletedItems.Row> listed, long bytes) {
        rows = listed;
        host.findViewById(R.id.deleted_progress).setVisibility(View.GONE);
        host.findViewById(R.id.deleted_empty)
                .setVisibility(listed.isEmpty() ? View.VISIBLE : View.GONE);
        ((TextView) host.findViewById(R.id.deleted_summary))
                .setText(
                        host.getResources()
                                .getQuantityString(
                                        R.plurals.deleted_summary,
                                        listed.size(),
                                        listed.size(),
                                        Formatter.formatShortFileSize(host, bytes)));
        boolean retained = false;
        for (DeletedItems.Row row : listed) {
            retained |= !row.waiting();
        }
        Button free = host.findViewById(R.id.deleted_free);
        // NOTE: the figure on the button too, so what a purge gives back is
        // read where it is asked for, not on the line beside it alone.
        free.setText(
                bytes > 0
                        ? host.getString(
                                R.string.deleted_free_amount,
                                Formatter.formatShortFileSize(host, bytes))
                        : host.getString(R.string.deleted_free));
        free.setEnabled(retained);
        free.setAlpha(retained ? 1f : 0.5f);
        free.setTag(bytes);
        adapter.notifyDataSetChanged();
    }

    // ---- restore ----------------------------------------------------------

    /**
     * Asks where to put the row back, among its account's writable
     * collections of its kind, the last one preselected (else the account's
     * default), and stages it there.
     */
    private void restore(DeletedItems.Row row) {
        if (row.waiting()) {
            return;
        }
        if (!row.stored()) {
            host.toast(host.getString(R.string.deleted_not_stored));
            return;
        }
        if (PimdirSummary.MAIL.equals(row.kind) && !appends(row.collection.accountEmail)) {
            host.toast(host.getString(R.string.deleted_unsupported));
            return;
        }

        List<PimdirCollections.Stored> own = new ArrayList<>();
        for (PimdirCollections.Stored collection : candidates(row.kind)) {
            if (collection.accountEmail.equals(row.collection.accountEmail)
                    && collection.writable) {
                own.add(collection);
            }
        }
        if (own.isEmpty()) {
            host.toast(host.getString(R.string.deleted_nowhere));
            return;
        }

        int preselected = -1;
        for (int index = 0; index < own.size(); index++) {
            if (own.get(index).id.equals(row.collection.id)) {
                preselected = index;
            }
        }
        if (preselected < 0 && !PimdirSummary.MAIL.equals(row.kind)) {
            PimdirCollections.Stored fallback =
                    DefaultCollection.of(host, row.collection.accountEmail, row.kind, own);
            preselected = fallback == null ? -1 : own.indexOf(fallback);
        }
        if (preselected < 0) {
            preselected = 0;
        }

        CharSequence[] labels = new CharSequence[own.size()];
        for (int index = 0; index < labels.length; index++) {
            labels[index] = host.bookLabel(label(row, own.get(index)), accountLabel(row));
        }
        int[] picked = {preselected};
        new AlertDialog.Builder(host)
                .setTitle(R.string.deleted_restore_title)
                .setSingleChoiceItems(labels, preselected, (dialog, which) -> picked[0] = which)
                .setPositiveButton(
                        R.string.deleted_restore,
                        (dialog, which) -> stage(row, own.get(picked[0]).id))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The collections of a kind a restore may target. */
    private List<PimdirCollections.Stored> candidates(String kind) {
        switch (kind) {
            case PimdirSummary.MAIL:
                return new PimdirCollections(host.pimdir, host).list(PimdirSummary.MAIL);
            case PimdirSummary.CONTACT:
                return host.collectionsOf(PimDomain.CONTACTS);
            default:
                return host.collectionsOf(PimDomain.CALENDAR);
        }
    }

    /**
     * Whether the account's mail sync uploads a create with no origin: an
     * append is IMAP's, the providers filing their own copies (Graph, Gmail)
     * and JMAP pushing none yet.
     */
    private boolean appends(String email) {
        for (AccountEntry account : host.accounts) {
            if (account.email.equals(email)) {
                Account server = account.server(PimDomain.MAIL);
                return server != null
                        && !PimalayaClient.isGraph(server)
                        && !PimalayaClient.isGoogle(server)
                        && !PimalayaClient.isJmap(server);
            }
        }
        return false;
    }

    private void stage(DeletedItems.Row row, String target) {
        String email = row.collection.accountEmail;
        host.io.execute(
                () -> {
                    Exception failure = null;
                    DeletedItems.Refusal refusal = null;
                    try {
                        PimdirEngine engine;
                        switch (row.kind) {
                            case PimdirSummary.MAIL:
                                engine = host.mailEngine(email);
                                break;
                            case PimdirSummary.CONTACT:
                                engine =
                                        new OfflineEngine(
                                                host.base, host.pimdir, host.client, null, null,
                                                null);
                                break;
                            default:
                                engine = host.calendarEngine(email);
                        }
                        store().restore(engine, row, target);
                    } catch (DeletedItems.RefusedException refused) {
                        refusal = refused.refusal;
                    } catch (Exception error) {
                        Log.w("pimalaya", "restore failed: " + row.linkId, error);
                        failure = error;
                    }

                    Exception outcome = failure;
                    DeletedItems.Refusal refused = refusal;
                    host.postAlive(
                            () -> {
                                if (outcome != null) {
                                    host.showError(outcome, R.string.deleted_restore_failed);
                                    return;
                                }
                                if (refused != null) {
                                    host.toast(host.getString(refusalText(refused)));
                                    return;
                                }
                                host.toast(host.getString(R.string.deleted_restored));
                                host.reloadContacts();
                                host.calendarList.reload();
                                host.mailList.reload();
                                load();
                            });
                });
    }

    private static int refusalText(DeletedItems.Refusal refusal) {
        switch (refusal) {
            case NOT_STORED:
                return R.string.deleted_not_stored;
            case PRESENT:
                return R.string.deleted_present;
            default:
                return R.string.deleted_waiting;
        }
    }

    // ---- free space -------------------------------------------------------

    private void confirmFree() {
        Object tag = host.findViewById(R.id.deleted_free).getTag();
        long bytes = tag instanceof Long ? (Long) tag : 0;
        new AlertDialog.Builder(host)
                .setMessage(
                        host.getString(
                                R.string.deleted_free_confirm,
                                Formatter.formatShortFileSize(host, bytes)))
                .setPositiveButton(R.string.deleted_free, (dialog, which) -> free(bytes))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** Purges every item retained until now, then collects what nothing references. */
    private void free(long bytes) {
        host.findViewById(R.id.deleted_progress).setVisibility(View.VISIBLE);
        String cutoff = stamp(new Date());
        host.io.execute(
                () -> {
                    Exception failure = null;
                    try {
                        synchronized (PimdirEngine.STORE) {
                            store().purgeBefore(cutoff);
                        }
                    } catch (Exception error) {
                        Log.w("pimalaya", "purge failed", error);
                        failure = error;
                    }
                    Exception outcome = failure;
                    host.postAlive(
                            () -> {
                                if (outcome != null) {
                                    host.showError(outcome, R.string.deleted_free_failed);
                                } else {
                                    host.toast(
                                            host.getString(
                                                    R.string.deleted_freed,
                                                    Formatter.formatShortFileSize(host, bytes)));
                                }
                                load();
                            });
                });
    }

    /** An instant as the store stamps a retention (RFC 3339, UTC, milliseconds). */
    private static String stamp(Date date) {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(date);
    }

    // ---- rows -------------------------------------------------------------

    private String accountLabel(DeletedItems.Row row) {
        return LocalBook.is(row.collection.accountEmail)
                ? host.getString(R.string.local_book)
                : row.collection.accountEmail;
    }

    private String title(DeletedItems.Row row) {
        if (!row.title.isEmpty()) {
            return row.title;
        }
        switch (row.kind) {
            case PimdirSummary.MAIL:
                return host.getString(R.string.message_no_subject);
            case PimdirSummary.CONTACT:
                return host.getString(R.string.deleted_unnamed);
            default:
                return host.getString(R.string.event_untitled);
        }
    }

    /** The summary's second line: who, and when for a mail or an event. */
    private String facts(DeletedItems.Row row) {
        List<String> parts = new ArrayList<>();
        if (!row.who.isEmpty()) {
            parts.add(row.who);
        }
        String when = when(row);
        if (!when.isEmpty()) {
            parts.add(when);
        }
        return String.join(" · ", parts);
    }

    private String when(DeletedItems.Row row) {
        if (row.when.isEmpty()) {
            return "";
        }
        if (PimdirSummary.MAIL.equals(row.kind)) {
            long stamp = PimdirSummary.stampOf(row.when);
            return stamp == 0 ? "" : dateTime(stamp);
        }
        // NOTE: an iCalendar DTSTART, a date or a date-time, read for its day.
        if (row.when.length() >= 8 && row.when.substring(0, 8).matches("\\d{8}")) {
            long stamp =
                    PimdirSummary.stampOf(PimdirSummary.calendarSortKey(row.when.substring(0, 8)));
            return stamp == 0
                    ? row.when
                    : DateUtils.formatDateTime(
                            host,
                            stamp,
                            DateUtils.FORMAT_SHOW_DATE
                                    | DateUtils.FORMAT_ABBREV_MONTH
                                    | DateUtils.FORMAT_UTC);
        }
        return row.when;
    }

    private String where(DeletedItems.Row row) {
        int domain =
                PimdirSummary.MAIL.equals(row.kind)
                        ? R.string.domain_mail
                        : PimdirSummary.CONTACT.equals(row.kind)
                                ? R.string.domain_contacts
                                : R.string.domain_calendar;
        return host.getString(domain) + " · " + accountLabel(row) + " · " + label(row, row.collection);
    }

    /** A collection of the row's kind as the reader is shown it. */
    private String label(DeletedItems.Row row, PimdirCollections.Stored collection) {
        return PimdirSummary.MAIL.equals(row.kind)
                ? host.mail.mailboxLabel(collection)
                : collection.name;
    }

    private String status(DeletedItems.Row row) {
        if (row.waiting()) {
            return host.getString(R.string.deleted_waiting);
        }
        // NOTE: the store stamps milliseconds, which the shared reader of a
        // key does not take: the second is precision enough here.
        String retained = row.retainedAt.substring(0, Math.min(19, row.retainedAt.length()));
        long stamp = PimdirSummary.stampOf(retained + "Z");
        String deleted =
                stamp == 0 ? "" : host.getString(R.string.deleted_at, dateTime(stamp));
        if (row.stored()) {
            return deleted;
        }
        String missing = host.getString(R.string.deleted_not_stored);
        return deleted.isEmpty() ? missing : deleted + " · " + missing;
    }

    private String dateTime(long stamp) {
        return DateUtils.formatDateTime(
                host,
                stamp,
                DateUtils.FORMAT_SHOW_DATE
                        | DateUtils.FORMAT_SHOW_TIME
                        | DateUtils.FORMAT_ABBREV_MONTH);
    }

    /** The rows shown so far, and a "Show more" row while some are not. */
    private final class Adapter extends BaseAdapter {
        @Override
        public int getCount() {
            int visible = Math.min(shown, rows.size());
            return visible < rows.size() ? visible + 1 : visible;
        }

        @Override
        public Object getItem(int position) {
            return position < rows.size() ? rows.get(position) : null;
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public int getViewTypeCount() {
            return 2;
        }

        @Override
        public int getItemViewType(int position) {
            return position < Math.min(shown, rows.size()) ? 0 : 1;
        }

        @Override
        public boolean isEnabled(int position) {
            return getItemViewType(position) == 1 || !rows.get(position).waiting();
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            if (getItemViewType(position) == 1) {
                TextView more =
                        convertView instanceof TextView ? (TextView) convertView : line(15, true);
                more.setText(R.string.deleted_more);
                more.setTextColor(host.resolveColor(android.R.attr.colorAccent));
                more.setPadding(host.dp(16), host.dp(16), host.dp(16), host.dp(16));
                return more;
            }

            DeletedItems.Row row = rows.get(position);
            LinearLayout view =
                    convertView instanceof LinearLayout ? (LinearLayout) convertView : rowView();
            ((TextView) view.getChildAt(0)).setText(title(row));
            String facts = facts(row);
            TextView second = (TextView) view.getChildAt(1);
            second.setText(facts);
            second.setVisibility(facts.isEmpty() ? View.GONE : View.VISIBLE);
            ((TextView) view.getChildAt(2)).setText(where(row));
            TextView state = (TextView) view.getChildAt(3);
            state.setText(status(row));
            state.setTextColor(
                    host.resolveColor(
                            row.waiting()
                                    ? android.R.attr.colorAccent
                                    : android.R.attr.textColorSecondary));
            view.setAlpha(row.waiting() ? 0.7f : 1f);
            return view;
        }

        private LinearLayout rowView() {
            LinearLayout view = new LinearLayout(host);
            view.setOrientation(LinearLayout.VERTICAL);
            view.setPadding(host.dp(16), host.dp(10), host.dp(16), host.dp(10));
            view.addView(line(16, true));
            view.addView(line(14, false));
            view.addView(line(13, false));
            view.addView(line(13, false));
            return view;
        }

        private TextView line(int sp, boolean primary) {
            TextView line = new TextView(host);
            line.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
            line.setTextColor(
                    host.resolveColor(
                            primary
                                    ? android.R.attr.textColorPrimary
                                    : android.R.attr.textColorSecondary));
            line.setSingleLine(true);
            line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            return line;
        }
    }
}
