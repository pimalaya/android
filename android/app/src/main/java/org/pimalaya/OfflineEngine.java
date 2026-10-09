package org.pimalaya;

import android.content.Context;
import android.util.Log;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.client.Cards;
import org.pimalaya.client.Account;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardDelta;
import org.pimalaya.client.PimalayaClient;
import org.pimalaya.client.Transport;

/**
 * One account's offline driver: the Rust bridge runs the engine's
 * coroutines and this class services their yields, storage against the
 * pimdir store ({@link PimdirStorage}) and remote against the backend
 * clients or, for a book's phone collection, against its raw contacts
 * through the {@link PhoneRemote} adapter. Every server backend enumerates
 * incrementally from its stored cursor (CardDAV sync-collection, Graph
 * delta, JMAP /changes, People sync tokens), falling back to a
 * complete round when the cursor expired; the account-wide deltas of
 * JMAP and Google are projected onto each book's enumerate.
 * {@link #syncBook} is the orchestration: a phone pass pulling the
 * contacts app's edits into the hub, the server exchange, then a
 * second phone pass projecting what the server round brought; each
 * pass reconciles, hydrates the missing bodies, auto-resolves
 * conflicts by three-way merge and pushes the resolutions. Everything
 * blocks; callers run it off the main thread.
 */
final class OfflineEngine extends PimdirEngine {
    /**
     * One lock per book URL, process-wide: the in-app sync, the
     * background worker and the OS-scheduled sync service can each
     * run an engine pass over the same book, and a pass spans many
     * store transactions, so interleaved passes could double-push
     * staged creates or clobber the sync checkpoint. Reentrant, so
     * syncBook's own phone passes nest freely under its hold.
     */
    private static final ConcurrentHashMap<String, ReentrantLock> SYNC_LOCKS =
            new ConcurrentHashMap<>();

    private final CardStore base;

    private final Account account;

    /**
     * The pass's primary connection, for its sequential verbs; null on a
     * driver that only stages, which reaches no server.
     *
     * <p>A concurrent push does not use it. A transport serves one caller
     * at a time, so each worker of a fan-out opens its own and closes it
     * with itself, which is also what bounds a round at four connections
     * rather than one per card.
     */
    private final Transport primary;

    /** The phone spoke's adapter; null on context-less (mutate) drivers. */
    private final PhoneRemote phone;

    /**
     * The report the running sync flows tally into (the driver seam
     * feeds it as pushes are accepted and writes land); null outside
     * syncs, so app-side mutations count nothing.
     */
    private Report tally;

    /** Book ids by collection URL (account-level membership mapping). */
    private Map<String, String> idByUrl;

    /**
     * The bodies the last delta rounds carried (JMAP and Google list
     * changed cards in full), by handle: the fetch tier reads them
     * back without another round-trip; any push invalidates them.
     */
    private final Map<String, Card> deltaCards = new HashMap<>();

    /**
     * The account-wide deltas this pass read (JMAP, Google), by the cursor
     * each was read from (null for a complete round): the account has one
     * delta for every book, so a book whose checkpoint matches one already
     * read projects it rather than listing the account again. Books
     * synced together share a checkpoint, so a quiet pass over three
     * Google groups reads People once rather than three times. Any push
     * invalidates them, as it does {@link #deltaCards}.
     */
    private final Map<String, CardDelta> accountDeltas = new HashMap<>();

    /** Per-collection card cache of the Graph listing, by handle. */
    private final Map<String, Map<String, Card>> graphCards = new HashMap<>();

    /**
     * The ETags observed by each collection's last enumerate, plus
     * whether that enumerate was complete: what the CardDAV If-Match
     * quirk retry checks the staged base against.
     */
    private final Map<String, Map<String, String>> listedEtags = new HashMap<>();
    private final Map<String, Boolean> listedComplete = new HashMap<>();

    /**
     * What a sync did to the cards, for the outcome report: distinct
     * affected cards per axis (the phone and the server tallied
     * apart), regardless of direction. A card counts once however many
     * passes touched it: in = cards that appeared, out = cards that
     * were deleted, changed = cards whose content changed; plus the
     * conflicts left for the user.
     */
    static final class Report {
        final Set<String> localIn = new HashSet<>();
        final Set<String> localOut = new HashSet<>();
        final Set<String> localChanged = new HashSet<>();
        final Set<String> remoteIn = new HashSet<>();
        final Set<String> remoteOut = new HashSet<>();
        final Set<String> remoteChanged = new HashSet<>();
        int conflicts;

        /** Tallies one effect on one card, deduped per card and axis. */
        void tally(String collection, String handle, String kind) {
            boolean phone = CardStore.isPhoneCollection(collection);
            Set<String> in = phone ? localIn : remoteIn;
            Set<String> out = phone ? localOut : remoteOut;
            Set<String> changed = phone ? localChanged : remoteChanged;
            String key = collection + "\n" + handle;

            switch (kind) {
                case "created":
                    in.add(key);
                    // NOTE: a new card's follow-up writes (hydration, base
                    // captures) stay part of its appearance, not a change.
                    changed.remove(key);
                    break;
                case "removed":
                    out.add(key);
                    break;
                case "changed":
                    if (!in.contains(key)) {
                        changed.add(key);
                    }
                    break;
                default:
                    break;
            }
        }
    }

