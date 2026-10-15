package org.pimalaya.contact;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.pimalaya.R;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.ContactEmail;
import org.pimalaya.client.ContactFields;
import org.pimalaya.client.ContactName;
import org.pimalaya.client.ContactOrganization;
import org.pimalaya.client.ContactPhone;
import org.pimalaya.client.ContactRelation;
import org.pimalaya.client.FormAddress;
import org.pimalaya.client.FormGender;
import org.pimalaya.client.FormView;
import org.pimalaya.client.TypeKind;
import org.pimalaya.ui.Sections;
import org.pimalaya.ui.Ui;

/**
 * The contact edit form: themed sections whose items open dialog mini-forms.
 *
 * <p>Dialogs edit a working field model that {@link #collect()} hands back;
 * the Rust bridge patches the vCard and computes the view support, this class
 * only localizes labels. Conflict mode glyphs diverged rows until reviewed.
 */
public final class ContactForm {
    static final int TEXT_NAME =
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_WORDS;

    private static final int URI_INPUT =
            InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI;

    /** String lists, as {@link ContactFieldDialogs#stringDialog} edits them. */
    private static final ContactFieldDialogs.StringList NICKNAMES =
            new ContactFieldDialogs.StringList("nicknames", R.string.item_nickname, 0, TEXT_NAME);

    private static final ContactFieldDialogs.StringList IMPPS =
            new ContactFieldDialogs.StringList(
                    "impps", R.string.item_impp, R.string.hint_impp, URI_INPUT);

    private static final ContactFieldDialogs.StringList WEBSITES =
            new ContactFieldDialogs.StringList(
                    "websites", R.string.item_website, R.string.hint_url, URI_INPUT);

    private static final ContactFieldDialogs.StringList LANGUAGES =
            new ContactFieldDialogs.StringList(
                    "languages",
                    R.string.item_language,
                    R.string.hint_language,
                    InputType.TYPE_CLASS_TEXT);

    /**
     * One addable field of the catalog: key, section, label, icon and dialog.
     *
     * <p>A list is always offerable (one entry per pick), a single field only
     * while absent.
     */
    private final class Field {
        final String key;
        final int category;
        final int label;
        final int icon;
        final boolean list;
        final Runnable open;

        Field(String key, int category, int label, int icon, boolean list, Runnable open) {
            this.key = key;
            this.category = category;
            this.label = label;
            this.icon = icon;
            this.list = list;
            this.open = open;
        }
    }

    final Activity activity;
    final int accentColor;
    private final ScrollView scroll;
    private final LinearLayout container;

    /** The page this form is drawn on, shared with the reader screens. */
    private final Sections sections;

    /** The working field model the dialogs edit in place. */
    ContactFields.Builder model = new ContactFields.Builder();

    /** The bridge-computed view support of the current model. */
    FormView view =
            new FormView(List.of(), null, null, List.of(), null, "", "", List.of(), List.of());

    /** Conflict alternatives by single-field path; null outside conflicts. */
    Map<String, List<String>> alternatives;

    /** Changed list sections of a conflict; null outside conflict mode. */
    private Set<String> changedLists;

    /** Field kinds revealed this session, so their empty section shows. */
    final Set<String> revealed = new HashSet<>();

    /** Conflict rows reviewed (opened, then OK), so their warning clears. */
    private final Set<String> resolved = new HashSet<>();

    /** Conflict rows still awaiting review, rebuilt on every render. */
    private final Set<String> pending = new HashSet<>();

    /**
     * Items each changed list held at load time.
     *
     * <p>Rows past that count were added this session and never wear the glyph.
     */
    private final Map<String, Integer> loadedCounts = new HashMap<>();

    /** The row a just-opened dialog resolves on OK; null when none. */
    private String pendingResolveKey;

    /** Notified after every render so the host can re-gate its save. */
    private Runnable onRender;

    final Ui ui;

    /** The per-property edit dialogs the tappable rows open. */
    private final ContactFieldDialogs dialogs;

