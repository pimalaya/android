package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What a mail search could not see over the mailboxes shown (pimdir SEARCH
 * 8.2), rolled up by the bridge for the mail list's end line.
 *
 * <p>Mirrors rs/search/coverage.rs {@code MailCoverage}: io-pimdir's
 * {@code PimdirSearchCoverage} per account, complete when it is for every
 * one.
 *
 * @param complete whether nothing the coverage reports could have changed
 *     the answer, for every account
 * @param shortAccounts how many shown accounts fall short on the range
 *     searched
 * @param worst the account falling shortest (its id), null when none does
 * @param since the instant below which the worst account is not known
 *     whole, null when no floor is the cause
 * @param bounded whether {@code since} is that account's own bound, below
 *     which the phone keeps nothing
 * @param filling whether a shown mailbox is still filling in
 * @param unread the messages searched without their body
 * @param unindexed the messages the index has not folded in yet
 * @param behind how far the whole index is behind the store
 * @param accounts every shown account searched
 */
public record SearchCoverage(
        boolean complete,
        int shortAccounts,
        String worst,
        String since,
        boolean bounded,
        boolean filling,
        long unread,
        long unindexed,
        long behind,
        List<Account> accounts) {

    /**
     * One shown account's coverage.
     *
     * @param account the account id, null for an account-less collection
     * @param complete whether its answer is complete
     * @param isShort whether it falls short on the range searched
     * @param since the instant below which it is not known whole, or null
     * @param filling whether one of its mailboxes is still filling in
     * @param bounded whether {@code since} is its own bound
     * @param unread its messages searched without their body
     * @param unindexed its messages the index has not folded in yet
     */
    public record Account(
            String account,
            boolean complete,
            boolean isShort,
            String since,
            boolean filling,
            boolean bounded,
            long unread,
            long unindexed) {
        static Account from(JSONObject reply) {
            return new Account(
                    Json.optString(reply, "account"),
                    reply.optBoolean("complete"),
                    reply.optBoolean("short"),
                    Json.optString(reply, "since"),
                    reply.optBoolean("filling"),
                    reply.optBoolean("bounded"),
                    reply.optLong("unread"),
                    reply.optLong("unindexed"));
        }
    }

    /** The coverage of a bridge reply, null for none. */
    public static SearchCoverage from(JSONObject reply) {
        if (reply == null) {
            return null;
        }
        List<Account> accounts = new ArrayList<>();
        JSONArray listed = reply.optJSONArray("accounts");
        for (int at = 0; listed != null && at < listed.length(); at++) {
            accounts.add(Account.from(listed.optJSONObject(at)));
        }
        return new SearchCoverage(
                reply.optBoolean("complete"),
                reply.optInt("short"),
                Json.optString(reply, "worst"),
                Json.optString(reply, "since"),
                reply.optBoolean("bounded"),
                reply.optBoolean("filling"),
                reply.optLong("unread"),
                reply.optLong("unindexed"),
                reply.optLong("behind"),
                List.copyOf(accounts));
    }
}
