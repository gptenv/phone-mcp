import pathlib
import sys
import unittest
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[1]))
from token_input import normalize_bearer_token
class TokenInputTests(unittest.TestCase):
    def test_raw_and_single_displayed_prefix(self):
        token = 'A' * 43
        self.assertEqual(token, normalize_bearer_token(token))
        self.assertEqual(token, normalize_bearer_token('Bearer ' + token))
    def test_boundaries(self):
        self.assertEqual('A'*128, normalize_bearer_token('Bearer '+'A'*128))
        for value in ('A'*42, 'A'*129, 'Bearer '+'A'*129):
            with self.assertRaises(ValueError): normalize_bearer_token(value)
    def test_malformed_and_control_input_is_redacted(self):
        for value in (None, '', 'Bearer Bearer '+'A'*43, 'Bearer '+'A'*43+'\r\nX: secret', 'secret value', 'A'*10000):
            with self.assertRaises(ValueError) as caught: normalize_bearer_token(value)
            self.assertEqual('Invalid session token format', str(caught.exception))
class RedirectTests(unittest.TestCase):
    def test_smoke_does_not_forward_token_on_redirect(self):
        import importlib.util, os, threading, urllib.request, urllib.error
        from http.server import BaseHTTPRequestHandler, HTTPServer
        from unittest.mock import patch
        spec = importlib.util.spec_from_file_location('smoke_redirect_test', pathlib.Path(__file__).resolve().parents[1] / 'mcp_smoke.py')
        module = importlib.util.module_from_spec(spec)
        with patch.dict(os.environ, {'PHONE_MCP_TOKEN': 'Bearer ' + 'A'*43}): spec.loader.exec_module(module)
        visited = []
        class Handler(BaseHTTPRequestHandler):
            def log_message(self, *args): pass
            def do_GET(self):
                visited.append(self.path)
                self.send_response(302); self.send_header('Location', '/must-not-follow'); self.end_headers()
        server = HTTPServer(('127.0.0.1', 0), Handler)
        worker = threading.Thread(target=server.serve_forever, daemon=True); worker.start()
        try:
            opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), module.NoRedirect())
            request = urllib.request.Request('http://127.0.0.1:%d/start' % server.server_port, headers={'Authorization': 'Bearer ' + 'A'*43})
            with self.assertRaises(urllib.error.HTTPError): opener.open(request, timeout=2)
            self.assertEqual(['/start'], visited)
            self.assertEqual('A'*43, module.TOKEN)
        finally:
            server.shutdown(); server.server_close(); worker.join(timeout=2)
if __name__ == '__main__': unittest.main()
