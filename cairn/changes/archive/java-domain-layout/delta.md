---
cairn: delta
change: java-domain-layout
---

Internal restructuring of the Java side: no requirement changes. Recorded behaviour changes, none moving a requirement: a lone mail worker runs on the calling thread as a lone calendar worker did; sync and screen posts that only update the UI are dropped once the activity is destroyed (posts that may persist an account still land); bridge replies are parsed strictly (an unknown enum value or a missing required key fails instead of passing as text); the hidden advanced vCard editor, unreachable since the edit form became the only editor, is removed.

## ADDED Requirements

## MODIFIED Requirements

## REMOVED Requirements
