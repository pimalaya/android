package org.pimalaya.client;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A rich draft's Markdown as its editor shows it: the text, its styles.
 *
 * <p>Mirrors rs/mail/markdown.rs {@code Rich}. The syntax is gone from the
 * text; what the message sends as text (a heading, typed HTML) stays as
 * written. Offsets count UTF-16 units, as a Java string indexes.
 *
 * @param text what the editor shows
 * @param styles the styles over it, outermost first
 */
public record MarkdownText(String text, List<Style> styles) {
    /** Reads Markdown into the text and styles its editor shows. */
    public static MarkdownText of(String markdown) {
        JSONObject reply = Replies.object(Native.markdownRich(markdown));
        JSONArray array = reply.optJSONArray("styles");
        List<Style> styles = new ArrayList<>();
        for (int index = 0; array != null && index < array.length(); index++) {
            JSONObject style = array.optJSONObject(index);
            styles.add(
                    new Style(
                            style.optString("kind"),
                            style.optInt("start"),
                            style.optInt("end"),
                            style.optString("url", null)));
        }
        return new MarkdownText(reply.optString("text"), styles);
    }

    /**
     * One styled range.
     *
     * @param kind {@code strong}, {@code emphasis}, {@code strike}, {@code
     *     code}, {@code link}, {@code quote}, or a list item, {@code bullet}
     *     or {@code ordered}
     * @param start where it starts
     * @param end where it ends, exclusive
     * @param url a link's address, else null
     */
    public record Style(String kind, int start, int end, String url) {}
}
