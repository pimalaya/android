package org.pimalaya.pimdir;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.function.IntConsumer;

/** A phone provider as a pass's remote, keyed by the spoke collection id. */
public interface PhoneSpoke {
    /** Whether the spoke can serve: permission granted, collection shown. */
    boolean available(String spoke);

    /** Whether the provider changed for the store: half the quiet path. */
    boolean changed(String spoke);

    /** The members the provider holds, counted as the store counts them. */
    int count(String spoke);

    /** The collection's member spine, as the reply to an enumerate yield. */
    JSONObject enumerate(String spoke) throws JSONException;

    /** The bodies of the given handles, as the reply to a fetch yield. */
    JSONObject fetch(String spoke, JSONArray handles) throws JSONException;

    /**
     * The staged changes written to the provider.
     *
     * <p>{@code pushed} is told how many are through.
     */
    JSONObject push(String spoke, JSONArray changes, IntConsumer pushed) throws JSONException;
}
