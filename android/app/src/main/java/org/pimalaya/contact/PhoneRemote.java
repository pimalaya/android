package org.pimalaya.contact;

import android.accounts.Account;
import android.content.ContentProviderOperation;
import android.content.ContentProviderResult;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.provider.ContactsContract.Data;
import android.provider.ContactsContract.Groups;
import android.provider.ContactsContract.RawContacts;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.IntConsumer;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.CardIndex;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.ContactFields;
import org.pimalaya.phone.Accounts;
import org.pimalaya.phone.PhoneMirror;
import org.pimalaya.pimdir.PhoneSpoke;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;

/**
 * The phone spoke's remote adapter for io-pimdir, over ContactsContract.
 *
 * <p>Serves the yields against a book's raw contacts (card id in SOURCE_ID, a
 * revision token in SYNC1). Writes carry CALLER_IS_SYNCADAPTER, so DIRTY alone
 * marks a Contacts-app edit. The pure field mapping lives in {@link Mapping}.
 */
final class PhoneRemote implements PhoneSpoke {
    private final Context context;

    /** The store's engine seam (the row reads behind the fetch). */
    private final PimdirStorage offline;

    /** Raw contact row ids by handle, primed by the pass's enumerate. */
    private final Map<String, Long> rawIds = new HashMap<>();

    PhoneRemote(Context context, PimdirDb pimdir) {
        this.context = context;
        this.offline = new PimdirStorage(pimdir);
    }

    /**
     * Whether the spoke can serve this book: permission and Android account.
     *
     * <p>The account exists when the book is mirrored
     * ({@link Accounts#reconcile}); callers skip the phone passes otherwise.
     */
    @Override
    public boolean available(String spoke) {
        String url = CardStore.serverUrl(spoke);
        return PhoneMirror.CONTACTS.granted(context) && account(url) != null;
    }