    /**
     * Builds a driver for one account; a null account services storage
     * yields only (local mutations never touch the remote), a null
     * context disables the phone spoke.
     */
    OfflineEngine(
            CardStore base,
            PimdirDb pimdir,
            PimalayaClient client,
            Transport primary,
            Account account,
            Context context) {
        super(pimdir, client);
        this.base = base;
        this.primary = primary;
        this.account = account;
        this.phone = context == null ? null : new PhoneRemote(context, pimdir);
    }

    /**
     * One book's full hub sync, three engine passes: phone (pull the
     * contacts app's edits into the hub), server (exchange with the
     * remote; the phone-won hub divergences push along), phone again
     * (project what the server round brought; local-only, cheap). A
     * book whose remote switch is off keeps only the first phone pass:
     * the hub still converges with the Contacts app, nothing touches
     * the server.
     */
    Report syncBook(String url, boolean remote) throws JSONException {
        ReentrantLock lock = syncLock(url);
        lock.lock();
        try {
            Report report = new Report();
            syncPhone(url, report);
            if (remote) {
                syncServer(url, report);
                syncPhone(url, report);
            }
            return report;
        } finally {
            lock.unlock();
        }
    }

    /**
     * The server collection's engine pass: reconcile with the remote,
     * fetch the bodies the spine still misses, then triage every
     * both-sides-edited row right here: a divergence whose three-way
     * merge needs no user choice resolves on the spot and pushes in a
     * second reconcile, and only genuine same-field collisions stay
     * conflicted (remote body captured) for the in-app resolution
     * form. So a conflict the user sees is always one the form has
     * something to ask about; the rest self-heals during the sync.
     */
    private void syncServer(String url, Report report) throws JSONException {
        tally = report;
        step(Progress.STAGE_SERVER, 0);
        Log.d("pimalaya", "server sync " + url + ": " + client.offlineSync(this, url, false));
        hydrate(url);

        List<JSONObject> conflicts = offline.loadConflicts(url);
        if (!conflicts.isEmpty()) {
            step(Progress.STAGE_RESOLVE, conflicts.size());
        }
        int resolved = 0;
        for (JSONObject conflict : conflicts) {
            if (resolveCleanConflict(url, conflict)) {
                resolved += 1;
            }
        }
        report.conflicts += conflicts.size() - resolved;

        if (resolved > 0) {
            client.offlineSync(this, url, false);
            hydrate(url);
        }
    }

    /**
     * The phone collection's engine pass, same shape as the server
     * one: reconcile the hub with the book's raw contacts, hydrate the
     * bodies the phone round brought, resolve divergences by the same
     * three-way merge. Skipped silently when the spoke is unavailable
     * (no contacts permission, or no Android account: the book is not
     * mirrored).
     */
    void syncPhone(String url, Report report) throws JSONException {
        if (phone == null || !phone.available(url)) {
            return;
        }

        ReentrantLock lock = syncLock(url);
        lock.lock();
        try {
            // NOTE: quiet path; nothing dirty/staged on either side and
            // matching member counts means the engine pass would reconcile
            // nothing. ContactsContract has no per-account changes token,
            // so three cheap counts stand in for one.
            String collection = PimdirStorage.phoneCollection(url);
            if (!phone.changed(url)
                    && !offline.pending(collection)
                    && phone.count(url) == offline.memberCount(collection)) {
                return;
            }

            tally = report;
            step(Progress.STAGE_PHONE, 0);

            Log.d(
                    "pimalaya",
                    "phone sync " + url + ": " + client.offlineSync(this, collection, false));
            hydrate(collection);

            List<JSONObject> conflicts = offline.loadConflicts(collection);
            if (!conflicts.isEmpty()) {
                step(Progress.STAGE_RESOLVE, conflicts.size());
                for (JSONObject conflict : conflicts) {
                    resolvePhoneConflict(collection, conflict);
                }

                client.offlineSync(this, collection, false);
                hydrate(collection);
            }
        } finally {
            lock.unlock();
        }
    }

    /** The book's process-wide sync lock, keyed by its collection URL. */
    private static ReentrantLock syncLock(String url) {
        return SYNC_LOCKS.computeIfAbsent(url, key -> new ReentrantLock());
    }

    /**
     * Announces a hydrate, unless it is the phone spoke's: projecting a
     * book onto the device downloads nothing, so a "downloading" line
     * over it would name the wrong thing.
     */
    @Override
    protected void hydrating(String collection, int count) {
        if (!CardStore.isPhoneCollection(collection)) {
            step(Progress.STAGE_DOWNLOAD, count);
        }
    }

    /**
     * Resolves one conflicted row on the spot when the three-way merge
     * needs no user choice: lists merge as sets and one-sided scalar
     * changes flow in, so only a field both sides edited, differently,
     * is the user's to settle. A clean merge stages as the resolution
     * (the caller's second reconcile pushes it) and reports true; a
     * genuine collision leaves the row conflicted for the resolution
     * form.
     *
     * <p>The three documents all come from the store: the engine records
     * the diverging remote body beside the revision it names, and the
     * hydrate pass before this one is what fetched it. So a conflict is
     * settled with no credentials, no backend and no network, here and
     * in the form.
     */
    private boolean resolveCleanConflict(String url, JSONObject conflict) throws JSONException {
        // NOTE: on a create collision (no captured base) the local body is
        // the base, so it reads unchanged and remote changes flow in clean.
        String local = conflict.getString("vcard");
        String baseVcard =
                conflict.isNull("baseVcard") ? local : conflict.getString("baseVcard");
        JSONObject resolution =
                Cards.mergeConflictForm(baseVcard, local, conflict.getString("remoteVcard"));
        if (!resolution.optBoolean("resolved")) {
            return false;
        }

        mutateEdit(url, conflict.getString("handle"), resolution.optString("vcard"));
        return true;
    }

