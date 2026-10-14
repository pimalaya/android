/**
 * Shared widgets: what every screen draws with, depending on no other
 * package.
 *
 * <p>{@link Host} is the activity as the screens see it: a serial {@code io}
 * executor and an {@code interactive} one, the guarded way back to the main
 * thread ({@link Host#postAlive}, {@link Background}), and the chrome a
 * list's scroll drives.
 *
 * <p>The rest is the app's one look: the item page ({@link Sections}), the
 * grouped lists ({@link CardSections}, {@link ListHeader}), chips, pill
 * fields, avatars, the wording of dates ({@link Dates}), and the sync dock
 * ({@link SyncDock}) the foreground sync draws on.
 */
package org.pimalaya.ui;
