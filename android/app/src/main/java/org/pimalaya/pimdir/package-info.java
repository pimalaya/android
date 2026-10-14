/**
 * The pimdir store: the one database every account and domain shares, and
 * the half of an engine driver no domain owns.
 *
 * <p>{@link PimdirDb} opens it on the bundled SQLite with io-pimdir's schema
 * ({@code PimdirSql}, over JNI), {@link PimdirBlobs} holding the bodies.
 * {@link PimdirCollections}, {@link PimdirItems}, {@link PimdirSummary} and
 * {@link PimdirQueue} write the rows pimdir STORAGE fixes.
 *
 * <p>{@link PimdirStorage} answers the engine's storage yields. A
 * {@link Stager} serves those and stages mutations; a {@link PimdirEngine}
 * adds the remote yields a domain implements, and a {@link PhonePass} is one
 * whose remote is a phone provider ({@link PhoneSpoke}).
 *
 * <p>{@link Locks} is the process's one lock registry: the store's single
 * writer, a lock per collection a pass syncs, and the one sync at a time.
 * {@link PimdirAccount} namespaces an account's collection ids under a
 * stable id.
 *
 * <p>The reads that cross domains live here too: what deletions retained
 * ({@link DeletedItems}), references between items ({@link ItemLinks}), and
 * the collection a new item of a kind goes to ({@link DefaultCollection}).
 */
package org.pimalaya.pimdir;
