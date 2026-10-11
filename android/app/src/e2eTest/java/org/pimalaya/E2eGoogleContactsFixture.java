package org.pimalaya;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;

/**
 * Google's contacts prepared through the People API: the user's one contact
 * list, which CardDAV serves as its default address book, so no scratch
 * collection is made and every contact the suite writes carries its tag in
 * its name.
 */
final class E2eGoogleContactsFixture implements E2eItemFixture {
    private static final String API = "https://people.googleapis.com/v1/";

    private final Callable<String> token;

    E2eGoogleContactsFixture(Callable<String> token) {
        this.token = token;
    }

    @Override
    public void createCollection(String name) {}

    @Override
    public void deleteCollection(String name) {}

    @Override
    public void seed(String collection, String title) throws Exception {
        E2eHttp.json(
                "POST",
                API + "people:createContact",
                token.call(),
                new JSONObject()
                        .put("names", new JSONArray().put(new JSONObject().put("givenName", title))));
    }

    @Override
    public List<String> titles(String collection, String text) throws Exception {
        List<String> titles = new ArrayList<>();
        for (JSONObject person : people(text)) {
            titles.add(nameOf(person));
        }
        return titles;
    }

    @Override
    public void sweep(String tag) throws Exception {
        for (JSONObject person : people(tag)) {
            try {
                E2eHttp.json(
                        "DELETE",
                        API + person.getString("resourceName") + ":deleteContact",
                        token.call(),
                        null);
            } catch (IOException gone) {
                // NOTE: deleted by the app meanwhile.
            }
        }
    }

    @Override
    public void sweepStale(LocalDateTime before) throws Exception {
        for (JSONObject person : people(E2eFixture.PREFIX)) {
            if (E2eFixture.stale(nameOf(person), before)) {
                E2eHttp.json(
                        "DELETE",
                        API + person.getString("resourceName") + ":deleteContact",
                        token.call(),
                        null);
            }
        }
    }

    /** The user's contacts whose name carries the text, every page read. */
    private List<JSONObject> people(String text) throws Exception {
        List<JSONObject> found = new ArrayList<>();
        String page = null;
        do {
            JSONObject answer =
                    E2eHttp.json(
                            "GET",
                            API + "people/me/connections?personFields=names&pageSize=1000"
                                    + (page == null ? "" : "&pageToken=" + page),
                            token.call(),
                            null);
            JSONArray connections = answer.optJSONArray("connections");
            for (int index = 0; connections != null && index < connections.length(); index++) {
                JSONObject person = connections.getJSONObject(index);
                if (nameOf(person).contains(text)) {
                    found.add(person);
                }
            }
            page = answer.optString("nextPageToken", null);
        } while (page != null);
        return found;
    }

    private static String nameOf(JSONObject person) {
        JSONArray names = person.optJSONArray("names");
        if (names == null || names.length() == 0) {
            return "";
        }
        JSONObject name = names.optJSONObject(0);
        return name.optString("displayName", name.optString("givenName", ""));
    }
}
