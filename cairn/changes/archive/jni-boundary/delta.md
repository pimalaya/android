---
cairn: delta
change: jni-boundary
---

Internal restructuring of the bridge: no requirement changes. Recorded behaviour changes, none moving a requirement: deleting a message where the account keeps no `\Deleted` and lists no trash is refused up front on Graph and Gmail as on JMAP (the bridge refused it there already; both always list their trash, so unreachable today); a server host shows lowercased as the URL parser normalises it.

## ADDED Requirements

## MODIFIED Requirements

## REMOVED Requirements
