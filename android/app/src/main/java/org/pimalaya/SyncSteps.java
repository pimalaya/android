package org.pimalaya;

/**
 * What the sync strip's step line says for one engine stage, in the
 * words of the domain being synced, and when a count is worth telling.
 *
 * <p>The stages are every engine's ({@link PimdirEngine.Progress}), the
 * nouns are not: a calendar pass downloads events and a mail pass messages,
 * and a line saying contacts while the agenda syncs reads as the wrong sync
 * running. The phone steps are the two mirrors', contacts and calendars, so
 * mail has no text for them and leaves the line as it was.
 */
final class SyncSteps {
    private SyncSteps() {}

    /**
     * The text of {@code stage} in {@code domain}: a plural for a counted
     * stage ({@link #counted}), a string otherwise, 0 when the domain has no
     * such stage.
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

    /** Whether a stage's text carries its count, a plural resource. */
    static boolean counted(int stage) {
        return stage != PimdirEngine.Progress.STAGE_SERVER
                && stage != PimdirEngine.Progress.STAGE_PHONE;
    }

    /**
     * The line counting an account's collections as they land: its
     * mailboxes, address books or calendars.
     */
    static int collectionsOf(PimDomain domain) {
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

    /** The line itself, or null when the domain has no such stage. */
    static String text(android.content.res.Resources resources, PimDomain domain, int stage,
            int count) {
        int text = textOf(domain, stage);
        if (text == 0) {
            return null;
        }
        return counted(stage)
                ? resources.getQuantityString(text, count, count)
                : resources.getString(text);
    }
}
