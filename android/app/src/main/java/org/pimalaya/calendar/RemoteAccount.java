package org.pimalaya.calendar;

import org.pimalaya.client.Account;
import org.pimalaya.client.Transport;

/**
 * The account a {@link CalendarEngine} reconciles with.
 *
 * @param transport the pass's sockets; null on a driver that only stages
 * @param account the server signed in to; null on a driver that only stages
 * @param accountId what the account's collection ids are namespaced under
 * @param address the account's address, the owner the phone gives its
 *     calendars; null on a driver that only stages
 */
public record RemoteAccount(
        Transport transport, Account account, String accountId, String address) {}