    /** An empty form drawing into the activity. */
    public ContactForm(Activity activity) {
        this.activity = activity;
        this.ui = new Ui(activity);
        this.dialogs = new ContactFieldDialogs(this);
        this.accentColor = ui.resolveColor(android.R.attr.colorAccent);
        this.scroll = activity.findViewById(R.id.contact_scroll);
        this.container = activity.findViewById(R.id.contact_form);
        this.sections = new Sections(activity, container);
    }

    /**
     * Loads the field model (null for a new contact) and renders the page.
     *
     * <p>A non-null {@code changed} turns on conflict mode: the diverged glyph
     * on disagreeing rows, {@code alternatives} feeding the value chips.
     */
    public void load(
            ContactFields model, Map<String, List<String>> alternatives, List<String> changed) {
        this.model = model != null ? new ContactFields.Builder(model) : new ContactFields.Builder();
        this.alternatives = alternatives;
        this.changedLists = null;
        this.revealed.clear();
        this.resolved.clear();
        this.loadedCounts.clear();
        this.pendingResolveKey = null;
        if (changed != null) {
            changedLists = new HashSet<>();
            for (String section : changed) {
                changedLists.add(section);
                loadedCounts.put(section, list(section).size());
            }
        } else if (model == null) {
            // NOTE: a new contact opens on the common fields, not a blank
            // page: given/family name plus an empty phone and email row.
            revealed.add("name.given");
            revealed.add("name.family");
            revealed.add("phones");
            revealed.add("emails");
        }

        render();
        scroll.scrollTo(0, 0);
    }

    /** The edited field model. */
    ContactFields collect() {
        return model.build();
    }

    /** Sets the post-render hook (the host's save-gate refresh). */
    public void setOnRender(Runnable onRender) {
        this.onRender = onRender;
    }

    /** What a render adds after the card's own sections (the Linked card). */
    private java.util.function.Consumer<Sections> trailing;

    /** Sets what a render adds after the card's own sections. */
    public void setTrailing(java.util.function.Consumer<Sections> trailing) {
        this.trailing = trailing;
    }

    /** Draws the page again from the working model, after an outside change. */
    public void refresh() {
        render();
    }

    /**
     * Whether the form may save: always, in a merge once every row is reviewed.
     */
    public boolean conflictResolved() {
        return !conflict() || pending.isEmpty();
    }

    /** The birthday as picked, empty when unset. */
    String birthday() {
        return Objects.toString(model.birthday, "").trim();
    }

    /** The anniversary as picked, empty when unset. */
    String anniversary() {
        return Objects.toString(model.anniversary, "").trim();
    }

    private boolean conflict() {
        return changedLists != null;
    }

    /**
     * Whether a section shows: it carries a value or was added this session.
     *
     * <p>Each render site's {@code || conflicted / changed} branch adds rows
     * that diverge while empty on the merged side.
     */
    private boolean shown(String key, boolean filled) {
        return filled || revealed.contains(key);
    }

    /** Whether the field or list section currently carries a value. */
    boolean filled(String key) {
        switch (key) {
            case "displayName":
                return !Objects.toString(model.displayName, "").trim().isEmpty();
            case "name.prefix":
            case "name.given":
            case "name.middle":
            case "name.family":
            case "name.suffix":
                return !namePart(key.substring("name.".length())).isEmpty();
            case "birthday":
                return !Objects.toString(model.birthday, "").trim().isEmpty();
            case "anniversary":
                return !Objects.toString(model.anniversary, "").trim().isEmpty();
            case "gender":
                return model.gender != null;
            case "organization.company":
            case "organization.department":
                return !organizationPart(key.substring("organization.".length())).isEmpty();
            case "title":
                return !Objects.toString(model.title, "").trim().isEmpty();
            case "role":
                return !Objects.toString(model.role, "").trim().isEmpty();
            default:
                return !list(key).isEmpty();
        }
    }

