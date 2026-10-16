package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

/**
 * The rich editor, through the real bridge: styled text on screen, Markdown
 * in and out.
 *
 * <p>No syntax shows; the formatting bar sets and takes off styles, lines
 * take a list or a quote, and the text goes out as the Markdown it reads as.
 */
@RunWith(RobolectricTestRunner.class)
public class MarkdownFieldTest {
    private MarkdownField field;

    @Before
    public void setUp() {
        field = new MarkdownField(Robolectric.buildActivity(Activity.class).get(), null);
    }

    private void open(String markdown, int start, int end) {
        field.open(markdown, true);
        field.setSelection(start, end);
    }

    @Test
    public void markdownOpensWithoutItsSyntax() {
        field.open("Hi **you**, see [page](https://example.org)\n\n- one\n- two", true);
        assertEquals("Hi you, see page\n\none\ntwo", field.getText().toString());
        assertEquals(
                "Hi **you**, see [page](https://example.org)\n\n- one\n- two", field.markdown());
    }

    @Test
    public void boldGoesOnAroundTheSelectionAndComesOff() {
        open("see this now", 4, 8);
        field.toggle("strong");
        assertEquals("see this now", field.getText().toString());
        assertTrue(field.active("strong"));
        assertEquals("see **this** now", field.markdown());

        field.toggle("strong");
        assertFalse(field.active("strong"));
        assertEquals("see this now", field.markdown());
    }

    @Test
    public void typedSyntaxStaysText() {
        open("", 0, 0);
        field.getText().insert(0, "2*3*4 [x]");
        assertEquals("2\\*3\\*4 \\[x\\]", field.markdown());
    }

    @Test
    public void linesTakeAListAndGiveItBack() {
        open("one\ntwo", 0, 7);
        field.toggleLines("ordered");
        assertEquals("1. one\n2. two", field.markdown());
        assertTrue(field.active("ordered"));

        field.setSelection(0, field.length());
        field.toggleLines("bullet");
        assertEquals("- one\n- two", field.markdown());

        field.setSelection(0, field.length());
        field.toggleLines("bullet");
        assertEquals("one\ntwo", field.markdown());
    }

    @Test
    public void enterContinuesAListAndLeavesItOnAnEmptyItem() {
        open("- one", 3, 3);
        field.getText().insert(3, "\n");
        field.getText().insert(4, "two");
        assertEquals("- one\n- two", field.markdown());

        field.getText().insert(7, "\n");
        field.getText().insert(8, "\n");
        assertEquals("one\ntwo\n", field.getText().toString());
        assertEquals("- one\n- two", field.markdown().stripTrailing());
    }

    @Test
    public void aDashTypedAtALineStartStartsAList() {
        open("", 0, 0);
        field.getText().insert(0, "-");
        field.getText().insert(1, " ");
        field.getText().insert(0, "item");
        assertEquals("item", field.getText().toString());
        assertEquals("- item", field.markdown());
    }

    @Test
    public void aLinkIsReadBackAndRemoved() {
        open("see [page](https://example.org)", 6, 6);
        MarkdownField.Link link = field.link();
        assertEquals("page", link.text());
        assertEquals("https://example.org", link.url());

        field.writeLink(link, link.text(), "");
        assertEquals("see page", field.markdown());
    }

    @Test
    public void clearingTakesOffEveryStyleTheSelectionTouches() {
        open("**a** _b_ c", 0, 3);
        field.clearInline();
        assertEquals("a b c", field.markdown());
    }

    @Test
    public void aReplyKeepsItsBlankLinesAboveTheQuote() {
        field.open("\n\nOn Monday, Léa wrote:\n> Hi **Alex**", true);
        assertEquals("\n\nOn Monday, Léa wrote:\n\nHi Alex", field.getText().toString());
        assertTrue(field.markdown().endsWith("On Monday, Léa wrote:\n\n> Hi **Alex**"));
    }
}
