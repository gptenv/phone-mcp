# Official SDK interoperability

## Preserved four-tool baseline host acceptance — 2026-10-31

Official Python SDK **`mcp==2.3.0`** against compiled Java `LoopbackMcpServer` with a deliberately fake controller, bound to `127.0.0.1:8765`. Server and client run in the same process/network namespace. A fresh random fixture token is generated for every run and never printed. No phone connection, accessibility, installation, screen inspection or actual phone action is part of this host test.

Local evidence is retained privately, not shipped in the public source. Reproduction writes a fresh report to the ignored verification directory.

| Client mode | Negotiation | Result |
|---|---|---|
| High-level SDK `Client`, default `auto` | SDK probes `server/discover` with 2026-07-28 header; receives 400, falls back to `initialize` proposing 2025-11-25; server selects 2025-06-18 | PASS |
| High-level SDK `Client`, `legacy` | Proposes 2025-11-25; server selects 2025-06-18 | PASS |
| Typed low-level SDK explicit initialize | Requests and adopts 2025-06-18 | PASS |
| Typed low-level SDK explicit initialize | Requests and adopts 2025-03-26 | PASS |

Every mode executed initialized notification (HTTP 202), `list_tools` (four schema-validated tools), successful fake `phone_status`, and a fake `screen_inspect` denial carried as `is_error=true` rather than a transport failure. The fake response includes a per-run nonce to ensure the test reached its own fixture.

The SDK did **not** open a GET stream for this stateless server. Each scenario separately issued an explicitly labelled HTTP GET probe and verified 405. This is not misrepresented as SDK-generated GET traffic. The high-level SDK cannot directly pin handshake-era versions; the two explicit-version scenarios use public typed `ClientSession.send_request`, `adopt` and `send_notification`, not SDK constant monkeypatches or handwritten HTTP requests.

These checks are narrower than full protocol certification. No SDK-driven legacy batching, OAuth discovery, SSE delivery, external host product integration, adversarial client campaign or physical-device certification was performed. Unsupported current-version discovery correctly falls back in this tested SDK; no support for protocol 2026-07-28 is claimed.

## Six-tool schema acceptance — 2026-10-31

After the explicitly scoped scroll/non-sensitive-text extension, the same four SDK negotiation modes passed against the updated six-tool Java fixture. The historical four-tool result is not reused as proof for the expanded inventory.

Current inventory: `phone_status`, `screen_inspect`, `node_click`, `global_action`, `node_scroll`, `node_set_text`. Each current SDK scenario also calls the new tools against the fake controller with a synthetic handle and non-sensitive fixture text and verifies a proper tool-error response. This validates SDK transport/schema/error interoperability, **not** real scrolling or editing, field eligibility, redaction, or Android scope enforcement. The device acceptance script now expects six tools but still calls only `phone_status`.

## Reproduce host test

Use a private virtual environment and the pinned dependency lock; an available JDK and `org.json:json:20240303` JAR are required. Port 8765 must be free. This script launches **only its own local fake server**, compiles current core source directly, and always stops that process on exit.

```sh
python3 -m venv /your/private/path/mcp-interop
/your/private/path/mcp-interop/bin/pip install -r scripts/interop/requirements-lock.txt
/your/private/path/mcp-interop/bin/python scripts/interop/run_interop.py \
  --java-home /path/to/jdk \
  --json-jar /path/to/json-20240303.jar \
  --output docs/verification/interop/python-sdk-2.3.0-six-tools.json
```

The script bypasses environment proxies for loopback requests, logs only protocol metadata, and removes the prior report before a new run so failure cannot leave a stale PASS. The exact SDK is also checked at runtime. Dependency pins describe this test environment, not Android production dependencies.

## Optional separately authorized Android/emulator acceptance

`scripts/interop/sdk_client.py` never installs an app, starts a session, grants permission, reads screen contents or performs actions. It requires a caller-controlled, explicitly activated session and a local forward to port 8765. Tokens enter through environment variables only; do not put them in command-line arguments, shell history, reports or source files.

