---
cairn: log
change: account-settings-shared-list-header
landed: 2026-11-17
---

# Use the Emails list header in account settings

The user initially requested RecyclerView. Inspection established that Emails uses ListView and ListHeader, and the user explicitly chose that existing combination instead. The human approved the Cairn proposal before implementation, including Windows APK assembly and test-source compilation with a longer command limit, without running tests or operating a device.

Account settings now uses ListView, CardSections and SectionsAdapter with the existing settings cards and controls. The shared ListHeader supplies the large account-address title and covered domains, with the existing avatar and sync status retained in the scrolling header. Its existing title visibility callback targets the account overlay's toolbar through MainActivity. Scrolling account settings does not fold the underlying list's FAB. Bottom/keyboard inset padding now targets account_list. Removal remains a fixed footer, addressed through its own view even while offscreen, and the existing confirmation, active-sync and final phone-pass safeguards are unchanged.

The account-specific scroll listener, duplicate large-address view and sign-in-again marker are removed. Normal opening attaches the adapter from the beginning. The explicit return path keeps the same adapter and native list position for the displayed account while refreshing its data; returning to another account opens from the beginning. No scroll snapshots, dependencies or RecyclerView support were added. The earlier correction that explicitly reopens the sign-in form is preserved, as are unrelated Windows setup edits.

The existing SignInAgainTest regression now covers shared-header title visibility, retaining adapter identity and the first visible position/offset after returning, keeping stored credentials unchanged, resetting on ordinary opening, and resetting when returning to a different account. These assertions were compiled, not executed.

Validation: the Windows wrapper completed :app:assembleDebug and :app:compileDebugUnitTestJavaWithJavac successfully in 8 seconds. The APK is android/app/build/outputs/apk/debug/app-universal-debug.apk. IDE analysis found no errors in AccountSettings, ListHeader or SignInAgainTest. MainActivity still reports diagnostics outside the edited code concerning drawer gravity, MessageView.open accessibility and API-29 Insets fields; the Gradle build and test-source compiler succeeded. git diff --check passed. Native-backed unit tests were not executed because of the user's restriction and the unavailable Nix environment. No installation, device interaction, visual verification or performance measurement was performed.

Capabilities moved: onboarding. CHANGELOG.md records the user-facing change.
