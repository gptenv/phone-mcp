# Per-session request admission

These provisional default limits count admitted valid attempts, not successful actions or billing units.

Each server/session owns one independent token bucket: burst 10 valid tool calls, refilling one token every 200 ms (5/s). Authentication and side-effect-free tool/argument validation happen before admission. Malformed or unauthenticated requests consume nothing. All valid `tools/call` requests, including `phone_status` and calls later denied by Android permissions/lock/session guards, share the bucket. Metadata requests and ignored notifications do not spend tool tokens; this is not a general connection-flood defense.

Denied calls return an MCP tool error with `error: RATE_LIMITED`, `executed: false` and a rounded-up `retryAfterMs`. The current request has not reached the controller. This says nothing about the outcome of an earlier timed-out request. Another client can consume newly refilled tokens; the retry hint is not a reservation. No request sleeps while awaiting tokens. Stop/server close does not acquire, reset or wait on the limiter, and a new explicitly activated session gets a fresh bucket.

Legacy batches still permit at most one tool call. Multi-tool batches are rejected atomically by transport preflight before spending tokens or invoking controllers. Runtime Android authorization remains separate and is always rechecked after admission.

The pure limiter and its 21 tests are preserved byte-for-byte from the reviewed Grok contribution. Fake-clock transport tests separately cover authentication, malformed arguments, status, permission denials, retry shortfall, refill, unsafe batches, rotation and close. The official SDK host matrix spaces its scenarios by one second to respect this policy; that matrix is not itself a rate-limit stress test. Device scope/lock/session tests must be rerun after integration.

## Accept-header admission

The current parser is Grok's independently contributed `AcceptHeader`, preserved byte-for-byte. It requires explicit, unparameterized `application/json` and `text/event-stream` ranges with positive RFC9110 weights. Wildcards/lookalikes cannot substitute; explicit zero weights and conflicting duplicate qualities reject. Parameterized ranges are syntactically parsed but do not satisfy these required types. Limits are8192header octets,32nonempty ranges and8parameters per range. This deliberately restricted native-client policy is not a complete general HTTP negotiator. Existing official-SDK headers are separately tested through real transport. Parser rejection precedes controller invocation.

Text entry uses one shared1000Unicode-code-point limit in schema/core/Android, rejects unpaired surrogates and first rejects strings above2000UTF16units. This is not a grapheme-cluster count; combining marks count individually. Empty text clears. Validation errors never echo the submitted text.
