package org.pimalaya.client;

/**
 * Consumer seam of the io-pimdir engine, served once per engine yield.
 *
 * <p>The bridge upcalls {@link #serve} with one JSON envelope per yield
 * (store reads and writes, remote enumerate, fetch, push) and takes its JSON
 * reply; a reply with an {@code error} field aborts the operation with it.
 */
public interface OfflineDriver {
    /** Services one engine yield, returning its JSON reply. */
    String serve(String yieldJson);
}
