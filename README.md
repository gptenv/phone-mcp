# Phone MCP — Android developer MVP

An opt-in Android app that exposes a **limited set of visible-phone interactions** as authenticated local MCP tools. Kotlin Android app plus independently testable Java protocol core. No root, cloud account, mirror worker, ChatGPT integration, billing, or telemetry.

**Status:** developer prototype, not a store release. Intended eventual paid download: $5 (currency and store pricing not configured). Package: `dev.phonemcp.android`; minimum Android 8 / API 26; targets API 35. Samsung S25 Ultra is an intended test device, not yet device-certified.

## Verification status (blunt)

**Passed (reported cloud build, see review-material/CHECKPOINT.md):** 24 core JVM tests, 86 Android host tests, debug/test APK assembly, lint with 0 errors. There is no CI configured in this repository yet. **Not yet passed:** device acceptance. Fixture screen actions, API 36 labels, Stop/Start lifecycle and Samsung S25 qualification have no passing device evidence. **Not yet run at all:** AcceptHeaderTest and AcceptHeaderRegressionTest (added after that build), and limiter/Unicode/Accept integration. Treat anything not listed as passed as unverified.

## Build

Install JDK 17 or 21 and Android SDK platform 36/build-tools 35.0.0. Set `ANDROID_HOME` or add `sdk.dir=/absolute/sdk/path` to local.properties (not committed).

```sh
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Use Android Studio to open this folder. A debug APK is for local development, not distribution or Play signing. Assembly itself does not install anything. Automated tests have installed debug/test APKs only on a dedicated synthetic emulator; no personal phone installation has been performed.

## Activate deliberately

1. Install your locally built APK on your own test device when ready.
2. Read the disclosure. For screen tools, explicitly enable Phone MCP in Android accessibility settings. A status-only session does not need accessibility.
3. Return to Phone MCP; choose scopes (both are required for node actions) and accept the session consent. Leaving both off deliberately permits only `phone_status`. On Android 13/API 33+, grant notification permission; on every supported version, app notifications and the Phone MCP session channel must remain enabled. A denied/disabled notification setting blocks activation and offers a settings route; returning never auto-starts a session.
4. Tap Start. A new random bearer token is shown **only on the phone**, held only in memory, and revoked by Stop or process death. Scopes cannot be broadened remotely.
5. Stop using the app or ongoing notification. There is no auto-start or sticky restart.

No endpoint is reachable over Wi-Fi: it binds only to `127.0.0.1:8765`. For a trusted development computer connected by authorized USB debugging:

```bash
adb forward tcp:8765 tcp:8765
# Bash: enter the token privately; do not paste it into a command or commit it.
read -r -s -p 'Session token: ' PHONE_MCP_TOKEN; printf '\n'
export PHONE_MCP_TOKEN
python3 scripts/mcp_smoke.py
unset PHONE_MCP_TOKEN
```

The smoke script performs initialization, the initialized notification, tool listing, and status only—no screen reads or actions. It reads the token from the environment, avoids credential-bearing command-line arguments, and disables HTTP proxies. Environment variables are still readable to sufficiently privileged local software; use only a trusted computer/account and unset the variable when finished. Do not use `curl -H "Authorization: Bearer …"` with a real token: expanded arguments may be visible in process listings.

A custom trusted client must send `notifications/initialized` after initialization, then `tools/list` and `tools/call`, with bearer authorization, JSON content/accept headers, and `MCP-Protocol-Version: 2025-06-18`. Example request body:

```json
{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"screen_inspect","arguments":{}}}
```

Remove the development forwarding when done: `adb forward --remove tcp:8765`; `unset PHONE_MCP_TOKEN`. Never expose this port through a tunnel, public proxy, or LAN binding.

## Bounded tools

| Tool | Scope | Purpose |
|---|---|---|
| `phone_status` | session only | readiness and current control state |
| `screen_inspect` | `screen.read` | bounded accessibility tree of visible screen |
| `node_click` | `screen.read` + `screen.act` | click an eligible node from a recent snapshot |
| `node_scroll` | `screen.read` + `screen.act` | one forward/backward scroll on a fresh eligible node |
| `node_set_text` | `screen.read` + `screen.act` | set/clear up to 1,000 UTF-16 units in an eligible ordinary text field, never submit |
| `global_action` | `screen.act` | back, home, or recents |

Android may withhold or incompletely describe UI. This is **not unrestricted phone control**. No credential entry or collection, clipboard reading, screenshots, shell, app installation, contacts, SMS, microphones, cameras, files, background usage history, payment tool, or permission granting. Known security-sensitive/system/settings/own-app screens are blocked, password/accessibility-sensitive fields are omitted; eligible ordinary editable fields expose metadata and action handles but never their values, stale node handles are rejected, and phone actions are disabled while locked. Redaction is best-effort: custom or incorrectly labelled controls, ordinary text, and unrecognized apps can expose secrets or personal data. It is not a guarantee that every credential or sensitive screen will be recognized. Actions can still have consequences in ordinary apps: use only a trusted client and carefully chosen scopes.

## Protocol

Minimal stateless MCP Streamable HTTP, JSON replies, protocol versions 2025-03-26 and 2025-06-18. No SSE stream, subscriptions, resources, prompts, OAuth server, or session persistence. Browser-origin requests are rejected; no CORS. This first slice intentionally supports native/local clients with bearer-token configuration, not direct ChatGPT connectivity.

Read [security boundaries](docs/SECURITY.md) and [device acceptance checklist](docs/DEVICE_TEST_PLAN.md) before use. No Play eligibility is claimed; Accessibility API and special-use foreground service declarations need policy review before a paid release.

**Paid-release caveat:** This is a sideload developer prototype, not a Play-ready product. Google's [Accessibility API policy](https://support.google.com/googleplay/android-developer/answer/16558241) restricts autonomous agent decision-making/actions through accessibility. Consent alone does not establish eligibility; the distribution and interaction model must be reviewed before any $5 release.

**Uncertain actions:** If a request times out, an Android action may already have executed. Inspect the screen again; never blindly retry clicks. A timeout cannot undo an action already accepted by Android.

### Bounded local protocol behavior

Legacy `2025-03-26` batches support up to 32 messages with **at most one `tools/call`**; batches with more are rejected atomically with HTTP 400 before any tool executes. `2025-06-18` uses single messages. Unsolicited JSON-RPC responses are rejected with HTTP 400 because the server issues no client requests. Input has a five-second deadline from socket acceptance; stalled response writes have a separate five-second close watchdog. These are not overall operation or availability guarantees.

Serialized tool text is capped at 512 KiB; complete responses at 2 MiB. Serialization also limits depth and value count. `RESULT_TOO_LARGE` is a tool error **after possible controller execution**, not permission to retry a click. Inspect state first when an action outcome is uncertain. See `docs/SECURITY.md` for limits and the local server-identity threat model.
