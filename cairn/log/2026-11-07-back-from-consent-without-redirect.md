---
cairn: log
change: back-from-consent-without-redirect
date: 2026-11-07
---

# Back from the consent page without a redirect, Continue is offered again

Found by the owner on the production build (2026-11-07): leaving Google's consent page and switching back to the app left the sign-in page on its loader for good, Continue and Back both dead.

- Cause: a browser sign-in sets OnboardingFlow's `checking` and the loader before the browser opens, and only a landed or failed grant cleared them; MainActivity.onResume reset the step's button, but the sign-in step skips that reset while `checking` is set.
- OauthFlow now knows when a grant's page is out with no redirect back (`browserOut`, set as the browser opens, cleared by a redirect, scheme or loopback, and by an abort). OnboardingFlow.resumed, called from onResume in place of the bare reset, then drops `checking` and the loader, so Continue starts a new grant and Back works; the pending grant stays, so a redirect still lands if the user goes back to the page and consents.
- A redirect now puts the step on its loader while it redeems, so one landing after the app dropped the loader shows it again.
- Regression: BrowserLeftTest, its first case failing on the old reset (the loader left on), its second keeping the loader when the redirect arrived before the app showed. `:app:assembleDebug` and `:app:testDebugUnitTest` green.

Capabilities moved: onboarding (An OAuth redirect is the grant's own, modified).
