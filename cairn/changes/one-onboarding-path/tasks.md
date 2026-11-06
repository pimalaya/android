---
cairn: tasks
change: one-onboarding-path
---

# Tasks

Reworked on 2026-11-05 (proposal.md, Why). The owner asked on 2026-11-05 to land steps 1 to 5 together for one device test, then a commit, rather than one device check per step.

- [x] Review the reworked design with the owner (design canvas, 2026-11-05); copy the frames to designs/
- [x] Rewrite proposal.md, delta.md and tasks.md to it

## Step 1: discovery on the address

- [x] ~~Discovery in the background while "Select your domains" is picked, nothing ticked~~, folded by the owner on 2026-11-05 into the servers page: the address's Continue runs discovery on its loader, a failure reported there and Continue running it again; an answer landing after the flow was left is dropped (`submitEmail`, `search`, `discoveryRun`, `leave`)
- [x] Each domain planned from discovery, ticked when found and not on the account already, by hand when nothing was found (`planSetups`); the nothing-connectable dialog (`offerAdvanced`) is gone
- [ ] Remove panel_domain and panel_config from the auth flipper (neither is shown any more) and renumber the steps; `STEP_DOMAIN` is kept until then

## Step 2: Review your domains

- [x] The servers page on the sign-in step: one card per ticked domain, Mail (sending) after Mail, each with its sentence (`cardLine`) and a settings button (`openServers`, `cardView`)
- [x] Folded with the domain selection (owner, 2026-11-05): one card, a section per domain under hairlines, ~~each led by its checkbox, the glyph before the name~~ each led by its domain's tile, which is its tick (owner, 2026-11-05, canvas row "Set up your domains", take A: `CardRows.tileRow`, tile_picked, tile_unpicked, tile_badge), the title "Set up your domains" (`cardView`, `toggle`, `counts`); every section always shown, Mail (sending) greyed out while Mail is not picked, a settings button disabled while its section is not (`disabledAlpha`); the menu rows end in a down arrow (`dropdown`); "Connected" and "Nothing found" when not picked; Mail (sending) fixed over mail's session, unpicked no sending; Continue waits for a pick (`refreshContinue`); Back to the address
- [x] *Auto* first in the settings, back to what discovery ranked first (`autoChoice`); *Auth* for "Sign in with"; sending's *Security* menu, STARTTLS or SSL/TLS (`pickSecurity`), *Plain* left out (proposal.md, Why); the menu rows share one shape (`menuRow`, `showMenu`)
- [x] OAuth's *Application* menu, Pimalaya where the server has the app's own, else Custom, Custom alone showing the client (`appFits`, `oauthBase`, `pickApplication`, `fillClient`); a typed server with none of the app's own refused on Pimalaya (`clientProblem`)
- [x] ~~The settings button turns its card into the custom form for good~~, reworked with the owner on 2026-11-05: the settings list what discovery found (`showChoices`), an IMAP, SMTP, CardDAV or CalDAV option filling the form (`fillForm`, `fillSending`), a JMAP or provider option said in its sentence (`pickOption`), *Custom settings* the form (`openForm`), mail's sending planned again when mail's option changes (`replanSending`)
- [x] The form's sign-in as a menu, Password, API token or OAuth (`authRow`, `authsOf`); a typed form read at Continue (`resolveForms`), OAuth running as a discovered option of the same server or through the client page (`typedOption`, `typedSubmit`)
- [x] After the first device test (owner, 2026-11-05): settings items plain and short, "JMAP · OAuth 2.0", the long label only where two collide (`choiceLabel`); no ticks in either menu; every option fills the form, a JMAP session or an API as a preset with its server said, its sign-in among the ones found for it (`fillForm`, `layoutForm`, `pickAuth`); each field's name inside it (`PillField.labeled`); the sign-in row's ripple clipped to the pill
- [x] The OAuth client on the reading form, prefilled with the default and changeable: the app's own at Google and Microsoft, "Registered automatically" at an issuer, the endpoints elsewhere (`fillClient`, `shippedClient`); a changed client runs the grant (`clientOf`, `startClientOauth`, `startIssuerOauth` with the client), the pages it covers grouped by client too (`sameGrant`); a missing client ID or an unreceivable redirect said at Continue (`clientProblem`, `comesBack`); the domain page's "Use my own OAuth client" link removed
- [x] A card found empty opens on the form under "Nothing could be discovered for <domain>. Set it up manually."; ~~an empty sending form connects without sending~~ a ticked form needs its server; a form's problem said under it on Continue (`formProblem`, `serversContinue`)

## Step 3: a page per sign-in, then the settings

