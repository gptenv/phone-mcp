# Production acceptance — independent gap audit

Audit date: 2026-10-31 UTC. Baseline: version 0.1.1 source inspected at approximately 02:20 UTC, before concurrent production-hardening work. This audit changed only this document. Findings below are source-derived unless explicit execution evidence is named; they are not device-reproduced exploits. A later fix does not close a gate until its regression evidence is recorded.

**Decision: BLOCKED for production release.** A buildable debug preview and passing host tests are not production or Samsung S25 Ultra certification. No distribution, signing, billing, publishing, device installation, Mirror Worker or ChatGPT integration is authorized by this document.

## Status meanings

- **PASS:** the specific narrow evidence exists; not an overall security claim.
- **NOT RUN:** required validation is missing; implementation alone does not pass it.
- **BLOCKED:** a concrete defect, unresolved design decision, or missing authority prevents acceptance.

## Existing evidence (baseline only)

| Gate | Status | Evidence / limits |
|---|---|---|
| Java core regressions | PASS | `verification/core-tests.xml`: 13 tests, 0 failures/errors/skips, timestamp `2026-10-31T01:57:58`, 15.488 seconds. Uses fake JVM controllers, not Android. |
| Authenticated expired queue regression | PASS | Existing XML and `BUILD_REPORT.md`: expired B never reaches fake controller, fresh C succeeds; complementary unexpired B succeeds. This is a request-input/queue test, not session rotation or Android lock timing. |
| Debug build | PASS | `verification/build.log` and `BUILD_REPORT.md` record `:app:assembleDebug`; baseline versionCode 2/versionName 0.1.1. Must rerun on final source. |
| Debug lint | PASS | `verification/lint.txt`: 0 errors, 8 warnings. Seven localization warnings and one legacy backup warning are not a waiver for commercial readiness. |
| Debug artifact signature/metadata | PASS | `verification/apk-signature.txt` verifies v2; `apk-metadata.txt` says `application-debuggable`, minSdk 26, targetSdk 35. This is **not** release signing. |
| Wrapper integrity configuration | PASS | Wrapper pins Gradle 8.11.1 distribution SHA-256. Does not authenticate every transitive dependency or final artifact. |
| Android unit/instrumentation tests | NOT RUN | No `app/src/test` or `app/src/androidTest` at audit baseline. None of the 13 core tests execute `AndroidPhoneController`, `McpService`, `MainActivity`, or real accessibility nodes. |
| Physical S25 Ultra / minimum API / current OS qualification | NOT RUN | No device or emulator evidence. Execute `DEVICE_TEST_PLAN.md` with OS/build identifiers and logs. |
| Production artifact / reproducibility / release signing | BLOCKED | Only debug evidence. No authorized signing identity or distribution decision; do not generate/store a production key without owner approval. |
| Commercial route / $5 offering | BLOCKED | Play versus direct APK, currency, markets, support/refunds, licensing/entitlement model and signing ownership remain undecided. No payment implementation is claimed. |

Baseline core XML SHA-256: `aafb67cd05f100c772deca716b0e98cb273fd8eb70c57a9c135f9f4951dd7ba5`.

## Source findings and closure criteria

### A1 — Session authority not bound to the authenticated endpoint (high; BLOCKED)

Baseline `McpService` constructs `AndroidPhoneController(this)` before publishing the fresh token. `AndroidPhoneController.execute` takes its expected token and scopes from current global `PhoneBridge` state **at invocation**, not from the old server's authenticated session. An old request can pass old-token HTTP authentication, pause before `controller.execute`, then resume after local Stop/Start. It can capture the new token and new scopes and pass the current guards. `shutdownNow()` is cooperative; closing a socket does not prevent already-running Java code from reaching the controller. No exploit was executed in this audit.

**Required fix/evidence:** bind each controller to immutable session identity supplied by its creating service; compare it at every guard, never adopt current authority. Deterministic regression: pause after old-token authentication but before controller invocation, revoke/start a different session (including broader scopes), release the old request, and assert no inspection/navigation/click effects. A fresh request under the new token must still succeed. Test both a single request and any legacy batch remainder. A closed-server dispatch guard is useful defense in depth, not a substitute for immutable Android authority.

