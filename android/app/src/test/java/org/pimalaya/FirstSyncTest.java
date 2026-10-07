package org.pimalaya;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

import java.util.List;

/**
 * Each domain of a freshly connected account owes its first sync to the
 * first visit of its tab, and only that domain's: the mail tab the flow
 * lands on pays the mail, and contacts and calendars stay owed until their
 * tabs are opened.
 */
@RunWith(RobolectricTestRunner.class)
public class FirstSyncTest {
    @Test
    public void eachDomainIsOwedUntilItsTabPaysIt() {
        Context context = RuntimeEnvironment.getApplication();
        FirstSync.owe(context, "jane@example.com", PimDomain.MAIL);
        FirstSync.owe(context, "jane@example.com", PimDomain.CONTACTS);
        FirstSync.owe(context, "jane@example.com", PimDomain.CALENDAR);
        FirstSync.owe(context, "joe@example.org", PimDomain.MAIL);

        assertEquals(2, FirstSync.owing(context, PimDomain.MAIL).size());

        // NOTE: the mail tab's dialog paid the mail of both accounts; the
        // contacts and the calendars wait for their own tabs.
        FirstSync.paid(context, "jane@example.com", PimDomain.MAIL);
        FirstSync.paid(context, "joe@example.org", PimDomain.MAIL);
        assertTrue(FirstSync.owing(context, PimDomain.MAIL).isEmpty());
        assertEquals(List.of("jane@example.com"), FirstSync.owing(context, PimDomain.CONTACTS));
        assertEquals(List.of("jane@example.com"), FirstSync.owing(context, PimDomain.CALENDAR));

        FirstSync.paid(context, "jane@example.com", PimDomain.CONTACTS);
        assertTrue(FirstSync.owing(context, PimDomain.CONTACTS).isEmpty());
        assertEquals(List.of("jane@example.com"), FirstSync.owing(context, PimDomain.CALENDAR));

        // NOTE: a removed account owes nothing any more.
        FirstSync.forget(context, "jane@example.com");
        assertTrue(FirstSync.owing(context, PimDomain.CALENDAR).isEmpty());
    }
}
