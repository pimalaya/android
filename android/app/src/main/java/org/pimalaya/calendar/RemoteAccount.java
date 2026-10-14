package org.pimalaya.calendar;

import org.pimalaya.client.Account;
import org.pimalaya.client.Transport;

/**
 * The account a {@link CalendarEngine} reconciles with.
 *
 * <p>{@code transport}, {@code account} and {@code address} are null on a
 * driver that only stages. {@code accountId} namespaces the collection ids;
 * {@code address} owns the phone's calendars.
 */
public record RemoteAccount(
        Transport transport, Account account, String accountId, String address) {}