### A2 — Consent controls lack explicit anti-overlay protection (high; BLOCKED)

Baseline `MainActivity` sets `FLAG_SECURE`, but Start, scope controls and the positive consent button do not reject obscured touches. Screenshot prevention is not tapjacking prevention. This app supports API 26; do not rely only on later Android defaults. Android documents separate full- and partial-occlusion mitigations. [Official Android guidance](https://developer.android.com/privacy-and-security/risks/tapjacking).

**Required fix/evidence:** protect authorization-bearing controls/dialogs against full and partial occlusion, consider overlay hiding where supported, and mark token/authorization UI sensitive where applicable. Verify both overlay cases on supported APIs, plus normal accessible interaction. Do not claim protection against a rooted/fully compromised device.

### A3 — Snapshot state survives session stop (medium; BLOCKED pending privacy fix)

Baseline stop paths clear `PhoneBridge` but do not call `PhoneAccessibilityService.invalidateSnapshot()`. Its retained node copies, fingerprints and `capturedToken` may remain until another accessibility event, another inspection or service destruction. Five-second click expiry prevents later use but does not itself dispose stored objects. This is in-process retention, not a demonstrated remote read after stop.

**Required fix/evidence:** explicit main-thread snapshot disposal on revocation, failure and accessibility disconnect; test retained node/handle count is zero immediately after stop and that old handles cannot revive after restart. Do not claim cryptographic zeroization of JVM strings or Android caches.

### A4 — Closed transport can leave misleading ACTIVE status (medium; BLOCKED pending recovery design)

Baseline transport calls its own `close()` on a fatal accept `IOException`, but has no lifecycle callback to clear `PhoneBridge.active` or stop the foreground service. UI/notification can continue announcing an active endpoint that is no longer listening. This is an availability/status defect, not evidence of unauthorized access.

**Required fix/evidence:** report endpoint failure to the Android owner, revoke token/scopes and dispose snapshot, show stopped/error status, require new local activation. Inject listener failure and check UI, notification, sockets and token state; also test bind conflict.

### P1 — JSON-RPC response inputs use incorrect HTTP disposition (medium; BLOCKED pending protocol fix)

For a valid incoming JSON-RPC response such as `{"jsonrpc":"2.0","id":1,"result":{}}`, baseline dispatcher requires `method`, so transport emits HTTP 200 with `-32600`. Streamable HTTP specifies 202 if a response/notification is accepted, or an HTTP error if rejected. A server that issues no requests may reject unsolicited responses, but should not return the normal request-response disposition. [MCP 2025-06-18 transport specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/transports).

**Required fix/evidence:** explicit request/notification/response classification and tests for success/error responses, malformed inputs, and old-version batches. Preserve no execution of tool notifications. Validate with a real MCP client/Inspector rather than only handwritten requests. Core transport/dispatcher changes belong to their current owner; this audit makes none.

### P2 — Compatibility and lifecycle claims exceed current evidence (NOT RUN)

Baseline advertises two versions, stateless JSON replies and no SSE/session IDs. Absence of SSE and stateful session IDs is not inherently a defect. However, host tests do not prove full schema validation, initialize/initialized interoperability, version mismatch handling, cancellation behavior, optional-field behavior or every HTTP media-type case. No OAuth discovery is implemented; native manually configured bearer clients are the intended subset, not direct ChatGPT compatibility. Follow the [MCP lifecycle specification](https://modelcontextprotocol.io/specification/2025-06-18/basic/lifecycle).

**Gate:** run a documented real-client matrix for both advertised versions; list intentional unsupported features. Do not call it unrestricted/full MCP compliance based on 13 unit tests.

## Required Android acceptance matrix (all NOT RUN at baseline)

Run automated controllable fakes for race cases and real-device checks for Android behavior. Record build hash, API/OEM version, test name, expected result, observed result and evidence path.

1. **Activation:** fresh process/reboot has no endpoint or restored authority; scope defaults, canceled consent, permission denial, repeated Start, activity recreation, background/foreground and port conflict cannot silently enable control.
2. **Revocation:** UI Stop and notification Stop revoke synchronously; socket close, token rotation and scope changes cannot admit old requests (A1). Process kill/reboot never restores a session. Include requests queued on the Android main thread.
3. **Locks and timeouts:** locked/noninteractive state blocks read/actions; lock while inspection or fingerprint traversal runs causes refusal before output/action. Expired queued work causes no action. A timed-out already-started platform action must report unknown outcome and must not be blindly retried.
4. **Scopes:** empty/read-only/action-only/both combinations; click requires current read snapshot and action authority; remote tools cannot change scopes or accessibility settings.
5. **Snapshot identity:** row recycling, descendant label changes, checked/selected state changes, window/app switch, password/editable/sensitive ancestors and descendants, malformed trees, event debounce and handle expiry. Reject stale targets and bound work.
6. **Privacy:** exclude editable/password/data-sensitive controls and protected packages; verify ordinary labels can still be sensitive and disclose this limitation. Validate on Compose, WebView and OEM apps. No exact semantic classifier is claimed. Test snapshot disposal (A3), no token/screen logs, backup exclusion and token UI protections.
7. **Lifecycle/status:** accessibility revoke/disconnect, notification permission/channel changes, Android foreground-service restrictions and battery/process management. No falsely ACTIVE status after transport death (A4); user always has a usable local stop path.
8. **Network:** only loopback listens; unauthorized/hostile-Origin requests fail; no outbound traffic; controlled port takeover, stale client and local saturation tests use synthetic tokens. See existing `DEVICE_TEST_PLAN.md`.
9. **Consent integrity:** full/partial overlay attacks cannot activate or broaden authority (A2); normal assistive navigation remains usable.
10. **Usability:** large fonts, screen reader labels, rotation, system bars, scope clarity and error recovery. Define minimum supported languages and localize visible text or explicitly limit launch market.

## Threat-model and release decisions (BLOCKED until explicitly resolved)

- **Server identity:** bearer authentication authenticates the client, not the phone endpoint. Another local app can occupy the port after stop and impersonate responses or capture credentials sent to it. Existing documentation discloses this, but a production pairing/server-authentication design or explicit constrained-risk acceptance is still needed. A different port is not server authentication.
- **Availability:** bounded input and queue tests do not provide a response-write deadline, saturation immunity, or cancellation of platform IPC. Set and test availability/recovery expectations; make no blanket DoS-resistance claim.
- **Dangerous actions:** `screen.act` can click ordinary-app controls that send messages or purchase items. Package filters and tool descriptions do not enforce a semantic transaction confirmation policy. Decide whether broad trusted-client control is the product or whether per-action user confirmation/allowlisting is required. Preserve truthful warnings; do not claim purchases are technically blocked.
- **Distribution:** Play accessibility/special-use foreground-service eligibility, disclosures and current target requirements need review if Play is chosen. Generic autonomous phone control has material policy risk; direct distribution is a separate decision, not a compliance workaround automatically authorized here. Review the [official sensitive-permissions policy](https://support.google.com/googleplay/android-developer/answer/16558241) for the final intended behavior and submission date.
- **Release operations:** owner-approved identity, signing-key custody, non-debuggable signed release artifact, dependency inventory/license review, privacy policy, support/security contact, update/rollback process, price/currency and commercial fulfillment. No repository URL is supplied; do not push/create a repository as part of acceptance.

## Final sign-off rule

After authorized fixes: rerun core tests, Android tests, assembly and lint against the **same final source**; replace stale verification evidence, preserve hashes, and execute device gates. A test added but not run remains NOT RUN. A source review or passing JVM fake is not a device test. Any remaining blocked gate must be explicitly accepted or resolved by the appropriate owner; do not rename a debug preview “production-ready.”

## Progress addendum — 2026-10-31 02:53 UTC (not release sign-off)

The original baseline findings above remain historical. Current source adds immutable controller session identity, activation tickets, snapshot disposal on revoke, endpoint-specific failure callbacks, full/partial obscured-gesture protection, notification visibility checks and delete-action revocation, bounded response writes/serialization, and six bounded tools including scroll/set-text. Source review is recorded separately in `FRESH_SECURITY_REVIEW.md`.

| Narrow current gate | Status | Evidence / remaining limit |
|---|---|---|
| Frozen current source core tests | PASS | `verification/production-host`:24 cases,0failures. JVM fake controllers, not Android accessibility. |
| Android host/framework tests | PASS |86 cases:39session,38UI,7editable policy,2fingerprint. Runtime26/28/35 coverage depends on each test's Config; noAPI36 runtime claim. |
| Debug APK, instrumentation APK, debug lint | PASS | Serial build58seconds; compileSDK36,min26,target35. Lint report preserved:0errors,1OldTargetApi warning (target35 deliberately unchanged pending modern-OS qualification). |
| API36 multi-label source handling | NOT RUN onAPI36 | Guarded list getter, bounded/refreshed labels and all-label credential policy implemented/reviewed. Pure policy cases pass; API36-only fixture remains unexecuted. |
| API35 real lifecycle baseline | PASS narrowly | Earlier2 real cases: protected UI/recreation and actual HTTP Stop/restart/token rotation. Not final-source qualification. |
| Current real accessibility suite | NOT RUN to completion | Corrected setup proved own-token-screen protection. One fixture-tree timeout remains unresolved; software emulator native crash interrupted remainder during host contention. Logs preserve infrastructure failure separately. Serial emulator-only rerun underway, not counted passed. |
| Official MCP SDK against emulator | NOT RUN | Host fake-controller SDK2.3.0 interop passed; emulator lifecycle pass not yet established. |
| Grok lifecycle diagnostic | BLOCKED interpretation | Genuine contributed diagnostic produced3control passes and3manual old-destroy/new-ticket preservation failures. Artificial callback ordering does not establish Android scheduling feasibility or authority escalation. Exact diagnostic/evidence retained outside green suite under `verification/grok`; no speculative production fix applied. |

Physical Samsung/current OS, current distribution policy/target, paid offering currency/channel, production signing, support/privacy/update operations and reciprocal Grok critique remain open. No overall production-readiness claim is made.

### Joint Grok review queue (after frozen device baseline)

- Per-session authenticated **time-based tool-call rate limiting** with rejection before execution; concurrency/queue bounds alone are not equivalent. Preserve deliberate legacy batch semantics. Independent limiter/test contribution pending.
- Strict HTTP Accept media-range/quality parsing: reject substring lookalikes and unusable q=0 negotiation. Regression cases pending.
- Align JSON Schema text maxLength (Unicode code points) with runtime currently limiting UTF-16 units; choose one consistent documented bound, not a blanket reduced ASCII limit.

These are open release gates, not completed fixes. They do not invalidate narrow historical test evidence but prevent calling the frozen source a production release.

### API35 serial baseline result (03:08 UTC)

Nine applicable instrumentation cases completed:3passes (own token screen protected, real HTTP Stop/restart/token rotation, secure UI/recreation) and6failures. Five failures traced to the synthetic test APK Activity crashing with missing Kotlin Intrinsics, leaving a system crash dialog; production PROTECTED_SCREEN rejection was correct. Separate test-only fixture APK is the correction, not a security-policy exception. One notification test queried before a record was posted; requested immediate API31+ visibility and a bounded asynchronous-posting wait are being verified.

Official MCP SDK2.3.0 real-emulator lifecycle passed active/stopped/restarted phases with initialization/reinitialization, six-tool listing, phone_status, invalid/old token rejection and post-Stop disconnection. `verification/interop/emulator-*.json` and `emulator-instrumentation.log` contain evidence; the instrumentation coordinator passed1test in39.009seconds. This validates protocol/lifecycle, not screen actions or physical-device behavior.
