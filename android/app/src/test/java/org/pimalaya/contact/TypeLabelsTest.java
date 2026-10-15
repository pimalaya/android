package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import java.util.Arrays;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.TypeKind;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;

/**
 * Pins the edit form's type spinners, built from Rust's type sets.
 *
 * <p>A position crosses the bridge as an index into the Rust order (a
 * mismatch once saved Work as Home), so every row must carry its own words,
 * in both shipped locales, as the string-arrays they replace did.
 */
@RunWith(RobolectricTestRunner.class)
public class TypeLabelsTest {
    @Test
    public void everySpinnerReadsAsItsRows() {
        assertRows(
                TypeKind.PHONE,
                "[Mobile, Home, Work, Work mobile, Fax (home), Fax (work), Pager, Other]",
                "[Mobile, Domicile, Professionnel, Mobile professionnel, Fax (domicile),"
                        + " Fax (professionnel), Bipeur, Autre]");
        assertRows(TypeKind.EMAIL, "[Home, Work, Other]", "[Domicile, Professionnel, Autre]");
        assertRows(TypeKind.ADDRESS, "[Home, Work, Other]", "[Domicile, Professionnel, Autre]");
        assertRows(
                TypeKind.RELATION,
                "[Spouse, Child, Parent, Sibling, Friend, Colleague, Emergency, Other]",
                "[Conjoint, Enfant, Parent, Frère ou sœur, Ami, Collègue, Urgence, Autre]");
        assertRows(
                TypeKind.GENDER,
                "[Not set, Male, Female, Other, None, Unknown]",
                "[Non défini, Homme, Femme, Autre, Aucun, Inconnu]");
    }

    @Test
    public void everyRustSetHasItsOwnWords() {
        for (TypeKind kind : TypeKind.values()) {
            for (List<String> set : ContactClient.cardTypeOrder(kind)) {
                assertNotEquals(kind + " " + set, 0, TypeLabels.labelOf(kind, set));
            }
        }
    }

    private static void assertRows(TypeKind kind, String english, String french) {
        RuntimeEnvironment.setQualifiers("en");
        assertEquals(kind + " en", english, rows(kind));
        RuntimeEnvironment.setQualifiers("fr");
        assertEquals(kind + " fr", french, rows(kind));
    }

    private static String rows(TypeKind kind) {
        return Arrays.toString(
                TypeLabels.of(RuntimeEnvironment.getApplication().getResources(), kind));
    }
}
