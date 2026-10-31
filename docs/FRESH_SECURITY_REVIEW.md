# Independent fresh security review — 2026-10-31

Reviewer: a separate read-only reviewer, not an author of the transport or Android lifecycle changes. Scope: `LoopbackMcpServer`, `McpDispatcher`, `BoundedJson`, strict input parser, `MainActivity`/`PhoneBridge`, `McpService`, `SessionVisibility`, `PhoneAccessibilityService`, and the current host/instrumentation tests. Only this report was written by the reviewer. Source is being developed concurrently; line references describe the review snapshots below and must be rechecked on the final artifact.

**Release decision: not production sign-off.** The initial review found two concrete security-hardening gaps and one rare cleanup defect; the authorized six-tool follow-up found two further eligibility/identity gaps. The implementation owners applied source fixes for all five during review. Runtime verification of those fixes and the remaining release gates still matters. No penetration test, phone test, store approval or signing review was performed by this reviewer.

## Findings

### F1 — Refreshed target still trusted cached ancestor sensitivity (medium)

**Original location:** `PhoneAccessibilityService.kt:177–185` (review snapshot at 02:32 UTC).

The click path refreshed the selected node and all fingerprint descendants, but walked `node.parent` and tested each ancestor's sensitivity without refreshing it. An ancestor newly marked editable/password/data-sensitive during the accessibility event debounce interval could still be represented by a cached old node. This undermined the specific promise that newly sensitive containers block their clickable children; it is not evidence of arbitrary permission bypass.

**Requested closure:** refresh each ancestor before reading flags, fail closed on obsolete nodes, retain the depth bound, check the action deadline/authority during traversal, dispose the snapshot on failure. Add a regression in which the parent changes sensitivity while the selected child's own content is unchanged. Android documents that `getParent()` may retrieve cached nodes and `refresh()` obtains current state. [Official AccessibilityNodeInfo reference](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo).

**Source recheck at 02:34 UTC:** refresh and authorization checks are present at lines 182–185, with an outer failure path invalidating the snapshot at line 194. This addresses the source finding. A real accessibility-cache race regression is still required; ordinary successful click coverage is not that regression. Prefer `try/finally` for each temporary ancestor so exceptions thrown by `refresh()`/`getParent()` also release old-API pooled node copies.

### F2 — Notification enabled was mistaken for notification still visible (medium)

**Original locations:** `SessionVisibility.kt:11–15`; `McpService.kt:68–73` (review snapshot at 02:33 UTC).

The permission/app/channel checks remain true when an Android 14+ user swipes away an ongoing foreground notification. `setOngoing(true)` no longer guarantees that the Stop notification remains in the shade. Without a deletion handler, phone control could remain active after the advertised local indicator was dismissed.

