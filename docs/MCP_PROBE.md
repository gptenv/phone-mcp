# Local MCP diagnostic

This development diagnostic checks initialization, the initialized notification, ping, tool discovery and `phone_status`. It does not inspect the screen or perform phone actions. It connects only to `127.0.0.1` at `/mcp`, defaults to port 8765, refuses redirects, and limits response size and time. It expects JSON responses from the current server; it does not implement SSE streaming.

On a trusted development computer, start an authorized phone session and establish the documented ADB forward first. Then run:

```sh
python3 tools/mcp_probe.py
```

Enter the token at the hidden prompt, either alone or with the displayed `Bearer ` prefix. Do not place it in command-line arguments or shell history. For automation, `--token-stdin` reads one token line from standard input; keep the input source private. `--port 12345` selects a different local forwarded port. The tool never prints the token or server response bodies. A failure is classified as configuration, connectivity or protocol; follow the on-screen status on the phone when diagnosing it.

The probe checks the advertised server name for protocol consistency, not cryptographic identity. The bearer token authenticates the client to the server; it does not authenticate the server to the client. A different local listener could receive a token and imitate its name. Confirm the intended session and forward, use a trusted computer and phone, stop clients when finished, and remove the forward. This probe does not verify device action safety or production readiness.

The local synthetic tests require no phone or external service:

```sh
python3 -m unittest discover -s tools/tests -v
```