    /**
     * Whether the raw contacts carry anything for the hub to ingest.
     *
     * <p>A user edit (DIRTY), a creation (no SOURCE_ID) or a deletion
     * (DELETED): one indexed count, half of the phone pass's quiet-path guard.
     */
    @Override
    public boolean changed(String spoke) {
        String url = CardStore.serverUrl(spoke);
        Account account = requireAccount(url);
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                rawContactsUri(account),
                                new String[] {RawContacts._ID},
                                RawContacts.DIRTY
                                        + " = 1 OR "
                                        + RawContacts.SOURCE_ID
                                        + " IS NULL OR "
                                        + RawContacts.SOURCE_ID
                                        + " = '' OR "
                                        + RawContacts.DELETED
                                        + " = 1",
                                null,
                                null)) {
            return cursor != null && cursor.getCount() > 0;
        }
    }

    /** The account's live raw contacts, for the quiet-path count match. */
    @Override
    public int count(String spoke) {
        String url = CardStore.serverUrl(spoke);
        Account account = requireAccount(url);
        try (Cursor cursor =
                context.getContentResolver()
                        .query(
                                rawContactsUri(account),
                                new String[] {RawContacts._ID},
                                RawContacts.DELETED + " = 0",
                                null,
                                null)) {
            return cursor == null ? 0 : cursor.getCount();
        }
    }

    /**
     * Serves an enumerate yield: raw contacts as (SOURCE_ID, SYNC1 token).
     *
     * <p>Always a delta: only DELETED rows vanish, so a torn-down account never
     * reads as a mass deletion. New rows get a handle first (no double ingest
     * after a crash); rows whose card the store lost are purged.
     */
    @Override
    public JSONObject enumerate(String collection) throws JSONException {
        String url = CardStore.serverUrl(collection);
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        ensureUngroupedVisible(resolver, account);
        removeGroups(resolver, account);
        rawIds.clear();

        JSONArray items = new JSONArray();
        JSONArray vanished = new JSONArray();
        try (Cursor cursor =
                resolver.query(
                        rawContactsUri(account),
                        new String[] {
                            RawContacts._ID,
                            RawContacts.SOURCE_ID,
                            RawContacts.VERSION,
                            RawContacts.DELETED,
                            RawContacts.SYNC1,
                            RawContacts.DIRTY,
                        },
                        null,
                        null,
                        null)) {
            while (cursor != null && cursor.moveToNext()) {
                long rawId = cursor.getLong(0);
                String sourceId = cursor.isNull(1) ? null : cursor.getString(1);
                boolean deleted = cursor.getInt(3) == 1;

                if (deleted) {
                    // NOTE: only a DELETED row propagates as a delete, then
                    // is finalized; mere absence never does (see below).
                    if (sourceId != null && !sourceId.isEmpty()) {
                        vanished.put(sourceId);
                    }
                    resolver.delete(rawContactUri(account, rawId), null, null);
                    continue;
                }

                String handle;
                if (sourceId == null || sourceId.isEmpty()) {
                    handle = UUID.randomUUID().toString();
                    ContentValues values = new ContentValues();
                    values.put(RawContacts.SOURCE_ID, handle);
                    resolver.update(rawContactUri(account, rawId), values, null, null);
                } else if (offline.loadRow(collection, sourceId) == null) {
                    // NOTE: stale projection of a card the store dropped
                    // (interrupted delete, store rebuild); purge it.
                    resolver.delete(rawContactUri(account, rawId), null, null);
                    continue;
                } else {
                    handle = sourceId;
                }
                // NOTE: the revision rides the same cursor, one provider
                // query per round rather than one per contact.
                String token = cursor.isNull(4) ? null : cursor.getString(4);
                String revision = revisionOf(token, cursor.getInt(5) != 0, cursor.getInt(2));

                rawIds.put(handle, rawId);
                JSONObject item = new JSONObject();
                item.put("handle", handle);
                item.put("revision", revision);
                items.put(item);
            }
        }

        // NOTE: complete=false; absence must not read as deletion, or a
        // removed account or toggled-off book would flush every card. Only
        // the DELETED rows above vanish; the hub re-projects the rest.
        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", vanished);
        reply.put("complete", false);
        return reply;
    }

    /**
     * Serves a fetch yield: each raw contact read back as a vCard.
     *
     * <p>The phone's field model merges over the last converged vCard's
     * (Mapping.merge) and patches it, so unknown properties survive; an
     * untouched contact rides back byte for byte and every read stamps it.
     */
    @Override
    public JSONObject fetch(String collection, JSONArray handles) throws JSONException {
        JSONArray items = new JSONArray();
        for (int index = 0; index < handles.length(); index++) {
            JSONObject item = read(collection, handles.getString(index));
            if (item != null) {
                items.put(item);
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /** One handle's fetched item (also the conflict-merge remote side). */
    JSONObject read(String collection, String handle) throws JSONException {
        String url = CardStore.serverUrl(collection);
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();
        Long rawId = rawId(resolver, account, handle);
        if (rawId == null) {
            return null;
        }

        // NOTE: capture the sync signals before the data rows; the stamp
        // below guards on this VERSION, so an edit racing the read stays
        // dirty for the next round instead of vanishing.
        String heldToken = null;
        boolean dirty = false;
        int version = 0;
        try (Cursor cursor =
                resolver.query(
                        rawContactUri(account, rawId),
                        new String[] {
                            RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.VERSION
                        },
                        null,
                        null,
                        null)) {
            if (cursor == null || !cursor.moveToFirst()) {
                return null;
            }
            heldToken = cursor.isNull(0) ? null : cursor.getString(0);
            dirty = cursor.getInt(1) != 0;
            version = cursor.getInt(2);
        }

        String baseVcard = phoneBase(collection, handle);
        ContactFields baseModel = ContactClient.projectCard(baseVcard);
        ContactFields phoneModel = Mapping.model(dataRows(resolver, account, rawId));
        ContactFields merged = Mapping.merge(baseModel, phoneModel);

        // NOTE: no field taken means no edit, so ride the base back byte
        // for byte; re-encoding a no-op canonicalizes bytes and the
        // hash-diffing engine would read it as a phone edit and cascade.
        String body = merged == null ? baseVcard : ContactClient.applyCard(baseVcard, merged);
        if (merged != null) {
            // NOTE: log which fields the phone won, to explain a phone-won
            // hub change when hunting phantom edits.
            StringBuilder taken = new StringBuilder();
            for (Mapping.Field field : Mapping.Field.values()) {
                if (!Objects.equals(field.of(merged), field.of(baseModel))) {
                    taken.append(taken.length() == 0 ? "" : ", ").append(field.key);
                }
            }
            Log.d("pimalaya", "phone edit on " + handle + " (fields: " + taken + ")");
        }

        // NOTE: stamp the row clean so it stops enumerating as pending;
        // this clears DIRTY on the pull path and heals pre-SYNC1 rows,
        // which otherwise refetch on every VERSION bump. Already-stamped
        // rows skip the write.
        String token = CardStore.byteHash(body);
        String revision;
        if (!dirty && token.equals(heldToken)) {
            revision = token;
        } else if (stampIfUnchanged(resolver, account, rawId, token, version)) {
            revision = token;
        } else {
            revision = revisionOf(heldToken, dirty, version);
        }

        CardIndex index = ContactClient.indexCard(body);
        String uid = index.uid();

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", uid.isEmpty() ? handle : uid);
        item.put("hash", token);
        item.put("body", body);
        item.put("revision", revision);
        return item;
    }

    /**
     * Serves a push yield: creates, in-place updates and purges.
     *
     * <p>Updates and removes are guarded on the recorded VERSION and reported
     * rejected on a mismatch, so a racing Contacts-app edit survives to the
     * next round. {@code pushed} is told the count after each change.
     */
    @Override
    public JSONObject push(String collection, JSONArray changes, IntConsumer pushed)
            throws JSONException {
        JSONArray results = new JSONArray();
        for (int index = 0; index < changes.length(); index++) {
            JSONObject change = changes.getJSONObject(index);
            String handle = change.getString("handle");
            try {
                switch (change.getString("op")) {
                    case "add":
                        results.put(pushAdd(collection, change));
                        break;
                    case "update":
                        results.put(pushUpdate(collection, change));
                        break;
                    case "remove":
                        results.put(pushRemove(collection, change));
                        break;
                    default:
                        // NOTE: no flag pushes on the phone spoke.
                        results.put(result(handle, true, null, null));
                        break;
                }
            } catch (Exception failure) {
                Log.w("pimalaya", "phone push failed for " + handle, failure);
                results.put(result(handle, false, null, null));
            }
            pushed.accept(index + 1);
        }

        JSONObject reply = new JSONObject();
        reply.put("results", results);
        return reply;
    }

    /**
     * Pushes a pending create onto a new raw contact stamped with its handle.
     *
     * <p>One already carrying the handle (a crash before convergence) is
     * rewritten in place instead of duplicated.
     */
    private JSONObject pushAdd(String collection, JSONObject change) throws Exception {
        String url = CardStore.serverUrl(collection);
        String handle = change.getString("handle");
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        JSONObject row = offline.loadRow(collection, handle);
        if (row == null || row.getString("vcard").isEmpty()) {
            return result(handle, false, null, null);
        }
        String vcard = row.getString("vcard");
        ContactFields model = ContactClient.projectCard(vcard);

        // NOTE: this app owns the phone's handle namespace, so a staged create
        // lands under the name behind its provisional handle (SYNC §2), not
        // under the handle, which holds a control character.
        String name = PimdirStorage.nameOf(handle);
        Long existing = rawId(resolver, account, name);
        long rawId;
        if (existing != null) {
            rawId = existing;
            rewrite(resolver, account, rawId, model);
        } else {
            rawId = insert(resolver, account, name, model);
            rawIds.put(name, rawId);
        }

        return result(handle, true, name, stampRevision(resolver, account, rawId, vcard));
    }

    /** Pushes a staged content change onto the existing raw contact. */
    private JSONObject pushUpdate(String collection, JSONObject change) throws Exception {
        String url = CardStore.serverUrl(collection);
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        JSONObject row = offline.loadRow(collection, handle);
        Long rawId = rawId(resolver, account, handle);
        if (row == null || rawId == null) {
            return result(handle, false, null, null);
        }
        if (ifMatch != null && !ifMatch.equals(phoneRevision(resolver, account, rawId))) {
            // NOTE: a contacts-app edit landed since the base; keep it for
            // the next round rather than clobbering it.
            return result(handle, false, null, null);
        }

        String vcard = row.getString("vcard");
        rewrite(resolver, account, rawId, ContactClient.projectCard(vcard));
        return result(handle, true, null, stampRevision(resolver, account, rawId, vcard));
    }

    /** Pushes a staged removal: the raw contact goes entirely. */
    private JSONObject pushRemove(String collection, JSONObject change) throws JSONException {
        String url = CardStore.serverUrl(collection);
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        Account account = requireAccount(url);
        ContentResolver resolver = context.getContentResolver();

        Long rawId = rawId(resolver, account, handle);
        if (rawId == null) {
            // NOTE: already gone phone-side; the removal converged.
            return result(handle, true, null, null);
        }
        if (ifMatch != null && !ifMatch.equals(phoneRevision(resolver, account, rawId))) {
            return result(handle, false, null, null);
        }

        context.getContentResolver().delete(rawContactUri(account, rawId), null, null);
        rawIds.remove(handle);
        return result(handle, true, null, null);
    }

    /** Inserts a raw contact with its data rows, returning its row id. */
    private long insert(ContentResolver resolver, Account account, String handle,
            ContactFields model) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(
                ContentProviderOperation.newInsert(rawContactsUri(account))
                        .withValue(RawContacts.ACCOUNT_TYPE, account.type)
                        .withValue(RawContacts.ACCOUNT_NAME, account.name)
                        .withValue(RawContacts.SOURCE_ID, handle)
                        .withValue(RawContacts.SYNC2, Objects.toString(model.uid(), ""))
                        .build());
        for (ContentValues row : contentValues(model)) {
            ops.add(
                    ContentProviderOperation.newInsert(dataUri(account))
                            .withValueBackReference(Data.RAW_CONTACT_ID, 0)
                            .withValues(row)
                            .build());
        }

        ContentProviderResult[] results =
                resolver.applyBatch(ContactsContract.AUTHORITY, ops);
        return ContentUris.parseId(results[0].uri);
    }

    /** Rewrites a raw contact's data rows wholesale from the model. */
    private void rewrite(ContentResolver resolver, Account account, long rawId,
            ContactFields model) throws Exception {
        ArrayList<ContentProviderOperation> ops = new ArrayList<>();
        ops.add(
                ContentProviderOperation.newDelete(dataUri(account))
                        .withSelection(
                                Data.RAW_CONTACT_ID + " = ?",
                                new String[] {String.valueOf(rawId)})
                        .build());
        for (ContentValues row : contentValues(model)) {
            ops.add(
                    ContentProviderOperation.newInsert(dataUri(account))
                            .withValue(Data.RAW_CONTACT_ID, rawId)
                            .withValues(row)
                            .build());
        }
        ops.add(
                ContentProviderOperation.newUpdate(rawContactUri(account, rawId))
                        .withValue(RawContacts.SYNC2, Objects.toString(model.uid(), ""))
                        .build());

        resolver.applyBatch(ContactsContract.AUTHORITY, ops);
    }

    /** The raw contact's data rows as plain maps for the pure Mapping. */
    private static List<Map<String, Object>> dataRows(
            ContentResolver resolver, Account account, long rawId) {
        String[] projection = {
            Data.MIMETYPE,
            Data.DATA1,
            Data.DATA2,
            Data.DATA3,
            Data.DATA4,
            Data.DATA5,
            Data.DATA6,
            Data.DATA7,
            Data.DATA8,
            Data.DATA9,
            Data.DATA10,
            Data.IS_PRIMARY,
        };

        List<Map<String, Object>> rows = new ArrayList<>();
        // NOTE: pin insertion order; the field-space diff compares
        // multi-valued fields as lists, so an unordered listing could read
        // as a permutation edit.
        try (Cursor cursor =
                resolver.query(
                        dataUri(account),
                        projection,
                        Data.RAW_CONTACT_ID + " = ?",
                        new String[] {String.valueOf(rawId)},
                        Data._ID)) {
            while (cursor != null && cursor.moveToNext()) {
                Map<String, Object> row = new LinkedHashMap<>();
                for (int column = 0; column < projection.length; column++) {
                    if (!cursor.isNull(column)) {
                        row.put(projection[column], cursor.getString(column));
                    }
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** The model's data rows (built by the pure Mapping), as ContentValues. */
    private static List<ContentValues> contentValues(ContactFields model) {
        List<ContentValues> rows = new ArrayList<>();
        for (Map<String, Object> row : Mapping.rows(model)) {
            ContentValues values = new ContentValues();
            for (Map.Entry<String, Object> entry : row.entrySet()) {
                if (entry.getValue() instanceof Integer) {
                    values.put(entry.getKey(), (Integer) entry.getValue());
                } else {
                    values.put(entry.getKey(), (String) entry.getValue());
                }
            }
            rows.add(values);
        }
        return rows;
    }

    /**
     * The vCard the phone last converged with, every read-back's patch base.
     *
     * <p>A phone-created contact starts from a skeleton with its handle as UID.
     */
    private String phoneBase(String collection, String handle) throws JSONException {
        JSONObject row = offline.loadRow(PimdirStorage.phoneCollection(
                PimdirStorage.collectionOf(collection)), handle);
        String held = null;
        if (row != null) {
            held = row.isNull("baseVcard") ? null : row.getString("baseVcard");
            if (held == null || held.isEmpty()) {
                // NOTE: never converged but the hub holds it; its own vCard
                // is the closest base, healing a lost axis without dropping
                // unmapped properties.
                String vcard = row.getString("vcard");
                held = vcard.isEmpty() ? null : vcard;
            }
        }
        if (held != null) {
            return held;
        }
        return "BEGIN:VCARD\r\nVERSION:3.0\r\nUID:" + handle + "\r\nEND:VCARD\r\n";
    }

    /** The raw contact id behind a handle, the pass cache first. */
    private Long rawId(ContentResolver resolver, Account account, String handle) {
        Long cached = rawIds.get(handle);
        if (cached != null) {
            return cached;
        }
        try (Cursor cursor =
                resolver.query(
                        rawContactsUri(account),
                        new String[] {RawContacts._ID},
                        RawContacts.SOURCE_ID + " = ? AND " + RawContacts.DELETED + " = 0",
                        new String[] {handle},
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                long rawId = cursor.getLong(0);
                rawIds.put(handle, rawId);
                return rawId;
            }
        }
        return null;
    }

    /**
     * The raw contact's sync revision, from the sync signals, not the content.
     *
     * <p>The provider bumps VERSION and rewrites rows after writes, a phantom
     * edit; DIRTY is set only by a user edit. So: the SYNC1 token while clean,
     * a version-tagged sentinel while dirty.
     */
    private static String phoneRevision(
            ContentResolver resolver, Account account, long rawId) {
        try (Cursor cursor =
                resolver.query(
                        rawContactUri(account, rawId),
                        new String[] {
                            RawContacts.SYNC1, RawContacts.DIRTY, RawContacts.VERSION
                        },
                        null,
                        null,
                        null)) {
            if (cursor != null && cursor.moveToFirst()) {
                String token = cursor.isNull(0) ? null : cursor.getString(0);
                return revisionOf(token, cursor.getInt(1) != 0, cursor.getInt(2));
            }
        }
        return null;
    }

    /** The revision rule: the stamped token while clean, else the sentinel. */
    private static String revisionOf(String token, boolean dirty, int version) {
        if (token != null && !dirty) {
            return token;
        }
        return "dirty:" + version;
    }

    /**
     * Stamps the raw contact converged, guarded on the VERSION read before.
     *
     * <p>An edit racing the ingestion bumps VERSION, the update matches
     * nothing, and the row stays dirty for the next round.
     */
    private static boolean stampIfUnchanged(
            ContentResolver resolver, Account account, long rawId, String token, int version) {
        ContentValues values = new ContentValues();
        values.put(RawContacts.SYNC1, token);
        values.put(RawContacts.DIRTY, 0);
        int stamped =
                resolver.update(
                        rawContactUri(account, rawId),
                        values,
                        RawContacts.VERSION + " = ?",
                        new String[] {String.valueOf(version)});
        return stamped == 1;
    }

    /**
     * Stamps the raw contact converged: SYNC1 gets the token, DIRTY clears.
     *
     * <p>The token (the vCard hash) is stable across provider churn; clearing
     * DIRTY keeps a push just applied from reading back as a user edit.
     */
    private static String stampRevision(
            ContentResolver resolver, Account account, long rawId, String vcard) {
        String token = CardStore.byteHash(vcard);
        ContentValues values = new ContentValues();
        values.put(RawContacts.SYNC1, token);
        values.put(RawContacts.DIRTY, 0);
        resolver.update(rawContactUri(account, rawId), values, null, null);
        return token;
    }

    private Account account(String url) {
        return Accounts.findByUrl(context, url);
    }

    private Account requireAccount(String url) {
        Account account = account(url);
        if (account == null) {
            throw new IllegalStateException("No Android account for " + url);
        }
        return account;
    }

    /**
     * Unhides our contacts: those of accounts without groups hide by default.
     */
    private static void ensureUngroupedVisible(ContentResolver resolver, Account account) {
        ContentValues values = new ContentValues();
        values.put(ContactsContract.Settings.ACCOUNT_NAME, account.name);
        values.put(ContactsContract.Settings.ACCOUNT_TYPE, account.type);
        values.put(ContactsContract.Settings.UNGROUPED_VISIBLE, 1);
        resolver.insert(asSyncAdapter(ContactsContract.Settings.CONTENT_URI, account), values);
    }

    /**
     * Drops every group of the account.
     *
     * <p>User decision: the Contacts app's labels stay untouched, CATEGORIES
     * stay vCard-side. Also heals phones with groups from earlier builds.
     */
    private static void removeGroups(ContentResolver resolver, Account account) {
        resolver.delete(asSyncAdapter(Groups.CONTENT_URI, account), null, null);
    }

    private static Uri rawContactsUri(Account account) {
        return asSyncAdapter(RawContacts.CONTENT_URI, account);
    }

    private static Uri rawContactUri(Account account, long rawId) {
        return ContentUris.withAppendedId(rawContactsUri(account), rawId);
    }

    private static Uri dataUri(Account account) {
        return asSyncAdapter(Data.CONTENT_URI, account);
    }

    private static Uri asSyncAdapter(Uri uri, Account account) {
        return uri.buildUpon()
                .appendQueryParameter(ContactsContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(RawContacts.ACCOUNT_NAME, account.name)
                .appendQueryParameter(RawContacts.ACCOUNT_TYPE, account.type)
                .build();
    }

    /** One push outcome on the engine wire. */
    private static JSONObject result(
            String handle, boolean accepted, String assigned, String revision)
            throws JSONException {
        JSONObject result = new JSONObject();
        result.put("handle", handle);
        result.put("accepted", accepted);
        if (assigned != null) {
            result.put("assigned", assigned);
        }
        if (revision != null) {
            result.put("revision", revision);
        }
        return result;
    }
}
