package org.pimalaya;

/**
 * What the sync strip says and how full its bar is.
 *
 * <p>The line names the domain being synced and nothing of the engine's
 * steps: a pass reads as one wait. The bar is one progress over an
 * account's collections, each counted step filling its share of the
 * collection it runs in: the download first, then the projection onto the
 * phone for the domains that have one.
 */
final class SyncSteps {
    private SyncSteps() {}

    /**
     * The share of a collection its download fills in a domain mirrored on
     * the phone, the projection filling the rest.
     */
    static final double DOWNLOAD_SHARE = 0.6;

    /** The strip's line for a pass over {@code domain}. */
    static int lineOf(PimDomain domain) {
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
     * How much of its collection a counted step has filled at {@code done}
     * of {@code total}, from 0 to 1.
     */
    static double shareOf(PimDomain domain, int stage, int done, int total) {
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
    static int permille(int landed, int of, double share) {
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
    static int across(int passed, int weight, int planned, int section) {
        if (planned <= 0) {
            return section;
        }
        long whole = (long) passed * 1000 + (long) weight * section;
        return (int) Math.min(1000, whole / planned);
    }

    /**
     * Whether {@code done} of {@code total} is worth telling the strip: the
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
