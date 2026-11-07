---
cairn: delta
change: debug-app-alongside
---

# Delta

## ADDED Requirements

### Requirement: A debug build installs beside a release build
Debug builds SHALL be the package `org.pimalaya.debug`, named "Pimalaya Dev", and SHALL share no identifier a second install collides on with release builds: their phone account types, their OAuth redirect schemes (Google's, Microsoft's and the dynamic-registration one) and their launcher shortcut SHALL be their own. Release builds SHALL keep `org.pimalaya` and every identifier they carry today.

#### Scenario: Both builds on one phone
- GIVEN a release build of Pimalaya installed with its accounts
- WHEN a debug build is installed
- THEN both stay installed, each with its own accounts, and a sign-in started in either one returns to the app that started it

## MODIFIED Requirements

## REMOVED Requirements
