/**
 * Mail: mailboxes and messages, their steps and bodies, the outbox, and the
 * mail screens.
 *
 * <p>{@link MailStore} keeps mailboxes as collections and messages as items
 * whose body comes later. {@link MailEngine} lists mailboxes on a held
 * session; a {@link MailStep} per backend brings the next messages whole,
 * and {@link MailBodies} downloads the bodies above the frontier.
 *
 * <p>Sending is a write: {@link MailSubmit} queues a composed message in the
 * account's {@link Outbox} (pimdir's action queue), and the next pass hands
 * it over. {@link MailStager} stages markers and deletions.
 *
 * <p>The screens are the merged list ({@link MailList}), the reader
 * ({@link MessageView}) and the composer ({@link MessageCompose}); what they
 * ask of the foreground sync goes through {@link MailSync}.
 */
package org.pimalaya.mail;
