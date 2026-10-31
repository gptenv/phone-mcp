# Staged proposals — NOT APPLIED / NOT BUILT

These proposals live outside `phone-mcp/` to preserve the running emulator baseline. No proposed Java or Android source has been compiled or tested in this staging round. Earlier test results do not validate these patches.

## Owned candidates

1. `text.patch`: shared `dev.phonemcp.core.TextInputPolicy.accepts(String)`; 1000 Unicode code points, upfront 2000 UTF-16-unit bound, no unpaired surrogates. Dispatcher uses this exact policy; schema `maxLength:1000` remains codepoint-based. Tests cover ASCII/emoji/mixed boundaries, empty clearing, lone high/low surrogates at start/middle/end, a valid pair across UTF-16 indices 999/1000, 1000 combining marks, and no payload echo/controller execution on rejected inputs.
2. `text-android-owner.patch`: single Android direct-call guard substitution to the shared policy. Android owner integrates this only after review/baseline boundary.
3. `limiter.patch`: integrates pristine Grok `SessionRateLimiter` and its tests without modifying their bytes. Each new server gets a fresh bucket; authenticated, side-effect-free validated tool requests spend one token (including status and controller-level denials). Unknown/malformed arguments consume zero. Admission denial has `RATE_LIMITED`, `executed:false`, rounded-up `retryAfterMs`, no supplied text. Close does not access/wait on the limiter. Package-private injected bucket enables deterministic HTTP integration tests. Host SDK scenarios are spaced one second so interoperability testing respects the production default burst/refill instead of silently disabling it.

Limiter core class SHA-256: `4a74b6c4a9602df57002b6094c4c82e560c9dc636ce5fd620296455295d41dce`

Limiter pristine test SHA-256: `7f49380af4f082b3d147808db213e36a95c59708a2bd340a8023b5d6f2590d81`

## Accept ownership

`accept.patch` is **critique-only**, not the production candidate. Grok owns the production Accept parser and JUnit contribution. Parent-selected production interface: public final `AcceptHeader.acceptsMcp(String): boolean` in `dev.phonemcp.core` (the critique-only draft uses a different name and must not be integrated), Java17/Android26-compatible APIs, JUnit4.13.2. The Grok-owned transport call-site replacement rejects false with HTTP406. Neither limiter nor text proposal depends on the draft Accept class. Explicit exact JSON/SSE ranges, positive valid q-values, quote-aware scanning and strict bounded native-client subset are expected. Existing line-level HTTP parsing still rejects HTAB even if a pure parser accepts RFC whitespace; do not claim full HTTP grammar support.

## Composition and evidence

`text.patch` and `limiter.patch` touch different regions of dispatcher; limiter constructor changes and Grok's expected Accept call-site change touch different transport regions. Review actual patches against the final baseline; do not overwrite entire source files from candidate snapshots. `baseline-source-sha256.json` records source hashes used for diff generation. New classes/tests have null baseline entries.

After permission to integrate: apply reviewed patches, run all core tests under the existing 64MiB test heap, SDK interop and the new Android guard/lifecycle/device fixtures. Preserve old baseline reports and identify new APK/source hashes. Rate admission does not replace Android authorization and these fake-clock host regressions are not evidence of real-device expiry or action eligibility.
