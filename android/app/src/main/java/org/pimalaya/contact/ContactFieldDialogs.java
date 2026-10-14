package org.pimalaya.contact;

import android.app.AlertDialog;
import android.app.DatePickerDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import java.util.Arrays;
import java.util.Calendar;
import java.util.List;
import java.util.Objects;
import org.pimalaya.R;
import org.pimalaya.client.ContactAddress;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.ContactEmail;
import org.pimalaya.client.ContactGender;
import org.pimalaya.client.ContactName;
import org.pimalaya.client.ContactOrganization;
import org.pimalaya.client.ContactPhone;
import org.pimalaya.client.ContactRelation;
import org.pimalaya.client.FormDate;
import org.pimalaya.client.FormGender;

/**
 * The contact form's per-field edit dialogs, plus their shared scaffolding.
 *
 * <p>One label-free mini-form per field kind. Each edits the working model in
 * place then re-renders through its {@link ContactForm}, which owns the model,
 * view support and conflict alternatives, and re-gates its save.
 */
final class ContactFieldDialogs {
    private final ContactForm host;

    ContactFieldDialogs(ContactForm host) {
        this.host = host;
    }

    /** The display name's own row; view-composed (the bridge mints no FN). */
    void displayNameDialog() {
        LinearLayout content = dialogContent();
        EditText display =
                dialogField(content, "displayName", R.string.hint_display_name,
                        Objects.toString(host.model.displayName, ""), ContactForm.TEXT_NAME);

        showDialog(
                host.getS(R.string.hint_display_name),
                content,
                () -> {
                    host.model.displayName = host.text(display);
                    host.revealed.add("displayName");
                },
                () -> {
                    host.model.displayName = null;
                    host.revealed.remove("displayName");
                });
    }

    /** Edits one name component in place, the model's others untouched. */
    void namePartDialog(String part, int hint) {
        LinearLayout content = dialogContent();
        EditText field =
                dialogField(content, "name." + part, hint, host.namePart(part),
                        ContactForm.TEXT_NAME);

        showDialog(
                host.getS(hint),
                content,
                () -> {
                    ContactName name = host.model.name;
                    if (name == null) {
                        name = new ContactName("", "", "", "", "");
                    }
                    host.model.name = ContactForm.withNamePart(name, part, host.text(field));
                    host.revealed.add("name." + part);
                },
                () -> {
                    ContactName name = host.model.name;
                    if (name != null) {
                        host.model.name = ContactForm.withNamePart(name, part, "");
                    }
                    host.revealed.remove("name." + part);
                });
    }

    /** Edits one organization component in place, the other untouched. */
    void organizationPartDialog(String part, int hint) {
        LinearLayout content = dialogContent();
        EditText field =
                dialogField(content, "organization." + part, hint,
                        host.organizationPart(part), ContactForm.TEXT_NAME);

        showDialog(
                host.getS(hint),
                content,
                () -> {
                    ContactOrganization organization = host.model.organization;
                    if (organization == null) {
                        organization = new ContactOrganization("", "");
                    }
                    host.model.organization =
                            ContactForm.withOrganizationPart(organization, part, host.text(field));
                    host.revealed.add("organization." + part);
                },
                () -> {
                    ContactOrganization organization = host.model.organization;
                    if (organization != null) {
                        host.model.organization =
                                ContactForm.withOrganizationPart(organization, part, "");
                    }
                    host.revealed.remove("organization." + part);
                });
    }

    /** Edits a top-level scalar field (title, role) in place. */
    void simpleFieldDialog(String key, int hint) {
        LinearLayout content = dialogContent();
        EditText field =
                dialogField(
                        content,
                        key,
                        hint,
                        Objects.toString(
                                key.equals("title") ? host.model.title : host.model.role, ""),
                        ContactForm.TEXT_NAME);

        showDialog(
                host.getS(hint),
                content,
                () -> {
                    setSimpleField(key, host.text(field));
                    host.revealed.add(key);
                },
                () -> {
                    setSimpleField(key, null);
                    host.revealed.remove(key);
                });
    }

