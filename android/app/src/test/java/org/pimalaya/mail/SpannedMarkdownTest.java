package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/**
 * An HTML original read into the Markdown a rich reply or forward carries.
 *
 * <p>Its styles become markers, its blocks lines a blank line apart, and its
 * own text stays text.
 */
@RunWith(RobolectricTestRunner.class)
public class SpannedMarkdownTest {
    @Test
    public void stylesAndLinksBecomeMarkers() {
        assertEquals(
                "Hi **you**, *see* ~~this~~ `x` [page](https://example.org)",
                SpannedMarkdown.html(
                        "<p>Hi <b>you</b>, <i>see</i> <s>this</s> <tt>x</tt> "
                                + "<a href=\"https://example.org\">page</a></p>"));
    }

    @Test
    public void aListIsPartedFromWhatFollows() {
        assertEquals(
                "Points:\n\n- one\n- two\n\nLéa",
                SpannedMarkdown.html("<p>Points:</p><ul><li>one</li><li>two</li></ul><p>Léa</p>"));
    }

    @Test
    public void theOriginalsOwnSyntaxStaysText() {
        assertEquals("2\\*3\\*4 \\[x\\]", SpannedMarkdown.html("<p>2*3*4 [x]</p>"));
        assertEquals("\\# not a heading", SpannedMarkdown.html("<p># not a heading</p>"));
    }

    @Test
    public void aLinkOfAnotherSchemeKeepsItsText() {
        assertEquals("run", SpannedMarkdown.html("<a href=\"javascript:alert(1)\">run</a>"));
    }
}
