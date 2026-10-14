/**
 * Sync: the engines run over every account, in the foreground and in the
 * background.
 *
 * <p>{@link SyncPasses} is every remote and phone pass, headless and
 * blocking, a token refreshed once on a 401. {@link SyncCoordinator} runs
 * them one at a time on the activity's io thread, its progress on the sync
 * dock ({@link SyncProgress}); {@link BackgroundCheck} schedules the job.
 *
 * <p>{@link WorkerPool} runs one account's mailboxes or calendars side by
 * side, each worker on a session of its own. {@link FirstSync} and
 * {@link SyncStamps} keep what a new account is owed and when each account
 * last synced.
 */
package org.pimalaya.sync;