    /** Sets a top-level scalar field (title, role), null for absent. */
    private void setSimpleField(String key, String value) {
        if (key.equals("title")) {
            host.model.title = value;
        } else {
            host.model.role = value;
        }
    }

    void phoneDialog(int index) {
        List<ContactPhone> phones = host.model.phones;
        ContactPhone entry = index >= 0 ? phones.get(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.phone_types);
        type.setSelection(entry == null ? 0 : ContactForm.typeAt(host.view.phones(), index));
        EditText number =
                typedValueLine(content, type, R.string.hint_number,
                        entry == null ? "" : entry.number(),
                        InputType.TYPE_CLASS_PHONE);

        showDialog(
                host.getS(R.string.item_phone),
                content,
                () ->
                        ContactForm.putEntry(
                                phones,
                                index,
                                host.text(number),
                                ContactClient.formPhone(
                                        type.getSelectedItemPosition(),
                                        host.text(number),
                                        entry != null && entry.pref())),
                index >= 0 ? () -> phones.remove(index) : null);
    }

    void emailDialog(int index) {
        List<ContactEmail> emails = host.model.emails;
        ContactEmail entry = index >= 0 ? emails.get(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.email_types);
        type.setSelection(entry == null ? 0 : ContactForm.typeAt(host.view.emails(), index));
        EditText address =
                typedValueLine(content, type, R.string.hint_email_address,
                        entry == null ? "" : entry.address(),
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);

        showDialog(
                host.getS(R.string.item_email),
                content,
                () ->
                        ContactForm.putEntry(
                                emails,
                                index,
                                host.text(address),
                                ContactClient.formEmail(
                                        type.getSelectedItemPosition(),
                                        host.text(address),
                                        entry != null && entry.pref())),
                index >= 0 ? () -> emails.remove(index) : null);
    }