    /**
     * Resolves one phone-conflicted row the same way, and always: the
     * phone side carries no field the user could be asked about that the
     * merge cannot settle, so the three-way result stands.
     *
     * <p>The revision is refreshed from the raw contact rather than from
     * the stored one, so the resolving push is conditioned on the state
     * the merge reconciled with rather than the one the conflict was
     * first noticed at.
     */
    private void resolvePhoneConflict(String collection, JSONObject conflict)
            throws JSONException {
        String handle = conflict.getString("handle");
        JSONObject remote = phone.read(collection, handle);
        if (remote == null) {
            // NOTE: raw contact vanished mid-conflict; next sync
            // reconciles the removal.
            return;
        }

        String merged =
                Cards.mergeCardChanges(
                        conflict.isNull("baseVcard") ? "" : conflict.getString("baseVcard"),
                        conflict.getString("vcard"),
                        remote.getString("body"));

        if (!remote.isNull("revision")) {
            offline.setConflictRevision(collection, handle, remote.getString("revision"));
        }
        mutateEdit(collection, handle, merged);
    }

    /**
     * Stages a content edit on one card through the engine (the next sync
     * pushes it); editing a conflicted placement resolves it.
     *
     * <p>The summary and the key come off the document itself, so a
     * rename moves the card in the list rather than leaving it where the
     * old name put it.
     */
    void mutateEdit(String url, String handle, String vcard) throws JSONException {
        JSONObject index = Cards.indexCard(vcard);
        mutateEdit(
                url,
                handle,
                vcard,
                index.optJSONObject("summary"),
                index.optString("sortKey"));
    }

    /**
     * A write to a book reaches the phone's contacts within a second
     * ({@link PhoneQueue}); a write to the phone collection is a phone
     * pass's own conflict resolution, pushed by that pass.
     */
    @Override
    protected void staged(String collection) {
        if (!CardStore.isPhoneCollection(collection)) {
            PhoneQueue.written(collection);
        }
    }

    @Override
    protected void applied(JSONArray effects) throws JSONException {
        for (int index = 0; tally != null && index < effects.length(); index++) {
            JSONObject effect = effects.getJSONObject(index);
            tally.tally(
                    effect.getString("collection"),
                    effect.getString("handle"),
                    effect.getString("kind"));
        }
    }

    /**
     * Services an enumerate yield: the collection's member spine
     * (handle plus content revision), incrementally from the cursor on
     * every backend (RFC 6578 sync-collection, Graph delta, JMAP
     * /changes, People sync tokens). An initial round (no cursor)
     * lists the complete member set and still yields the cursor to
     * delta from next time; a cursor the server no longer accepts
     * falls back to an initial round.
     */
    @Override
    protected PimDomain domain() {
        return PimDomain.CONTACTS;
    }

    @Override
    protected JSONObject enumerate(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        if (CardStore.isPhoneCollection(url)) {
            return phone.enumerate(url);
        }
        String cursor = yielded.isNull("cursor") ? null : yielded.getString("cursor");

        boolean accountLevel = PimalayaClient.isAccountLevel(account);
        CardDelta delta = accountLevel ? accountDeltas.get(cursor) : null;
        if (delta == null) {
            long asked = System.nanoTime();
            delta = client.syncCards(primary, account, url, cursor);
            remote(System.nanoTime() - asked);
            if (accountLevel) {
                accountDeltas.put(cursor, delta);
            }
        }
        listed(delta.changed.size());

        for (Card card : delta.changed) {
            if (!card.vcard.isEmpty()) {
                deltaCards.put(card.uri, card);
            }
        }

        // NOTE: Graph delta rows carry no body, so a complete round primes
        // the body cache with one full listing. The delta link predates the
        // listing; anything changed between re-lists on the next round. An
        // incremental round reads what changed 20 to a $batch (fetchGraph).
        if (isGraph() && delta.complete && !delta.changed.isEmpty()) {
            Map<String, Card> byHandle = new HashMap<>();
            long listing = System.nanoTime();
            for (Card card : client.listCards(primary, account, url)) {
                byHandle.put(card.uri, card);
            }
            remote(System.nanoTime() - listing);
            graphCards.put(url, byHandle);
        }

        if (accountLevel) {
            return accountSnapshot(url, delta);
        }
        return snapshot(url, delta.changed, delta.vanished, delta.complete, delta.token);
    }

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate through the bridge: cards member of the book are its
     * items, and on an incremental round a changed card that left the
     * book (still held locally but no longer listing it) rides as
     * vanished.
     */
    private JSONObject accountSnapshot(String url, CardDelta delta) throws JSONException {
        JSONArray changed = new JSONArray();
        for (Card card : delta.changed) {
            changed.put(
                    new JSONObject()
                            .put("handle", card.uri)
                            .put("books", new JSONArray(card.books))
                            .put(
                                    "known",
                                    !delta.complete && offline.loadRow(url, card.uri) != null));
        }
        JSONObject facts = new JSONObject();
        facts.put("bookId", bookId(url));
        facts.put("complete", delta.complete);
        facts.put("changed", changed);
        facts.put("vanished", new JSONArray(delta.vanished));

        JSONObject projected = Cards.offlineAccountSnapshot(facts);
        List<Card> members = new ArrayList<>();
        JSONArray indexes = projected.optJSONArray("members");
        for (int at = 0; indexes != null && at < indexes.length(); at++) {
            members.add(delta.changed.get(indexes.optInt(at)));
        }
        List<String> vanished = new ArrayList<>();
        JSONArray gone = projected.optJSONArray("vanished");
        for (int at = 0; gone != null && at < gone.length(); at++) {
            vanished.add(gone.optString(at));
        }

        return snapshot(url, members, vanished, delta.complete, delta.token);
    }

