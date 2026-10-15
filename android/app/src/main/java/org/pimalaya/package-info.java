/**
 * Pimalaya Android, the Java half: the screens and the platform around a
 * Rust bridge that holds every protocol, document format and sync decision.
 *
 * <h2>Boundary</h2>
 *
 * <p>Java owns everything with a platform behind it: the views, the sockets,
 * the SQLite pimdir store, the scheduler, the phone's contacts and calendars.
 * Rust (libpimalaya.so) performs no I/O and is reached over JNI through the
 * {@code :client} module alone ({@link org.pimalaya.client.Clients}).
 *
 * <p>Java holds as little logic as possible, what threading, Android
 * components and platform providers need, and decides by the facts the
 * bridge answers, never a backend's identity. Logic here that still
 * duplicates it (setup planning, edit diffs, contact fetch and push
 * strategy, mail naming, SQL beside the canonical statements) is tracked for
 * later boundary changes.
 *
 * <h2>Layout</h2>
 *
 * <p>Packages from the bottom up, as {@code LayeringTest} encodes them: each
 * references only packages below it, and this one, the composition root,
 * references all of them while nothing references it.
 *
 * <ul>
 *   <li>{@code client}: the bridge, behind five domain clients.
 *   <li>{@code account}: accounts, credentials, endpoints and list filters.
 *   <li>{@code phone}: the Android accounts and queue behind the phone mirror.
 *   <li>{@code pimdir}: the store, its locks and the engine drivers' base.
 *   <li>{@code ui}: the shared widgets and the activity they draw in.
 *   <li>{@code file}: attachments, folders and the Files tab.
 *   <li>{@code contact}, {@code calendar}: a store, an engine, a phone pass
 *       and the screens each.
 *   <li>{@code mail}: the mail store, engine, steps, outbox and screens.
 *   <li>{@code sync}: the passes, run in the foreground and in the background.
 *   <li>{@code setup}: the connection wizard, OAuth and account settings.
 *   <li>{@code filter}, {@code trash}, {@code link}: the filter page, deleted
 *       items and the Linked card, pages across the domains.
 *   <li>the root: {@link App}, {@link MainActivity} and the other components
 *       the manifest names, kept thin.
 * </ul>
 *
 * <h2>Sync</h2>
 *
 * <p>A pass is io-pimdir's engine run in Rust, its yields served in Java by a
 * driver ({@link org.pimalaya.pimdir.Stager}) against the store; a domain's
 * {@link org.pimalaya.pimdir.PimdirEngine} adds the remote yields (enumerate,
 * fetch, push) over its client, a phone pass the phone's provider instead.
 *
 * <p>{@link org.pimalaya.sync.SyncPasses} runs every remote and phone pass,
 * headless and blocking. {@link org.pimalaya.sync.SyncCoordinator} runs them
 * in the foreground with the sync dock, {@link BackgroundJob} headless, and
 * one sync holds the process at a time.
 *
 * <p>An edit is staged, never sent: a stager records a mutation the next pass
 * pushes, and {@link org.pimalaya.phone.PhoneQueue} runs the touched
 * collection's phone pass a second later. The OS sync adapters
 * ({@link SyncService}, {@link CalendarSyncService}) run phone passes too.
 *
 * <h2>Store</h2>
 *
 * <p>One pimdir store holds every account and every domain
 * ({@link org.pimalaya.pimdir.PimdirDb}, on a bundled SQLite): collections by
 * kind and account, items whose bodies are content-addressed blobs in a
 * directory beside the database.
 *
 * <p>Its schema and canonical statements come from io-pimdir over JNI
 * ({@link org.pimalaya.client.PimdirSql}), never transcribed. The domain
 * stores read and write their kind on top of it, under one writer lock and a
 * lock per synced collection ({@link org.pimalaya.pimdir.Locks}).
 *
 * <h2>Threading</h2>
 *
 * <p>{@link App} owns the process's state: the store, the card store, the
 * list filters, mail's state and the phone queue, each opened when first
 * asked for. {@link MainActivity} builds the screens and the coordinator; the
 * job and the sync adapters build what their pass needs.
 *
 * <p>{@link org.pimalaya.ui.Host}, the activity, owns the executors: a serial
 * {@code io} and an {@code interactive} one for what someone waits on. Work
 * posts back through {@code postAlive}, dropped once the activity is
 * destroyed, except the posts that may persist an account.
 *
 * <p>A pass runs one account's mailboxes or calendars side by side
 * ({@link org.pimalaya.sync.WorkerPool}), each worker on a session of its
 * own: only the network overlaps, the store keeping one writer.
 *
 * <h2>Tests</h2>
 *
 * <p>JVM tests mirror the packages, under Robolectric where they need Android
 * types, and load the real bridge built for the host. They fake only the
 * {@code :client} domain clients, a subclass handed in a {@code Clients};
 * {@code LayeringTest} fails on any reference against the layout above.
 */
package org.pimalaya;
