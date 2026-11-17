---
cairn: tasks
change: caldav-capability-fallback
---

- [x] Inspect the bridge, io-webdav 0.11.0, existing Rust tests and DAVx5's capability selection.
- [x] Separate the confirmed selection defect from the unconfirmed source of the supplied HTTP 404.
- [x] Obtain human review of the minimal implementation plan.
- [x] Select the existing fallback from collection capabilities without catching generic 404.
- [x] Add regression cases using the existing Rust test infrastructure.
- [x] Review the Rust changes statically and run cargo fmt; tests remain unexecuted at the user's request.
- [x] Assemble the debug APK, as subsequently authorized by the user: BUILD SUCCESSFUL in 8m 24s, all four Rust ABIs rebuilt.
- [x] Fold the spec, update the changelog, append the landing log and archive.

The reporter subsequently tested the rebuilt application and confirmed that the Mail.ru synchronization issue is resolved. Exact event counts and individual repeat-sync scenarios were not separately reported. The agent did not run tests, launch the app or make authenticated server probes.
