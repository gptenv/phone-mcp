#!/usr/bin/env python3
"""Read-only official-SDK acceptance client for an explicitly activated device session.
Tokens ONLY from environment; no activation, installation, screen reads, or actions.
Caller owns authorized start/stop/restart and supplies labels/evidence paths.
"""
import argparse
import asyncio
from datetime import datetime, timezone
import importlib.metadata
import json
import os
from pathlib import Path
import secrets
from urllib.parse import urlsplit

import httpx2
from mcp import Client
from mcp.client.streamable_http import streamable_http_client

async def check(args):
    token = os.environ.get("PHONE_MCP_TOKEN")
    if not token: raise ValueError("PHONE_MCP_TOKEN must come from an explicitly activated session")
    old = os.environ.get("PHONE_MCP_OLD_TOKEN")
    parsed = urlsplit(args.url)
    if parsed.scheme != "http" or parsed.hostname not in ("127.0.0.1", "localhost") or parsed.port != 8765 or parsed.path != "/mcp":
        raise ValueError("This acceptance client permits only local http://127.0.0.1:8765/mcp (or localhost)")
    if importlib.metadata.version("mcp") != "2.3.0": raise ValueError("Install pinned mcp==2.3.0")
    events = []
    async def record(response):
        body = json.loads(response.request.content) if response.request.method == "POST" else {}
        events.append({"http_method": response.request.method, "rpc_method": body.get("method"),
                       "protocol_header": response.request.headers.get("mcp-protocol-version"),
                       "status": response.status_code})
    report = {"timestamp_utc": datetime.now(timezone.utc).isoformat(), "sdk": "mcp", "sdk_version": "2.3.0",
              "label": args.label, "target": "caller-supplied local endpoint; device identity supplied by caller",
              "device_certification": False}
    headers = {"Authorization": "Bearer " + token, "Accept": "application/json, text/event-stream",
               "MCP-Protocol-Version": "2025-06-18"}
    ping = {"jsonrpc": "2.0", "id": 90, "method": "ping"}
    async with httpx2.AsyncClient(trust_env=False, timeout=8) as probes:
        if args.expect_stopped:
            try:
                response = await probes.post(args.url, headers=headers, json=ping)
                assert response.status_code in (401,503), f"Endpoint still accepted token: HTTP {response.status_code}"
                report["stopped_observation"] = f"HTTP {response.status_code}"
            except httpx2.TransportError as error:
                report["stopped_observation"] = type(error).__name__
            report["result"] = "PASS"
            return report
        bad_headers = dict(headers, Authorization="Bearer " + secrets.token_urlsafe(32))
        invalid = await probes.post(args.url, headers=bad_headers, json=ping)
        assert invalid.status_code == 401, f"Invalid token HTTP {invalid.status_code}"
        report["invalid_token_status"] = 401
        if old:
            assert old != token, "Restart must rotate the token"
            stale = await probes.post(args.url, headers=dict(headers, Authorization="Bearer " + old), json=ping)
            assert stale.status_code == 401, f"Old token HTTP {stale.status_code}"
            report["old_token_status"] = 401
        get = await probes.get(args.url, headers=headers)
        assert get.status_code == 405
        report["explicit_get_status"] = 405
    sessions = []
    for index in range(2):
        # Reconnect with a fresh official SDK client/session each time.
        async with httpx2.AsyncClient(headers={"Authorization": "Bearer " + token}, trust_env=False,
                                    timeout=8, event_hooks={"response": [record]}) as http:
            async with Client(streamable_http_client(args.url, http_client=http), read_timeout_seconds=8) as client:
                assert client.protocol_version == "2025-06-18"
                inventory = await client.list_tools()
                names = sorted(tool.name for tool in inventory.tools)
                assert names == ["global_action", "node_click", "node_scroll", "node_set_text", "phone_status", "screen_inspect"]
                status = await client.call_tool("phone_status", {})
                assert not status.is_error, "phone_status reported unavailable session"
                payload = json.loads(status.content[0].text)
                assert payload.get("active") is True, "Expected active Android session status"
                sessions.append({"connection": index+1, "negotiated": client.protocol_version,
                                 "tool_names": names, "phone_status_active": True})
    assert sum(e["rpc_method"] == "notifications/initialized" and e["status"] == 202 for e in events) == 2
    report.update(result="PASS", sessions=sessions, sdk_events=events)
    return report

def main():
    p=argparse.ArgumentParser()
    p.add_argument("--url", default="http://127.0.0.1:8765/mcp")
    p.add_argument("--label", default="local-endpoint")
    p.add_argument("--expect-stopped", action="store_true")
    p.add_argument("--output", type=Path, required=True)
    args=p.parse_args()
    args.output.unlink(missing_ok=True)  # Never leave a stale PASS report if this run fails.
    result=asyncio.run(check(args))
    args.output.parent.mkdir(parents=True,exist_ok=True)
    args.output.write_text(json.dumps(result,indent=2)+"\n")
    print(json.dumps(result,indent=2))

if __name__ == "__main__": main()
