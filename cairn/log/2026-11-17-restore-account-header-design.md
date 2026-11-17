---
cairn: log
change: restore-account-header-design
landed: 2026-11-17
---

# Preserve the original account identity design

The user rejected the avatar relocation introduced by the shared-header migration and confirmed the intended design: keep the avatar in its original place in the scrolling identity block, and show only the account name/address in the toolbar after scrolling. The original layout was recovered from the repository's pre-migration panel_account.xml.

account_identity.xml restores the original 52dp avatar to the left of the 22sp address and covered domains, with the 30dp sync status below, including the original gutters and spacing. The address and domain views use the shared header IDs without adding a duplicate title. ListHeader accepts this layout through an additional constructor while the existing constructor continues to use the unchanged domain header layout. The shared scroll calculation converts the nested title rectangle into header coordinates through the platform API, reusing one scratch Rect rather than allocating on every scroll callback. There is still one shared listener, no account-specific animation and no avatar in the toolbar.

The ListView adapter, native return position, default domain headers, account actions, permissions and removal safeguards remain unchanged. The existing SignInAgainTest now also checks avatar/name placement, status below the identity row, the toolbar's lack of an avatar, and the exact nested-title visibility boundary. The test scrolls through the native list API rather than selecting its nonselectable settings rows; its scrolling loop asserts forward progress.

Validation: the Windows wrapper completed :app:assembleDebug and :app:compileDebugUnitTestJavaWithJavac successfully in 9 seconds. The APK is android/app/build/outputs/apk/debug/app-universal-debug.apk. Focused IDE analysis found no errors in the changed Java files; warnings remain. The regression assertions were compiled, not executed. No unit tests, device interaction, screenshot comparison or visual/runtime verification was performed, respecting the existing restrictions.

Capabilities moved: onboarding. CHANGELOG.md records the layout correction. Previous logs and unrelated changes were preserved.