    /** A single component of the structured name, empty when unset. */
    String namePart(String part) {
        ContactName name = model.name;
        return name == null ? "" : namePart(name, part).trim();
    }

    /** One component of a structured name by its model key. */
    static String namePart(ContactName name, String part) {
        switch (part) {
            case "prefix":
                return name.prefix();
            case "given":
                return name.given();
            case "middle":
                return name.middle();
            case "family":
                return name.family();
            case "suffix":
                return name.suffix();
            default:
                return "";
        }
    }

    /** The structured name with one component, by its model key, replaced. */
    static ContactName withNamePart(ContactName name, String part, String value) {
        return new ContactName(
                part.equals("family") ? value : name.family(),
                part.equals("given") ? value : name.given(),
                part.equals("middle") ? value : name.middle(),
                part.equals("prefix") ? value : name.prefix(),
                part.equals("suffix") ? value : name.suffix());
    }

    /** Appends a name component row: valued, revealed, or diverging. */
    private void namePartRow(List<View> identity, String part, int hint) {
        String key = "name." + part;
        if (shown(key, filled(key)) || unreviewed(key)) {
            identity.add(
                    entryItem(
                            getS(hint),
                            value(namePart(part)),
                            divergedField(key),
                            () -> dialogs.namePartDialog(part, hint)));
        }
    }

    /** The full field catalog: category, label, icon and edit dialog each. */
    private List<Field> catalog() {
        int person = R.drawable.ic_section_person;
        int identity = R.string.section_identity;
        List<Field> fields = new ArrayList<>();

        fields.add(new Field("displayName", identity, R.string.hint_display_name,
                R.drawable.ic_display_name, false, dialogs::displayNameDialog));
        fields.add(new Field("name.prefix", identity, R.string.hint_prefix,
                R.drawable.ic_prefix, false,
                () -> dialogs.namePartDialog("prefix", R.string.hint_prefix)));
        fields.add(new Field("name.given", identity, R.string.hint_given, person, false,
                () -> dialogs.namePartDialog("given", R.string.hint_given)));
        fields.add(new Field("name.middle", identity, R.string.hint_middle, person, false,
                () -> dialogs.namePartDialog("middle", R.string.hint_middle)));
        fields.add(new Field("name.family", identity, R.string.hint_family, person, false,
                () -> dialogs.namePartDialog("family", R.string.hint_family)));
        fields.add(new Field("name.suffix", identity, R.string.hint_suffix,
                R.drawable.ic_suffix, false,
                () -> dialogs.namePartDialog("suffix", R.string.hint_suffix)));
        fields.add(new Field("gender", identity, R.string.item_gender,
                R.drawable.ic_gender, false, dialogs::genderDialog));
        fields.add(new Field("birthday", identity, R.string.hint_birthday,
                R.drawable.ic_birthday, false, () -> dialogs.pickDate("birthday")));
        fields.add(new Field("anniversary", identity, R.string.item_anniversary,
                R.drawable.ic_anniversary, false, () -> dialogs.pickDate("anniversary")));

        int work = R.string.tab_work;
        fields.add(new Field("organization.company", work, R.string.hint_company,
                R.drawable.ic_company, false,
                () -> dialogs.organizationPartDialog("company", R.string.hint_company)));
        fields.add(new Field("organization.department", work, R.string.hint_department,
                R.drawable.ic_section_work, false,
                () -> dialogs.organizationPartDialog("department", R.string.hint_department)));
        fields.add(new Field("title", work, R.string.hint_job_title,
                R.drawable.ic_title, false,
                () -> dialogs.simpleFieldDialog("title", R.string.hint_job_title)));
        fields.add(new Field("role", work, R.string.hint_role,
                R.drawable.ic_role, false,
                () -> dialogs.simpleFieldDialog("role", R.string.hint_role)));

        fields.add(new Field("nicknames", R.string.section_nicknames, R.string.item_nickname,
                R.drawable.ic_section_label, true,
                () -> dialogs.stringDialog(NICKNAMES, -1)));
        fields.add(new Field("relations", R.string.section_relations, R.string.item_relation,
                R.drawable.ic_section_group, true, () -> dialogs.relationDialog(-1)));
        fields.add(new Field("phones", R.string.section_phones, R.string.item_phone,
                R.drawable.ic_section_call, true, () -> dialogs.phoneDialog(-1)));
        fields.add(new Field("emails", R.string.section_emails, R.string.item_email,
                R.drawable.ic_section_mail, true, () -> dialogs.emailDialog(-1)));
        fields.add(new Field("impps", R.string.section_impps, R.string.item_impp,
                R.drawable.ic_section_chat, true,
                () -> dialogs.stringDialog(IMPPS, -1)));
        fields.add(new Field("addresses", R.string.section_addresses, R.string.item_address,
                R.drawable.ic_section_location_on, true, () -> dialogs.addressDialog(-1)));
        fields.add(new Field("websites", R.string.section_websites, R.string.item_website,
                R.drawable.ic_section_language, true,
                () -> dialogs.stringDialog(WEBSITES, -1)));
        fields.add(new Field("languages", R.string.section_languages, R.string.item_language,
                R.drawable.ic_section_translate, true,
                () -> dialogs.stringDialog(LANGUAGES, -1)));
        fields.add(new Field("notes", R.string.section_notes, R.string.item_note,
                R.drawable.ic_section_notes, true, () -> dialogs.noteDialog(-1)));

        return fields;
    }

