# Changelog

## 0.1.1 — 2026-10-31

Collaboration iteration: Grok and the Mac coding worker proposed and implemented an accepted-connection deadline patch and initial regression. The cloud engineering executor independently reproduced baseline failure, verified the patch, identified a weak controller nonexecution assertion, and implemented Grok's follow-up latch-controlled authenticated-command test and positive control. Parent coordination carried results and critique between both sides.

- Start the five-second input deadline when each socket is accepted, including time waiting for the single serial worker. Expired queued sockets close before request parsing/controller dispatch.
- Keep bounded queues, serial phone operations, existing authentication and permission gates; no new phone capability or external connection.
- Test both idle queued expiry and complete authenticated queued commands: expired B must not execute, fresh C recovers, unexpired B still executes.
- Clarify loopback port-squatting/server-identity and availability limitations, with device test additions.

This mitigates stale queued requests and bounds their input budget. It does not guarantee availability under saturation, authenticate the server to the client, or cancel an action already accepted by Android. No device testing, installation, publishing, billing, repository push, Mirror Worker or ChatGPT integration was performed.
