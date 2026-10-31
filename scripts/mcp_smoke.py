#!/usr/bin/env python3
"""Read-only local MCP smoke check. Requires explicit phone session + token env var."""
import json
import os
import urllib.request

TOKEN = os.environ.get("PHONE_MCP_TOKEN")
if not TOKEN:
    raise SystemExit("Set PHONE_MCP_TOKEN from an explicitly started local phone session.")

def call(method, params, request_id=None):
    payload = {"jsonrpc": "2.0", "method": method, "params": params}
    if request_id is not None:
        payload["id"] = request_id
    request = urllib.request.Request("http://127.0.0.1:8765/mcp", data=json.dumps(payload).encode(), headers={
        "Authorization": "Bearer " + TOKEN,
        "Content-Type": "application/json",
        "Accept": "application/json, text/event-stream",
        "MCP-Protocol-Version": "2025-06-18",
    })
    # Do not send the bearer token through a host-configured HTTP proxy.
    with urllib.request.build_opener(urllib.request.ProxyHandler({})).open(request, timeout=8) as response:
        raw = response.read()
        return json.loads(raw) if raw else None

init = call("initialize", {"protocolVersion": "2025-06-18", "capabilities": {}, "clientInfo": {"name": "phone-mcp-smoke", "version": "1"}}, 1)
assert init["result"]["protocolVersion"] == "2025-06-18", "Protocol negotiation failed"
call("notifications/initialized", {})
tools = call("tools/list", {}, 2)
assert len(tools["result"]["tools"]) == 6
status = call("tools/call", {"name": "phone_status", "arguments": {}}, 3)
assert not status["result"]["isError"], "Phone session unavailable"
print("PASS: initialize, notification, tool inventory and phone_status (no screen read or actions).")
