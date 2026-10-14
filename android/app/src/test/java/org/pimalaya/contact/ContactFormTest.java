package org.pimalaya.contact;

import static org.junit.Assert.assertEquals;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.app.Dialog;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.DatePicker;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.TextView;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.pimalaya.R;
import org.pimalaya.client.CardCorpus;
import org.pimalaya.client.CardsMerge;
import org.pimalaya.client.ConflictForm;
import org.pimalaya.client.ContactClient;
import org.pimalaya.ui.StubHost;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.shadows.ShadowDialog;

/**
 * Pins the edit form end to end on the real bridge, driven as a user would.
 *
 * <p>Rows, order, labels, glyphs, dialog prefills and the applied vCard, over
 * every field kind, a merge and a conflict form (contact-form.txt, captured
 * from the JSON working model the records replaced).
 */
@RunWith(RobolectricTestRunner.class)
public class ContactFormTest {
    private StubHost activity;
    private ContactForm form;
    private final List<String> out = new ArrayList<>();

    @Test
    public void everyFormOutcomeIsTheOneCaptured() throws Exception {
        activity = Robolectric.buildActivity(StubHost.class).create().get();
        activity.setContentView(R.layout.panel_contact);
        form = new ContactForm(activity);

        newContact();
        editRich();
        for (int index = 0; index < CardCorpus.VCARDS.size() - 1; index++) {
            browse(index);
        }
        mergeForm();
        conflictForm();

        List<String> expected = new ArrayList<>();
        try (InputStream in = ContactFormTest.class.getResourceAsStream("contact-form.txt");
                BufferedReader reader =
                        new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            for (String line = reader.readLine(); line != null; line = reader.readLine()) {
                expected.add(line);
            }
        }
        for (int index = 0; index < Math.min(expected.size(), out.size()); index++) {
            assertEquals("line " + (index + 1), expected.get(index), out.get(index));
        }
        assertEquals(expected.size(), out.size());
    }

    /** A fresh card filled field by field through the chooser. */
    private void newContact() throws Exception {
        String vcard = "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:fixed\r\nEND:VCARD\r\n";
        out.add("== new");
        form.load(null, null, null);
        page();

        openTitled(R.string.section_identity, R.string.hint_given);
        type(0, "Jane");
        ok();
        openTitled(R.string.section_identity, R.string.hint_family);
        type(0, "Doe");
        ok();
        page();

        choose(R.string.item_phone);
        spin(2);
        type(0, "+33 1");
        ok();
        choose(R.string.item_phone);
        type(0, "   ");
        ok();
        choose(R.string.item_email);
        spin(1);
        type(0, "a@b.c");
        ok();
        choose(R.string.hint_display_name);
        type(0, "Jane Doe");
        ok();
        choose(R.string.hint_birthday);
        date(1990, 0, 2);
        choose(R.string.item_gender);
        type(0, "female");
        ok();
        choose(R.string.item_address);
        radio(2);
        type(0, "1 Rue\nBat B");
        type(1, "Lyon");
        ok();
        choose(R.string.item_nickname);
        type(0, "JD");
        ok();
        choose(R.string.item_note);
        type(0, "hello");
        ok();
        choose(R.string.item_website);
        type(0, "https://x.example");
        ok();
        choose(R.string.item_impp);
        type(0, "xmpp:a@b");
        ok();
        choose(R.string.item_language);
        type(0, "fr");
        ok();
        choose(R.string.item_relation);
        spin(4);
        type(0, "Bob");
        ok();
        choose(R.string.hint_company);
        type(0, "ACME");
        ok();
        choose(R.string.hint_department);
        type(0, "R&D");
        ok();
        choose(R.string.hint_job_title);
        type(0, "Boss");
        ok();
        choose(R.string.hint_role);
        type(0, "  ");
        ok();
        choose(R.string.item_anniversary);
        clearDate();
        choose(R.string.item_anniversary);
        date(2000, 2, 4);
        page();
        chooser();
        collect(vcard);
    }

