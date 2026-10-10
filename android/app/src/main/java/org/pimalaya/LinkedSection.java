package org.pimalaya;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;

/**
 * An item's links made visible (plan C.5): a Linked card listing what the
 * item references and what references it, each saying why in words and
 * opening the other item; "Link to" searching the four kinds for an item to
 * link; and unlinking.
 *
 * <p>Inline on the pages built of cards (a contact, an entry), in a dialog
 * from the reader and the file sheet, whose layouts have no card column.
 * Every read and write runs on the io executor: a page draws its card from
 * the links last read for it, and redraws once a read lands.
 */
final class LinkedSection {
    private final MainActivity host;

    /** The links read for an item and not drawn yet, by endpoint. */
    private final Map<String, List<ItemLinks.Link>> read = new HashMap<>();

    /** The dialog a tap in it closes before opening the other item. */
    private AlertDialog opening;

    LinkedSection(MainActivity host) {
        this.host = host;
    }

    /**
     * Adds the Linked card to a page; {@code refresh} redraws the page once
     * the links are read, or a link is added or removed. Nothing for an item
     * that cannot be linked (a contact not saved yet, a message still in an
     * outbox). Drawn empty while the read runs.
     */
    void section(Sections sections, ItemLinks.Endpoint self, Runnable refresh) {
        if (self == null) {
            return;
        }
        List<ItemLinks.Link> links = read.remove(keyOf(self));
        if (links == null) {
            load(self, refresh);
            links = new ArrayList<>();
        }
        List<View> rows = new ArrayList<>();
        for (ItemLinks.Link link : links) {
            rows.add(row(sections, self, link, refresh));
        }
        sections.section(
                R.string.linked_title, R.drawable.ic_link, rows, linkTo(self, refresh), true);
    }

    /** Reads an item's links off the main thread, then redraws its page with them. */
    private void load(ItemLinks.Endpoint self, Runnable refresh) {
        run(
                () -> {
                    List<ItemLinks.Link> links = host.links.links(self);
                    host.postAlive(
                            () -> {
                                read.put(keyOf(self), links);
                                refresh.run();
                            });
                });
    }

    private static String keyOf(ItemLinks.Endpoint endpoint) {
        return endpoint.kind + "\u0000" + endpoint.linkId;
    }

    /** Runs store work on the io executor, unless the activity is going. */
    private void run(Runnable work) {
        if (host.isDestroyed() || host.io.isShutdown()) {
            return;
        }
        try {
            host.io.execute(work);
        } catch (RejectedExecutionException closing) {
            Log.i("pimalaya", "links not read: the activity is closing");
        }
    }

    /** The Linked card in a dialog of its own, titled by the item. */
    void dialog(ItemLinks.Endpoint self, String title) {
        dialog(self, title, () -> {});
    }

    /** {@link #dialog(ItemLinks.Endpoint, String)}, running {@code closed} once it goes. */
    void dialog(ItemLinks.Endpoint self, String title, Runnable closed) {
        if (self == null) {
            return;
        }
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(0, 0, 0, host.dp(16));
        ScrollView scroll = new ScrollView(host);
        scroll.addView(content);
        Sections sections = new Sections(host, content);
        AlertDialog dialog =
                new AlertDialog.Builder(host)
                        .setTitle(title)
                        .setView(scroll)
                        .setPositiveButton(android.R.string.ok, null)
                        .create();
        Runnable[] render = new Runnable[1];
        render[0] =
                () -> {
                    sections.clear();
                    section(sections, self, render[0]);
                };
        render[0].run();
        dialog.setOnDismissListener(gone -> closed.run());
        // NOTE: the other item opens over the screen, the dialog gone.
        opening = dialog;
        dialog.show();
    }

