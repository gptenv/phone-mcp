import contextlib
import http.server
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import threading
import time
import unittest
from unittest import mock

SCRIPT = Path(__file__).resolve().parents[1] / "mcp_probe.py"
spec = importlib.util.spec_from_file_location("mcp_probe", SCRIPT)
probe = importlib.util.module_from_spec(spec)
spec.loader.exec_module(probe)
TOKEN = "a" * 43
MARKER = "secret-response-should-not-appear"


class FakeHandler(http.server.BaseHTTPRequestHandler):
    protocol_version = "HTTP/1.1"

    def log_message(self, *args):
        pass

    def do_POST(self):
        raw = self.rfile.read(int(self.headers["Content-Length"]))
        message = json.loads(raw)
        self.server.methods.append(message["method"])
        if self.path != "/mcp" or self.headers.get("Authorization") != "Bearer " + TOKEN:
            return self.reply(401, b"")
        if self.server.mode == "redirect":
            return self.reply(302, b"", {"Location": "http://example.invalid/"})
        if self.server.mode == "malformed":
            return self.reply(200, b"not-json " + MARKER.encode())
        if self.server.mode == "oversize":
            return self.reply(200, b"", {"Content-Length": str(probe.MAX_BODY + 1)})
        if self.server.mode == "trickle":
            try:
                for byte in b"HTTP/1.1 200 OK\r\n":
                    self.connection.sendall(bytes([byte]))
                    time.sleep(0.05)
            except OSError:
                pass
            return
        if self.server.mode == "duplicate":
            return self.reply(200, b'{"jsonrpc":"2.0","id":1,"id":1,"result":{}}')
        if self.server.mode == "boolean-id":
            return self.reply(200, b'{"jsonrpc":"2.0","id":true,"result":{}}')
        method = message["method"]
        if method == "notifications/initialized":
            return self.reply(202, b"")
        results = {
            "initialize": {"protocolVersion": probe.VERSION, "capabilities": {"tools": {}},
                           "serverInfo": {"name": "phone-mcp", "version": "test"}},
            "ping": {},
            "tools/list": {"tools": [{"name": "phone_status"}]},
            "tools/call": {"isError": False, "content": [{"type": "text", "text": json.dumps({
                "active": True, "unlocked": False, "accessibilityConnected": True})}]},
        }
        if self.server.mode == "bad-capability" and method == "initialize":
            results["initialize"]["capabilities"] = []
        if self.server.mode == "wrong-server" and method == "initialize":
            results["initialize"]["serverInfo"]["name"] = "another-server"
        if self.server.mode == "duplicate-tool" and method == "tools/list":
            results["tools/list"]["tools"].append({"name": "phone_status"})
        if self.server.mode == "status-error" and method == "tools/call":
            results["tools/call"] = {"isError": True, "content": [{"text": MARKER}]}
        if method == "tools/call":
            self.server.arguments = message.get("params")
        result = {"jsonrpc": "2.0", "id": 999 if self.server.mode == "wrong-id" else message["id"], "result": results[method]}
        return self.reply(200, json.dumps(result).encode())

    def reply(self, status, data, extra=None):
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Connection", "close")
        if not extra or "Content-Length" not in extra:
            self.send_header("Content-Length", str(len(data)))
        for key, value in (extra or {}).items():
            self.send_header(key, value)
        self.end_headers()
        self.wfile.write(data)
        self.close_connection = True


@contextlib.contextmanager
def server(mode="normal"):
    instance = http.server.HTTPServer(("127.0.0.1", 0), FakeHandler)
    instance.mode = mode
    instance.methods = []
    thread = threading.Thread(target=instance.serve_forever, daemon=True)
    thread.start()
    try:
        yield instance
    finally:
        instance.shutdown()
        thread.join(3)
        instance.server_close()