    /**
     * Every kind of edit on the richest card, present-empty values included.
     */
    private void editRich() throws Exception {
        String vcard = CardCorpus.VCARDS.get(1);
        out.add("== rich");
        loadCard(vcard);
        page();

        openTitled(R.string.section_identity, R.string.hint_display_name);
        type(0, "");
        ok();
        openTitled(R.string.section_identity, R.string.hint_prefix);
        remove();
        openTitled(R.string.section_identity, R.string.hint_middle);
        type(0, "");
        ok();
        openAt(R.string.section_phones, 0);
        spin(5);
        ok();
        openAt(R.string.section_phones, 1);
        remove();
        openAt(R.string.section_emails, 1);
        type(0, "jane@new.example");
        ok();
        openAt(R.string.section_addresses, 0);
        radio(3);
        type(1, "Marseille");
        ok();
        openAt(R.string.section_addresses, 1);
        for (int index = 0; index < 5; index++) {
            type(index, "");
        }
        ok();
        openTitled(R.string.section_identity, R.string.item_gender);
        type(0, "nb");
        ok();
        openTitled(R.string.section_identity, R.string.hint_birthday);
        date(1985, 3, 13);
        openTitled(R.string.section_identity, R.string.item_anniversary);
        clearDate();
        openTitled(R.string.tab_work, R.string.hint_department);
        type(0, "");
        ok();
        openTitled(R.string.tab_work, R.string.hint_company);
        remove();
        openTitled(R.string.tab_work, R.string.hint_job_title);
        remove();
        openTitled(R.string.tab_work, R.string.hint_role);
        type(0, "VP");
        ok();
        openAt(R.string.section_nicknames, 1);
        type(0, "");
        ok();
        openAt(R.string.section_notes, 0);
        type(0, "first note, edited");
        ok();
        openAt(R.string.section_websites, 0);
        remove();
        openAt(R.string.section_languages, 0);
        type(0, "de");
        ok();
        openAt(R.string.section_relations, 1);
        spin(0);
        ok();
        page();
        chooser();
        collect(vcard);
    }

    /** Opens every row of a card's page, as loaded, and cancels it. */
    private void browse(int index) throws Exception {
        String vcard = CardCorpus.VCARDS.get(index);
        out.add("== browse " + index);
        loadCard(vcard);
        page();
        List<View> rows = rows(container());
        for (int at = 0; at < rows.size(); at++) {
            List<View> fresh = rows(container());
            fresh.get(at).performClick();
            idle();
            dialog();
            cancel();
        }
        chooser();
        collect(vcard);
    }

    /** A merge of diverging cards: glyphs, chips, review and an added row. */
    private void mergeForm() throws Exception {
        out.add("== merge");
        String vcard =
                loadMerge(
                        List.of(
                                CardCorpus.VCARDS.get(3),
                                CardCorpus.VCARDS.get(1),
                                CardCorpus.VCARDS.get(2)));
        page();
        out.add("resolved " + form.conflictResolved());

        openTitled(R.string.section_identity, R.string.hint_display_name);
        chip(1);
        ok();
        openTitled(R.string.section_identity, R.string.hint_given);
        cancel();
        page();
        openTitled(R.string.section_identity, R.string.hint_given);
        ok();
        choose(R.string.item_phone);
        type(0, "+1 000");
        ok();
        page();

        for (int round = 0; round < 40 && !form.conflictResolved(); round++) {
            View pending = firstDiverged(container());
            pending.performClick();
            idle();
            Dialog shown = ShadowDialog.getLatestDialog();
            if (shown instanceof DatePickerDialog) {
                ((DatePickerDialog) shown)
                        .getButton(DatePickerDialog.BUTTON_POSITIVE)
                        .performClick();
                idle();
            } else {
                dialog();
                ok();
            }
        }
        page();
        out.add("resolved " + form.conflictResolved());
        collect(vcard);
    }

    /** A conflicted row edited both sides, settled through its chips. */
    private void conflictForm() throws Exception {
        String base =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:c\r\nFN:Base\r\nTITLE:T\r\n"
                        + "TEL:1\r\nREV:20200101T000000Z\r\nEND:VCARD\r\n";
        String local =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:c\r\nFN:Local\r\nTITLE:LT\r\n"
                        + "TEL:1\r\nTEL:2\r\nREV:20210101T000000Z\r\nEND:VCARD\r\n";
        String remote =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:c\r\nFN:Remote\r\nTITLE:RT\r\n"
                        + "TEL:1\r\nTEL:3\r\nREV:20220101T000000Z\r\nEND:VCARD\r\n";
        String clean =
                "BEGIN:VCARD\r\nVERSION:4.0\r\nUID:c\r\nFN:Base\r\nTITLE:T\r\n"
                        + "TEL:1\r\nTEL:4\r\nREV:20220101T000000Z\r\nEND:VCARD\r\n";

        out.add("== conflict clean");
        loadConflict(base, local, clean);

        out.add("== conflict");
        String vcard = loadConflict(base, local, remote);
        page();
        out.add("resolved " + form.conflictResolved());
        openTitled(R.string.section_identity, R.string.hint_display_name);
        chip(1);
        ok();
        openTitled(R.string.tab_work, R.string.hint_job_title);
        chip(0);
        type(0, "Mine");
        ok();
        page();
        out.add("resolved " + form.conflictResolved());
        collect(vcard);
    }