    /**
     * One link: the other item, why it is linked, and the unlink action,
     * which an attachment does not offer: the file is the message's own, and
     * unlinked it would be lost.
     */
    private View row(
            Sections sections, ItemLinks.Endpoint self, ItemLinks.Link link, Runnable refresh) {
        ImageView unlink = null;
        if (!(link.automatic() && "attachment".equals(link.role))) {
            unlink = new ImageView(host);
            unlink.setImageResource(R.drawable.ic_link_off);
            unlink.setImageTintList(
                    ColorStateList.valueOf(
                            host.ui.resolveColor(android.R.attr.textColorSecondary)));
            unlink.setContentDescription(host.getString(R.string.linked_unlink));
            unlink.setOnClickListener(view -> confirmUnlink(link, refresh));
        }

        ItemLinks.Endpoint other = link.from.same(self) ? link.to : link.from;
        String title =
                link.other == null
                        ? host.getString(R.string.linked_gone)
                        : link.other.title.isEmpty()
                                ? host.getString(R.string.linked_untitled)
                                : link.other.title;
        String subtitle = host.getString(kindLabel(other.kind)) + " · " + reason(link);
        return sections.row(
                title,
                subtitle,
                unlink,
                link.other == null ? null : () -> open(link.other));
    }

    /** Why a link exists, in words: the rule's role, or the person who made it. */
    String reason(ItemLinks.Link link) {
        if (!link.automatic()) {
            return host.getString(R.string.linked_by_you);
        }
        switch (link.role) {
            case "sender":
                return host.getString(R.string.linked_sender);
            case "attachment":
                return host.getString(R.string.linked_attachment);
            case "invitation":
                return host.getString(R.string.linked_invitation);
            default:
                return host.getString(R.string.linked_related);
        }
    }

    static int kindLabel(String kind) {
        switch (kind) {
            case PimdirSummary.MAIL:
                return R.string.linked_kind_message;
            case PimdirSummary.CONTACT:
                return R.string.linked_kind_contact;
            case PimdirSummary.CALENDAR:
                return R.string.linked_kind_event;
            default:
                return R.string.linked_kind_file;
        }
    }

