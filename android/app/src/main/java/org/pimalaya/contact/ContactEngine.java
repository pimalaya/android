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
import org.pimalaya.client.AccountSnapshot;
import org.pimalaya.client.AccountSnapshotFacts;
import org.pimalaya.client.BookCard;
import org.pimalaya.client.BooksUpdate;
import org.pimalaya.client.Card;
import org.pimalaya.client.CardDelta;
import org.pimalaya.client.CardFetch;
import org.pimalaya.client.CardIndex;
import org.pimalaya.client.Clients;
import org.pimalaya.client.ConflictForm;
import org.pimalaya.client.ContactClient;
import org.pimalaya.client.NewCard;
import org.pimalaya.client.OfflineClient;
import org.pimalaya.client.PimalayaException;
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
 * One account's contact driver: serves the Rust engine's yields.
 *
 * <p>Storage against {@link PimdirStorage}, remote against the backend
 * clients, incrementally from each cursor; JMAP and Google's account-wide
 * deltas project onto each book. Blocking: run it off the main thread.
 */
public final class ContactEngine extends PimdirEngine {
    private final CardStore base;

    private final Account account;

    /**
     * The pass's primary connection for sequential verbs; null when staging.
     *
     * <p>A transport serves one caller at a time, so each worker of a
     * concurrent push opens its own: that bounds a round at four connections.
     */
    private final Transport primary;

    /** The book's phone pass, against its raw contacts. */
    private final ContactPhonePass phone;

    /** The store, for the phone queue its writes reach. */
    private final PimdirDb pimdir;

    /** The report the running sync tallies into; null outside syncs. */
    private Report tally;

    /** Book ids by collection URL (account-level membership mapping). */
    private Map<String, String> idByUrl;

    /**
     * The books this pass added a card to on an account-level backend, which
     * it lists again.
     *
     * <p>A membership patch and a JMAP create answer no revision, a People
     * create one its listing does not name: the binding meets the next
     * listing at another, and a removal staged before that listing would read
     * as overtaken by a remote edit and be undone (pimdir SYNC §5).
     */
    private final Set<String> joined = Collections.synchronizedSet(new HashSet<>());

    /**
     * The bodies the last delta rounds carried (JMAP, Google), by handle.
     *
     * <p>The fetch tier reads them back without a round-trip; any push
     * invalidates them.
     */
    private final Map<String, Card> deltaCards = new HashMap<>();

    /**
     * The account-wide deltas this pass read (JMAP, Google), by cursor.
     *
     * <p>Null keys a complete round. A book whose checkpoint matches one read
     * projects it rather than listing the account again (three Google groups,
     * one People read). Any push invalidates them, as {@link #deltaCards}.
     */
    private final Map<String, CardDelta> accountDeltas = new HashMap<>();

    /** The handles whose update People refused as a conflict this sync. */
    private final Set<String> refused = Collections.synchronizedSet(new HashSet<>());

    /** Per-collection card cache of the Graph listing, by handle. */
    private final Map<String, Map<String, Card>> graphCards = new HashMap<>();

    /**
     * Each collection's last enumerate ETags, plus whether it was complete.
     *
     * <p>What the CardDAV If-Match quirk retry checks the staged base against.
     */
    private final Map<String, Map<String, String>> listedEtags = new HashMap<>();
    private final Map<String, Boolean> listedComplete = new HashMap<>();

    /**
     * What a sync did to the cards: distinct cards per axis, phone and server.
     *
     * <p>A card counts once however many passes touched it: in appeared, out
     * deleted, changed content changed; plus the conflicts left for the user.
     */
    public static final class Report {
        /** Cards that appeared on the phone axis. */
        public final Set<String> localIn = new HashSet<>();
        /** Cards deleted on the phone axis. */
        public final Set<String> localOut = new HashSet<>();
        /** Cards whose content changed on the phone axis. */
        public final Set<String> localChanged = new HashSet<>();
        /** Cards that appeared on the server axis. */
        public final Set<String> remoteIn = new HashSet<>();
        /** Cards deleted on the server axis. */
        public final Set<String> remoteOut = new HashSet<>();
        /** Cards whose content changed on the server axis. */
        public final Set<String> remoteChanged = new HashSet<>();
        /** Conflicts left for the user. */
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