    /**
     * Whether a field can still be added: a list always, a single if absent.
     */
    private boolean offerable(Field field) {
        return field.list || !shown(field.key, filled(field.key));
    }

    /** The main add action (FAB): every field that can still be added. */
    public void addField() {
        List<Field> offer = new ArrayList<>();
        for (Field field : catalog()) {
            if (offerable(field)) {
                offer.add(field);
            }
        }
        showChooser(offer);
    }

    /** A section's add action: the chooser filtered to that section. */
    private void addToSection(int category) {
        List<Field> offer = new ArrayList<>();
        for (Field field : catalog()) {
            if (field.category == category && offerable(field)) {
                offer.add(field);
            }
        }
        showChooser(offer);
    }

    /**
     * Presents the field chooser; a picked field opens its edit dialog.
     *
     * <p>A lone option opens straight away; an empty set says everything is
     * already on the page.
     */
    private void showChooser(List<Field> fields) {
        if (fields.isEmpty()) {
            Toast.makeText(activity, R.string.add_field_none, Toast.LENGTH_SHORT).show();
            return;
        }
        if (fields.size() == 1) {
            fields.get(0).open.run();
            return;
        }

        // NOTE: locale-aware sort by localized label, so a field is found
        // by name rather than by the catalog's layout order.
        java.text.Collator collator = java.text.Collator.getInstance();
        fields.sort((left, right) -> collator.compare(getS(left.label), getS(right.label)));

        LinearLayout list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        // NOTE: bottom padding mirrors the framework title's top padding
        // so the dialog is symmetric and the last row clears the corner.
        list.setPadding(0, ui.dp(8), 0, ui.dp(18));
        ScrollView scroller = new ScrollView(activity);
        scroller.addView(list);

        AlertDialog dialog =
                new AlertDialog.Builder(activity)
                        .setTitle(R.string.contact_add_field)
                        .setView(scroller)
                        .create();

        for (Field field : fields) {
            list.addView(chooserRow(field, dialog));
        }
        dialog.show();
    }