    private void loadCard(String vcard) {
        form.load(ContactClient.projectCard(vcard), null, null);
    }

    private String loadMerge(List<String> docs) {
        CardsMerge merged = ContactClient.mergeCards(docs);
        out.add("merged " + escape(merged.vcard()));
        form.load(merged.model(), merged.alternatives(), merged.changed());
        return merged.vcard();
    }

    private String loadConflict(String base, String local, String remote) {
        ConflictForm resolution = ContactClient.mergeConflictForm(base, local, remote);
        out.add("resolution " + resolution.resolved() + " " + escape(resolution.vcard()));
        if (!resolution.resolved()) {
            form.load(resolution.model(), resolution.alternatives(), resolution.changed());
        }
        return resolution.vcard();
    }

    private void collect(String vcard) {
        out.add("birthday [" + form.birthday() + "] anniversary [" + form.anniversary() + "]");
        try {
            out.add("apply " + escape(ContactClient.applyCard(vcard, form.collect())));
        } catch (Exception error) {
            out.add("apply ERR " + error.getMessage());
        }
    }

    private View container() {
        return activity.findViewById(R.id.contact_form);
    }

    /** The section card headed so. */
    private View card(int section) {
        String title = activity.getString(section);
        ViewGroup container = (ViewGroup) container();
        for (int index = 0; index < container.getChildCount(); index++) {
            if (title.equals(firstText(container.getChildAt(index)))) {
                return container.getChildAt(index);
            }
        }
        throw new AssertionError("no section " + title);
    }

    /** Taps the section's row titled so, then records the dialog it opens. */
    private void openTitled(int section, int title) {
        String text = activity.getString(title);
        for (View row : rows(card(section))) {
            if (text.equals(firstText(row))) {
                tap(text, row);
                return;
            }
        }
        throw new AssertionError("no row " + text);
    }

    /** Taps the section's nth row, then records the dialog it opens. */
    private void openAt(int section, int index) {
        tap(activity.getString(section) + " " + index, rows(card(section)).get(index));
    }

    private void tap(String what, View row) {
        out.add("open " + what);
        row.performClick();
        idle();
        dialog();
    }

    /** Picks a field from the main add chooser, then records its dialog. */
    private void choose(int label) {
        form.addField();
        idle();
        Dialog chooser = ShadowDialog.getLatestDialog();
        String text = activity.getString(label);
        for (View row : rows(chooser.getWindow().getDecorView())) {
            if (text.equals(firstText(row))) {
                out.add("choose " + text);
                row.performClick();
                idle();
                dialog();
                return;
            }
        }
        throw new AssertionError("no choice " + text);
    }

    /** Records what the main add chooser offers, then closes it. */
    private void chooser() {
        form.addField();
        idle();
        Dialog chooser = ShadowDialog.getLatestDialog();
        StringBuilder line = new StringBuilder("chooser");
        for (View row : rows(chooser.getWindow().getDecorView())) {
            line.append(" [").append(firstText(row)).append("]");
        }
        out.add(line.toString());
        chooser.dismiss();
        idle();
    }

    /** Records the open dialog: its views, or a date picker's date. */
    private void dialog() {
        Dialog shown = ShadowDialog.getLatestDialog();
        if (shown instanceof DatePickerDialog) {
            DatePicker picker = ((DatePickerDialog) shown).getDatePicker();
            Calendar today = Calendar.getInstance();
            boolean isToday =
                    picker.getYear() == today.get(Calendar.YEAR)
                            && picker.getMonth() == today.get(Calendar.MONTH)
                            && picker.getDayOfMonth() == today.get(Calendar.DAY_OF_MONTH);
            out.add(
                    "  picker "
                            + (isToday
                                    ? "today"
                                    : picker.getYear() + "-" + picker.getMonth() + "-"
                                            + picker.getDayOfMonth()));
            return;
        }
        StringBuilder line = new StringBuilder("  dialog");
        dump(shown.getWindow().getDecorView(), line);
        out.add(line.toString());
    }

    private void type(int index, String text) {
        edits(ShadowDialog.getLatestDialog().getWindow().getDecorView()).get(index).setText(text);
    }

    private void spin(int position) {
        find(ShadowDialog.getLatestDialog().getWindow().getDecorView(), Spinner.class)
                .setSelection(position);
    }

    private void radio(int id) {
        find(ShadowDialog.getLatestDialog().getWindow().getDecorView(), RadioGroup.class).check(id);
    }

    /** Taps the dialog's nth value chip. */
    private void chip(int index) {
        List<Button> chips = new ArrayList<>();
        collectChips(ShadowDialog.getLatestDialog().getWindow().getDecorView(), chips);
        out.add("  chip " + chips.get(index).getText());
        chips.get(index).performClick();
    }