    void addressDialog(int index) {
        List<ContactAddress> addresses = host.model.addresses;
        ContactAddress entry = index >= 0 ? addresses.get(index) : null;
        ContactAddress safe =
                entry == null
                        ? new ContactAddress("", "", "", "", "", false, "", "", List.of())
                        : entry;

        LinearLayout content = dialogContent();
        RadioGroup type =
                typeRadios(
                        content,
                        R.array.address_types,
                        entry == null ? 0 : host.addressAt(index).index());
        EditText street =
                dialogField(content, null, R.string.hint_street, safe.street(),
                        InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        EditText city =
                dialogField(content, null, R.string.hint_city, safe.city(),
                        ContactForm.TEXT_NAME);
        EditText region =
                dialogField(content, null, R.string.hint_region, safe.region(),
                        ContactForm.TEXT_NAME);
        EditText postcode =
                dialogField(content, null, R.string.hint_postcode, safe.postcode(),
                        InputType.TYPE_CLASS_TEXT);
        EditText country =
                dialogField(content, null, R.string.hint_country, safe.country(),
                        ContactForm.TEXT_NAME);

        showDialog(
                host.getS(R.string.item_address),
                content,
                () -> {
                    String filled =
                            (host.text(street) + host.text(city) + host.text(region)
                                    + host.text(postcode) + host.text(country)).trim();
                    ContactAddress fresh =
                            new ContactAddress(
                                    host.text(city),
                                    host.text(country),
                                    // NOTE: fields the dialog omits ride
                                    // along untouched.
                                    safe.ext(),
                                    safe.pobox(),
                                    host.text(postcode),
                                    safe.pref(),
                                    host.text(region),
                                    host.text(street),
                                    ContactClient.formAddressTypes(
                                            type.getCheckedRadioButtonId() - 1));
                    ContactForm.putEntry(addresses, index, filled, fresh);
                },
                index >= 0 ? () -> addresses.remove(index) : null);
    }

    /**
     * One plain string list: model list, title, hint (0 = none), input type.
     */
    record StringList(String list, int title, int hint, int inputType) {}

    /** A one-field dialog for the plain string lists (hint 0 = none). */
    void stringDialog(StringList kind, int index) {
        String list = kind.list();
        int title = kind.title();
        int hint = kind.hint();
        int inputType = kind.inputType();

        List<String> values = host.strings(list);

        LinearLayout content = dialogContent();
        EditText input =
                dialogField(content, null, hint,
                        index >= 0 ? values.get(index) : "", inputType);

        showDialog(
                host.getS(title),
                content,
                () -> {
                    String fresh = host.text(input);
                    if (fresh.isEmpty()) {
                        if (index >= 0) {
                            values.remove(index);
                        }
                    } else if (index >= 0) {
                        values.set(index, fresh);
                    } else {
                        values.add(fresh);
                    }
                },
                index >= 0 ? () -> values.remove(index) : null);
    }

    void noteDialog(int index) {
        List<String> notes = host.model.notes;

        LinearLayout content = dialogContent();
        EditText input =
                dialogField(content, null, 0,
                        index >= 0 ? notes.get(index) : "",
                        InputType.TYPE_CLASS_TEXT
                                | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);

        showDialog(
                host.getS(R.string.item_note),
                content,
                () -> {
                    String fresh = host.text(input);
                    if (fresh.isEmpty()) {
                        if (index >= 0) {
                            notes.remove(index);
                        }
                    } else if (index >= 0) {
                        notes.set(index, fresh);
                    } else {
                        notes.add(fresh);
                    }
                },
                index >= 0 ? () -> notes.remove(index) : null);
    }

    void relationDialog(int index) {
        List<ContactRelation> relations = host.model.relations;
        ContactRelation entry = index >= 0 ? relations.get(index) : null;

        LinearLayout content = dialogContent();
        Spinner type = typeSpinner(R.array.relation_types);
        type.setSelection(entry == null ? 0 : ContactForm.typeAt(host.view.relations(), index));
        EditText value =
                typedValueLine(content, type, R.string.hint_relation,
                        entry == null ? "" : entry.value(), ContactForm.TEXT_NAME);

        showDialog(
                host.getS(R.string.item_relation),
                content,
                () ->
                        ContactForm.putEntry(
                                relations,
                                index,
                                host.text(value),
                                ContactClient.formRelation(
                                        type.getSelectedItemPosition(),
                                        host.text(value),
                                        entry != null && entry.pref())),
                index >= 0 ? () -> relations.remove(index) : null);
    }

    /**
     * The GENDER field: free text with the standard genders as choices.
     *
     * <p>A listed value stores its RFC 6350 sex code, anything else the free
     * identity. The dropdown opens when adding, stays closed when editing.
     */
    void genderDialog() {
        String[] labels = host.activity.getResources().getStringArray(R.array.gender_types);
        // NOTE: suggestions drop the leading "Not set" placeholder.
        String[] choices = Arrays.copyOfRange(labels, 1, labels.length);
        boolean adding = !host.filled("gender");

        LinearLayout content = dialogContent();
        AutoCompleteTextView field =
                autoCompleteField(content, R.string.item_gender, genderPrefill(labels), choices,
                        adding);

        showDialog(
                host.getS(R.string.item_gender),
                content,
                () -> {
                    String value = host.text(field);
                    if (value.isEmpty()) {
                        host.model.gender = null;
                    } else {
                        int position = indexOfIgnoreCase(labels, value);
                        host.model.gender =
                                position > 0
                                        ? ContactClient.formGender(position, "")
                                        : new ContactGender(value, "");
                        host.revealed.add("gender");
                    }
                },
                () -> {
                    host.model.gender = null;
                    host.revealed.remove("gender");
                });
    }

    /** The gender's display value: free identity, else its sex code's label. */
    private String genderPrefill(String[] labels) {
        FormGender genderView = host.view.gender();
        if (genderView == null) {
            return "";
        }
        String identity = genderView.identity();
        if (!identity.isEmpty()) {
            return identity;
        }
        int sexIndex = genderView.sexIndex();
        return sexIndex > 0 && sexIndex < labels.length ? labels[sexIndex] : "";
    }

    private static int indexOfIgnoreCase(String[] labels, String value) {
        for (int index = 0; index < labels.length; index++) {
            if (labels[index].equalsIgnoreCase(value)) {
                return index;
            }
        }
        return -1;
    }

    /** A date field goes straight to the system date picker. */
    void pickDate(String field) {
        Calendar calendar = Calendar.getInstance();
        FormDate stored = field.equals("birthday") ? host.view.birthday() : host.view.anniversary();
        if (stored != null) {
            // NOTE: today stands in when the stored value is not a date.
            calendar.set(
                    stored.year(),
                    stored.month() - 1,
                    stored.day());
        }

        DatePickerDialog dialog =
                new DatePickerDialog(
                        host.activity,
                        (picker, year, month, day) -> {
                            setDate(field, ContactClient.formDate(year, month + 1, day));
                            host.revealed.add(field);
                            host.markResolvedAndRender();
                        },
                        calendar.get(Calendar.YEAR),
                        calendar.get(Calendar.MONTH),
                        calendar.get(Calendar.DAY_OF_MONTH));
        dialog.setButton(
                DatePickerDialog.BUTTON_NEUTRAL,
                host.activity.getString(R.string.clear),
                (d, which) -> {
                    setDate(field, null);
                    host.revealed.remove(field);
                    host.markResolvedAndRender();
                });
        dialog.show();
    }

    /** Sets a date field (birthday, anniversary), null for absent. */
    private void setDate(String field, String value) {
        if (field.equals("birthday")) {
            host.model.birthday = value;
        } else {
            host.model.anniversary = value;
        }
    }

    /** The vertical field container of an edit dialog. */
    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(host.activity);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(host.ui.dp(24), host.ui.dp(8), host.ui.dp(24), 0);
        return content;
    }

