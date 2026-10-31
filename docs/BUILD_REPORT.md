# Verification report — 0.1.1, 2026-10-31

## Executed against final source

- Gradle 8.11.1, AGP 8.9.1, Kotlin 2.1.10, Android SDK 35 and JDK 21: `:core:test :app:assembleDebug :app:lintDebug` successful.
- 13 host tests, 0 failures/errors/skips (15.488 seconds).
- Strong authenticated queue regression: original transport executed expired B (HTTP 200, calls A/B/C), failing the assertion in 5.347 seconds. Final transport closed B without execution (calls A/C), passing in 5.307 seconds; fresh C succeeded.
- Complementary unexpired-queue control passed in 0.115 seconds: B returned HTTP 200 and calls were A/B/C.
- Original idle-queue regression also failed original behavior at 7.031 seconds and passed patched behavior at 5.006 seconds. Three independent patched repetitions passed at 5.042/5.004/5.004 seconds.
- Baseline-red used the unchanged original transport behavior plus the same package-private `trackedConnections()` observer needed by the test. The observer reports socket-set size and does not change deadlines/dispatch.
- Lint: 0 errors, 8 existing warnings (7 English-only UI localization warnings; 1 legacy backup configuration warning with `allowBackup=false`).
- Debug APK v2 signature and package metadata verified: `dev.phonemcp.android`, versionCode 2/versionName 0.1.1, minSdk 26, targetSdk 35.

The strengthened test occupies the serial worker using an explicit entered/release latch, sends B's complete authenticated body, confirms B is accepted, and waits 5.3 seconds from that observation. It then releases A, observes B before opening fresh C, and asserts controller identities rather than merely counting empty requests. All waits are bounded; the release latch is opened in finally blocks. A 15-second controller safety timeout prevents stranded tests. These host tests use a fake controller, not a phone.

## Scope and limitations

The input deadline starts at socket acceptance, not worker dispatch. The accept loop does not read request B; B is parsed only by the serial worker. The change avoids granting stale queued sockets a fresh input budget. It does not guarantee availability during saturation, bound an already-running Android action, provide server authentication, or undo actions.

No physical-device/emulator install or UI tests, Samsung S25 Ultra certification, live accessibility testing, store approval, penetration test, payment, repo push, publishing, Mirror Worker or ChatGPT integration. The device acceptance checklist remains required. Debug signing is not production signing. Google Play Accessibility API restrictions remain unresolved for a generic autonomous-control product.

See CHANGELOG.md for collaboration attribution. Verification evidence is included in `docs/verification/`; baseline-red failures are intentionally expected, not failures of the final build. Tools, local SDK config, caches and signing keys are excluded from the source archive.

Final collaborative review accepted this narrow change after checking expired-return socket cleanup and rejected-executor cleanup. Remaining limitation: no application-level response-write deadline. Slow response consumers and ongoing request floods can still affect availability; no general DoS guarantee is claimed.

Three additional isolated repetitions of the final stronger authenticated-command regression also passed; exact timings and observed A/C controller identities are retained in `docs/verification/iteration2/repeated-strong-regression.log`.
