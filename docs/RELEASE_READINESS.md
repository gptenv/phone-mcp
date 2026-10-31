# Developer candidate — not a production release

## Established locally

- Core protocol/parser/rate-limit/Unicode checks pass; Android host/framework
  checks pass across tested API configurations. See the README for current counts.
- Debug application, instrumentation, and separate synthetic fixture compile.
- Lint: zero errors, one intentional target-API warning (target 35, compile 36).
- Read-only native Python probe and SDK client have synthetic server tests.
- Historical emulator runs exercised explicit start/stop/token rotation and
  secure UI; those results do not validate every subsequent source change.

## Required before release

- Run the repaired independent fixture smoke test, then complete current
  accessibility click/scroll/text, stale/sensitive-node, lock, permission,
  revocation/lifecycle, and API 36 label tests on a stable emulator.
- Validate the intended Samsung device/OS, including notification lifecycle,
  accessibility classification, battery restrictions, and ordinary-app effects.
- Run coordinated SDK stop/restart checks. Host timeout/EOF/reset alone is
  inconclusive, not proof the phone processed Stop; require device-side revoked
  state and local listener refusal plus healthy forwarding and token rotation.
- Review paid distribution channel, accessibility policy, target SDK,
  dependency notices, release signing, support/privacy copy, and price/currency.
- Obtain independent final security review of the release candidate.

The manual GitHub workflow is a reviewed source draft, not executed validation.
No continuous push-triggered job, deployment, signing, or release automation is
configured. Billing and execution approval remain separate decisions.

Concurrency caps and time-based admission limits do not provide denial-of-service
immunity. A native Android action already accepted cannot be undone by revocation.
Custom fields can be misclassified despite conservative metadata checks.
