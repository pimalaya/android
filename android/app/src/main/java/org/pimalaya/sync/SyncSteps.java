package org.pimalaya.sync;

import android.content.res.Resources;
import org.pimalaya.R;
import org.pimalaya.account.PimDomain;

/**
 * What the sync dock says and how full its bar is.
 *
 * <p>The line names the domain being synced, then the engine's step in
 * that domain's own words: a calendar pass downloads events and a mail
 * pass messages. The bar is one progress over an account's collections,
 * each counted step filling its share of the collection it runs in: the
 * download first, then the projection onto the phone for the domains that
 * have one.
 */
public final class SyncSteps {
    private SyncSteps() {}

    /**
     * The share of a collection its download fills in a domain mirrored on
     * the phone, the projection filling the rest.
     */
    static final double DOWNLOAD_SHARE = 0.6;

    /** The dock's domain for a pass over {@code domain}. */
    public static int lineOf(PimDomain domain) {
        switch (domain) {
            case MAIL:
                return R.string.sync_line_mail;
            case CALENDAR:
                return R.string.sync_line_calendars;
            default:
                return R.string.sync_line_contacts;
        }
    }

    /**
     * The words of {@code stage} in {@code domain}, after the domain on the
     * dock's line: a plural for a counted stage, a string otherwise, 0 when
     * the domain has no such stage. The phone steps are the two mirrors',
     * contacts and calendars, so mail has no words for them.
     */
    static int textOf(PimDomain domain, int stage) {
        switch (stage) {
            case PimdirEngine.Progress.STAGE_SERVER:
                return R.string.sync_step_server;
            case PimdirEngine.Progress.STAGE_DOWNLOAD:
                switch (domain) {
                    case MAIL:
                        return R.plurals.sync_step_download_mail;
                    case CALENDAR:
                        return R.plurals.sync_step_download_events;
                    default:
                        return R.plurals.sync_step_download_contacts;
                }
            case PimdirEngine.Progress.STAGE_UPLOAD:
                return R.plurals.sync_step_upload;
            case PimdirEngine.Progress.STAGE_PHONE:
                switch (domain) {
                    case CONTACTS:
                        return R.string.sync_step_phone;
                    case CALENDAR:
                        return R.string.sync_step_phone_calendar;
                    default:
                        return 0;
                }
            case PimdirEngine.Progress.STAGE_PROJECT:
                switch (domain) {
                    case CONTACTS:
                        return R.plurals.sync_step_project;
                    case CALENDAR:
                        return R.plurals.sync_step_project_events;
                    default:
                        return 0;
                }
            case PimdirEngine.Progress.STAGE_RESOLVE:
                return R.plurals.sync_step_resolve;
            default:
                return 0;
        }
    }

    /** Whether a stage's words carry its count, a plural resource. */
    static boolean counted(int stage) {
        return stage != PimdirEngine.Progress.STAGE_SERVER
                && stage != PimdirEngine.Progress.STAGE_PHONE;
    }

    /** The words of a stage, or null when the domain has no such stage. */
    public static String text(Resources resources, PimDomain domain, int stage, int count) {
        int text = textOf(domain, stage);
        if (text == 0) {
            return null;
        }
        return counted(stage)
                ? resources.getQuantityString(text, count, count)
                : resources.getString(text);
    }

    /**
     * The step counting an account's collections as they land: its
     * mailboxes, address books or calendars.
     */
    public static int collectionsOf(PimDomain domain) {
        switch (domain) {
            case MAIL:
                return R.string.sync_step_mailboxes;
            case CALENDAR:
                return R.string.sync_step_calendars;
            default:
                return R.string.sync_step_books;
        }
    }

    /**
     * How much of its collection a counted step has filled at {@code done}
     * of {@code total}, from 0 to 1.
     */
    public static double shareOf(PimDomain domain, int stage, int done, int total) {
        if (total <= 0) {
            return 0;
        }
        double step = (double) Math.min(Math.max(done, 0), total) / total;
        if (domain == PimDomain.MAIL) {
            return step;
        }
        if (stage == PimdirEngine.Progress.STAGE_PROJECT) {
            return DOWNLOAD_SHARE + (1 - DOWNLOAD_SHARE) * step;
        }
        return DOWNLOAD_SHARE * step;
    }

    /**
     * The bar in thousandths: {@code landed} of {@code of} collections plus
     * the {@code share} of the one under way, or that share alone when no
     * collection is counted.
     */
    public static int permille(int landed, int of, double share) {
        double current = Math.min(Math.max(share, 0), 1);
        if (of <= 0) {
            return (int) (current * 1000);
        }
        return (int) Math.min(1000, (Math.min(landed, of) + current) * 1000 / of);
    }

    /**
     * The bar over a whole pass, in thousandths: the sections passed, whose
     * weights come to {@code passed}, and the {@code section} thousandths
     * of the one under way weighing {@code weight}, out of the {@code
     * planned} whole; the section alone when nothing was planned.
     */
    public static int across(int passed, int weight, int planned, int section) {
        if (planned <= 0) {
            return section;
        }
        long whole = (long) passed * 1000 + (long) weight * section;
        return (int) Math.min(1000, whole / planned);
    }

    /**
     * Whether {@code done} of {@code total} is worth telling the dock: the
     * last item, or one crossing a percent. A projection of a thousand
     * contacts moves the bar ten at a time rather than posting a thousand
     * frames.
     */
    static boolean tells(int done, int total) {
        if (total <= 0 || done <= 0) {
            return false;
        }
        return done >= total || done * 100L / total != (done - 1) * 100L / total;
    }
}