    /** Builds an enumerate reply, recording the ETags for the 412 quirk. */
    private JSONObject snapshot(
            String url, List<Card> cards, List<String> vanished, boolean complete, String token)
            throws JSONException {
        Map<String, String> etags = new HashMap<>();
        JSONArray items = new JSONArray();
        for (Card card : cards) {
            JSONObject item = new JSONObject();
            item.put("handle", card.uri);
            if (card.etag != null) {
                item.put("revision", card.etag);
            }
            items.put(item);
            etags.put(card.uri, card.etag);
        }
        listedEtags.put(url, etags);
        listedComplete.put(url, complete);

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        reply.put("vanished", new JSONArray(vanished));
        reply.put("complete", complete);
        if (token != null) {
            reply.put("checkpoint", token);
        }
        return reply;
    }

    /**
     * Services a fetch yield: the full bodies of the given handles, each
     * with its link id (the vCard UID), the summary STORAGE Annex A
     * derives from it, and its content hash.
     */
    @Override
    protected JSONObject fetch(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        JSONArray handles = yielded.getJSONArray("handles");
        if (CardStore.isPhoneCollection(url)) {
            return phone.fetch(url, handles);
        }

        JSONArray items = new JSONArray();
        if (isGoogle()) {
            fetchGoogle(url, handles, items);
        } else if (PimalayaClient.isAccountLevel(account)) {
            for (int index = 0; index < handles.length(); index++) {
                String handle = handles.getString(index);
                Card card = deltaCards.get(handle);
                if (card == null) {
                    card = client.readCard(primary, account, url, handle);
                }
                items.put(fetchedItem(url, handle, card));
            }
        } else if (isGraph()) {
            fetchGraph(url, handles, items);
        } else {
            // NOTE: CardDAV multiget in chunks, matched back by resource
            // name.
            List<String> uris = new ArrayList<>(handles.length());
            for (int index = 0; index < handles.length(); index++) {
                uris.add(handles.getString(index));
            }
            for (int start = 0; start < uris.size(); start += MULTIGET_CHUNK) {
                List<String> chunk =
                        uris.subList(start, Math.min(start + MULTIGET_CHUNK, uris.size()));
                for (Card card : client.multigetCards(primary, account, url, chunk)) {
                    items.put(fetchedItem(url, card.uri, card));
                }
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return reply;
    }

    /**
     * The Google bodies of a fetch: what the pass's People round carried
     * first (it lists every changed contact whole), then everything it
     * does not hold in one batched read, 200 contacts to a {@code
     * people:batchGet}, where it used to be one {@code people.get} a
     * contact (a fetch after a push, which drops the round's bodies). A
     * contact gone since the round is left out of the reply, as a
     * multiget leaves out a resource it no longer finds.
     */
    private void fetchGoogle(String url, JSONArray handles, JSONArray items)
            throws JSONException {
        List<String> missing = new ArrayList<>();
        for (int index = 0; index < handles.length(); index++) {
            String handle = handles.getString(index);
            if (!deltaCards.containsKey(handle)) {
                missing.add(handle);
            }
        }

        Map<String, Card> read = new HashMap<>();
        if (!missing.isEmpty()) {
            long asked = System.nanoTime();
            for (Card card : client.readGoogleCards(primary, account, missing)) {
                read.put(card.uri, card);
            }
            remote(System.nanoTime() - asked);
        }

        for (int index = 0; index < handles.length(); index++) {
            String handle = handles.getString(index);
            Card card = deltaCards.get(handle);
            if (card == null) {
                card = read.get(handle);
            }
            if (card != null) {
                items.put(fetchedItem(url, handle, card));
            }
        }
    }

    /**
     * The Graph bodies of a fetch: the complete round's listing first,
     * then everything it does not hold in one batched read, 20 contacts
     * to a {@code $batch}, where it used to be one request a contact (a
     * bulk change elsewhere, an import or a merge, names hundreds at
     * once). A contact gone since the delta is left out of the reply, as
     * a multiget leaves out a resource it no longer finds.
     */
    private void fetchGraph(String url, JSONArray handles, JSONArray items)
            throws JSONException {
        Map<String, Card> cached = graphCards.get(url);
        List<String> missing = new ArrayList<>();
        for (int index = 0; index < handles.length(); index++) {
            String handle = handles.getString(index);
            if (cached == null || !cached.containsKey(handle)) {
                missing.add(handle);
            }
        }

        Map<String, Card> read = new HashMap<>();
        long took = 0;
        if (!missing.isEmpty()) {
            long asked = System.nanoTime();
            for (Card card : client.readGraphCards(primary, account, missing)) {
                read.put(card.uri, card);
            }
            took = System.nanoTime() - asked;
            remote(took);
        }

        int gone = 0;
        for (int index = 0; index < handles.length(); index++) {
            String handle = handles.getString(index);
            Card card = cached == null ? null : cached.get(handle);
            if (card == null) {
                card = read.get(handle);
            }
            if (card == null) {
                gone++;
                continue;
            }
            items.put(fetchedItem(url, handle, card));
        }

        Log.d(
                "pimalaya",
                "graph fetch " + url + ": " + handles.length() + " asked, "
                        + (handles.length() - missing.size()) + " from the listing, "
                        + missing.size() + " read in " + (missing.size() + 19) / 20
                        + " batches, remote " + took / 1_000_000 + " ms, " + gone + " gone");
    }

    /**
     * One fetched card on the engine wire. The recorded revision must
     * be the flavour the next enumerate compares against: posteo's
     * SabreDAV serves multiget ETags that differ from its listing
     * ETags for some cards (Google's people.get ETag likewise differs
     * from its listing's), and recording the fetch flavour ping-pongs
     * those cards through a refresh-and-refetch on every sync forever.
     * The ETag the pass's own enumerate listed wins; the fetched one
     * only stands in when the pass never listed the handle. Content
     * moving between the listing and the fetch records the older
     * listing ETag against the newer body, which reads as one more
     * refresh next sync and then converges.
     */
    private JSONObject fetchedItem(String url, String handle, Card card) throws JSONException {
        JSONObject index = Cards.indexCard(card.vcard);
        String uid = index.optString("uid");

        Map<String, String> listed = listedEtags.get(url);
        String listedEtag = listed == null ? null : listed.get(handle);
        String revision = listedEtag != null ? listedEtag : card.etag;

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", uid.isEmpty() ? handle : uid);
        // NOTE: the standard's summary, not the raw card index beside it: the
        // index is this app's projection and the summary is what any reader of
        // the store understands. Both come back from one derivation, so the
        // fetch path and the edit path cannot write different shapes.
        item.put("summary", index.optJSONObject("summary"));
        item.put("sortKey", index.optString("sortKey"));
        item.put("hash", CardStore.byteHash(card.vcard));
        item.put("body", card.vcard);
        if (revision != null) {
            item.put("revision", revision);
        }
        return item;
    }

    /**
     * Services a push yield: creates, content updates, deletes and
     * membership patches, one result per change. An optimistic
     * concurrency failure (HTTP 412) retries unguarded when the last
     * enumerate proves the remote unchanged (some servers, posteo's
     * SabreDAV among them, serve listing ETags their If-Match never
     * matches), and reports the change rejected otherwise so the next
     * sync reconciles it; any other failure aborts the pass (offline
     * fallback).
     */
    @Override
    protected JSONObject push(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        JSONArray changes = yielded.getJSONArray("changes");
        if (CardStore.isPhoneCollection(url)) {
            step(Progress.STAGE_PROJECT, changes.length());
            JSONObject reply = phone.push(url, changes);
            tallyPushes(url, changes, reply.getJSONArray("results"));
            return reply;
        }
        step(Progress.STAGE_UPLOAD, changes.length());

        // NOTE: CardDAV has no batch verb, so the round parallelizes; each
        // change is one guarded round trip on its own transport, dividing
        // the wall clock by the pool on a latency-bound link.
        boolean concurrent =
                changes.length() > 1
                        && !PimalayaClient.isAccountLevel(account)
                        && !isGraph();

        JSONArray results;
        try {
            if (isGoogle() && changes.length() > 1) {
                results = pushGoogle(url, changes);
            } else if ((isJmap() || isGraph()) && changes.length() > 1) {
                results = pushRound(url, changes);
            } else {
                results = concurrent ? pushAll(url, changes) : pushEach(url, changes);
            }
        } finally {
            // NOTE: drop the pass caches once per round, not per change; no
            // fetch interleaves a push, and the workers must not race them.
            invalidate(url);
        }
        tallyPushes(url, changes, results);

        JSONObject reply = new JSONObject();
        reply.put("results", results);
        return reply;
    }

    /** One round's changes, sequentially (the batch-verb backends). */
    private JSONArray pushEach(String url, JSONArray changes) throws JSONException {
        JSONArray results = new JSONArray();
        for (int index = 0; index < changes.length(); index++) {
            results.put(pushOne(primary, url, changes.getJSONObject(index)));
        }
        return results;
    }

    /**
     * One round's changes, concurrently over a small pool (CardDAV).
     * Results keep the change order; the first hard failure aborts the
     * round like its sequential counterpart, dropping the still-flying
     * requests with it (the next sync reconciles whatever landed).
     *
     * <p>One worker per connection rather than one task per connection: a
     * transport serves one caller at a time, so the workers drain a
     * shared queue on their own, and a round of forty cards costs four
     * connections rather than forty.
     */
    private JSONArray pushAll(String url, JSONArray changes) throws JSONException {
        // NOTE: prime the book map single-threaded; the workers only read.
        bookId(url);

        int workers = Math.min(PUSH_CONCURRENCY, changes.length());
        JSONObject[] pushed = new JSONObject[changes.length()];
        AtomicInteger next = new AtomicInteger();

        ExecutorService pool = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> running = new ArrayList<>(workers);
            for (int worker = 0; worker < workers; worker++) {
                running.add(
                        pool.submit(
                                () -> {
                                    try (Transport own = new Transport()) {
                                        for (int index = next.getAndIncrement();
                                                index < changes.length();
                                                index = next.getAndIncrement()) {
                                            pushed[index] =
                                                    pushOne(
                                                            own,
                                                            url,
                                                            changes.getJSONObject(index));
                                        }
                                    }
                                    return null;
                                }));
            }
            for (Future<?> future : running) {
                future.get();
            }

            JSONArray results = new JSONArray();
            for (JSONObject result : pushed) {
                results.put(result);
            }
            return results;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(interrupted);
        } catch (ExecutionException failure) {
            Throwable cause = failure.getCause();
            if (cause instanceof JSONException) {
                throw (JSONException) cause;
            }
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new IllegalStateException(cause);
        } finally {
            pool.shutdownNow();
        }
    }

    /**
     * One Google round: the genuine creates and deletes group into the
     * People batch verbs (200 and 500 a call, one write-quota unit per
     * call instead of one per card, which throttled large rounds);
     * membership patches and guarded updates stay per-card. Results
     * keep the change order, and a failed batch aborts the round like
     * a failed per-card call would.
     */
    private JSONArray pushGoogle(String url, JSONArray changes) throws JSONException {
        JSONObject[] results = new JSONObject[changes.length()];

        List<Integer> creates = new ArrayList<>();
        List<String> createVcards = new ArrayList<>();
        List<JSONArray> createBooks = new ArrayList<>();
        List<Integer> removes = new ArrayList<>();
        List<String> removeIds = new ArrayList<>();

        for (int index = 0; index < changes.length(); index++) {
            JSONObject change = changes.getJSONObject(index);
            String op = change.getString("op");
            String handle = change.getString("handle");

            if ("add".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, false, null, null);
                    continue;
                }
                JSONObject plan = pushPlan("add", url, !change.isNull("origin"), false);
                if ("membership".equals(plan.getString("action"))) {
                    client.updateCardBooks(
                            primary, account, row.getString("id"), List.of(bookId(url)), List.of());
                    results[index] = result(handle, true, handle, null);
                    continue;
                }
                creates.add(index);
                createVcards.add(row.getString("vcard"));
                createBooks.add(plan.optJSONArray("postCreateBooks"));
            } else if ("remove".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                JSONObject plan = pushPlan("remove", url, false, row.optBoolean("deleted"));
                if ("membership".equals(plan.getString("action"))) {
                    client.updateCardBooks(
                            primary, account, row.getString("id"), List.of(), List.of(bookId(url)));
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                removes.add(index);
                removeIds.add(row.getString("id"));
            } else {
                results[index] = pushOne(primary, url, change);
            }
        }

        if (!creates.isEmpty()) {
            List<Card> created = client.createCards(primary, account, createVcards);
            for (int at = 0; at < creates.size(); at++) {
                Card card = created.get(at);
                int index = creates.get(at);

                JSONArray postCreate = createBooks.get(at);
                for (int book = 0; postCreate != null && book < postCreate.length(); book++) {
                    client.updateCardBooks(
                            primary, account, card.id, List.of(postCreate.optString(book)), List.of());
                }
                results[index] =
                        result(
                                changes.getJSONObject(index).getString("handle"),
                                true,
                                card.uri,
                                card.etag);
            }
        }

        if (!removes.isEmpty()) {
            client.deleteCards(primary, account, removeIds);
            for (int index : removes) {
                results[index] =
                        result(changes.getJSONObject(index).getString("handle"), true, null, null);
            }
        }

        JSONArray ordered = new JSONArray();
        for (JSONObject result : results) {
            ordered.put(result);
        }
        return ordered;
    }