        /** Tallies a batch of write effects into the report, if a sync runs. */
        static void tallyEffects(Report tally, JSONArray effects) throws JSONException {
            for (int index = 0; tally != null && index < effects.length(); index++) {
                JSONObject effect = effects.getJSONObject(index);
                tally.tally(
                        effect.getString("collection"),
                        effect.getString("handle"),
                        effect.getString("kind"));
            }
        }

        /** Tallies accepted pushes: add is in, remove out, update changed. */
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

    /** A driver for one account; a null account serves storage yields only. */
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
     * One book's hub sync: phone pass, server pass, phone pass again.
     *
     * <p>The first pulls the Contacts app's edits, the last projects what the
     * server round brought. With the remote switch off only the first runs: the
     * hub converges with the Contacts app, nothing touches the server.
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
     * The server pass: reconcile, fetch missing bodies, triage conflicts.
     *
     * <p>A divergence whose three-way merge needs no user choice resolves and
     * pushes in a second reconcile; only same-field collisions stay conflicted
     * (remote body captured), so the form always has something to ask.
     */
    private void syncServer(String url, Report report) throws JSONException {
        tally = report;
        List<String[]> copies = account.info().accountLevel() ? copiesOf(url) : List.of();
        step(Progress.STAGE_SERVER, 0);
        Log.d(
                "pimalaya",
                "server sync " + url + ": " + clients.offline().sync(this, url, false));
        conflictRefused(url);
        hydrate(url);
        boolean relist = joined.remove(url);
        follow(url, copies);

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

        // NOTE: a book this pass patched a card into is listed again, its
        // binding then at the revision the server names (see joined).
        if (resolved > 0 || relist) {
            clients.offline().sync(this, url, false);
            conflictRefused(url);
            hydrate(url);
        }
    }

    /** Marks conflicted the cards People refused, for hydrate to capture. */
    private void conflictRefused(String url) {
        synchronized (refused) {
            Map<String, String> listed = listedEtags.get(url);
            for (String handle : refused) {
                offline.markConflicted(url, handle, listed == null ? null : listed.get(handle));
            }
            refused.clear();
        }
    }

    /**
     * The tick copies of this book's cards elsewhere on the account, each
     * with the body it holds: the collection, the link id and the body.
     */
    private List<String[]> copiesOf(String url) throws JSONException {
        List<String[]> copies = new ArrayList<>();
        for (String[] copy : offline.cardCopies(url)) {
            JSONObject row = offline.loadRow(copy[0], offline.handleFor(copy[0], copy[1]));
            if (row != null) {
                copies.add(new String[] {copy[0], copy[1], row.getString("vcard")});
            }
        }
        return copies;
    }

