# Addressbook management: not yet supported

The app can list addressbooks and toggle their subscription, but it cannot create, rename, or delete an addressbook. This is a deliberate gap, recorded here so the UI work that surfaced it is not lost.

## What exists today

- `CardStore.loadAllAddressbooks` / `loadSubscribedAddressbooks`: read the books discovered by the last account sync.
- `CardStore.setBookState`: set one book's subscription and its remote and phone sync switches (no server call).
- `CardStore.forgetAccount`: drop an account's books, called by `AccountRemoval` when a whole account goes.
- An account's settings screen (`AccountSettings.addBooks`) lists its books, each with a switch and its sync and phone rows; its delete button confirms account deletion.

There is no per-addressbook mutation at any layer: not in `CardStore`, not in `ContactClient` / `Native`, and not in the Rust bridge (each backend under rust/src/contact/ exposes `list_addressbooks` plus card CRUD only).

## What a create / rename / delete would take

A full vertical slice per operation, and the semantics differ by backend:

- CardDAV: `MKCOL` (extended, addressbook resourcetype) to create, `PROPPATCH` on `displayname` to rename, `DELETE` on the collection to remove. These coroutines do not exist in io-webdav yet.
- JMAP (RFC 9610): `AddressBook/set` create/update/destroy.
- Google People: books map to contact groups; arbitrary creation is possible but the "default" group is special and cannot be removed.
- Microsoft Graph: contact folders; the default folder cannot be renamed or deleted.

Each needs: the protocol coroutine in the relevant io-* crate, a Rust bridge method, a `Native` FFI entry, a `ContactClient` wrapper, `CardStore` persistence, then the UI (a FAB dropdown offering account vs addressbook creation, and a long-press dialog on a book row to rename or delete, the delete guarded by a confirm dialog).

## Desired UI, when the backend lands

- FAB in the home drawer opens a dropdown: "Create account" (primary text tone) and "Create addressbook" (secondary tone), mirroring the sync dropdown (menu_sync.xml).
- Long-press on an addressbook row opens a dialog to rename it, or to delete it; delete triggers a second confirm dialog, like `confirmDeleteAccount`.