    /**
     * One JMAP or Graph round through the bridge's batch seam: JMAP
     * folds the whole round (creates, content updates, membership
     * patches, destroys) into ContactCard/set calls, Graph into $batch
     * calls of twenty, both answering one outcome per change, so a
     * rejected card no longer aborts its round. Results keep the
     * change order.
     */
    private JSONArray pushRound(String url, JSONArray changes) throws JSONException {
        JSONObject[] results = new JSONObject[changes.length()];
        Map<String, Integer> indexByRef = new HashMap<>();
        JSONArray batch = new JSONArray();

        for (int index = 0; index < changes.length(); index++) {
            JSONObject change = changes.getJSONObject(index);
            String op = change.getString("op");
            String handle = change.getString("handle");

            if ("add".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, false, null, null);
                    continue;
                }
                JSONObject plan = pushPlan("add", url, !change.isNull("origin"), false);
                JSONObject item = new JSONObject();
                item.put("ref", handle);
                if ("membership".equals(plan.getString("action"))) {
                    item.put("op", "books");
                    item.put("id", row.getString("id"));
                    item.put("add", new JSONArray().put(bookId(url)));
                } else {
                    item.put("op", "create");
                    item.put("vcard", row.getString("vcard"));
                }
                indexByRef.put(handle, index);
                batch.put(item);
            } else if ("update".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, false, null, null);
                    continue;
                }
                JSONObject item = new JSONObject();
                item.put("ref", handle);
                item.put("op", "update");
                item.put("id", row.getString("id"));
                item.put("vcard", row.getString("vcard"));
                if (!row.isNull("baseVcard")) {
                    item.put("baseVcard", row.getString("baseVcard"));
                }
                indexByRef.put(handle, index);
                batch.put(item);
            } else if ("remove".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                JSONObject plan = pushPlan("remove", url, false, row.optBoolean("deleted"));
                JSONObject item = new JSONObject();
                item.put("ref", handle);
                item.put("id", row.getString("id"));
                if ("membership".equals(plan.getString("action"))) {
                    item.put("op", "books");
                    item.put("remove", new JSONArray().put(bookId(url)));
                } else {
                    item.put("op", "destroy");
                }
                indexByRef.put(handle, index);
                batch.put(item);
            } else {
                // NOTE: no flag pushes on any contacts backend.
                results[index] = result(handle, true, null, null);
            }
        }

        if (batch.length() > 0) {
            JSONArray replies = client.pushCards(primary, account, url, batch);
            for (int at = 0; at < replies.length(); at++) {
                JSONObject reply = replies.getJSONObject(at);
                String ref = reply.getString("ref");
                Integer index = indexByRef.get(ref);
                if (index == null) {
                    continue;
                }

                boolean accepted = reply.optBoolean("accepted");
                if (!accepted && !reply.isNull("error")) {
                    Log.w("pimalaya", "push rejected for " + ref + ": " + reply.optString("error"));
                }

                // NOTE: only an add renames its handle, to the created id
                // or (on a membership add) the handle itself.
                String assigned = null;
                if ("add".equals(changes.getJSONObject(index).getString("op")) && accepted) {
                    assigned = reply.isNull("id") ? ref : reply.getString("id");
                }
                String revision = reply.isNull("etag") ? null : reply.getString("etag");
                results[index] = result(ref, accepted, assigned, revision);
            }

            // NOTE: an unanswered ref counts rejected, so the next sync
            // reconciles it rather than trusting silence.
            for (Map.Entry<String, Integer> entry : indexByRef.entrySet()) {
                if (results[entry.getValue()] == null) {
                    results[entry.getValue()] = result(entry.getKey(), false, null, null);
                }
            }
        }

        JSONArray ordered = new JSONArray();
        for (JSONObject result : results) {
            ordered.put(result);
        }
        return ordered;
    }

    /** Dispatches one push change to its verb. */
    private JSONObject pushOne(Transport transport, String url, JSONObject change)
            throws JSONException {
        switch (change.getString("op")) {
            case "add":
                return pushAdd(transport, url, change);
            case "update":
                return pushUpdate(transport, url, change);
            case "remove":
                return pushRemove(transport, url, change);
            default:
                // NOTE: no flag pushes on any contacts backend.
                return result(change.getString("handle"), true, null, null);
        }
    }

    /**
     * Tallies the accepted pushes into the sync report: an accepted
     * add is a card in, a remove a card out, an update a card changed.
     */
    private void tallyPushes(String url, JSONArray changes, JSONArray results)
            throws JSONException {
        if (tally == null) {
            return;
        }

        for (int index = 0; index < changes.length() && index < results.length(); index++) {
            if (!results.getJSONObject(index).optBoolean("accepted")) {
                continue;
            }
            JSONObject change = changes.getJSONObject(index);
            String kind;
            switch (change.getString("op")) {
                case "add":
                    kind = "created";
                    break;
                case "update":
                    kind = "changed";
                    break;
                case "remove":
                    kind = "removed";
                    break;
                default:
                    continue;
            }
            tally.tally(url, change.getString("handle"), kind);
        }
    }

    /**
     * Pushes a pending create as the bridge plans it: a membership
     * patch when the body already lives on the account (add with an
     * origin), a genuine create otherwise, with any post-create
     * membership patch the backend needs riding along.
     */
    private JSONObject pushAdd(Transport transport, String url, JSONObject change)
            throws JSONException {
        String handle = change.getString("handle");
        JSONObject row = offline.loadRow(url, handle);
        if (row == null) {
            return result(handle, false, null, null);
        }

        JSONObject plan = pushPlan("add", url, !change.isNull("origin"), false);
        if ("membership".equals(plan.getString("action"))) {
            client.updateCardBooks(
                    transport, account, row.getString("id"), List.of(bookId(url)), List.of());
            // The body is already on the account, so the member this book now
            // holds is the one the origin names: assigning the provisional
            // handle instead would bind the book to a name the server never
            // heard of, and the next enumerate would read it as vanished.
            JSONObject origin = change.optJSONObject("origin");
            String assigned = origin == null ? row.getString("id") : origin.getString("handle");
            return result(handle, true, assigned, null);
        }

        // NOTE: the resource name, not the handle: a staged create sits under
        // the provisional handle until this push assigns a real one (SYNC §2),
        // and io-webdav names the resource verbatim now instead of appending
        // .vcf itself, so what goes out is the name this side calls the card
        // by. Sending the handle would file it under a control character.
        String name = CardStore.resourceName(url, PimdirStorage.nameOf(handle));
        Card created = client.createCard(transport, account, url, name, row.getString("vcard"));

        JSONArray postCreate = plan.optJSONArray("postCreateBooks");
        for (int index = 0; postCreate != null && index < postCreate.length(); index++) {
            client.updateCardBooks(
                    transport, account, created.id, List.of(postCreate.optString(index)), List.of());
        }

        return result(handle, true, created.uri, created.etag);
    }

    /** Pushes a staged content edit, guarded by the base revision. */
    private JSONObject pushUpdate(Transport transport, String url, JSONObject change)
            throws JSONException {
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        JSONObject row = offline.loadRow(url, handle);
        if (row == null) {
            return result(handle, false, null, null);
        }
        String baseVcard = row.isNull("baseVcard") ? null : row.getString("baseVcard");

        try {
            Card updated =
                    client.updateCard(
                            transport,
                            account,
                            url,
                            new Card(row.getString("id"), handle, ifMatch, row.getString("vcard")),
                            baseVcard);
            return result(handle, true, null, updated.etag);
        } catch (RuntimeException failure) {
            if (!isPreconditionFailure(failure)) {
                throw failure;
            }
            if (!listingUnchanged(url, handle, ifMatch)) {
                return result(handle, false, null, null);
            }

            // NOTE: the enumerate moments ago proves the remote unchanged,
            // so the unguarded write carries the same guarantee.
            Log.w(
                    "pimalaya",
                    "retrying push unguarded, If-Match rejected but listing "
                            + "unchanged: " + handle);
            Card updated =
                    client.updateCard(
                            transport,
                            account,
                            url,
                            new Card(row.getString("id"), handle, null, row.getString("vcard")),
                            baseVcard);
            return result(handle, true, null, updated.etag);
        }
    }

    /**
     * Pushes a staged removal as the bridge plans it: a membership
     * patch when the card is not deleted on an account-level backend,
     * the card's deletion otherwise.
     */
    private JSONObject pushRemove(Transport transport, String url, JSONObject change)
            throws JSONException {
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        JSONObject row = offline.loadRow(url, handle);
        if (row == null) {
            return result(handle, true, null, null);
        }

        JSONObject plan = pushPlan("remove", url, false, row.optBoolean("deleted"));
        if ("membership".equals(plan.getString("action"))) {
            client.updateCardBooks(
                    transport, account, row.getString("id"), List.of(), List.of(bookId(url)));
            return result(handle, true, null, null);
        }

        try {
            client.deleteCard(
                    transport, account, url, new Card(row.getString("id"), handle, ifMatch, ""));
        } catch (RuntimeException failure) {
            if (isGone(failure)) {
                // NOTE: already deleted upstream; the removal converged.
            } else if (!isPreconditionFailure(failure)) {
                throw failure;
            } else if (!listingUnchanged(url, handle, ifMatch)) {
                return result(handle, false, null, null);
            } else {
                Log.w(
                        "pimalaya",
                        "retrying delete unguarded, If-Match rejected but listing "
                                + "unchanged: " + handle);
                client.deleteCard(
                        transport, account, url, new Card(row.getString("id"), handle, null, ""));
            }
        }
        return result(handle, true, null, null);
    }

    /** How many resource names one addressbook-multiget carries. */
    private static final int MULTIGET_CHUNK = 50;

    /** How many CardDAV pushes fly concurrently (one request per card). */
    private static final int PUSH_CONCURRENCY = 4;

    /** Drops the pass caches after a push changed the remote. */
    private void invalidate(String url) {
        deltaCards.clear();
        accountDeltas.clear();
        graphCards.remove(url);
    }

    /** The book id behind a collection URL (membership patches address it). */
    private String bookId(String url) {
        if (idByUrl == null) {
            idByUrl = new HashMap<>();
            for (BookEntry entry : base.loadAllAddressbooks()) {
                idByUrl.put(entry.book.url, entry.book.id);
            }
        }
        return idByUrl.get(url);
    }

    /** One push change's bridge plan. */
    private JSONObject pushPlan(String op, String url, boolean origin, boolean deleted)
            throws JSONException {
        JSONObject facts = new JSONObject();
        facts.put("op", op);
        facts.put("collection", url);
        facts.put("bookId", bookId(url));
        facts.put("origin", origin);
        facts.put("deleted", deleted);
        return Cards.offlinePushPlan(facts);
    }

    /**
     * Whether the last enumerate proves the handle unchanged at the
     * staged base revision, decided by the bridge over the recorded
     * listing.
     */
    private boolean listingUnchanged(String url, String handle, String ifMatch) {
        try {
            JSONObject facts = new JSONObject();
            Map<String, String> etags = listedEtags.get(url);
            if (etags != null) {
                facts.put("listed", new JSONObject(etags));
            }
            facts.put("complete", Boolean.TRUE.equals(listedComplete.get(url)));
            facts.put("handle", handle);
            facts.put("ifMatch", ifMatch);
            return Cards.offlineRetryUnguarded(facts);
        } catch (JSONException error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean isGraph() {
        return account != null && PimalayaClient.isGraph(account);
    }

    private boolean isGoogle() {
        return account != null && PimalayaClient.isGoogle(account);
    }

    private boolean isJmap() {
        return account != null
                && PimalayaClient.isAccountLevel(account)
                && !PimalayaClient.isGoogle(account);
    }

}
