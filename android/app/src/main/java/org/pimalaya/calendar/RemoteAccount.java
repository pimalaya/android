package org.pimalaya.calendar;

import org.pimalaya.client.Account;
import org.pimalaya.client.Transport;

/**
 * The account a {@link CalendarEngine} reconciles with.
 *
 * <p>{@code transport} and {@code account} are null on a driver that only
 * stages. {@code accountId} namespaces the collection ids.
 */
public record RemoteAccount(Transport transport, Account account, String accountId) {}