After caller-authorized setup, supply `PHONE_MCP_TOKEN` privately and run:

```sh
/your/private/path/mcp-interop/bin/python scripts/interop/sdk_client.py \
  --label emulator-active --output docs/verification/interop/emulator-active.json
```

It checks wrong-token HTTP 401, GET 405 and two fresh SDK connections, each performing initialize/initialized/list_tools/phone_status. It verifies `active=true` but does not report personal screen content.

For a caller-coordinated Stop, retain the prior token and add `--expect-stopped`. The standalone client passes only the narrow **host endpoint observation** when it receives explicit HTTP 401/503 or a `ConnectError` whose causal OS exception has `errno.ECONNREFUSED`. Even these observations set `phone_stop_proven:false`: credential rejection, reported unavailability or host connection refusal does not itself prove phone Stop or healthy ADB/device state. Timeout, EOF, protocol error, reset and generic connection/ADB failure are INCONCLUSIVE (exit 2), never PASS. Other HTTP statuses fail (exit 1). For a caller-coordinated restart, supply the new `PHONE_MCP_TOKEN` and old `PHONE_MCP_OLD_TOKEN`; it additionally checks rotation and old-token HTTP 401. Stop/start controls are intentionally outside the script. A connection failure by itself does not prove token revocation on a replacement endpoint; the restart check is separate.

Actual emulator/device results are **not implied by the host report**. They require their own generated reports plus caller evidence identifying APK hash, device/API and authorized lifecycle steps. Generic endpoint labels in the script cannot certify Samsung hardware.

## Primary API references

- [Official Python SDK repository](https://github.com/modelcontextprotocol/python-sdk)
- [SDK client transports and injected HTTP client](https://py.sdk.modelcontextprotocol.io/client/transports/)
- [SDK protocol negotiation and legacy modes](https://py.sdk.modelcontextprotocol.io/protocol-versions/)
- Installed pinned official SDK source was inspected for the typed `ClientSession.initialize`/`adopt` APIs used by the explicit-version scenarios.

## Acceptance-client review correction — 2026-10-31

The original stopped-phase client treated every transport exception as success. The historical observation recorded `RemoteProtocolError` and was corrected to **INCONCLUSIVE**, not reused as a pass. EOF through an ADB forward is inherently ambiguous. Do not cite that observation as proof of endpoint closure or token revocation; the earlier active/restarted observations are separate evidence, not a remedy for the stopped-phase gap.

Actual emulator Stop acceptance must correlate test-only instrumentation showing Stop processed, `PhoneBridge` inactive and token revoked, plus device-local `ECONNREFUSED` while the device/forward are independently healthy, followed by host observation and restart with a new token/old-token HTTP401. No production diagnostic API is added. A host exit 2 must be preserved as inconclusive; orchestration can continue separately authorized restart checks without converting that result into PASS.

The hardened client rejects **every HTTP 3xx response** in a response hook before SDK processing, including same-origin redirects. A sticky request hook prevents auto-negotiation fallback from continuing after a rejected redirect. `follow_redirects=False` alone is not relied upon because SDK 2.3.0 has its own redirect handling. Only the two exact URLs `http://127.0.0.1:8765/mcp` and `http://localhost:8765/mcp` are allowed; userinfo, query strings, fragments, other ports/schemes and alternate spellings are rejected. CLI failures emit fixed JSON codes without exception tracebacks, Location values, rejected arguments or token headers; third-party diagnostic logging is suppressed in this dedicated CLI.

`test_sdk_client.py` uses pinned SDK 2.3.0 and an in-process MockTransport, not a real phone or listener. Deterministic coverage includes timeout/EOF/reset-like failures, explicit rejection/unavailability, causal connection refusal, all 3xx status classes, the real SDK's same-origin redirect path, exact URL checks, raw/single-Bearer normalization, sanitized failures and successful reinitialization. This test run does not replace a new coordinated emulator/device acceptance run.

```sh
PYTHONDONTWRITEBYTECODE=1 /your/private/path/mcp-interop/bin/python -m unittest discover \
  -s scripts/interop -p test_sdk_client.py -v
```