**Requested closure:** treat notification dismissal as local Stop (for example an immutable delete PendingIntent) and test the actual Android 14+ swipe-dismissal path. Do not use a misleading exempt notification category just to prevent dismissal. [Official Android 14 behavior change](https://developer.android.com/about/versions/14/behavior-changes-all#non-dismissable-notifications).

**Source recheck at 02:34 UTC:** `McpService.kt:73` now attaches `setDeleteIntent(stop)` to the same explicit Stop action. This addresses the missing source path. A direct invocation of the Stop method or PendingIntent does not by itself prove actual OS dismissal delivery; retain a device test for notification swipe, revocation, socket closure and fresh local restart.

### F3 — Accepted socket can be lost before tracked ownership (low)

**Location:** `LoopbackMcpServer.java:68–79` (review snapshot at 02:34 UTC).

After `listener.accept()`, the code calls `setSoTimeout()` and the optional test send-buffer setter before adding the socket to `sockets`. If socket setup throws an IOException, `unexpectedFailure()` closes the listener/tracked sockets but has no reference to this just-accepted untracked socket. This is a rare resource-cleanup failure path, not a demonstrated client-driven exploit.

**Requested closure:** establish tracked ownership immediately after accept or retain a local try/finally until successful worker handoff. Failure injection should verify the accepted socket, listener, worker and watchdog are closed, with one owner callback. Source recheck at 02:41 UTC: accepted sockets are tracked under the same synchronized closed-state gate before configuration (`LoopbackMcpServer.java:75–81`). The deterministic setup-failure and concurrent intentional-close regression is present in `McpTest.java:179–207`. This closes the source finding; aggregate execution evidence belongs in the build/test report.

## Changes that appear sound within the limited scope

- Each Android controller is bound to its creating server's immutable token, and every operation checks that token against current session state. A stopped server cannot adopt a later global token.
- One-use local activation tickets are invalidated by Stop. Mismatched stale tickets leave a newer grant intact; the service is non-exported and requires an explicit START ticket.
- Failure callbacks are scoped to the service token and current bridge token. Delayed old callbacks cannot revoke a newer session through this callback path.
- UI, Stop, failure and destroy paths clear token/scopes and call synchronous main-thread snapshot revocation. JVM strings are not cryptographically zeroized, and no such claim should be made.
- Activity and consent dialogs have secure windows and full-gesture obscured-touch rejection; partial-occlusion handling is guarded by API level, and supported devices hide other-app overlays. This needs physical/OEM validation and accessibility usability testing.
- Input framing rejects ambiguous duplicate headers, transfer encoding and unexpected framing; body/header/depth budgets precede parsing. Loopback binding, exact local Host checks, Origin rejection and constant-time bearer comparison are present.
- Accepted-time input deadlines and a separate write-close watchdog bound stalled I/O. Completed watchdog tasks are canceled/removed. Serialized tool output and total output have byte/depth/value limits; legacy multi-tool batches are rejected before effects, rather than pretending earlier actions were rolled back.
- Unsolicited response messages are rejected with HTTP errors because this server has no outstanding server-originated requests. Real-client interoperability remains a separate acceptance gate.

## Residual release gates — not cleared by source review

1. **Actual Android behavior:** full Stop/restart, permission/channel revoke, notification dismissal, lock transitions, stale/recycled nodes, changed ancestors, foreground service restrictions, process kill/reboot, and responsiveness during slow accessibility IPC. Framework fakes and API35 emulator tests do not certify a physical Samsung S25 Ultra or all supported APIs.
2. **End-to-end races:** current unit tests invoke an old controller after token rotation, which checks the key authority boundary. Also retain a deterministic request paused after old HTTP authentication and resumed after rotation, including shutdown/batch edges, if claiming full end-to-end race coverage.
3. **Snapshot disposal coverage:** the current host test seeds and checks snapshot metadata. Also seed retained node/fingerprint entries and assert those collections empty immediately on every revocation path; do not infer that from metadata alone.
4. **Trust model:** bearer authentication does not authenticate the local server to the client. Port takeover, malicious local software, stolen tokens and privileged hosts remain risks. The distribution/pairing model needs explicit owner acceptance or a server-identity design.
5. **Action semantics:** the initial four-tool scope has been superseded by explicit owner authorization for six tools, adding bounded `node_scroll` and `node_set_text`. This remains a limited product, not general phone control: no coordinates, explicit submit, clipboard, arbitrary app management, usage-history access, files, messages API, camera/microphone or cloud integration. Ordinary eligible clicks may still send messages or purchase items. Do not call this unrestricted/full phone control or claim semantic payment blocking.
6. **Availability:** bounded queues, output and I/O timers do not promise flood immunity or cancellation of Android IPC already in progress. The main thread may still wait in framework calls; measure and disclose behavior rather than claiming hard real-time cancellation.
7. **Release operations:** real MCP-client matrix, final same-source aggregate tests/lint, dependency/license inventory, privacy/support policies, distribution/price/currency decisions, owner-controlled signing/update process and non-debug release verification remain necessary. Do not substitute debug signatures or unsigned build success for production signing.

## Reviewed-source fingerprints (02:34 UTC snapshot)

- `PhoneAccessibilityService.kt`: `449353a59ae97030389c397479713eac2887c810e04bc4915bf7adf62c02eb45`
- `McpService.kt`: `1ed537c655cd600632b71b7cc3fb10e1c1e79002ee52815db22407c26966bcf3`
- `LoopbackMcpServer.java`: `a651b657dddc7718464a6c4a83fb6d6b2fdf72625ba4d1a11834ac865eff0703`

Later edits require a focused recheck and new evidence. This report deliberately does not assert that concurrent emulator or interoperability tests passed.


## Six-tool expansion follow-up (02:41 UTC; source changing)

The owner explicitly authorized bounded node scrolling and non-sensitive text replacement (at most 1000 UTF-16 code units). This is not speculative scope expansion by the reviewer. The source uses the existing immutable-session/read+act/unlocked/notification guard; one native action only; all node actions invalidate handles after success or failure; editable values are not returned in the inspected field stub; payloads are not returned in action success responses. Core argument validation rejects non-string, over-limit and extra-key inputs. Empty text intentionally clears. Text-change listeners in the target app may cause side effects even without an explicit submit action.

### F4 — Editable fingerprint omits semantic field metadata (medium; source fix verified)

At `PhoneAccessibilityService.kt:115–125`, the editable fingerprint retains class/package/bounds/window/view ID/unique ID/input type/state/actions, but not hint or content description. Those metadata are used for eligibility, but a recycled same-ID field changing from ordinary “Title” to ordinary “Message” remains eligible with the same fingerprint. A previously inspected handle could therefore write to a different-purpose field. Include bounded non-value metadata in the digest and test this change without relying on accessibility event invalidation. Do not include the actual editable value or export these hidden metadata as field contents.

### F5 — Standard associated credential labels are not checked (medium; source fix verified for singular labels)

At `PhoneAccessibilityService.kt:86–93`, credential metadata is limited to ID, content description and hint. A standard TextView associated using `labelFor` can label an otherwise generic text field “Username” or “PIN”; `AccessibilityNodeInfo.labeledBy` is not consulted. This can permit a credential field with explicit platform label metadata, rather than merely an inherently unknowable custom control. Use a bounded, refreshed, nonsensitive associated-label check (including identity in the fingerprint) and add a label-only credential fixture. Never traverse editable label values; fail closed on unavailable/oversized/sensitive associated labels. Heuristic language coverage and genuinely mislabelled custom controls remain limitations, not a guarantee of semantic credential detection.

**Required runtime additions:** ordinary set/clear with no value echo; both scroll directions; wrong-type and read-only denials; 1000/1001 UTF-16 boundaries; editable changing to password/input-type sensitive; ancestor or linked label changing to credentials; same-ID ordinary field metadata changes; stale/expired handles, lock/Stop/session rotation and queued timeout for both new mutations. Current source contains ordinary set/clear/scroll/read-only/wrong-type instrumentation fixtures, but this reviewer has not run or certified them.

Associated-label API verification: the [official AccessibilityNodeInfo reference](https://developer.android.com/reference/android/view/accessibility/AccessibilityNodeInfo#getLabeledBy()) documents the singular label getter from API17 and the multi-label replacement in API36. SDK35 source/testing must not be treated as comprehensive validation of API36 multi-label credential handling.


### Follow-up source closure recheck (02:45 UTC)

- **F4:** `PhoneAccessibilityService.kt:134–135` now includes bounded hint, description and linked-label metadata in editable identity. `EditableFingerprintTest` exercises changed hint/description and verifies changing only the editable value leaves the digest unchanged. Observed local Gradle XML at 02:45:11 reports **2 tests, 0 failures, 0 errors** (API28/35). This is a digest regression, not proof of real cross-process recycled-node timing.
- **F5:** lines 89–101 now obtain the singular associated label, refresh before reading, reject sensitive/unavailable labels through fail-closed credential markers, bound each metadata string, and recycle in `finally`. The separate test APK contains a Username `labelFor` field and the device test expects it not to produce an editable handle. Actual fixture execution remains pending reviewer evidence. API36 multi-label support remains a release compatibility gate.
- No additional concrete source blocker was found in this focused six-tool pass. This is not production sign-off and does not clear the residual gates above.

Follow-up hashes: `PhoneAccessibilityService.kt` = `140ba81241d818f006c5a4b6772d789fc96885186d4294c2391516a8dcaf81bb`; `LoopbackMcpServer.java` = `b16f755c87cbec6ea8ca970ad9b59cadf95d59565aaa410753afda9e1775da5b`. These replace the earlier snapshots only for this focused follow-up; final artifact changes require recheck.


### API36 multi-label recheck (02:47 UTC)

Current source compiles against SDK36 (target35, minimum26) and guards the new `labeledByList` getter at runtime with SDK>=36; older APIs use the singular getter. `PhoneAccessibilityService.kt:101–114` rejects more than eight acquired labels before reading their metadata, refreshes each label, rejects sensitive/unavailable labels, bounds each of four strings, checks the aggregate 32768-character label budget, and returns a credential fail-closed marker if any later label is unsafe. The acquired list is recycled in `finally` even on early return. Metadata copying can reach the bounded eight-label per-string allowance before the aggregate check, so do not describe the aggregate threshold as an allocation-free/preallocation bound.

`EditablePolicyTest` now includes second-label PIN/username and count/aggregate overflow cases. These pure-policy cases do not execute the Android36 getter, Binder refresh, or platform cleanup path. The prior source-level API36 omission is addressed; an actual API36 multi-label credential fixture remains a runtime compatibility gate. No additional concrete bug found in this focused source change. Current API35 emulator tests cannot clear that API36 gate.

This independent review is **not a substitute for the separately requested genuine Grok collaboration/review**; that review and real-device acceptance are tracked by the implementation owner.

### Notification-posting and standalone-fixture recheck (03:09 UTC)

`McpService.kt:74` now uses API31-guarded `FOREGROUND_SERVICE_IMMEDIATE`. This is the correct documented mechanism for disabling the platform's intentional foreground-notification deferral; it is not a workaround that misrepresents the service category. [Official Notification.Builder reference](https://developer.android.com/reference/android/app/Notification.Builder#setForegroundServiceBehavior(int)).

The instrumented deletion-path test now waits at most ten seconds for the application's notification record, asserts the requested immediate policy where supported, then sends its delete PendingIntent and checks session revocation. That proves a posted record and the registered Stop dispatch path if it passes. It does **not** prove actual notification-shade rendering, OS delivery after a real swipe, or atomic notification visibility before `PhoneBridge.active` becomes true. `SessionVisibility` still checks notification permission/app/channel availability, not visual rendering. Do not report an absolute continuously visible indicator guarantee; actual OS swipe and OEM UI tests remain necessary. No test execution was performed in this recheck.

The synthetic Activity moved to a standalone `:fixture` application to give it its own packaged Kotlin runtime. Reviewed configuration: distinct application ID, `android:testOnly=true`, no permissions/services/providers/receivers, only the exported synthetic Activity; release variant disabled; explicit Kotlin stdlib dependency. Product `:app` has no fixture dependency and protected-package checks remain unchanged. Instrumentation references the new fixture package and declares test-only package visibility. This avoids weakening product protections to accommodate its test process. No new concrete source blocker found. Build success and emulator rerun remain the implementation owner's pending evidence, not findings inferred by this review.
