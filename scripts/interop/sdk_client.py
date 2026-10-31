#!/usr/bin/env python3
"""Read-only SDK checks for an explicitly activated session. No token/URL diagnostics.
Stop PASS means explicit 401/503 or positively identified ECONNREFUSED, not ambiguous EOF.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import errno
import importlib.metadata
import json
import logging
import os
from pathlib import Path
import re
import secrets
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from token_input import normalize_bearer_token

import httpx2
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

ALLOWED_URLS = frozenset(("http://127.0.0.1:8765/mcp", "http://localhost:8765/mcp"))

class AcceptanceFailure(Exception):
    """Contains a fixed diagnostic code only, never a URL, header, token or response body."""
    def __init__(self, code):
        self.code = code
        super().__init__(code)

class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse's default error includes rejected user arguments, potentially secrets.
        raise AcceptanceFailure("INVALID_ARGUMENTS")

def require(condition, code):
    if not condition: raise AcceptanceFailure(code)

def validate_url(url):
    # Exact comparison intentionally rejects userinfo, query, fragments and normalization tricks.
    require(url in ALLOWED_URLS, "INVALID_URL")

def concrete_connection_refusal(error):
    """Never infer refusal by parsing an exception message; require a real OS cause."""
    if not isinstance(error, httpx2.ConnectError): return False
    pending, seen = [error], set()
    for _ in range(16):
        if not pending: break
        item = pending.pop()
        if id(item) in seen: continue
        seen.add(id(item))
        if isinstance(item, OSError) and item.errno == errno.ECONNREFUSED:
            return True
        if item.__cause__ is not None: pending.append(item.__cause__)
        # An explicit cause takes precedence; do not classify from a suppressed stale context.
        elif not item.__suppress_context__ and item.__context__ is not None: pending.append(item.__context__)
    return False

def report_base():
    return {"timestamp_utc": datetime.now(timezone.utc).isoformat(), "sdk": "mcp", "sdk_version": "2.3.0",
            "target": "caller-supplied exact local endpoint; device identity supplied by caller", "device_certification": False}

def safe_failure_code(error):
    pending, seen = [error], set()
    for _ in range(64):
        if not pending: break
        item = pending.pop()
        if id(item) in seen: continue
        seen.add(id(item))
        if isinstance(item, AcceptanceFailure): return item.code
        pending.extend(getattr(item, "exceptions", ()))
        if item.__cause__ is not None: pending.append(item.__cause__)
    return "CHECK_FAILED"

async def check(args):
    validate_url(args.url)
    try:
        token = normalize_bearer_token(os.environ.get("PHONE_MCP_TOKEN"))
        old_input = os.environ.get("PHONE_MCP_OLD_TOKEN")
        old = normalize_bearer_token(old_input) if old_input is not None else None
    except ValueError:
        raise AcceptanceFailure("INVALID_TOKEN") from None
    require(importlib.metadata.version("mcp") == "2.3.0", "SDK_VERSION_MISMATCH")
    events, redirect_seen = [], []
    async def guard_request(request):
        # SDK auto-negotiation must not silently recover after a forbidden redirect.
        require(not redirect_seen, "REDIRECT_REJECTED")
    async def guard_response(response):
        if 300 <= response.status_code < 400:
            redirect_seen.append(response.status_code)
            raise AcceptanceFailure("REDIRECT_REJECTED")
    async def record(response):
        await guard_response(response)  # Runs before SDK redirect handling, regardless of follow_redirects.
        body = json.loads(response.request.content) if response.request.method == "POST" else {}
        events.append({"http_method": response.request.method, "rpc_method": body.get("method"),
                       "protocol_header": response.request.headers.get("mcp-protocol-version"), "status": response.status_code})
    report = report_base()
    label = args.label
    report["label"] = label if re.fullmatch(r"[A-Za-z0-9_.-]{1,80}", label) and not any(value in label for value in (token, old) if value) else "local-endpoint"
    headers = {"Authorization": "Bearer " + token, "Accept": "application/json, text/event-stream", "MCP-Protocol-Version": "2025-06-18"}
    ping = {"jsonrpc": "2.0", "id": 90, "method": "ping"}
    async with httpx2.AsyncClient(trust_env=False, follow_redirects=False, timeout=8,
                                event_hooks={"request": [guard_request], "response": [guard_response]}) as probes:
        if args.expect_stopped:
            report.update(check_kind="stop_endpoint_observation", phone_stop_proven=False,
                          requires_coordinated_device_evidence=True)
            try:
                response = await probes.post(args.url, headers=headers, json=ping)
                report["stopped_observation"] = "HTTP " + str(response.status_code)
                report["result"] = "PASS" if response.status_code in (401,503) else "FAIL"
                if response.status_code == 401: report["observation_meaning"] = "Provided credential rejected; not proof of phone Stop"
                elif response.status_code == 503: report["observation_meaning"] = "Endpoint reports unavailable; not proof of phone Stop"
                if report["result"] == "FAIL": report["reason"] = "STOP_NOT_CONFIRMED"
            except httpx2.TransportError as error:
                if concrete_connection_refusal(error):
                    report.update(result="PASS", stopped_observation="CONNECTION_REFUSED", observation_meaning="Host connection refused; not proof of phone Stop or device health")
                else:
                    observation = "TIMEOUT" if isinstance(error, httpx2.TimeoutException) else "TRANSPORT_FAILURE_OR_EOF"
                    report.update(result="INCONCLUSIVE", reason="STOP_OBSERVATION_AMBIGUOUS", stopped_observation=observation)
            return report
        invalid = await probes.post(args.url, headers=dict(headers, Authorization="Bearer " + secrets.token_urlsafe(32)), json=ping)
        require(invalid.status_code == 401, "INVALID_TOKEN_NOT_REJECTED")
        report["invalid_token_status"] = 401
        if old:
            require(old != token, "TOKEN_NOT_ROTATED")
            stale = await probes.post(args.url, headers=dict(headers, Authorization="Bearer " + old), json=ping)
            require(stale.status_code == 401, "OLD_TOKEN_NOT_REJECTED")
            report["old_token_status"] = 401
        get = await probes.get(args.url, headers=headers)
        require(get.status_code == 405, "UNEXPECTED_GET_STATUS")
        report["explicit_get_status"] = 405
    sessions = []
    for index in range(2):
        async with httpx2.AsyncClient(headers={"Authorization": "Bearer " + token}, trust_env=False, follow_redirects=False,
                                    timeout=8, event_hooks={"request": [guard_request], "response": [record]}) as http:
            async with Client(streamable_http_client(args.url, http_client=http), read_timeout_seconds=8) as client:
                require(client.protocol_version == "2025-06-18", "PROTOCOL_MISMATCH")
                inventory = await client.list_tools()
                names = sorted(tool.name for tool in inventory.tools)
                require(names == ["global_action", "node_click", "node_scroll", "node_set_text", "phone_status", "screen_inspect"], "TOOL_INVENTORY_MISMATCH")
                status = await client.call_tool("phone_status", {})
                require(not status.is_error, "PHONE_STATUS_UNAVAILABLE")
                payload = json.loads(status.content[0].text)
                require(payload.get("active") is True, "PHONE_STATUS_NOT_ACTIVE")
                sessions.append({"connection": index+1, "negotiated": client.protocol_version, "tool_names": names, "phone_status_active": True})
    require(not redirect_seen, "REDIRECT_REJECTED")
    require(sum(e["rpc_method"] == "notifications/initialized" and e["status"] == 202 for e in events) == 2, "INITIALIZED_NOT_CONFIRMED")
    report.update(result="PASS", sessions=sessions, sdk_events=events)
    return report

def main(argv=None):
    # Third-party HTTP/SDK diagnostics can contain Location, URLs or headers. This dedicated
    # CLI emits only its own sanitized JSON; never render chained exceptions or tracebacks.
    logging.disable(logging.CRITICAL)
    args = None
    try:
        p=SafeArgumentParser(prog="sdk_client.py")
        p.add_argument("--url", default="http://127.0.0.1:8765/mcp")
        p.add_argument("--label", default="local-endpoint")
        p.add_argument("--expect-stopped", action="store_true")
        p.add_argument("--output", type=Path, required=True)
        args=p.parse_args(argv)
        args.output.unlink(missing_ok=True)
        result=asyncio.run(check(args))
    except KeyboardInterrupt:
        result=dict(report_base(), result="INCONCLUSIVE", reason="INTERRUPTED")
    except Exception as error:
        result=dict(report_base(), result="FAIL", reason=safe_failure_code(error))
    try:
        if args is not None:
            args.output.parent.mkdir(parents=True,exist_ok=True)
            args.output.write_text(json.dumps(result,indent=2)+"\n")
    except Exception:
        result=dict(report_base(), result="FAIL", reason="REPORT_WRITE_FAILED")
    print(json.dumps(result,indent=2))
    return {"PASS":0,"INCONCLUSIVE":2,"FAIL":1}[result["result"]]

if __name__ == "__main__": sys.exit(main())
