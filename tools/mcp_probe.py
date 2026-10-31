#!/usr/bin/env python3
"""Read-only Phone MCP connectivity probe. Never prints request or response bodies."""
import argparse
import getpass
import json
import re
import socket
import sys
import time
import warnings

HOST = "127.0.0.1"
PATH = "/mcp"
VERSION = "2025-06-18"
MAX_HEADERS = 8192
MAX_BODY = 512 * 1024
TIMEOUT = 5.0
TOKEN = re.compile(r"[A-Za-z0-9_-]{43,128}\Z")


class ProbeError(Exception):
    def __init__(self, category, message):
        super().__init__(message)
        self.category = category


class SafeArgumentParser(argparse.ArgumentParser):
    def error(self, message):
        # argparse normally echoes invalid arguments, which might contain a token.
        self.print_usage(sys.stderr)
        self.exit(2, "CONFIGURATION: Invalid command-line arguments\n")


def _json(data):
    def pairs(items):
        result = {}
        for key, value in items:
            if key in result:
                raise ValueError("duplicate key")
            result[key] = value
        return result

    def invalid_constant(_):
        raise ValueError("invalid constant")

    return json.loads(data, object_pairs_hook=pairs, parse_constant=invalid_constant)


def _read_more(sock, buffer, deadline, limit):
    remaining = deadline - time.monotonic()
    if remaining <= 0:
        raise ProbeError("connectivity", "Server response timed out")
    sock.settimeout(remaining)
    data = sock.recv(min(4096, limit + 1 - len(buffer)))
    if not data:
        raise ProbeError("protocol", "Server closed an incomplete response")
    buffer.extend(data)
    if len(buffer) > limit:
        raise ProbeError("protocol", "Server response exceeds the size limit")


def post(port, token, message):
    body = json.dumps(message, ensure_ascii=True, separators=(",", ":")).encode("ascii")
    headers = (f"POST {PATH} HTTP/1.1\r\nHost: {HOST}:{port}\r\n"
               f"Authorization: Bearer {token}\r\nContent-Type: application/json\r\n"
               f"Accept: application/json, text/event-stream\r\nMCP-Protocol-Version: {VERSION}\r\n"
               f"Content-Length: {len(body)}\r\nConnection: close\r\n\r\n").encode("ascii")
    deadline = time.monotonic() + TIMEOUT
    try:
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.settimeout(TIMEOUT)
            sock.connect((HOST, port))
            sock.settimeout(max(0.001, deadline - time.monotonic()))
            sock.sendall(headers + body)
            received = bytearray()
            while b"\r\n\r\n" not in received:
                _read_more(sock, received, deadline, MAX_HEADERS)
            head, content = bytes(received).split(b"\r\n\r\n", 1)
            lines = head.split(b"\r\n")
            if not re.fullmatch(rb"HTTP/1\.[01] [0-9]{3}(?: [\x20-\x7e]*)?", lines[0]):
                raise ProbeError("protocol", "Invalid HTTP response")
            status = int(lines[0].split(b" ")[1])
            response_headers = {}
            for line in lines[1:]:
                if b":" not in line:
                    raise ProbeError("protocol", "Invalid HTTP headers")
                key, value = line.split(b":", 1)
                key = key.strip().lower()
                if key in response_headers:
                    raise ProbeError("protocol", "Ambiguous HTTP headers")
                response_headers[key] = value.strip()
            if b"transfer-encoding" in response_headers:
                raise ProbeError("protocol", "Unsupported response framing")
            raw_length = response_headers.get(b"content-length", b"")
            if not re.fullmatch(rb"[0-9]{1,8}", raw_length):
                raise ProbeError("protocol", "Missing or invalid response length")
            length = int(raw_length)
            if length > MAX_BODY:
                raise ProbeError("protocol", "Server response exceeds the size limit")
            data = bytearray(content)
            if len(data) > length:
                raise ProbeError("protocol", "Invalid response length")
            while len(data) < length:
                _read_more(sock, data, deadline, length)
            if status in (401, 403):
                raise ProbeError("configuration", "Authentication or local access was rejected")
            if status != (202 if "id" not in message else 200):
                raise ProbeError("protocol", f"Unexpected HTTP status {status}")
            if status == 202:
                if data:
                    raise ProbeError("protocol", "Notification response must be empty")
                return None
            if response_headers.get(b"content-type", b"").split(b";", 1)[0].strip().lower() != b"application/json":
                raise ProbeError("protocol", "Expected a JSON response")
            try:
                result = _json(data.decode("utf-8"))
            except (UnicodeError, ValueError, RecursionError):
                raise ProbeError("protocol", "Invalid JSON response") from None
            if not isinstance(result, dict) or result.get("jsonrpc") != "2.0" or type(result.get("id")) is not int or result.get("id") != message["id"]:
                raise ProbeError("protocol", "Invalid or mismatched JSON-RPC response")
            if "error" in result or not isinstance(result.get("result"), dict):
                raise ProbeError("protocol", "JSON-RPC request failed")
            return result["result"]
    except ProbeError:
        raise
    except (OSError, TimeoutError):
        raise ProbeError("connectivity", "Could not reach the local endpoint or response timed out") from None


