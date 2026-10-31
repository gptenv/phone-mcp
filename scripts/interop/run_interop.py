#!/usr/bin/env python3
"""Official SDK vs real Java transport + fake controller. No Android/device integration.
Runs server and client as child processes of one invocation/network namespace.
Never prints or accepts a real phone token; creates a disposable random fixture token.
"""
import argparse
import asyncio
import importlib.metadata
import json
import os
from pathlib import Path
import secrets
import select
import subprocess
import tempfile
from datetime import datetime, timezone

import httpx2
from mcp import Client, types
from mcp.client.session import ClientSession
from mcp.client.streamable_http import streamable_http_client

ROOT = Path(__file__).resolve().parents[2]
URL = "http://127.0.0.1:8765/mcp"
SDK_VERSION = "2.3.0"

async def scenario(mode, token, nonce):
    events = []
    async def record(response):
        request = response.request
        body = json.loads(request.content) if request.method == "POST" else {}
        events.append({"http_method": request.method, "rpc_method": body.get("method"),
                       "proposed_version": body.get("params", {}).get("protocolVersion"),
                       "protocol_header": request.headers.get("mcp-protocol-version"),
                       "status": response.status_code})
    async with httpx2.AsyncClient(headers={"Authorization": "Bearer " + token}, trust_env=False,
                                timeout=8, event_hooks={"response": [record]}) as http:
        transport = streamable_http_client(URL, http_client=http)
        if mode in ("auto", "legacy"):
            async with Client(transport, mode=mode, read_timeout_seconds=8) as client:
                negotiated = client.protocol_version
                assert negotiated == "2025-06-18", negotiated
                inventory = await client.list_tools()
                result = await client.call_tool("phone_status", {})
                denied = await client.call_tool("screen_inspect", {})
                assert (await client.call_tool("node_scroll", {"nodeId":"fake:1","direction":"forward"})).is_error
                assert (await client.call_tool("node_set_text", {"nodeId":"fake:1","text":"sdk-fixture-only"})).is_error
                assert result.is_error is False and denied.is_error is True
                names = sorted(tool.name for tool in inventory.tools)
                assert json.loads(result.content[0].text)["nonce"] == nonce
        else:
            # Public typed low-level SDK APIs; no monkeypatch of SDK protocol constants.
            # Client(mode=...) cannot pin handshake-era versions in SDK 2.3.0.
            async with transport as (read, write):
                async with ClientSession(read, write, read_timeout_seconds=8) as session:
                    initialized = await session.send_request(types.InitializeRequest(params=types.InitializeRequestParams(
                        protocol_version=mode, capabilities=types.ClientCapabilities(),
                        client_info=types.Implementation(name="phone-mcp-host-interop", version="1"))), types.InitializeResult)
                    negotiated = initialized.protocol_version
                    assert negotiated == mode, negotiated
                    session.adopt(initialized)
                    await session.send_notification(types.InitializedNotification())
                    inventory = await session.list_tools()
                    result = await session.call_tool("phone_status", {})
                    denied = await session.call_tool("screen_inspect", {})
                    assert (await session.call_tool("node_scroll", {"nodeId":"fake:1","direction":"forward"})).is_error
                    assert (await session.call_tool("node_set_text", {"nodeId":"fake:1","text":"sdk-fixture-only"})).is_error
                    assert result.is_error is False and denied.is_error is True
                    names = sorted(tool.name for tool in inventory.tools)
                    assert json.loads(result.content[0].text)["nonce"] == nonce
        assert names == ["global_action", "node_click", "node_scroll", "node_set_text", "phone_status", "screen_inspect"], names
        # Explicit HTTP checks complement SDK-generated traffic; not misreported as SDK requests.
        sdk_events = list(events)
        probe = await http.get(URL, headers={"Accept": "text/event-stream", "MCP-Protocol-Version": negotiated})
        assert probe.status_code == 405
        assert any(e["rpc_method"] == "notifications/initialized" and e["status"] == 202 for e in sdk_events)
        assert all(e["status"] == 405 for e in sdk_events if e["http_method"] == "GET")
        return {"mode": mode, "negotiated": negotiated, "result": "PASS", "tools": names,
                "sdk_events": sdk_events, "explicit_get_probe": 405,
                "sdk_get_observed": any(e["http_method"] == "GET" for e in sdk_events)}

async def run_all(token, nonce):
    results = []
    for mode in ("auto", "legacy", "2025-06-18", "2025-03-26"):
        # One shared fixture session: four valid calls/scenario must respect its 5/s refill.
        # Rate admission itself is verified with deterministic fake clocks in core integration tests.
        if results: await asyncio.sleep(1.0)
        results.append(await scenario(mode, token, nonce))
    return results

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--java-home", type=Path, required=True)
    parser.add_argument("--json-jar", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    args.output.unlink(missing_ok=True)  # Never leave a stale PASS report if this run fails.
    assert importlib.metadata.version("mcp") == SDK_VERSION, "Install pinned requirements.txt"
    token, nonce = secrets.token_urlsafe(32), secrets.token_hex(16)
    with tempfile.TemporaryDirectory(prefix="phone-mcp-interop-") as classes:
        sources = sorted((ROOT / "core/src/main/java").rglob("*.java"))
        sources.append(Path(__file__).with_name("FakeMcpServer.java"))
        subprocess.run([str(args.java_home / "bin/javac"), "-cp", str(args.json_jar), "-d", classes,
                        *map(str, sources)], check=True)
        env = dict(os.environ, PHONE_MCP_INTEROP_TOKEN=token, PHONE_MCP_INTEROP_NONCE=nonce)
        server = subprocess.Popen([str(args.java_home / "bin/java"), "-Xmx64m", "-cp",
                                   classes + os.pathsep + str(args.json_jar), "FakeMcpServer"],
                                  stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, env=env)
        try:
            ready, _, _ = select.select([server.stdout], [], [], 10)
            assert ready and server.stdout.readline().decode().strip() == "READY host-only-fake-controller", "Fixture startup failed (port 8765 must be free)"
            results = asyncio.run(run_all(token, nonce))
            report = {"timestamp_utc": datetime.now(timezone.utc).isoformat(), "sdk": "mcp", "sdk_version": SDK_VERSION,
                      "controller": "host-only-fake; no Android/device", "scenario_spacing_seconds": 1.0, "result": "PASS", "scenarios": results}
            args.output.parent.mkdir(parents=True, exist_ok=True)
            args.output.write_text(json.dumps(report, indent=2) + "\n")
            print(json.dumps(report, indent=2))
        finally:
            server.stdin.close()
            try: server.wait(timeout=5)
            except subprocess.TimeoutExpired:
                server.kill(); server.wait(timeout=5)
            stderr = server.stderr.read().decode()
            if stderr: print(stderr)
    assert server.returncode == 0, f"Fixture exited {server.returncode}"

if __name__ == "__main__": main()