- [x] ~~One page per card, titled with its domain~~, regrouped by the owner on 2026-11-05: the title "Review your domains"; the domains grouped by sign-in, one browser grant for a server, audience and client, one login and password or token for the servers of one name, sending through mail's sign-in with mail (`Identity`, `buildIdentities`, `together`, `settle`); a page per sign-in titled with its provider or servers' name over its domains, the card naming every host (`identityName`, `domainNames`, `identityServers`, `signInCard`); a lone browser grant opens the browser from the domains page's Continue, its page shown only for what the grant left to ask (`direct`)
- [x] Continue signs in at every server of the sign-in, one after the other, then the next page asking something, sign-ins made passed, Back to the page shown before (`pageContinue`, `nextPage`, `signIn`, `probe`, `probed`, `abortAuthSteps`); a refusal names the domains refused, the others staying signed in (`identityProblem`); ~~`signInThen`, `probePage`); a refusal on the card (`probed`)
- [x] *Same login and password as*: ~~linked to the earlier password page whose server bears the same name, else the first, on by default for the same name~~ servers of one name share a sign-in, so a later password sign-in links to the first, off to begin with (`buildIdentities`); turned off before a refusal is said, which it used to wipe; ~~(`linkOf`, `sameServer`)~~ fields hidden while on; a refusal turns it off
- [x] A browser grant on its first page's Continue, covering the pages of its server and audience (`startGrant`); Google's granted scopes read from the token response, a page whose scopes did not come back said and asked again (`grantNeeds`, `connect`); the JMAP fallback per page (`fallBack`)
- [x] ~~The options card on each page~~, moved by the owner on 2026-11-05 to one settings page after the last, "Review your settings", a section per domain (`settingsCard`): notifications and phone mirrors as switches, asking their permission as they turn on; subscribed mailboxes and addressbooks picked on the books page (`pickMailboxes`, `pickBooks`); your name in a dialog, applied at the save (`finishOnboarding`), Finish saving (`pageContinue`)
- [x] Subscribed calendars (owner, 2026-11-05): no per-calendar switch was missing, the calendar filter being the calendars' sync scope by store id (the account's id and the calendar's URL); listed from the signed-in server, picked on the books page, kept out of the filter at Finish before the first sync (`pickCalendars`, `listCalendars`, `openCalendarsSelection`, `confirmCalendars`, `applyCalendars`)
- [x] `SetupPlan.hostOf` read hosts with java.net.URL, which knows no imaps or smtps: a mail server's card showed the whole endpoint and a typed IMAP server never matched its discovered OAuth; read from the URI now, the provider markers aside, their API hosts given (`providerOption`), with a test failing on the old helper

## Step 4: the account and its syncs

- [x] Check that `SyncCoordinator` runs one domain's first pass while another's runs, for one account: it does not (proposal.md, Open points)
- [x] ~~Save the account at the first validated page, marked as being set up until Finish; each later page adds its domain~~, dropped by the owner on 2026-11-05: the settings come last
- [x] ~~Continue on a page starts its domain's first sync in the background~~, dropped: nothing syncs before Finish; checked, the setup's only sync calls are Finish's (`commitBooks`, `finishOnboarding`), the overlay's chrome owing no first sync (`domainOf` is null for it)
- [x] ~~Backing out removes the account and its store; an account still marked at launch is removed~~, dropped with them: nothing is saved before Finish
- [x] Finish lands on the first domain the account covers, the dock showing its first sync (unchanged: `syncConnected`)

## Step 5: the subscribed rows, cleanup, fold

- [x] A subscribed row shows its default until opened; every page is signed in by the settings page, so it opens the list at once
- [x] Remove the shared sign-in page, the tune menu, the setup mode, the one-password standard setup and their strings (the result step stays, for what the phone already shows)
- [x] While a list's filter panel shows, its FAB shrinks away (owner, 2026-11-05), as left across tabs (`ListHeader.filtersShown`, `FilterPanel`, `listFab`); while the sync dock shows too, rather than riding over it (`Host.dockShown`, `Host.refreshFab`, `SyncDock`)
- [x] After the fourth device test (owner, 2026-11-05): "Review your settings" drawn as the domains page, one card, a section per domain led by its tile, picked (`settingsCard`); a password or token asked per domain again, Mail's and Mail (sending)'s apart as Fastmail's app passwords may differ, the same-login switch on for a server of the linked one's name (`together`, `linkOf`, `identityTitle`)
- [x] Every circular spinner at one size, `@dimen/spinner` (24dp, the icon grid's), the platform's stroke following its size; the small style gone; the drawer's empty state centred in what the account cards leave (`fillViewport`)
- [x] Account settings redesigned after the setup's settings (owner, 2026-11-05): designed on the canvas first, as its rows change behaviour, then built under account-settings-page
- [x] A tap on a field's label or ends types in the field (`PillField`); the setup overlay takes every touch made on it (`android:clickable` on overlay_auth), a drag around its Continue button no longer switching the screen beneath and raising its FAB (owner, 2026-11-05)
- [x] Build, unit tests (864, none failing), install (again after each rework, 2026-11-05)
- [ ] Device check of the whole flow (owner)
- [ ] Fold the delta into cairn/spec/onboarding.md once step 4 lands or is split out, reword the "advanced setup" left in "An account can gain a sender after it is connected" and "Setup accepts encrypted servers only"; log, archive
