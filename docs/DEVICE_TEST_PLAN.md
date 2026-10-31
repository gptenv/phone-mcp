# Device acceptance (not yet executed)

Run on a personally owned Samsung S25 Ultra and an API 26 device/emulator before release.

- Fresh launch: endpoint absent, scopes unchecked, consent required.
- Start with a screen scope before accessibility enable, or cancel consent: clear error/no unintended activation. With no scopes selected, explicitly confirm a status-only session; `phone_status` succeeds but all screen tools are denied.
- Enable accessibility manually; cancel permission flow; return and retry.
- On API 33+, grant/deny/dismiss notification permission (including repeated denial); verify no automatic start and a settings recovery route. On every supported API, disable app notifications and separately disable the session channel; activation must be blocked. Re-enable and explicitly start; verify visible foreground Stop and status. Test notification disablement during an active session separately.
- Start repeatedly: one endpoint and token; controls cannot broaden active scopes.
- Initialize MCP, initialized notification, list tools, read ordinary screen. Verify bounded data and no password/editable values.
- With read-only scope, action requests fail. Try unknown tool, extra arguments, malformed JSON, wrong token, missing token, hostile Origin, oversized request, unsupported protocol.
- With actions enabled, click a recent ordinary node; mutate window and retry old handle; expect stale rejection.
- Lock phone during a queued request; read/action must fail. Unlock and obtain a fresh snapshot.
- Open settings, permission dialog, package installer and app itself; inspect/actions denied.
- Revoke accessibility during session; requests produce availability errors, no stale action.
- Stop via UI and notification; existing token fails and socket closes. Restart: old token never works.
- Rotate activity, background app, swipe it away, kill process, reboot; no surprise reactivation. Validate Android foreground-service UI behavior.
- Verify no endpoint listens on LAN and no runtime network traffic leaves the phone.
- Confirm no user text/token appears in logcat or persistent files.
- Same-device port conflict: using a controlled test listener on 127.0.0.1:8765, attempt to start Phone MCP. Verify a visible bind error, inactive state, and no newly displayed or usable session token. Reverse the order and verify the test listener cannot bind while Phone MCP owns the port. Do not send a real session token to the test listener.
- Stale client and endpoint spoofing: after stopping a session, use a disposable synthetic token and controlled listener to verify whether an existing client continues sending requests or accepts fabricated responses. Record the behavior as a server-authentication limitation; ensure the operator stops the client and removes its ADB forward. Never use a real session token in this test.
- Separately, with no test listener present, start and stop a real session, start a fresh session, and send the old token to the verified real server. Expect HTTP 401. After removing the forward and stopping the server, check that the client reports a clear connection error and record any automatic retries.
- Local saturation: first measure authorized request latency without the test app running. Then, from a controlled second app, hold or repeatedly open unauthenticated loopback connections while an authorized client makes a request. Record connection count, duration, latency, rejection and recovery after the load stops. The current single-worker queue can deny availability under sustained load; do not interpret recovery as resistance to sustained attack.
- Recycled-list race: inspect a clickable row with empty parent label and text only in child nodes, replace the row content within the accessibility event debounce window, then click the old ID. Refreshed descendant digest must reject the stale handle. Test selected/checked/state-description changes and oversized/sensitive subtrees as well.
- Queue-age regression (host tests): occupy the single worker with a latch-controlled fake A tool call, submit a complete authenticated B tool request, wait over five seconds from confirmed acceptance, then release A. B must close without execution; a fresh authenticated C must succeed. Repeat releasing A before expiry to confirm B succeeds. This checks transport queue behavior, not Android action timeout behavior.

- Tapjacking: on a controlled test device, test full overlay and API29+ partial overlay against the main screen and each consent dialog. No scope/start action may occur. Test clean DOWN → obscured MOVE → clean UP: the gesture must remain cancelled. Verify a fresh clean gesture works. On API31+, confirm non-system overlays are hidden. Framework simulation is not proof of OS overlay behavior.

- Response-write recovery: use a bounded large synthetic result and an authorized local client that stops reading; verify the connection is closed by the separate five-second write watchdog and a fresh ping recovers. Observe resource/thread cleanup and latency on Android; JVM fake tests are not device proof. Sustained flooding can still deny service.
- Protocol/output budgets: exercise one-call legacy batches (accepted), multi-call batches (HTTP 400 with zero effects), unsolicited success/error responses (HTTP 400), UTF-8/escaping/depth/output limits, and oversized result errors. An oversized result can follow completed controller work; never infer rollback or retry automatically.
- Unexpected listener failure: inject a controlled failure, verify instance-specific callback revokes state and truthful UI status, then restart locally. Delayed callbacks from an old server must not stop a newer session. Deliberate Stop must not be reported as an unexpected failure.

## Six-tool and modern-label release gates

- Ordinary editable notes: inspect returns a fresh handle and metadata, never its value; set and clear work without focus/submit/clipboard. Reject password/credential hints, IDs, linked labels, input types, protected screens, stale/wrong-node handles and payloads over1,000 Unicode code points. No payload echo in responses/errors/logs.
- Recycled editable identity: change hint/description or external label while keeping class/bounds; old handle must fail. Do not assume stable field identity merely because value changes are intentionally not hashed.
- Android36+: a harmless first accessibility label plus credential-bearing second label must deny editing. Run the dedicated API36 fixture; an API35 skip is not success.
- Scroll one step forward/back with a fresh handle, reject wrong direction/type, read-only scope and stale handle; verify actual UI movement.
- Notification deletion Stop, permission/channel loss, fast local Stop→Start, locked-device state and old endpoint requests must revoke/fail closed without silently broadening scopes. Distinguish a safe failed start from unauthorized control.