    /** Shows an edit dialog: OK writes back and re-renders, Remove drops. */
    private void showDialog(
            CharSequence title, LinearLayout content, Runnable onOk, Runnable onRemove) {
        ScrollView wrap = new ScrollView(host.activity);
        wrap.addView(content);

        AlertDialog.Builder builder =
                new AlertDialog.Builder(host.activity)
                        .setTitle(title)
                        .setView(wrap)
                        .setPositiveButton(
                                android.R.string.ok,
                                (dialog, which) -> {
                                    onOk.run();
                                    host.markResolvedAndRender();
                                })
                        .setNegativeButton(android.R.string.cancel, null);
        if (onRemove != null) {
            builder.setNeutralButton(
                    R.string.remove_row,
                    (dialog, which) -> {
                        onRemove.run();
                        host.markResolvedAndRender();
                    });
        }

        AlertDialog dialog = builder.create();

        // NOTE: force the keyboard only for a plain field; an autocomplete
        // keeps it closed so its dropdown lands in that space, not behind it.
        EditText first = firstInput(content);
        if (first != null) {
            first.requestFocus();
            first.setSelection(first.getText().length());
            if (!(first instanceof AutoCompleteTextView)) {
                dialog.getWindow()
                        .setSoftInputMode(
                                WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_VISIBLE);
            }
        }

        dialog.show();
    }

    /** The first EditText under the view, depth first; null when none. */
    private static EditText firstInput(View view) {
        if (view instanceof EditText) {
            return (EditText) view;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int index = 0; index < group.getChildCount(); index++) {
                EditText input = firstInput(group.getChildAt(index));
                if (input != null) {
                    return input;
                }
            }
        }

