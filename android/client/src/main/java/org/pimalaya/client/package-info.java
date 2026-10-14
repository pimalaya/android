/**
 * The bridge client: the {@code :client} module, the only Java that reaches
 * libpimalaya.so.
 *
 * <p>{@link Native} declares the JNI exports, package-private: each blocks,
 * drives a {@link Transport} for its sockets, and answers JSON, a value or an
 * {@code error} with the HTTP status the caller branches on
 * ({@link PimalayaException#status}).
 *
 * <p>The app holds the five domain clients ({@link Clients}):
 * {@link AccountClient}, {@link ContactClient}, {@link CalendarClient},
 * {@link MailClient} on a held {@link MailSession}, and {@link OfflineClient},
 * running io-pimdir's engine through an {@link OfflineDriver}.
 *
 * <p>Replies are read into records mirroring the Rust serde types; JSON stays
 * on the driver's wire and in the summary rows pimdir binds by column.
 * {@link PimdirSql} carries io-pimdir's schema and canonical statements
 * across rather than a transcription. Tests fake a domain client, never this.
 */
package org.pimalaya.client;
