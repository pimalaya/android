package org.pimalaya.mail;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;
import java.util.Set;

/**
 * The owed bodies are rows of their own table, one written per key owed or
 * settled (cairn/changes/store-performance-followups, audit item 16), and
 * the set an earlier version kept is carried over.
 */
@RunWith(RobolectricTestRunner.class)
public class MailOwedTest {
    private Context context;

    @Before
    public void setUp() {
        context = RuntimeEnvironment.getApplication();
    }

    @Test
    public void owedKeysAreRowsOwedAndSettledOneByOne() {
        MailOwed.owe(context, "inbox", List.of("1", "2"));
        MailOwed.owe(context, "archive", List.of("3", "1"));
        MailOwed.owe(context, "inbox", List.of("2"));

        assertTrue(MailOwed.owed(context, "inbox", "1"));
        assertTrue(MailOwed.owed(context, "archive", "1"));
        assertFalse(MailOwed.owed(context, "archive", "2"));
        assertEquals(
                "each key once, in the order owed",
                List.of(
                        MailOwed.key("inbox", "1"),
                        MailOwed.key("inbox", "2"),
                        MailOwed.key("archive", "3"),
                        MailOwed.key("archive", "1")),
                MailOwed.all(context));

        MailOwed.settle(
                context, List.of(MailOwed.key("inbox", "2"), MailOwed.key("elsewhere", "9")));
        assertFalse(MailOwed.owed(context, "inbox", "2"));
        assertEquals(3, MailOwed.all(context).size());
    }

    @Test
    public void theSetAnEarlierVersionKeptIsCarriedOver() {
        context.getSharedPreferences("mail-owed", Context.MODE_PRIVATE)
                .edit()
                .putStringSet("owed", Set.of(MailOwed.key("inbox", "7")))
                .commit();

        assertTrue(MailOwed.owed(context, "inbox", "7"));
        assertFalse(
                "the set is forgotten once carried",
                context.getSharedPreferences("mail-owed", Context.MODE_PRIVATE).contains("owed"));
        MailOwed.settle(context, List.of(MailOwed.key("inbox", "7")));
        assertTrue(MailOwed.all(context).isEmpty());
    }
}