    /**
     * Restages the tick copies whose card this pass pulled a remote edit of.
     *
     * <p>A copy a tick made holds the body agreed here then, no edit of its
     * own: left at it, it reads as an edit the server lacks and is held for
     * ever. A copy edited since keeps its body.
     */
    private void follow(String url, List<String[]> copies) throws JSONException {
        for (String[] copy : copies) {
            JSONObject card = offline.loadRow(url, offline.handleFor(url, copy[1]));
            String base = card == null ? null : card.optString("baseVcard", null);
            if (base == null || base.equals(copy[2])) {
                continue;
            }
            JSONObject row = offline.loadRow(copy[0], offline.handleFor(copy[0], copy[1]));
            if (row != null
                    && !row.optBoolean("deleted")
                    && copy[2].equals(row.getString("vcard"))) {
                new PimdirContacts(pimdir).follow(copy[0], copy[1], base);
            }
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
     * Resolves one conflicted row when the three-way merge needs no choice.
     *
     * <p>A clean merge stages as the resolution (pushed by the caller's second
     * reconcile) and answers true; a field both sides edited differently stays
     * conflicted. All three documents come from the store: no network needed.
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

        // NOTE: a resolution naming the diverging body the merge read (SYNC
        // §7); one the engine refuses, a conflict it meets standing over
        // another body, stays for the form rather than failing the pass.
        try {
            ContactStager.resolve(
                    this,
                    url,
                    conflict.getString("handle"),
                    resolution.vcard(),
                    PimdirStorage.resolvesOf(conflict));
        } catch (PimalayaException refused) {
            Log.w("pimalaya", "contact triage refused: " + refused.getMessage());
            return false;
        }
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
     * Serves an enumerate yield: the member spine (handle plus revision).
     *
     * <p>Incremental from the cursor on every backend (RFC 6578, Graph delta,
     * JMAP /changes, People sync tokens); no cursor lists the complete set and
     * yields one, a cursor the server refuses falls back to that.
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

        boolean accountLevel = account.info().accountLevel();
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
        if (account.info().cardFetch() == CardFetch.JSON_BATCH
                && delta.complete()
                && !delta.changed().isEmpty()) {
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
     * Projects an account-wide delta (JMAP, Google) onto one book's enumerate.
     *
     * <p>On an incremental round, a changed card that left the book rides as
     * vanished, unless another book stages it as a pending create.
     */
    private JSONObject accountSnapshot(String url, CardDelta delta) throws JSONException {
        Set<String> awaited = offline.awaitedHandles(url);
        List<SnapshotCard> changed = new ArrayList<>();
        for (Card card : delta.changed()) {
            changed.add(
                    new SnapshotCard(
                            card.uri(),
                            card.books(),
                            !delta.complete() && offline.loadRow(url, card.uri()) != null,
                            awaited.contains(card.uri())));
        }
        AccountSnapshotFacts facts =
                new AccountSnapshotFacts(bookId(url), delta.complete(), changed, delta.vanished());

        AccountSnapshot projected = OfflineClient.accountSnapshot(facts);
        Map<String, PimdirStorage.Leaving> leaving = offline.leavingAgreed(url);
        List<Card> members = new ArrayList<>();
        for (int index : projected.members()) {
            members.add(standing(delta.changed().get(index), leaving));
        }
        List<String> vanished = projected.vanished();

        return snapshot(url, new CardDelta(members, vanished, delta.token(), delta.complete()));
    }

    /** A member, at its staged removal's revision when its body was agreed. */
    private static Card standing(Card card, Map<String, PimdirStorage.Leaving> leaving) {
        PimdirStorage.Leaving removal = leaving.get(card.uri());
        if (removal == null
                || card.vcard().isEmpty()
                || !removal.agreed().contains(CardStore.byteHash(card.vcard()))) {
            return card;
        }
        return new Card(card.id(), card.uri(), removal.revision(), card.vcard(), card.books());
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
     * Serves a fetch yield: full bodies with link id, summary and hash.
     *
     * <p>The link id is the vCard UID, or the identity a bound handle is filed
     * under ({@link #keepBoundNames}); the summary is STORAGE Annex A's.
     */
    @Override
    protected JSONObject fetch(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        JSONArray handles = yielded.getJSONArray("handles");
        serverOnly(url);

        JSONArray items = new JSONArray();
        switch (account.info().cardFetch()) {
            case BATCH_GET:
                fetchGoogle(url, handles, items);
                break;
            case CHANGES:
                for (int index = 0; index < handles.length(); index++) {
                    String handle = handles.getString(index);
                    Card card = deltaCards.get(handle);
                    if (card == null) {
                        card = clients.contact().readCard(primary, account, url, handle);
                    }
                    items.put(fetchedItem(url, handle, card));
                }
                break;
            case JSON_BATCH:
                fetchGraph(url, handles, items);
                break;
            default:
                // NOTE: multiget in chunks, matched back by resource name.
                List<String> uris = new ArrayList<>(handles.length());
                for (int index = 0; index < handles.length(); index++) {
                    uris.add(handles.getString(index));
                }
                for (int start = 0; start < uris.size(); start += MULTIGET_CHUNK) {
                    List<String> chunk =
                            uris.subList(start, Math.min(start + MULTIGET_CHUNK, uris.size()));
                    List<Card> cards = clients.contact().multigetCards(primary, account, url, chunk);
                    for (Card card : cards) {
                        items.put(fetchedItem(url, card.uri(), card));
                    }
                }
        }

        JSONObject reply = new JSONObject();
        reply.put("items", items);
        return keepBoundNames(url, reply);
    }

    /**
     * The Google bodies of a fetch: the People round's, then a batched read.
     *
     * <p>200 contacts per {@code people:batchGet}. A contact gone since the
     * round is left out of the reply, as a multiget leaves out a missing one.
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
     * The Graph bodies of a fetch: the round's listing, then a batched read.
     *
     * <p>20 contacts per {@code $batch}. A contact gone since the delta is left
     * out of the reply, as a multiget leaves out a missing one.
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
     * One fetched card on the engine wire, at the listing's revision.
     *
     * <p>Some servers (SabreDAV, Google) fetch other ETags than they list:
     * recording the fetched one refetches those cards every sync forever. The
     * pass's listed ETag wins; the fetched one stands in when never listed.
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
     * Serves a push yield: creates, updates, deletes, membership patches.
     *
     * <p>One result per change. A 412 retries unguarded when the last enumerate
     * proves the remote unchanged (SabreDAV's If-Match quirk), else reports the
     * change rejected; any other failure aborts the pass.
     */
    @Override
    protected JSONObject push(JSONObject yielded) throws JSONException {
        String url = yielded.getString("collection");
        JSONArray changes = yielded.getJSONArray("changes");
        serverOnly(url);
        step(Progress.STAGE_UPLOAD, changes.length());

        JSONArray results;
        try {
            if (changes.length() < 2) {
                results = pushEach(url, changes);
            } else {
                switch (account.info().cardPush()) {
                    case BATCH:
                        results = pushGoogle(url, changes);
                        break;
                    case ROUND:
                        results = pushRound(url, changes);
                        break;
                    default:
                        // NOTE: with no batch verb the round parallelizes;
                        // each change is one guarded round trip on its own
                        // transport, dividing the wall clock by the pool on a
                        // latency-bound link.
                        results = pushAll(url, changes);
                }
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
     * One CardDAV round's changes, concurrently over a small pool.
     *
     * <p>Results keep the change order; the first hard failure aborts the
     * round. Workers drain a shared queue, one per connection (a transport
     * serves one caller), so forty cards cost four connections.
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
     * One Google round: creates and deletes grouped into People batch verbs.
     *
     * <p>200 and 500 a call (one write-quota unit per call); membership patches
     * and guarded updates stay per card. Results keep the change order; a
     * failed batch aborts the round.
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
                JSONObject elsewhere = offline.cardElsewhere(url, row.getString("id"));
                PushPlan plan = addPlan(url, change, elsewhere);
                if (plan.action() == PushAction.HOLD) {
                    results[index] = held(handle);
                    continue;
                }
                if (plan.action() == PushAction.MEMBERSHIP) {
                    results[index] = pushMembership(primary, url, change, elsewhere);
                    continue;
                }
                if (plan.action() == PushAction.MOVE) {
                    results[index] = pushMove(primary, url, change, row, elsewhere);
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
                JSONObject elsewhere = offline.cardElsewhere(url, row.getString("id"));
                PushPlan plan = removePlan(url, elsewhere);
                if (plan.action() == PushAction.MEMBERSHIP) {
                    clients.contact().updateCardBooks(
                            primary, account, leave(url, handle, elsewhere));
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                removes.add(index);
                removeIds.add(handle);
            } else {
                results[index] = pushOne(primary, url, change);
            }
        }

        if (!creates.isEmpty()) {
            List<Card> created = clients.contact().createCards(primary, account, createVcards);
            joined.add(url);
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
     * One JMAP or Graph round through the bridge's batch seam.
     *
     * <p>JMAP ContactCard/set, Graph $batch of twenty: one outcome per change,
     * so a rejected card does not abort the round. Results keep the order.
     */
    private JSONArray pushRound(String url, JSONArray changes) throws JSONException {
        JSONObject[] results = new JSONObject[changes.length()];
        Map<String, Integer> indexByRef = new HashMap<>();
        Map<String, String> members = new HashMap<>();
        Map<String, String> updates = new HashMap<>();
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
                JSONObject elsewhere = offline.cardElsewhere(url, row.getString("id"));
                PushPlan plan = addPlan(url, change, elsewhere);
                if (plan.action() == PushAction.HOLD) {
                    results[index] = held(handle);
                    continue;
                }
                PushChange item;
                if (plan.action() == PushAction.MEMBERSHIP) {
                    String id = change.getJSONObject("origin").getString("handle");
                    members.put(handle, id);
                    item =
                            new PushChange(
                                    handle,
                                    PushOp.BOOKS,
                                    id,
                                    null,
                                    null,
                                    Collections.singletonList(bookId(url)),
                                    books(elsewhere, "leaving", List.of()));
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
                                handle,
                                row.getString("vcard"),
                                row.isNull("baseVcard") ? null : row.getString("baseVcard"),
                                List.of(),
                                List.of());
                indexByRef.put(handle, index);
                updates.put(handle, row.getString("id"));
                batch.add(item);
            } else if ("remove".equals(op)) {
                JSONObject row = offline.loadRow(url, handle);
                if (row == null) {
                    results[index] = result(handle, true, null, null);
                    continue;
                }
                JSONObject elsewhere = offline.cardElsewhere(url, row.getString("id"));
                PushPlan plan = removePlan(url, elsewhere);
                PushChange item;
                if (plan.action() == PushAction.MEMBERSHIP) {
                    BooksUpdate leave = leave(url, handle, elsewhere);
                    item =
                            new PushChange(
                                    handle,
                                    PushOp.BOOKS,
                                    handle,
                                    null,
                                    null,
                                    leave.add(),
                                    leave.remove());
                } else {
                    item =
                            new PushChange(
                                    handle,
                                    PushOp.DESTROY,
                                    handle,
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
            List<PushChange> leaves = new ArrayList<>();
            for (PushOutcome reply : replies) {
                String linkId = updates.get(reply.ref());
                if (linkId != null && reply.accepted() && account.info().accountLevel()) {
                    // NOTE: as leaveWith, for the updates of a round.
                    List<String> leaving =
                            books(offline.cardElsewhere(url, linkId), "leaving", List.of());
                    if (!leaving.isEmpty()) {
                        leaves.add(
                                new PushChange(
                                        reply.ref(),
                                        PushOp.BOOKS,
                                        reply.ref(),
                                        null,
                                        null,
                                        List.of(),
                                        leaving));
                    }
                }
            }
            if (!leaves.isEmpty()) {
                List<PushOutcome> lefts =
                        clients.contact().pushCards(primary, account, url, leaves);
                for (PushOutcome left : lefts) {
                    if (!left.accepted()) {
                        Log.w("pimalaya", "leaving books refused for " + left.ref());
                    }
                }
            }
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

                // NOTE: only an add renames its handle, to the created id or
                // (on a membership add) the one its origin binds.
                String member = members.get(ref);
                String assigned = null;
                if ("add".equals(changes.getJSONObject(index).getString("op")) && accepted) {
                    assigned = member != null ? member : reply.id() == null ? ref : reply.id();
                    if (account.info().accountLevel()) {
                        joined.add(url);
                    }
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
     * Pushes a pending create as the bridge plans it.
     *
     * <p>A membership patch when the body already lives on the account (add
     * with an origin), held when the account binds the card under another
     * body, else a create plus any membership patch it needs.
     */
    private JSONObject pushAdd(Transport transport, String url, JSONObject change)
            throws JSONException {
        String handle = change.getString("handle");
        JSONObject row = offline.loadRow(url, handle);
        if (row == null) {
            return result(handle, false, null, null);
        }

        JSONObject elsewhere = offline.cardElsewhere(url, row.getString("id"));
        PushPlan plan = addPlan(url, change, elsewhere);
        if (plan.action() == PushAction.HOLD) {
            return held(handle);
        }
        if (plan.action() == PushAction.MEMBERSHIP) {
            return pushMembership(transport, url, change, elsewhere);
        }
        if (plan.action() == PushAction.MOVE) {
            return pushMove(transport, url, change, row, elsewhere);
        }

        // NOTE: the resource name, not the handle: a staged create sits under
        // the provisional handle until this push assigns a real one (SYNC §2),
        // and io-webdav names the resource verbatim now instead of appending
        // .vcf itself, so what goes out is the name this side calls the card
        // by. Sending the handle would file it under a control character.
        String name = PimdirStorage.statedOf(handle);
        if (account.info().namesResources()) {
            name += ".vcf";
        }
        Card created =
                clients.contact().createCard(
                        transport, account, url, new NewCard(name, row.getString("vcard")));
        if (account.info().accountLevel()) {
            joined.add(url);
        }

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
            String revision = landed(transport, url, handle, updated.etag());
            leaveWith(transport, url, handle, row.getString("id"));
            return result(handle, true, null, revision);
        } catch (RuntimeException failure) {
            if (!isPreconditionFailure(failure)) {
                throw failure;
            }
            if (!listingUnchanged(url, handle, ifMatch)) {
                if (account.info().accountLevel()) {
                    refused.add(handle);
                }
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
            String revision = landed(transport, url, handle, updated.etag());
            leaveWith(transport, url, handle, row.getString("id"));
            return result(handle, true, null, revision);
        }
    }

    /**
     * Takes an updated card out of the books it is leaving on an
     * account-level backend.
     *
     * <p>The update moved its revision, and a tombstone still listed at
     * another revision reads as overtaken and is revived (pimdir SYNC §5):
     * out of its book, it is listed no more and its removal converges. This
     * book keeps the card, so it is never the last group.
     */
    private void leaveWith(Transport transport, String url, String handle, String linkId)
            throws JSONException {
        if (!account.info().accountLevel()) {
            return;
        }
        List<String> leaving = books(offline.cardElsewhere(url, linkId), "leaving", List.of());
        if (!leaving.isEmpty()) {
            clients.contact().updateCardBooks(
                    transport, account, new BooksUpdate(handle, List.of(), leaving));
        }
    }

    /**
     * Pushes a staged removal as the bridge plans it.
     *
     * <p>A membership patch when the card survives on an account-level backend,
     * the card's deletion otherwise. A move's removal never gets here before
     * its add is pushed: the store holds it (pimdir STORAGE §15.3).
     */
    private JSONObject pushRemove(Transport transport, String url, JSONObject change)
            throws JSONException {
        String handle = change.getString("handle");
        String ifMatch = change.isNull("ifMatch") ? null : change.getString("ifMatch");
        JSONObject row = offline.loadRow(url, handle);
        if (row == null) {
            return result(handle, true, null, null);
        }

        JSONObject elsewhere = offline.cardElsewhere(url, row.getString("id"));
        PushPlan plan = removePlan(url, elsewhere);
        if (plan.action() == PushAction.MEMBERSHIP) {
            clients.contact().updateCardBooks(transport, account, leave(url, handle, elsewhere));
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
     * The revision a CardDAV write landed at: answered, else read back.
     *
     * <p>A server storing other than it was sent answers no ETag (RFC 4791
     * 5.3.4), and a base with no revision reads the next listing as a remote
     * edit, undoing a staged removal. A failed read leaves it unknown.
     */
    private String landed(Transport transport, String url, String uri, String answered) {
        if (answered != null || !account.info().readsBackRevision()) {
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

    /** A pending create's bridge plan, from where else its card is bound. */
    private PushPlan addPlan(String url, JSONObject change, JSONObject elsewhere)
            throws JSONException {
        return OfflineClient.pushPlan(
                new PushPlanFacts(
                        PushPlanFacts.Op.ADD,
                        url,
                        bookId(url),
                        !change.isNull("origin"),
                        false,
                        elsewhere.getBoolean("bound"),
                        change.optJSONObject("supersedes") != null));
    }

    /** A staged removal's bridge plan, from where else the card lives. */
    private PushPlan removePlan(String url, JSONObject elsewhere) throws JSONException {
        return OfflineClient.pushPlan(
                new PushPlanFacts(
                        PushPlanFacts.Op.REMOVE,
                        url,
                        bookId(url),
                        false,
                        !elsewhere.getBoolean("live"),
                        elsewhere.getBoolean("bound")));
    }

    /**
     * A create held back: its card is on the account under another body.
     *
     * <p>Rejected, so it stays staged, which the report of what waits reads
     * back from the store ({@link UnsentContacts}): a patch would place the
     * card without its body, which the next pull would then erase, and a
     * create would make a second card.
     */
    private static JSONObject held(String handle) throws JSONException {
        Log.w("pimalaya", "push held for " + handle + ": its card is bound under another body");
        return result(handle, false, null, null);
    }

    /**
     * The book ids of the collections a lookup lists under {@code key},
     * after {@code books}.
     */
    private List<String> books(JSONObject elsewhere, String key, List<String> books)
            throws JSONException {
        List<String> ids = new ArrayList<>(books);
        JSONArray collections = elsewhere.getJSONArray(key);
        for (int index = 0; index < collections.length(); index++) {
            String book = bookId(collections.getString(index));
            if (book != null) {
                ids.add(book);
            }
        }
        return ids;
    }

    /**
     * The patch taking a card out of this book, and of every one it leaves.
     *
     * <p>A patch moves the card's revision, and a tombstone still listed at
     * another revision is revived (pimdir SYNC §5): out of its book, it is
     * listed no more and its removal converges. With no book binding it yet,
     * the ones staging it take it in the same patch: Google refuses to remove
     * a card's last group.
     */
    private BooksUpdate leave(String url, String handle, JSONObject elsewhere)
            throws JSONException {
        List<String> add =
                elsewhere.getBoolean("held") ? List.of() : books(elsewhere, "pending", List.of());
        return new BooksUpdate(handle, add, books(elsewhere, "leaving", List.of(bookId(url))));
    }

    /**
     * Patches a pending create's card into this book, from its origin.
     *
     * <p>The card is named by the handle its holder binds, never by the link
     * id: a card the phone created keeps its UID, which no server issued. A
     * move takes it out of the books it leaves in the same patch.
     */
    private JSONObject pushMembership(
            Transport transport, String url, JSONObject change, JSONObject elsewhere)
            throws JSONException {
        String id = change.getJSONObject("origin").getString("handle");
        clients.contact().updateCardBooks(
                transport,
                account,
                new BooksUpdate(
                        id, List.of(bookId(url)), books(elsewhere, "leaving", List.of())));
        joined.add(url);
        // NOTE: the book now holds the member the origin names; the
        // provisional handle would bind it to a name the server never heard
        // of, read as vanished by the next enumerate.
        return result(change.getString("handle"), true, id, null);
    }

    /** Delivers a move as a guarded update of its member, then a patch. */
    private JSONObject pushMove(
            Transport transport,
            String url,
            JSONObject change,
            JSONObject row,
            JSONObject elsewhere)
            throws JSONException {
        String handle = change.getString("handle");
        JSONObject member = change.getJSONObject("supersedes");
        String from = member.getString("collection");
        String id = member.getString("handle");
        String ifMatch = member.isNull("ifMatch") ? null : member.getString("ifMatch");
        JSONObject moved = offline.loadRow(from, id);
        if (moved == null || moved.isNull("baseVcard")) {
            return held(handle);
        }

        Card updated;
        try {
            updated =
                    clients.contact().updateCard(
                            transport,
                            account,
                            new BookCard(
                                    from,
                                    new Card(
                                            row.getString("id"),
                                            id,
                                            ifMatch,
                                            row.getString("vcard"))),
                            moved.getString("baseVcard"));
        } catch (RuntimeException failure) {
            if (!isPreconditionFailure(failure)) {
                throw failure;
            }
            Log.w("pimalaya", "move held for " + handle + ": " + failure.getMessage());
            return result(handle, false, null, null);
        }
        clients.contact().updateCardBooks(
                transport,
                account,
                new BooksUpdate(id, List.of(bookId(url)), books(elsewhere, "leaving", List.of())));
        joined.add(url);
        return result(handle, true, id, updated.etag());
    }

    /** Whether the last enumerate proves the handle unchanged at the base. */
    private boolean listingUnchanged(String url, String handle, String ifMatch) {
        return OfflineClient.retryUnguarded(
                new RetryFacts(
                        url,
                        listedEtags.get(url),
                        Boolean.TRUE.equals(listedComplete.get(url)),
                        handle,
                        ifMatch));
    }

}