    private void collectChips(View view, List<Button> chips) {
        if (view instanceof Button && view.getParent() instanceof LinearLayout
                && ((View) view.getParent()).getParent()
                        instanceof android.widget.HorizontalScrollView) {
            chips.add((Button) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectChips(group.getChildAt(index), chips);
            }
        }
    }

    private void ok() {
        button(AlertDialog.BUTTON_POSITIVE);
        page();
    }

    private void remove() {
        button(AlertDialog.BUTTON_NEUTRAL);
        page();
    }

    private void cancel() {
        button(AlertDialog.BUTTON_NEGATIVE);
    }

    private void date(int year, int month, int day) {
        DatePickerDialog picker = (DatePickerDialog) ShadowDialog.getLatestDialog();
        picker.updateDate(year, month, day);
        picker.getButton(DatePickerDialog.BUTTON_POSITIVE).performClick();
        idle();
        page();
    }

    private void clearDate() {
        DatePickerDialog picker = (DatePickerDialog) ShadowDialog.getLatestDialog();
        picker.getButton(DatePickerDialog.BUTTON_NEUTRAL).performClick();
        idle();
        page();
    }

    private void button(int which) {
        AlertDialog shown = (AlertDialog) ShadowDialog.getLatestDialog();
        shown.getButton(which).performClick();
        idle();
    }

    private static void idle() {
        shadowOf(Looper.getMainLooper()).idle();
    }

    /** Records the page: section headers, rows and their glyphs, in order. */
    private void page() {
        StringBuilder line = new StringBuilder("  page");
        dump(container(), line);
        out.add(line.toString());
    }

    private void dump(View view, StringBuilder line) {
        if (view.getVisibility() != View.VISIBLE) {
            return;
        }
        if (view instanceof Spinner) {
            line.append(" s:").append(((Spinner) view).getSelectedItemPosition());
            return;
        }
        if (view instanceof RadioButton) {
            RadioButton radio = (RadioButton) view;
            line.append(" r:").append(radio.getText()).append(radio.isChecked() ? "*" : "");
            return;
        }
        if (view instanceof EditText) {
            EditText edit = (EditText) view;
            line.append(" e:[").append(escape(edit.getText().toString())).append("]/")
                    .append(edit.getHint());
            return;
        }
        if (view instanceof Button) {
            line.append(" b:").append(((Button) view).getText());
            return;
        }
        if (view instanceof TextView) {
            line.append(" t:[").append(escape(((TextView) view).getText().toString())).append("]");
            return;
        }
        if (view instanceof ImageView) {
            ImageView image = (ImageView) view;
            int id =
                    image.getDrawable() == null
                            ? 0
                            : shadowOf(image.getDrawable()).getCreatedFromResId();
            line.append(" i:")
                    .append(id > 0 ? activity.getResources().getResourceEntryName(id) : "?")
                    .append(image.hasOnClickListeners() ? "+" : "");
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                dump(group.getChildAt(index), line);
            }
        }
    }

    /** The tappable rows under the view (those holding a text), in order. */
    private static List<View> rows(View root) {
        List<View> rows = new ArrayList<>();
        collectRows(root, rows);
        return rows;
    }

    private static void collectRows(View view, List<View> rows) {
        if (view instanceof LinearLayout && view.hasOnClickListeners() && firstText(view) != null) {
            rows.add(view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectRows(group.getChildAt(index), rows);
            }
        }
    }

    /** The first row whose glyph says it still awaits review. */
    private View firstDiverged(View root) {
        for (View row : rows(root)) {
            StringBuilder line = new StringBuilder();
            dump(row, line);
            if (line.toString().contains("i:ic_diverged")) {
                return row;
            }
        }
        throw new AssertionError("no diverged row");
    }

    private static String firstText(View view) {
        if (view instanceof TextView && !(view instanceof EditText)) {
            return ((TextView) view).getText().toString();
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                String text = firstText(group.getChildAt(index));
                if (text != null) {
                    return text;
                }
            }
        }
        return null;
    }

    private static List<EditText> edits(View root) {
        List<EditText> edits = new ArrayList<>();
        collectEdits(root, edits);
        return edits;
    }

    private static void collectEdits(View view, List<EditText> edits) {
        if (view instanceof EditText) {
            edits.add((EditText) view);
        } else if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                collectEdits(group.getChildAt(index), edits);
            }
        }
    }

    private static <T extends View> T find(View view, Class<T> type) {
        if (type.isInstance(view)) {
            return type.cast(view);
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                T found = find(group.getChildAt(index), type);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\r", "\\r").replace("\n", "\\n");
    }
}
