package org.pimalaya.sync;

import android.content.res.Resources;
import org.pimalaya.R;
import org.pimalaya.account.PimDomain;
import org.pimalaya.pimdir.PimdirEngine;

/**
 * What the sync dock says and how full its bar is.
 *
 * <p>The line names the domain, then the engine's step in its own words. The
 * bar is one progress over an account's collections, each counted step filling
 * its share: the download, then the phone projection where there is one.
 */
public final class SyncSteps {
    private SyncSteps() {}

    /**
     * A collection's share its download fills in a phone-mirrored domain.
     *
     * <p>The projection onto the phone fills the rest.
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
     * The words of {@code stage} in {@code domain}, 0 for no such stage.
     *
     * <p>A plural for a counted stage, a string otherwise. The phone steps are
     * the contact and calendar mirrors', so mail has no words for them.
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

    /** The step counting an account's collections as they land. */
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

    /** How much of its collection a counted step filled, from 0 to 1. */
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
     * The bar in thousandths: {@code landed} of {@code of} plus {@code share}.
     *
     * <p>{@code share} is the collection under way's; alone when none is
     * counted.
     */
    public static int permille(int landed, int of, double share) {
        double current = Math.min(Math.max(share, 0), 1);
        if (of <= 0) {
            return (int) (current * 1000);
        }
        return (int) Math.min(1000, (Math.min(landed, of) + current) * 1000 / of);
    }

    /**
     * The bar over a whole pass, in thousandths.
     *
     * <p>The sections passed (weights summing to {@code passed}) plus {@code
     * section} thousandths of the current one weighing {@code weight}, out of
     * {@code planned}; {@code section} alone when nothing was planned.
     */
    public static int across(int passed, int weight, int planned, int section) {
        if (planned <= 0) {
            return section;
        }
        long whole = (long) passed * 1000 + (long) weight * section;
        return (int) Math.min(1000, whole / planned);
    }
}
