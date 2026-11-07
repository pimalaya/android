---
cairn: delta
change: google-data-notice
---

# Delta

## ADDED Requirements

### Requirement: Google's consent page follows the app's notice of what it reads
Before the app's Google client opens Google's consent page, the app SHALL show a notice in a dialog, titled with the app's name, saying: the Google data the grant asks for, one line each for Gmail, Google Calendar, Google Tasks and Google Contacts, only those asked, with what each is used for; that the app reads the account's address to check the sign-in, where the grant reads one (mail or the calendar list); that the data goes only between Google and the phone, is stored only on the phone, and is never received by Pimalaya, sold or used for ads; where calendars or contacts are asked, that turning a phone mirror on copies them into the phone's own apps; and a link to the privacy policy. Google's consent page SHALL open only on Agree, the Continue button's loader starting then. Not now, back, a tap outside or the dialog closing any other way SHALL open nothing and leave Continue to ask again. The notice SHALL NOT dismiss itself, SHALL show before every Google consent page (a sign-in again's included), and before no other provider's. It is the disclosure of Google's Workspace API user data policy ("Transparent and accurate notice and control").

#### Scenario: Agreeing to the notice
- GIVEN a Google account at its sign-in page
- WHEN Continue is tapped
- THEN the notice shows, no loader runs and no page opens
- WHEN Agree is tapped
- THEN Continue's loader starts and Google's consent page opens

#### Scenario: Backing out of the notice
- GIVEN the notice showing
- WHEN back is pressed
- THEN no page opens, Continue is offered again, and tapping it shows the notice again

## MODIFIED Requirements

## REMOVED Requirements
