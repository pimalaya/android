---
cairn: delta
change: rust-domain-layout
---

Internal restructuring of the bridge crate: no requirement changes. One fix brings JMAP into line with mail.md as written: a blob download refused with 401 now fails the step or the source read for the token to be renewed ("an expired token failing the call"), where it was logged as one failed body per message. The JMAP revision hash keeps its values, now pinned to SipHash-1-3 with zero keys rather than left to `DefaultHasher`.

## ADDED Requirements

## MODIFIED Requirements

## REMOVED Requirements
