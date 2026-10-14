---
cairn: log
change: java-domain-layout
landed: 2026-10-14
---

# The Java side laid out by domain, typed, and thin

Capabilities moved: none in requirements. contact (the hidden advanced vCard editor and its source panel are removed).

The `:app` module's 121 files in one flat package are now packages by domain and layer, bottom up: `account`, `phone`, `pimdir`, `ui`, `file`, `contact`, `calendar`, `mail`, `sync`, `setup`, `filter`, `trash`, `link`, and the root for the manifest's components. LayeringTest enforces the direction; no screen names MainActivity, which shrank from 4,784 to about 2,200 lines and keeps the composition root, lifecycle, navigation and chrome. Screens get their stores and collaborators by constructor and reach the activity through `ui.Host` and two small shells.

App, registered in the manifest, owns the process state that statics held (the store, the card store, the phone queue, the filters, mail state); one lock registry on the store replaces four. Engines no longer pick a role by null arguments: `pimdir.Stager` stages, `PimdirEngine` runs the remote half, `PhonePass` the phone half over `PhoneSpoke`. `sync.SyncPasses` runs every pass headless and `sync.SyncCoordinator` owns the foreground sync out of MainActivity; `WorkerPool` replaces the mail and calendar pools. Logic left in screens moved verbatim into tested classes (SetupPlan, AccountCommit, AccountRemoval, ContactEditor, ContactMerge, EventWrites, MailReply, BodyPlan, Filters).

`:client` splits PimalayaClient into five domain clients behind a `Clients` record and answers with records mirroring the Rust serde types, strict enums for its closed sets; JSON stays only on the offline-driver wire, the phone event view and the store's summary rows, the recorded exceptions. Every typed reply and Java-built input was captured on the old code and pinned (corpora for vCards, messages, events, the contact form and stored rows; persisted formats byte-identical). MailStore splits into Outbox and MailPolicy; wide signatures take params records or carry a recorded reason. Tests fake only the domain clients and MailEngine is final. Each package opens with a package-info.java, the root one the architecture document. JVM tests 531 to 731, exported JNI symbols unchanged, Rust untouched. The org guidelines gained a `java` scope; the JNI boundary itself is the next change.