def request(port, token, method, request_id, params=None):
    message = {"jsonrpc": "2.0", "id": request_id, "method": method}
    if params is not None:
        message["params"] = params
    return post(port, token, message)


def run_probe(port, token):
    init = request(port, token, "initialize", 1, {"protocolVersion": VERSION, "capabilities": {},
        "clientInfo": {"name": "phone-mcp-probe", "version": "1"}})
    capabilities = init.get("capabilities")
    info = init.get("serverInfo")
    if (init.get("protocolVersion") != VERSION or not isinstance(capabilities, dict)
            or not isinstance(capabilities.get("tools"), dict) or not isinstance(info, dict)
            or info.get("name") != "phone-mcp" or not isinstance(info.get("version"), str) or not info["version"]):
        raise ProbeError("protocol", "Server did not negotiate the expected protocol and tools capability")
    post(port, token, {"jsonrpc": "2.0", "method": "notifications/initialized"})
    request(port, token, "ping", 2)
    listed = request(port, token, "tools/list", 3).get("tools")
    if not isinstance(listed, list) or sum(isinstance(item, dict) and item.get("name") == "phone_status" for item in listed) != 1:
        raise ProbeError("protocol", "phone_status is missing or duplicated in tools/list")
    result = request(port, token, "tools/call", 4, {"name": "phone_status", "arguments": {}})
    if result.get("isError") is not False:
        raise ProbeError("configuration", "Phone status is unavailable; check the active session on the phone")
    content = result.get("content")
    if not isinstance(content, list) or len(content) != 1 or not isinstance(content[0], dict) or content[0].get("type") != "text" or not isinstance(content[0].get("text"), str):
        raise ProbeError("protocol", "Unexpected phone status result")
    try:
        status = _json(content[0]["text"])
    except (ValueError, RecursionError):
        raise ProbeError("protocol", "Invalid phone status result") from None
    if not isinstance(status, dict) or any(type(status.get(key)) is not bool for key in ("active", "unlocked", "accessibilityConnected")):
        raise ProbeError("protocol", "Incomplete phone status result")
    if not status["active"]:
        raise ProbeError("configuration", "Phone session is inactive")
    return status["unlocked"], status["accessibilityConnected"]


def main(argv=None):
    parser = SafeArgumentParser(description="Probe the local Phone MCP endpoint without reading the screen or performing actions.")
    parser.add_argument("--port", type=int, default=8765, help="Local forwarded TCP port (default: 8765)")
    parser.add_argument("--token-stdin", action="store_true", help="Read one token line from stdin instead of prompting")
    args = parser.parse_args(argv)
    if not 1 <= args.port <= 65535:
        parser.error("port must be between 1 and 65535")
    try:
        if args.token_stdin:
            token = sys.stdin.readline(138).rstrip("\r\n")
        else:
            with warnings.catch_warnings():
                warnings.simplefilter("error", getpass.GetPassWarning)
                token = getpass.getpass("Session token: ")
        # The app displays the conventional prefix with the token.
        if token.startswith("Bearer "):
            token = token[7:]
        if not TOKEN.fullmatch(token):
            raise ProbeError("configuration", "Invalid token format")
        unlocked, connected = run_probe(args.port, token)
        print("OK: MCP initialized, pinged, and listed tools; phone session active.")
        print(f"Phone unlocked: {'yes' if unlocked else 'no'}; Accessibility connected: {'yes' if connected else 'no'}.")
        return 0
    except ProbeError as error:
        print(f"{error.category.upper()}: {error}", file=sys.stderr)
        return 1
    except (EOFError, KeyboardInterrupt, getpass.GetPassWarning):
        print("CONFIGURATION: Token entry cancelled", file=sys.stderr)
        return 1
    except (OSError, UnicodeError):
        print("CONFIGURATION: Token entry unavailable", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
