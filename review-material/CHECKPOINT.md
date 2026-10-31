# Canonical-source sync checkpoint — 2026-10-31 03:22 UTC

Sync candidate, NOT a production release. Parent/Mac worker must merge safely with canonical folder and preserve dirty files.

phone-mcp/ is the latest applied source: compileSDK36,target35,min26; six tools; lifecycle/security/transport hardening; standalone testOnly fixture APK; immediate foreground-notification policy and bounded readiness test. This exact production/test source built successfully in49seconds before emulator retry (24core +86Android host tests;3debug/test APKs; lint0errors/1OldTargetApi warning). Source code is unchanged since build; documentation updates are newer. No SDK, caches, local config, signing keys, APKs or credentials included. Verification logs have workspace/home paths and synthetic activation-ticket values redacted for transfer.

Latest device evidence: earlier real API35 SDK2.3.0 active/stopped/restarted lifecycle PASS; frozen baseline9tests had3passes/6failures, including confirmed test-APK missing Kotlin runtime causing5fixture failures and1notification-post timing failure. Current standalone fixture+notification fixes built, but device smoke still PENDING: emulator session was lost due executor-key replacement; one clean emulator-only retry is booting. No new screen-action pass is claimed.

proposals/ is UNAPPLIED, UNBUILT review work: text.patch +text-android-owner.patch align1000Unicodecodepoints and reject malformed surrogates; limiter.patch integrates Grok pristine bucket with dispatcher/socket tests. Grok helper21tests separately passed64MiB JVM. Integration regressions NOT RUN. accept.patch is our old CRITIQUE-ONLY draft, NOT canonical implementation: Grok owns the forthcoming production AcceptHeader helper/tests. Never apply all proposals blindly.

Remaining gates include actual fixture actions, API36 device labels, quick Stop/Start lifecycle feasibility, limiter/Unicode/Accept integration, S25 device/OS qualification, distribution/currency/signing/policy decisions, and continuing reciprocal Grok review.
