package org.pimalaya.contact;

import android.content.res.Resources;
import java.util.List;
import org.pimalaya.R;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.TypeKind;

/**
 * The edit form's type labels, one per type set, in the order Rust gives.
 *
 * <p>A spinner position crosses the bridge as an index into
 * {@link ContactClient#cardTypeOrder}, so the rows are built from it; only
 * the words are this app's, a string per set of type tokens.
 */
final class TypeLabels {
    private TypeLabels() {}

    /** The labels of one kind's rows, by position. */
    static String[] of(Resources resources, TypeKind kind) {
        List<List<String>> order = ContactClient.cardTypeOrder(kind);
        String[] labels = new String[order.size()];
        for (int position = 0; position < labels.length; position++) {
            List<String> set = order.get(position);
            int label = labelOf(kind, set);
            labels[position] = label == 0 ? String.join(" ", set) : resources.getString(label);
        }
        return labels;
    }

    /** The string naming a type set (a sex code for gender), 0 for none. */
    static int labelOf(TypeKind kind, List<String> set) {
        String tokens = String.join(",", set);
        if (kind == TypeKind.GENDER) {
            switch (tokens) {
                case "":
                    return R.string.sex_unset;
                case "M":
                    return R.string.sex_male;
                case "F":
                    return R.string.sex_female;
                case "O":
                    return R.string.sex_other;
                case "N":
                    return R.string.sex_none;
                case "U":
                    return R.string.sex_unknown;
                default:
                    return 0;
            }
        }
        switch (tokens) {
            case "":
                return R.string.type_other;
            case "home":
                return R.string.type_home;
            case "work":
                return R.string.type_work;
            case "cell":
                return R.string.type_cell;
            case "cell,work":
                return R.string.type_cell_work;
            case "fax,home":
                return R.string.type_fax_home;
            case "fax,work":
                return R.string.type_fax_work;
            case "pager":
                return R.string.type_pager;
            case "spouse":
                return R.string.type_spouse;
            case "child":
                return R.string.type_child;
            case "parent":
                return R.string.type_parent;
            case "sibling":
                return R.string.type_sibling;
            case "friend":
                return R.string.type_friend;
            case "colleague":
                return R.string.type_colleague;
            case "emergency":
                return R.string.type_emergency;
            default:
                return 0;
        }
    }
}
