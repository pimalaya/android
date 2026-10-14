package org.pimalaya.contact;

import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.pimalaya.account.PimDomain;
import org.pimalaya.client.Account;
import org.pimalaya.client.AccountClient;
import org.pimalaya.client.AccountSnapshot;
import org.pimalaya.client.AccountSnapshotFacts;
import org.pimalaya.client.BookCard;
import org.pimalaya.client.BooksUpdate;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardDelta;
import org.pimalaya.client.CardIndex;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ConflictForm;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.NewCard;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PushAction;
import org.pimalaya.client.PushChange;
import org.pimalaya.client.PushOp;
import org.pimalaya.client.PushOutcome;
import org.pimalaya.client.PushPlan;
import org.pimalaya.client.PushPlanFacts;
import org.pimalaya.client.RetryFacts;
import org.pimalaya.client.SnapshotCard;
import org.pimalaya.client.Transport;
import org.pimalaya.pimdir.PimdirDb;
import org.pimalaya.pimdir.PimdirStorage;
import org.pimalaya.pimdir.PimdirSummary;
import org.pimalaya.pimdir.PimdirEngine;

/**
 * One account's offline driver: the Rust bridge runs the engine's
 * coroutines and this class services their yields, storage against the
 * pimdir store ({@link PimdirStorage}) and remote against the backend
 * clients; a book's phone collection is its phone pass's
 * ({@link ContactPhonePass}), against its raw contacts. Every server
 * backend enumerates incrementally from its stored cursor (CardDAV
 * sync-collection, Graph
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
public final class ContactEngine extends PimdirEngine {
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

    /** The book's phone pass, against its raw contacts. */
    private final ContactPhonePass phone;

    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

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
    public static final class Report {
        public final Set<String> localIn = new HashSet<>();
        public final Set<String> localOut = new HashSet<>();
        public final Set<String> localChanged = new HashSet<>();
        public final Set<String> remoteIn = new HashSet<>();
        public final Set<String> remoteOut = new HashSet<>();
        public final Set<String> remoteChanged = new HashSet<>();
        public int conflicts;

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

        /** Tallies a batch of write effects into the report, when a sync runs one. */
        static void tallyEffects(Report tally, JSONArray effects) throws JSONException {
            for (int index = 0; tally != null && index < effects.length(); index++) {
                JSONObject effect = effects.getJSONObject(index);
                tally.tally(
                        effect.getString("collection"),
                        effect.getString("handle"),
                        effect.getString("kind"));
            }
        }

        /**
         * Tallies the accepted pushes into the sync report: an accepted
         * add is a card in, a remove a card out, an update a card changed.
         */
        static void tallyPushes(Report tally, String url, JSONArray changes, JSONArray results)
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
    }

    /**
     * Builds a driver for one account; a null account services storage
     * yields only (local mutations never touch the remote).
     */
    public ContactEngine(
            CardStore base,
            PimdirDb pimdir,
            Clients clients,
            Transport primary,
            Account account) {
        super(pimdir, clients);
        this.base = base;
        this.pimdir = pimdir;
        this.primary = primary;
        this.account = account;
        this.phone = new ContactPhonePass(pimdir, clients);
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
    public Report syncBook(String url, boolean remote) throws JSONException {
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
        Log.d(
                "pimalaya",
                "server sync " + url + ": " + clients.offline().sync(this, url, false));
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
            clients.offline().sync(this, url, false);
            hydrate(url);
        }
    }

    /** The book's phone pass, told through this pass's progress. */
    private void syncPhone(String url, Report report) throws JSONException {
        phone.progress = progress;
        phone.sync(url, report);
    }

    /** The book's process-wide sync lock, keyed by its collection URL. */
    private ReentrantLock syncLock(String url) {
        return locks.collection(PimdirSummary.CONTACT, url);
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
        ConflictForm resolution =
                ContactClient.mergeConflictForm(baseVcard, local, conflict.getString("remoteVcard"));
        if (!resolution.resolved()) {
            return false;
        }

        ContactStager.edit(this, url, conflict.getString("handle"), resolution.vcard());
        return true;
    }

    @Override
    protected void staged(String collection) {
        ContactStager.staged(pimdir, collection);
    }

    @Override
    protected void applied(JSONArray effects) throws JSONException {
        Report.tallyEffects(tally, effects);
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
        serverOnly(url);
        String cursor = yielded.isNull("cursor") ? null : yielded.getString("cursor");

        boolean accountLevel = AccountClient.isAccountLevel(account);
        CardDelta delta = accountLevel ? accountDeltas.get(cursor) : null;
        if (delta == null) {
            long asked = System.nanoTime();
            delta = clients.contact().syncCards(primary, account, url, cursor);
            remote(System.nanoTime() - asked);
            if (accountLevel) {
                accountDeltas.put(cursor, delta);
            }
        }
        listed(delta.changed().size());

        for (Card card : delta.changed()) {
            if (!card.vcard().isEmpty()) {
                deltaCards.put(card.uri(), card);
            }
        }

        // NOTE: Graph delta rows carry no body, so a complete round primes
        // the body cache with one full listing. The delta link predates the
        // listing; anything changed between re-lists on the next round. An
        // incremental round reads what changed 20 to a $batch (fetchGraph).
        if (isGraph() && delta.complete() && !delta.changed().isEmpty()) {
            Map<String, Card> byHandle = new HashMap<>();
            long listing = System.nanoTime();
            for (Card card : clients.contact().listCards(primary, account, url)) {
                byHandle.put(card.uri(), card);
            }
            remote(System.nanoTime() - listing);
            graphCards.put(url, byHandle);
        }

        if (accountLevel) {
            return accountSnapshot(url, delta);
        }
        return snapshot(url, delta);
    }

    /**
     * Projects an account-wide delta (JMAP, Google) onto one book's
     * enumerate through the bridge: cards member of the book are its
     * items, and on an incremental round a changed card that left the
     * book (still held locally but no longer listing it) rides as
     * vanished.
     */
    private JSONObject accountSnapshot(String url, CardDelta delta) throws JSONException {
        List<SnapshotCard> changed = new ArrayList<>();
        for (Card card : delta.changed()) {
            changed.add(
                    new SnapshotCard(
                            card.uri(),
                            card.books(),
                            !delta.complete() && offline.loadRow(url, card.uri()) != null));
        }
        AccountSnapshotFacts facts =
                new AccountSnapshotFacts(bookId(url), delta.complete(), changed, delta.vanished());

        AccountSnapshot projected = OfflineClient.accountSnapshot(facts);
        List<Card> members = new ArrayList<>();
        for (int index : projected.members()) {
            members.add(delta.changed().get(index));
        }
        List<String> vanished = projected.vanished();

        return snapshot(url, new CardDelta(members, vanished, delta.token(), delta.complete()));
    }

    /** Builds an enumerate reply, recording the ETags for the 412 quirk. */
    private JSONObject snapshot(String url, CardDelta delta) throws JSONException {
        List<Card> cards = delta.changed();
        List<String> vanished = delta.vanished();
        boolean complete = delta.complete();
        String token = delta.token();

        Map<String, String> etags = new HashMap<>();
        JSONArray items = new JSONArray();
        for (Card card : cards) {
            JSONObject item = new JSONObject();
            item.put("handle", card.uri());
            if (card.etag() != null) {
                item.put("revision", card.etag());
            }
            items.put(item);
            etags.put(card.uri(), card.etag());
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
     * with its link id (the vCard UID, or the identity a bound handle is
     * already filed under: {@link #keepBoundNames}), the summary STORAGE
     * Annex A derives from it, and its content hash.
     */
    @Override
    protected JSONObject fetch(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        JSONArray handles = yielded.getJSONArray("handles");
        serverOnly(url);

        JSONArray items = new JSONArray();
        if (isGoogle()) {
            fetchGoogle(url, handles, items);
        } else if (AccountClient.isAccountLevel(account)) {
            for (int index = 0; index < handles.length(); index++) {
                String handle = handles.getString(index);
                Card card = deltaCards.get(handle);
                if (card == null) {
                    card = clients.contact().readCard(primary, account, url, handle);
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
                for (Card card : clients.contact().multigetCards(primary, account, url, chunk)) {
                    items.put(fetchedItem(url, card.uri(), card));
                }
            }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return keepBoundNames(url, reply);
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
            for (Card card : clients.contact().readGoogleCards(primary, account, missing)) {
                read.put(card.uri(), card);
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
            for (Card card : clients.contact().readGraphCards(primary, account, missing)) {
                read.put(card.uri(), card);
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
        CardIndex index = ContactClient.indexCard(card.vcard());
        String uid = index.uid();

        Map<String, String> listed = listedEtags.get(url);
        String listedEtag = listed == null ? null : listed.get(handle);
        String revision = listedEtag != null ? listedEtag : card.etag();

        JSONObject item = new JSONObject();
        item.put("handle", handle);
        item.put("linkId", uid.isEmpty() ? handle : uid);
        // NOTE: the standard's summary, not the raw card index beside it: the
        // index is this app's projection and the summary is what any reader of
        // the store understands. Both come back from one derivation, so the
        // fetch path and the edit path cannot write different shapes.
        item.put("summary", index.summary());
        item.put("sortKey", index.sortKey());
        item.put("hash", CardStore.byteHash(card.vcard()));
        item.put("body", card.vcard());
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
        serverOnly(url);
        step(Progress.STAGE_UPLOAD, changes.length());

        // NOTE: CardDAV has no batch verb, so the round parallelizes; each
        // change is one guarded round trip on its own transport, dividing
        // the wall clock by the pool on a latency-bound link.
        boolean concurrent =
                changes.length() > 1
                        && !AccountClient.isAccountLevel(account)
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
        Report.tallyPushes(tally, url, changes, results);

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
        List<List<String>> createBooks = new ArrayList<>();
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
                PushPlan plan =
                        pushPlan(PushPlanFacts.Op.ADD, url, !change.isNull("origin"), false);
                if (plan.action() == PushAction.MEMBERSHIP) {
                    clients.contact().updateCardBooks(
                            primary,
                            account,
                            new BooksUpdate(
                                    row.getString("id"), List.of(bookId(url)), List.of()));
                    results[index] = result(handle, true, handle, null);
                    continue;
                }
                creates.add(index);
                createVcards.add(row.getString("vcard"));
                createBooks.add(plan.postCreateBooks());
            } else if ("remove".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                PushPlan plan =
                        pushPlan(PushPlanFacts.Op.REMOVE, url, false, row.optBoolean("deleted"));
                if (plan.action() == PushAction.MEMBERSHIP) {
                    clients.contact().updateCardBooks(
                            primary,
                            account,
                            new BooksUpdate(
                                    row.getString("id"), List.of(), List.of(bookId(url))));
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
            List<Card> created = clients.contact().createCards(primary, account, createVcards);
            for (int at = 0; at < creates.size(); at++) {
                Card card = created.get(at);
                int index = creates.get(at);

                List<String> postCreate = createBooks.get(at);
                for (int book = 0; postCreate != null && book < postCreate.size(); book++) {
                    clients.contact().updateCardBooks(
                            primary,
                            account,
                            new BooksUpdate(
                                    card.id(), List.of(postCreate.get(book)), List.of()));
                }
                results[index] =
                        result(
                                changes.getJSONObject(index).getString("handle"),
                                true,
                                card.uri(),
                                card.etag());
            }
        }

        if (!removes.isEmpty()) {
            clients.contact().deleteCards(primary, account, removeIds);
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
        List<PushChange> batch = new ArrayList<>();

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
                PushPlan plan =
                        pushPlan(PushPlanFacts.Op.ADD, url, !change.isNull("origin"), false);
                PushChange item;
                if (plan.action() == PushAction.MEMBERSHIP) {
                    item =
                            new PushChange(
                                    handle,
                                    PushOp.BOOKS,
                                    row.getString("id"),
                                    null,
                                    null,
                                    Collections.singletonList(bookId(url)),
                                    List.of());
                } else {
                    item =
                            new PushChange(
                                    handle,
                                    PushOp.CREATE,
                                    null,
                                    row.getString("vcard"),
                                    null,
                                    List.of(),
                                    List.of());
                }
                indexByRef.put(handle, index);
                batch.add(item);
            } else if ("update".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, false, null, null);
                    continue;
                }
                PushChange item =
                        new PushChange(
                                handle,
                                PushOp.UPDATE,
                                row.getString("id"),
                                row.getString("vcard"),
                                row.isNull("baseVcard") ? null : row.getString("baseVcard"),
                                List.of(),
                                List.of());
                indexByRef.put(handle, index);
                batch.add(item);
            } else if ("remove".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                PushPlan plan =
                        pushPlan(PushPlanFacts.Op.REMOVE, url, false, row.optBoolean("deleted"));
                PushChange item;
                if (plan.action() == PushAction.MEMBERSHIP) {
                    item =
                            new PushChange(
                                    handle,
                                    PushOp.BOOKS,
                                    row.getString("id"),
                                    null,
                                    null,
                                    List.of(),
                                    Collections.singletonList(bookId(url)));
                } else {
                    item =
                            new PushChange(
                                    handle,
                                    PushOp.DESTROY,
                                    row.getString("id"),
                                    null,
                                    null,
                                    List.of(),
                                    List.of());
                }
                indexByRef.put(handle, index);
                batch.add(item);
            } else {
                // NOTE: no flag pushes on any contacts backend.
                results[index] = result(handle, true, null, null);
            }
        }

        if (!batch.isEmpty()) {
            List<PushOutcome> replies = clients.contact().pushCards(primary, account, url, batch);
            for (PushOutcome reply : replies) {
                String ref = reply.ref();
                Integer index = indexByRef.get(ref);
                if (index == null) {
                    continue;
                }

                boolean accepted = reply.accepted();
                if (!accepted && reply.error() != null) {
                    Log.w("pimalaya", "push rejected for " + ref + ": " + reply.error());
                }

                // NOTE: only an add renames its handle, to the created id
                // or (on a membership add) the handle itself.
                String assigned = null;
                if ("add".equals(changes.getJSONObject(index).getString("op")) && accepted) {
                    assigned = reply.id() == null ? ref : reply.id();
                }
                String revision = reply.etag();
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

        PushPlan plan = pushPlan(PushPlanFacts.Op.ADD, url, !change.isNull("origin"), false);
        if (plan.action() == PushAction.MEMBERSHIP) {
            clients.contact().updateCardBooks(
                    transport,
                    account,
                    new BooksUpdate(
                            row.getString("id"), List.of(bookId(url)), List.of()));
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
        Card created =
                clients.contact().createCard(
                        transport, account, url, new NewCard(name, row.getString("vcard")));

        List<String> postCreate = plan.postCreateBooks();
        for (int index = 0; postCreate != null && index < postCreate.size(); index++) {
            clients.contact().updateCardBooks(
                    transport,
                    account,
                    new BooksUpdate(
                            created.id(), List.of(postCreate.get(index)), List.of()));
        }

        return result(
                handle, true, created.uri(), landed(transport, url, created.uri(), created.etag()));
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
                    clients.contact().updateCard(
                            transport,
                            account,
                            new BookCard(
                                    url,
                                    new Card(
                                            row.getString("id"),
                                            handle,
                                            ifMatch,
                                            row.getString("vcard"))),
                            baseVcard);
            return result(handle, true, null, landed(transport, url, handle, updated.etag()));
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
                    clients.contact().updateCard(
                            transport,
                            account,
                            new BookCard(
                                    url,
                                    new Card(
                                            row.getString("id"),
                                            handle,
                                            null,
                                            row.getString("vcard"))),
                            baseVcard);
            return result(handle, true, null, landed(transport, url, handle, updated.etag()));
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

        PushPlan plan = pushPlan(PushPlanFacts.Op.REMOVE, url, false, row.optBoolean("deleted"));
        if (plan.action() == PushAction.MEMBERSHIP) {
            clients.contact().updateCardBooks(
                    transport,
                    account,
                    new BooksUpdate(
                            row.getString("id"), List.of(), List.of(bookId(url))));
            return result(handle, true, null, null);
        }

        try {
            clients.contact().deleteCard(
                    transport,
                    account,
                    new BookCard(
                            url, new Card(row.getString("id"), handle, ifMatch, "")));
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
                clients.contact().deleteCard(
                        transport,
                        account,
                        new BookCard(
                                url, new Card(row.getString("id"), handle, null, "")));
            }
        }
        return result(handle, true, null, null);
    }

    /**
     * The revision a CardDAV write landed at: the ETag the server answered,
     * else the one it holds the card at now, read back. The other backends
     * answer theirs.
     *
     * <p>A server storing something other than what it was sent answers no
     * ETag, as CalDAV's RFC 4791 section 5.3.4 has it, and a base kept with
     * no revision reads the next listing's as a change made elsewhere, which
     * undoes a removal staged before it: the card is pulled back rather than
     * deleted. A read that fails leaves the revision unknown, as before,
     * rather than failing a write that landed.
     */
    private String landed(Transport transport, String url, String uri, String answered) {
        if (answered != null || !CardStore.isCarddavUrl(url)) {
            return answered;
        }
        try {
            List<Card> read = clients.contact().multigetCards(transport, account, url, List.of(uri));
            return read.isEmpty() ? null : read.get(0).etag();
        } catch (RuntimeException failure) {
            Log.w("pimalaya", "revision of " + uri + " not read back", failure);
            return null;
        }
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
                idByUrl.put(entry.book.url(), entry.book.id());
            }
        }
        return idByUrl.get(url);
    }

    /** One push change's bridge plan. */
    private PushPlan pushPlan(PushPlanFacts.Op op, String url, boolean origin, boolean deleted) {
        return OfflineClient.pushPlan(new PushPlanFacts(op, url, bookId(url), origin, deleted));
    }

    /**
     * Whether the last enumerate proves the handle unchanged at the
     * staged base revision, decided by the bridge over the recorded
     * listing.
     */
    private boolean listingUnchanged(String url, String handle, String ifMatch) {
        return OfflineClient.retryUnguarded(
                new RetryFacts(
                        listedEtags.get(url),
                        Boolean.TRUE.equals(listedComplete.get(url)),
                        handle,
                        ifMatch));
    }

    private boolean isGraph() {
        return account != null && AccountClient.isGraph(account);
    }

    private boolean isGoogle() {
        return account != null && AccountClient.isGoogle(account);
    }

    private boolean isJmap() {
        return account != null
                && AccountClient.isAccountLevel(account)
                && !AccountClient.isGoogle(account);
    }

}