        return null;
    }

    /**
     * A label-free dialog field, its name as the placeholder hint.
     *
     * <p>A conflicted path renders one chip per candidate value below it.
     */
    private EditText dialogField(
            LinearLayout content, String path, int label, String prefill, int inputType) {
        EditText input = new EditText(host.activity);
        input.setBackgroundTintList(ColorStateList.valueOf(host.accentColor));
        input.setInputType(inputType);
        if (label != 0) {
            input.setHint(label);
        }
        input.setText(prefill);
        content.addView(input);

        List<String> values = path == null || host.alternatives == null
                ? null
                : host.alternatives.get(path);
        if (values != null && values.size() > 0) {
            LinearLayout chips = new LinearLayout(host.activity);
            chips.setOrientation(LinearLayout.HORIZONTAL);
            for (int index = 0; index < values.size(); index++) {
                String candidate = values.get(index);
                Button chip = new Button(host.activity, null, android.R.attr.buttonStyleSmall);
                // NOTE: the empty alternative is pickable too; it reads
                // "Not set" and clears the field.
                chip.setText(
                        candidate.isEmpty() ? host.getS(R.string.value_not_set) : candidate);
                chip.setAllCaps(false);
                chip.setOnClickListener(view -> input.setText(candidate));
                chips.addView(chip);
            }

            HorizontalScrollView chipScroll = new HorizontalScrollView(host.activity);
            chipScroll.setHorizontalScrollBarEnabled(false);
            chipScroll.addView(chips);
            content.addView(chipScroll);
        }

        return input;
    }

    /**
     * A free-text field with suggested choices in a dropdown.
     *
     * <p>When {@code open} the dropdown pops with the dialog (a fresh field),
     * else it waits for a tap.
     */
    private AutoCompleteTextView autoCompleteField(
            LinearLayout content, int hint, String prefill, String[] choices, boolean open) {
        AutoCompleteTextView field = new AutoCompleteTextView(host.activity);
        field.setBackgroundTintList(ColorStateList.valueOf(host.accentColor));
        field.setHint(hint);
        field.setText(prefill);
        field.setThreshold(1);
        field.setAdapter(new ArrayAdapter<>(host.activity, R.layout.dropdown_item, choices));
        field.setOnClickListener(view -> field.showDropDown());
        if (open) {
            field.setOnFocusChangeListener(
                    (view, focused) -> {
                        if (focused) {
                            field.post(field::showDropDown);
                        }
                    });
        }
        content.addView(field);
        return field;
    }

    /** The type spinner of an entry dialog (self-describing, no label). */
    private Spinner typeSpinner(int entries) {
        Spinner spinner = new Spinner(host.activity);
        ArrayAdapter<CharSequence> adapter =
                ArrayAdapter.createFromResource(host.activity, entries, R.layout.spinner_form_item);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        // NOTE: zero out the stock spinner's own padding, which otherwise
        // breaks the shared text start.
        spinner.setPadding(0, 0, 0, 0);
        return spinner;
    }

    /** A typed value line: content-sized type spinner, then the value field. */
    private EditText typedValueLine(
            LinearLayout content, Spinner type, int hint, String prefill, int inputType) {
        LinearLayout line = new LinearLayout(host.activity);
        line.setOrientation(LinearLayout.HORIZONTAL);
        line.setBaselineAligned(false);
        line.setGravity(Gravity.CENTER_VERTICAL);
        line.addView(
                type,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText input = dialogField(line, null, hint, prefill, inputType);
        LinearLayout.LayoutParams params =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        params.setMarginStart(host.ui.dp(12));
        input.setLayoutParams(params);

        content.addView(line);
        return input;
    }

    /** The entry types as one line of radios (position = table index). */
    private RadioGroup typeRadios(LinearLayout content, int entries, int selected) {
        RadioGroup group = new RadioGroup(host.activity);
        group.setOrientation(LinearLayout.HORIZONTAL);

        String[] labels = host.activity.getResources().getStringArray(entries);
        for (int index = 0; index < labels.length; index++) {
            RadioButton option = new RadioButton(host.activity);
            option.setText(labels[index]);
            option.setId(index + 1);
            option.setPadding(0, 0, host.ui.dp(16), 0);
            group.addView(option);
        }
        group.check(selected + 1);

        content.addView(group);
        return group;
    }
}