class ProbeTests(unittest.TestCase):
    def test_read_only_sequence_and_status(self):
        with server() as s:
            self.assertEqual((False, True), probe.run_probe(s.server_port, TOKEN))
            self.assertEqual(["initialize", "notifications/initialized", "ping", "tools/list", "tools/call"], s.methods)
            self.assertEqual({"name": "phone_status", "arguments": {}}, s.arguments)

    def test_auth_rejection_is_configuration_failure(self):
        with server() as s, self.assertRaises(probe.ProbeError) as caught:
            probe.run_probe(s.server_port, "b" * 43)
        self.assertEqual("configuration", caught.exception.category)

    def test_redirect_is_not_followed(self):
        with server("redirect") as s, self.assertRaises(probe.ProbeError) as caught:
            probe.run_probe(s.server_port, TOKEN)
        self.assertEqual("protocol", caught.exception.category)
        self.assertEqual(["initialize"], s.methods)

    def test_untrusted_error_content_never_printed(self):
        with server("status-error") as s:
            result = subprocess.run([sys.executable, str(SCRIPT), "--port", str(s.server_port), "--token-stdin"],
                input=TOKEN + "\n", text=True, capture_output=True, timeout=10)
        self.assertEqual(1, result.returncode)
        self.assertIn("CONFIGURATION", result.stderr)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)
        self.assertNotIn(MARKER, result.stdout + result.stderr)

    def test_bad_json_and_oversize_are_protocol_errors(self):
        for mode in ("malformed", "oversize", "bad-capability", "duplicate", "boolean-id", "wrong-id", "wrong-server", "duplicate-tool"):
            with self.subTest(mode=mode), server(mode) as s:
                with self.assertRaises(probe.ProbeError) as caught:
                    probe.run_probe(s.server_port, TOKEN)
                self.assertEqual("protocol", caught.exception.category)
                self.assertNotIn(MARKER, str(caught.exception))

    def test_absolute_deadline_stops_trickle_response(self):
        previous = probe.TIMEOUT
        try:
            probe.TIMEOUT = 0.2
            with server("trickle") as s, self.assertRaises(probe.ProbeError) as caught:
                probe.run_probe(s.server_port, TOKEN)
            self.assertEqual("connectivity", caught.exception.category)
        finally:
            probe.TIMEOUT = previous

    def test_cli_rejects_url_and_invalid_token_without_leaking(self):
        result = subprocess.run([sys.executable, str(SCRIPT), "--port", "http://127.0.0.1:8765"],
            input=TOKEN + "\n", text=True, capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        result = subprocess.run([sys.executable, str(SCRIPT), "--token-stdin"],
            input="not-a-secret-format\n", text=True, capture_output=True, timeout=5)
        self.assertEqual(1, result.returncode)
        self.assertNotIn("not-a-secret-format", result.stdout + result.stderr)
        result = subprocess.run([sys.executable, str(SCRIPT), "--token", MARKER],
            text=True, capture_output=True, timeout=5)
        self.assertNotEqual(0, result.returncode)
        self.assertNotIn(MARKER, result.stdout + result.stderr)

    def test_token_line_and_input_failures_are_redacted(self):
        for supplied in ("", " " + TOKEN + "\n", TOKEN + " \n"):
            with self.subTest(supplied=repr(supplied)):
                result = subprocess.run([sys.executable, str(SCRIPT), "--token-stdin"],
                    input=supplied, text=True, capture_output=True, timeout=5)
                self.assertEqual(1, result.returncode)
                self.assertNotIn(TOKEN, result.stdout + result.stderr)
        out = io.StringIO()
        with mock.patch.object(probe.getpass, "getpass", side_effect=OSError(MARKER)), contextlib.redirect_stderr(out):
            self.assertEqual(1, probe.main([]))
        self.assertNotIn(MARKER, out.getvalue())

    def test_accepts_the_displayed_bearer_prefix_without_echo(self):
        with server() as s:
            result = subprocess.run([sys.executable, str(SCRIPT), "--port", str(s.server_port), "--token-stdin"],
                input="Bearer " + TOKEN + "\n", text=True, capture_output=True, timeout=10)
        self.assertEqual(0, result.returncode)
        self.assertNotIn(TOKEN, result.stdout + result.stderr)



if __name__ == "__main__":
    unittest.main()
