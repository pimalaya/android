package org.pimalaya.client;

/**
 * The five domain clients the app composes, one per domain of the bridge.
 *
 * @param account config search, OAuth and backend info
 * @param contact addressbooks, cards and the vCard computations
 * @param calendar calendars, events and the iCalendar computations
 * @param mail the verbs on a held mail session
 * @param offline the io-pimdir engine and its plan verbs
 */
public record Clients(
        AccountClient account,
        ContactClient contact,
        CalendarClient calendar,
        MailClient mail,
        OfflineClient offline) {
    /** The clients over the real bridge. */
    public static Clients real() {
        return new Clients(
                new AccountClient(),
                new ContactClient(),
                new CalendarClient(),
                new MailClient(),
                new OfflineClient());
    }
}