    /** Asks before unlinking, saying when a rule may link the two again. */
    private void confirmUnlink(ItemLinks.Link link, Runnable refresh) {
        String question = host.getString(R.string.linked_unlink_confirm);
        if (link.automatic()) {
            question += "\n\n" + host.getString(R.string.linked_unlink_automatic);
        }
        new AlertDialog.Builder(host)
                .setMessage(question)
                .setPositiveButton(
                        R.string.linked_unlink,
                        (dialog, which) ->
                                run(
                                        () -> {
                                            host.links.remove(link);
                                            host.postAlive(refresh);
                                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    /** The card header's "Link to" action. */
    private View linkTo(ItemLinks.Endpoint self, Runnable refresh) {
        ImageView icon = new ImageView(host);
        icon.setImageResource(R.drawable.ic_link_add);
        icon.setImageTintList(
                ColorStateList.valueOf(host.ui.resolveColor(android.R.attr.colorAccent)));
        icon.setContentDescription(host.getString(R.string.linked_link_to));
        icon.setBackgroundResource(
                host.ui.resolveAttr(android.R.attr.selectableItemBackgroundBorderless));
        icon.setPadding(host.dp(6), host.dp(6), host.dp(6), host.dp(6));
        icon.setOnClickListener(view -> pick(self, refresh));
        return icon;
    }

    /**
     * The picker: a search field over mixed results from the four kinds; a
     * tap links the item to the result, as the person's.
     */
    private void pick(ItemLinks.Endpoint self, Runnable refresh) {
        EditText field = new EditText(host);
        field.setHint(R.string.linked_search);
        field.setSingleLine(true);
        LinearLayout results = new LinearLayout(host);
        results.setOrientation(LinearLayout.VERTICAL);
        LinearLayout content = new LinearLayout(host);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(host.dp(16), host.dp(8), host.dp(16), 0);
        content.addView(field);
        ScrollView scroll = new ScrollView(host);
        scroll.addView(results);
        content.addView(
                scroll,
                new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, host.dp(360)));
        AlertDialog dialog =
                new AlertDialog.Builder(host)
                        .setTitle(R.string.linked_link_to)
                        .setView(content)
                        .setNegativeButton(android.R.string.cancel, null)
                        .create();
        Sections sections = new Sections(host, results);
        Runnable[] pending = new Runnable[1];
        // NOTE: a search still waiting when the picker goes would run against
        // a dialog nobody sees, or an executor already shut.
        dialog.setOnDismissListener(
                gone -> {
                    if (pending[0] != null) {
                        host.main.removeCallbacks(pending[0]);
                    }
                });
        field.addTextChangedListener(
                new TextWatcher() {
                    @Override
                    public void beforeTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void onTextChanged(CharSequence s, int a, int b, int c) {}

                    @Override
                    public void afterTextChanged(Editable s) {
                        String words = s.toString();
                        if (pending[0] != null) {
                            host.main.removeCallbacks(pending[0]);
                        }
                        pending[0] = () -> search(words, self, sections, dialog, refresh);
                        host.main.postDelayed(pending[0], 250);
                    }
                });
        dialog.show();
    }

    /** Runs one search off the main thread, then lists what it found. */
    private void search(
            String words,
            ItemLinks.Endpoint self,
            Sections sections,
            AlertDialog dialog,
            Runnable refresh) {
        run(
                () -> {
                    List<ItemLinks.Placement> found = host.links.search(words);
                    host.postAlive(
                            () -> {
                                if (!dialog.isShowing()) {
                                    return;
                                }
                                sections.clear();
                                List<View> rows = new ArrayList<>();
                                for (ItemLinks.Placement placement : found) {
                                    if (placement.endpoint.same(self)) {
                                        continue;
                                    }
                                    String kind =
                                            host.getString(kindLabel(placement.endpoint.kind));
                                    rows.add(
                                            sections.row(
                                                    placement.title.isEmpty()
                                                            ? host.getString(
                                                                    R.string.linked_untitled)
                                                            : placement.title,
                                                    placement.detail.isEmpty()
                                                            ? kind
                                                            : kind + " · " + placement.detail,
                                                    null,
                                                    () -> {
                                                        dialog.dismiss();
                                                        link(self, placement, refresh);
                                                    }));
                                }
                                sections.section(0, 0, rows, null, false);
                            });
                });
    }

    private void link(ItemLinks.Endpoint self, ItemLinks.Placement other, Runnable refresh) {
        run(
                () -> {
                    boolean linked = host.links.add(self, other.endpoint);
                    host.postAlive(
                            () -> {
                                if (linked) {
                                    refresh.run();
                                } else {
                                    host.toast(host.getString(R.string.linked_failed));
                                }
                            });
                });
    }

    /** Opens the other item on its own page, read off the store first. */
    private void open(ItemLinks.Placement other) {
        if (opening != null) {
            opening.dismiss();
            opening = null;
        }
        String linkId = other.endpoint.linkId;
        switch (other.endpoint.kind) {
            case PimdirSummary.MAIL:
                run(
                        () -> {
                            MailStore.StoredMessage message =
                                    host.mail.message(other.collection, linkId);
                            host.postAlive(
                                    () -> {
                                        if (message == null) {
                                            gone();
                                        } else {
                                            host.messageView.open(message);
                                        }
                                    });
                        });
                break;
            case PimdirSummary.CONTACT:
                Group group = host.contactGroupOf(linkId);
                if (group == null) {
                    gone();
                } else {
                    host.openGroup(group);
                }
                break;
            case PimdirSummary.CALENDAR:
                host.openEvent(other.collection, linkId, this::gone);
                break;
            default:
                host.filesList.openFile(other.collection, linkId);
                break;
        }
    }

    private void gone() {
        host.toast(host.getString(R.string.linked_gone));
    }
}
