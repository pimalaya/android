---
cairn: delta
change: hide-in-recents
---

# Delta: onboarding

## ADDED Requirements

### Requirement: Recent apps can show a blank card in place of the app
The app's Settings page SHALL offer "Hide in recent apps" in a Privacy card under the Notifications one, off by default. Off, recent apps SHALL show the app's last screen. On, from Android 13, recent apps SHALL show a blank card while screenshots and screen recordings of the app still work; below Android 13 the app's window SHALL be secured, which blanks recent apps, screenshots and screen recordings alike, and the switch's note SHALL say so. The choice SHALL apply as soon as it is made and again each time the app opens.

#### Scenario: Off by default
- GIVEN a fresh install
- WHEN a message is open and the user goes to recent apps
- THEN the app's card shows the open message

#### Scenario: Turned on from Android 13
- GIVEN "Hide in recent apps" turned on, on Android 13 or later
- WHEN the user goes to recent apps
- THEN the app's card is blank, and a screenshot of the app still shows its screen

#### Scenario: Turned on below Android 13
- GIVEN "Hide in recent apps" turned on, on Android 12 or earlier
- WHEN the user goes to recent apps, or takes a screenshot of the app
- THEN the card and the screenshot are blank

## MODIFIED Requirements

## REMOVED Requirements