    /** One chooser row; picking it closes the chooser and opens the dialog. */
    private View chooserRow(Field field, AlertDialog dialog) {
        ImageView iconView = new ImageView(activity);
        iconView.setImageResource(field.icon);
        iconView.setImageTintList(
                ColorStateList.valueOf(ui.resolveColor(android.R.attr.textColorSecondary)));

        TextView labelView = new TextView(activity);
        labelView.setText(field.label);
        labelView.setTextColor(ui.resolveColor(android.R.attr.textColorPrimary));
        labelView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        LinearLayout.LayoutParams labelParams =
                new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        labelParams.setMarginStart(ui.dp(12));

        LinearLayout row = new LinearLayout(activity);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(ui.dp(24), ui.dp(14), ui.dp(24), ui.dp(14));
        row.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackground));
        row.addView(iconView, new LinearLayout.LayoutParams(ui.dp(22), ui.dp(22)));
        row.addView(labelView, labelParams);
        row.setOnClickListener(
                view -> {
                    dialog.dismiss();
                    field.open.run();
                });
        return row;
    }

    /** Rebuilds the whole page from the working model. */
    private void render() {
        view = ContactClient.formView(model.build());
        container.removeAllViews();
        pending.clear();

        // NOTE: identity holds the solo fields (display name, name parts,
        // dates, gender); every multi-valued list is its own section below.
        List<View> identity = new ArrayList<>();
        if (shown("displayName", filled("displayName")) || unreviewed("displayName")) {
            identity.add(
                    entryItem(
                            getS(R.string.hint_display_name),
                            value(Objects.toString(model.displayName, "")),
                            divergedField("displayName"),
                            dialogs::displayNameDialog));
        }
        namePartRow(identity, "prefix", R.string.hint_prefix);
        namePartRow(identity, "given", R.string.hint_given);
        namePartRow(identity, "middle", R.string.hint_middle);
        namePartRow(identity, "family", R.string.hint_family);
        namePartRow(identity, "suffix", R.string.hint_suffix);
        if (shown("birthday", filled("birthday")) || unreviewed("birthday")) {
            identity.add(
                    entryItem(
                            getS(R.string.hint_birthday),
                            value(Objects.toString(model.birthday, "")),
                            divergedField("birthday"),
                            () -> dialogs.pickDate("birthday")));
        }
        if (shown("anniversary", filled("anniversary")) || unreviewed("anniversary")) {
            identity.add(
                    entryItem(
                            getS(R.string.item_anniversary),
                            value(Objects.toString(model.anniversary, "")),
                            divergedField("anniversary"),
                            () -> dialogs.pickDate("anniversary")));
        }
        if (shown("gender", filled("gender")) || unreviewed("gender", "gender.sex", "gender.identity")) {
            identity.add(
                    entryItem(
                            getS(R.string.item_gender),
                            genderSummary(),
                            divergedField("gender", "gender.sex", "gender.identity"),
                            dialogs::genderDialog));
        }
        section(R.string.section_identity, R.drawable.ic_section_person, identity);

        if (shown("nicknames", filled("nicknames")) || unreviewedList("nicknames")) {
            List<View> items = new ArrayList<>();
            List<String> nicknames = model.nicknames;
            for (int index = 0; index < nicknames.size(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_nickname),
                                nicknames.get(index),
                                divergedItem("nicknames", at),
                                () -> dialogs.stringDialog(NICKNAMES, at)));
            }
            section(R.string.section_nicknames, R.drawable.ic_section_label, items);
        }

        // NOTE: work fields are solo rows too, so each edits and (in
        // conflict mode) resolves on its own, like the identity section.
        List<View> work = new ArrayList<>();
        if (shown("organization.company", filled("organization.company"))
                || unreviewed("organization.company")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_company),
                            value(organizationPart("company")),
                            divergedField("organization.company"),
                            () -> dialogs.organizationPartDialog("company",
                                    R.string.hint_company)));
        }
        if (shown("organization.department", filled("organization.department"))
                || unreviewed("organization.department")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_department),
                            value(organizationPart("department")),
                            divergedField("organization.department"),
                            () -> dialogs.organizationPartDialog("department",
                                    R.string.hint_department)));
        }
        if (shown("title", filled("title")) || unreviewed("title")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_job_title),
                            value(Objects.toString(model.title, "")),
                            divergedField("title"),
                            () -> dialogs.simpleFieldDialog("title", R.string.hint_job_title)));
        }
        if (shown("role", filled("role")) || unreviewed("role")) {
            work.add(
                    entryItem(
                            getS(R.string.hint_role),
                            value(Objects.toString(model.role, "")),
                            divergedField("role"),
                            () -> dialogs.simpleFieldDialog("role", R.string.hint_role)));
        }
        section(R.string.tab_work, R.drawable.ic_section_work, work);

        if (shown("relations", filled("relations")) || unreviewedList("relations")) {
            List<View> items = new ArrayList<>();
            List<ContactRelation> relations = model.relations;
            for (int index = 0; index < relations.size(); index++) {
                int at = index;
                ContactRelation entry = relations.get(index);
                items.add(
                        entryItem(
                                typeLabel(TypeKind.RELATION, typeAt(view.relations(), index)),
                                entry.value(),
                                divergedItem("relations", at),
                                () -> dialogs.relationDialog(at)));
            }
            section(R.string.section_relations, R.drawable.ic_section_group, items);
        }

        if (shown("phones", filled("phones")) || unreviewedList("phones")) {
            List<View> items = new ArrayList<>();
            List<ContactPhone> phones = model.phones;
            for (int index = 0; index < phones.size(); index++) {
                int at = index;
                ContactPhone entry = phones.get(index);
                items.add(
                        entryItem(
                                typeLabel(TypeKind.PHONE, typeAt(view.phones(), index)),
                                entry.number(),
                                divergedItem("phones", at),
                                () -> dialogs.phoneDialog(at)));
            }
            // NOTE: keepEmpty renders the section as a bare header (with its
            // add control) for a fresh contact, no blank placeholder row.
            section(R.string.section_phones, R.drawable.ic_section_call, items, true);
        }

        if (shown("emails", filled("emails")) || unreviewedList("emails")) {
            List<View> items = new ArrayList<>();
            List<ContactEmail> emails = model.emails;
            for (int index = 0; index < emails.size(); index++) {
                int at = index;
                ContactEmail entry = emails.get(index);
                items.add(
                        entryItem(
                                typeLabel(TypeKind.EMAIL, typeAt(view.emails(), index)),
                                entry.address(),
                                divergedItem("emails", at),
                                () -> dialogs.emailDialog(at)));
            }
            section(R.string.section_emails, R.drawable.ic_section_mail, items, true);
        }

        if (shown("impps", filled("impps")) || unreviewedList("impps")) {
            List<View> items = new ArrayList<>();
            List<String> impps = model.impps;
            for (int index = 0; index < impps.size(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_impp),
                                impps.get(index),
                                divergedItem("impps", at),
                                () -> dialogs.stringDialog(IMPPS, at)));
            }
            section(R.string.section_impps, R.drawable.ic_section_chat, items);
        }

        if (shown("addresses", filled("addresses")) || unreviewedList("addresses")) {
            List<View> items = new ArrayList<>();
            List<?> entries = model.addresses;
            for (int index = 0; index < entries.size(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                typeLabel(
                                        TypeKind.ADDRESS,
                                        addressAt(index).index()),
                                value(addressAt(index).summary()),
                                divergedItem("addresses", at),
                                () -> dialogs.addressDialog(at)));
            }
            section(R.string.section_addresses, R.drawable.ic_section_location_on, items);
        }

        if (shown("websites", filled("websites")) || unreviewedList("websites")) {
            List<View> items = new ArrayList<>();
            List<String> websites = model.websites;
            for (int index = 0; index < websites.size(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_website),
                                websites.get(index),
                                divergedItem("websites", at),
                                () -> dialogs.stringDialog(WEBSITES, at)));
            }
            section(R.string.section_websites, R.drawable.ic_section_language, items);
        }

        if (shown("languages", filled("languages")) || unreviewedList("languages")) {
            List<View> items = new ArrayList<>();
            List<String> languages = model.languages;
            for (int index = 0; index < languages.size(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_language),
                                languages.get(index),
                                divergedItem("languages", at),
                                () -> dialogs.stringDialog(LANGUAGES, at)));
            }
            section(R.string.section_languages, R.drawable.ic_section_translate, items);
        }

        if (shown("notes", filled("notes")) || unreviewedList("notes")) {
            List<View> items = new ArrayList<>();
            List<String> notes = model.notes;
            for (int index = 0; index < notes.size(); index++) {
                int at = index;
                items.add(
                        entryItem(
                                getS(R.string.item_note),
                                notes.get(index),
                                divergedItem("notes", at),
                                () -> dialogs.noteDialog(at)));
            }
            section(R.string.section_notes, R.drawable.ic_section_notes, items);
        }

        if (trailing != null) {
            trailing.accept(sections);
        }

        if (onRender != null) {
            onRender.run();
        }
    }

    /**
     * Marks the open conflict row reviewed, then re-renders.
     *
     * <p>Cancelling a dialog skips this, so the warning stays.
     */
    void markResolvedAndRender() {
        if (pendingResolveKey != null) {
            resolved.add(pendingResolveKey);
            pendingResolveKey = null;
        }
        render();
    }

    /**
     * The review key of a diverged single field, null when settled.
     *
     * <p>A compound field like gender checks each of its component paths.
     */
    private String divergedField(String key, String... paths) {
        return conflicted(paths.length == 0 ? new String[] {key} : paths) ? key : null;
    }

    /** The review key of a changed list row; null if settled or added now. */
    private String divergedItem(String section, int index) {
        if (!conflict() || !changedLists.contains(section)) {
            return null;
        }
        Integer loaded = loadedCounts.get(section);
        return loaded != null && index < loaded ? section + ":" + index : null;
    }

    /**
     * Whether a diverged single field still awaits review.
     *
     * <p>It renders even while empty; once reviewed it follows the plain rules,
     * so clearing its value drops the row.
     */
    private boolean unreviewed(String key, String... paths) {
        return divergedField(key, paths) != null && !resolved.contains(key);
    }

    /**
     * Whether a changed list section awaits review on any loaded row.
     *
     * <p>It renders even once emptied; all reviewed, the plain rules apply.
     */
    private boolean unreviewedList(String section) {
        if (!conflict() || !changedLists.contains(section)) {
            return false;
        }
        for (int index = 0; index < loadedCounts.getOrDefault(section, 0); index++) {
            if (!resolved.contains(section + ":" + index)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Adds a section: icon, label, an add icon filtered to it, then its items.
     *
     * <p>An empty section vanishes.
     */
    private void section(int title, int icon, List<View> items) {
        section(title, icon, items, false);
    }

    private void section(int title, int icon, List<View> items, boolean keepEmpty) {
        sections.section(
                title,
                icon,
                items,
                addIcon(R.string.contact_add_field, () -> addToSection(title)),
                keepEmpty);
    }

    /** The right-aligned add action of a section header. */
    private View addIcon(int label, Runnable onClick) {
        ImageView icon = new ImageView(activity);
        icon.setImageResource(R.drawable.ic_add_entry);
        icon.setImageTintList(ColorStateList.valueOf(accentColor));
        icon.setContentDescription(getS(label));
        icon.setBackgroundResource(resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        icon.setPadding(ui.dp(6), ui.dp(6), ui.dp(6), ui.dp(6));
        icon.setOnClickListener(view -> onClick.run());
        return icon;
    }

    /**
     * A tappable row: title over a diminished subtitle.
     *
     * <p>A non-null {@code diverged} key wears the warning glyph until opened
     * and confirmed. Single fields key by path, list rows by section and loaded
     * position; a row added this session never wears it.
     */
    private View entryItem(
            CharSequence title, CharSequence subtitle, String diverged, Runnable onClick) {
        String key = diverged;

        ImageView warn = null;
        if (key != null && !resolved.contains(key)) {
            pending.add(key);
            warn = new ImageView(activity);
            warn.setImageResource(R.drawable.ic_diverged);
            // NOTE: 24dp (not the list's 32dp) so the glyph centres on the
            // section headers' add-icon column instead of reading off-axis.
            warn.setImageTintList(
                    android.content.res.ColorStateList.valueOf(
                            ui.resolveColor(android.R.attr.colorError)));
        }

        return sections.row(
                title,
                subtitle,
                warn,
                () -> {
                    pendingResolveKey = key;
                    onClick.run();
                });
    }

    /** A field's display value, empty when unset: no "not set" filler. */
    private String value(String text) {
        return text.trim();
    }

    private String genderSummary() {
        FormGender gender = view.gender();
        if (gender == null) {
            return "";
        }

        List<String> parts = new ArrayList<>();
        int sex = gender.sexIndex();
        if (sex > 0) {
            parts.add(typeLabel(TypeKind.GENDER, sex));
        }
        if (!gender.identity().isEmpty()) {
            parts.add(gender.identity());
        }
        return String.join(" · ", parts);
    }

    private String typeLabel(TypeKind kind, int index) {
        String[] labels = TypeLabels.of(activity.getResources(), kind);
        return labels[Math.min(index, labels.length - 1)];
    }

    /** The type spinner position of a list entry, bridge-computed. */
    static int typeAt(List<Integer> positions, int index) {
        return index >= 0 && index < positions.size() ? positions.get(index) : 0;
    }

    /** An address entry's view support. */
    FormAddress addressAt(int index) {
        List<FormAddress> entries = view.addresses();
        return index >= 0 && index < entries.size() ? entries.get(index) : new FormAddress(0, "");
    }

    /** One organization component (company or department); empty when unset. */
    String organizationPart(String part) {
        ContactOrganization organization = model.organization;
        return organization == null ? "" : organizationPart(organization, part).trim();
    }

    /** One organization component by its model key. */
    static String organizationPart(ContactOrganization organization, String part) {
        switch (part) {
            case "company":
                return organization.company();
            case "department":
                return organization.department();
            default:
                return "";
        }
    }

    /** The organization with one component, by its model key, replaced. */
    static ContactOrganization withOrganizationPart(
            ContactOrganization organization, String part, String value) {
        return new ContactOrganization(
                part.equals("company") ? value : organization.company(),
                part.equals("department") ? value : organization.department());
    }

    /** The model's list section under the key, empty for any other key. */
    List<?> list(String key) {
        switch (key) {
            case "phones":
                return model.phones;
            case "emails":
                return model.emails;
            case "addresses":
                return model.addresses;
            case "relations":
                return model.relations;
            default:
                return strings(key);
        }
    }

    /** The model's string list under the key, empty for any other key. */
    List<String> strings(String key) {
        switch (key) {
            case "nicknames":
                return model.nicknames;
            case "notes":
                return model.notes;
            case "impps":
                return model.impps;
            case "languages":
                return model.languages;
            case "websites":
                return model.websites;
            default:
                return new ArrayList<>();
        }
    }

    /** Writes an entry back: replaced, appended if new, dropped if empty. */
    static <T> void putEntry(List<T> values, int index, String filled, T entry) {
        if (filled.trim().isEmpty()) {
            if (index >= 0) {
                values.remove(index);
            }
            return;
        }

        if (index >= 0) {
            values.set(index, entry);
        } else {
            values.add(entry);
        }
    }

    private boolean conflicted(String... paths) {
        if (alternatives == null) {
            return false;
        }
        for (String path : paths) {
            if (alternatives.containsKey(path)) {
                return true;
            }
        }
        return false;
    }

    static String text(EditText input) {
        return input.getText().toString().trim();
    }

    String getS(int id) {
        return activity.getString(id);
    }

    int resolveAttr(int attr) {
        return ui.resolveAttr(attr);
    }
}
