package org.pimalaya.client;

import static org.junit.Assert.assertEquals;

import android.content.res.Resources;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.R;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Pins the edit form's type spinners to the Rust-owned ordering.
 *
 * <p>Each string-array matches project.rs's TYPE sets by position (a mismatch
 * saved Work as Home). The pairings below are the cross-language contract,
 * checked against Rust and {@code arrays.xml}.
 */
@RunWith(RobolectricTestRunner.class)
public class TypeTablesTest {
    @Test
    public void phoneSpinnerIsAligned() {
        assertAligned(
                R.array.phone_types,
                TypeKind.PHONE,
                pair("Mobile", "cell"),
                pair("Home", "home"),
                pair("Work", "work"),
                pair("Work mobile", "cell", "work"),
                pair("Fax (home)", "fax", "home"),
                pair("Fax (work)", "fax", "work"),
                pair("Pager", "pager"),
                pair("Other"));
    }

    @Test
    public void emailSpinnerIsAligned() {
        assertAligned(
                R.array.email_types, TypeKind.EMAIL, pair("Home", "home"), pair("Work", "work"), pair("Other"));
    }

    @Test
    public void addressSpinnerIsAligned() {
        assertAligned(
                R.array.address_types,
                TypeKind.ADDRESS,
                pair("Home", "home"),
                pair("Work", "work"),
                pair("Other"));
    }

    @Test
    public void relationSpinnerIsAligned() {
        assertAligned(
                R.array.relation_types,
                TypeKind.RELATION,
                pair("Spouse", "spouse"),
                pair("Child", "child"),
                pair("Parent", "parent"),
                pair("Sibling", "sibling"),
                pair("Friend", "friend"),
                pair("Colleague", "colleague"),
                pair("Emergency", "emergency"),
                pair("Other"));
    }

    @Test
    public void genderSpinnerIsAligned() {
        // NOTE: the gender spinner carries a sex code per position, not a TYPE
        // list; an empty code is the unset first row.
        assertAligned(
                R.array.gender_types,
                TypeKind.GENDER,
                pair("Not set", ""),
                pair("Male", "M"),
                pair("Female", "F"),
                pair("Other", "O"),
                pair("None", "N"),
                pair("Unknown", "U"));
    }

    /** Checks the array's labels and the Rust ordering against the pairings. */
    private static void assertAligned(int arrayId, TypeKind kind, Pair... expected) {
        Resources resources = RuntimeEnvironment.getApplication().getResources();
        String[] labels = resources.getStringArray(arrayId);
        List<List<String>> order = ContactClient.cardTypeOrder(kind);

        assertEquals(kind.wire() + " label count", expected.length, labels.length);
        assertEquals(kind.wire() + " Rust order length", expected.length, order.size());

        for (int position = 0; position < expected.length; position++) {
            assertEquals(
                    kind.wire() + " label " + position, expected[position].label, labels[position]);
            assertEquals(
                    kind.wire() + " types " + position,
                    expected[position].types,
                    order.get(position));
        }
    }

    private static Pair pair(String label, String... types) {
        // NOTE: a list kind's "Other" row is the empty TYPE set; the gender
        // rows carry their sex code, the unset row an empty one.
        List<String> list = new ArrayList<>();
        for (String type : types) {
            list.add(type);
        }
        return new Pair(label, list);
    }

    /** One expected spinner row: its base-locale label and TYPE set. */
    private static final class Pair {
        final String label;
        final List<String> types;

        Pair(String label, List<String> types) {
            this.label = label;
            this.types = types;
        }
    }
}
