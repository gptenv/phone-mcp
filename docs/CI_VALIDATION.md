# Manual Android CI — review draft, UNEXECUTED

**This workflow has not been dispatched or run.** The intended repository is public `gptenv/phone-mcp`. Applicable no-paid-overage controls, SDK-license/setup approval, exact execution commit, and execution approval remain **PENDING**. Source publication does not authorize a workflow run.

Owned proposal files:
- `.github/workflows/android-emulator-review.yml`
- `scripts/ci/android-emulator-validation.sh`
- This document.

## Approval and billing boundary

The only trigger is `workflow_dispatch`. There is no push, pull-request, schedule, reusable-workflow, or external-event trigger. Defaults fail before checkout/setup. An owner must explicitly review the exact repository, full commit SHA, visibility, SDK/JDK setup/licenses, and a single execution's budget. The reviewed SHA must equal the dispatched ref's SHA. Inputs are passed as environment variables, not interpolated into shell commands. Manual dispatch and limited token permissions are documented in [GitHub workflow syntax](https://docs.github.com/en/actions/reference/workflows-and-actions/workflow-syntax).

The runner is the standard `ubuntu-24.04` x64 label—not a larger runner or self-hosted machine. Permissions are only `contents: read`; checkout does not persist credentials. No external secrets, release keys, deployment, publishing, cache uploads, or artifact-upload actions are configured. Debug APKs still require ordinary **disposable debug signing**, solely for installation on the fresh emulator. This is not release signing.

**This workflow cannot guarantee zero charges.** Even a refused dispatch can allocate a runner and consume time. GitHub says standard hosted runners are free for public repositories; private repositories have plan-dependent included usage and possible additional charges. Visibility alone does not establish the account's current billing state. An authorized account administrator must verify the applicable Actions budget scope and enabled stop-usage controls before dispatch; an alert-only budget is insufficient. The booleans attest to a human review and neither inspect nor change billing settings. If a no-paid-overage guard cannot be verified, do not run this proposal. [GitHub Actions billing](https://docs.github.com/en/actions/concepts/billing-and-usage), [budget stop-usage configuration](https://docs.github.com/en/billing/how-tos/set-up-budgets).

One job has a 50-minute platform cap; its validation command has a 40-minute cap, plus bounded individual stages. Concurrency permits only one active validation for this workflow/repository, without canceling an active run. These limits are operational bounds, **not spending limits**; repeated manual dispatches remain possible. Repository maintainers must review any change to triggers, actions, permissions, inputs, runner labels, or limits before use.

## Reviewed first-party action pins

Verified against official release commit links on 2026-10-31:

| Action | Release | Pinned commit |
|---|---|---|
| `actions/checkout` | v7.0.1 | `3d3c42e5aac5ba805825da76410c181273ba90b1` |
| `actions/setup-java` | v6.1.0 | `b843ebf9b913146b38da2eaa7aeddf76074ae1ad` |

Sources: [checkout release commit](https://github.com/actions/checkout/commit/3d3c42e5aac5ba805825da76410c181273ba90b1), [setup-java release commit](https://github.com/actions/setup-java/commit/b843ebf9b913146b38da2eaa7aeddf76074ae1ad). The review checked release identity and declared action inputs; it is **not** an independent audit of every bundled/transitive dependency. Pins prevent silently following a moving major-version tag, but do not make an action or reviewed repository code inherently safe.

JDK setup selects official Eclipse Temurin 21 x64 and requires downloaded-package signature verification. It enables neither dependency caching nor JDK cache uploads. The exact JDK patch may vary with the approved hosted image/tool cache and is printed in the run; the JDK is not claimed to be byte-for-byte pinned. No custom download mirror or disabled certificate check is used. Both `verify-signature` and `cache-jdk` were checked in the exact pinned action declaration, rather than assumed to exist. See the pinned [setup-java input declarations](https://github.com/actions/setup-java/blob/b843ebf9b913146b38da2eaa7aeddf76074ae1ad/action.yml).

## Prerequisites: fail closed, do not alter host security

1. The standard hosted image must already expose `/dev/kvm` as a character device readable and writable by the runner. The script also requires successful `emulator -accel-check` identifying KVM. There is **no** `sudo`, device `chmod`, group/udev modification, privileged container, or software-CPU fallback. An unavailable KVM environment is a blocker—not permission to bypass a restriction. [Android acceleration check](https://developer.android.com/studio/run/emulator-acceleration).
2. Official Android command-line tools and SDK license records must already exist in the runner's `ANDROID_HOME`. The owner must have reviewed the applicable licenses; file presence is only a technical precondition, not proof of legal authorization. Required packages are platform 36, build-tools 35.0.0, platform-tools, emulator, and exactly one API35 or API36 `google_apis;x86_64` image.
3. Missing packages cause failure unless `install_missing_sdk` is explicitly selected. In that case, only those missing stable packages are requested with Google's `sdkmanager`, bounded by ten minutes and stdin from `/dev/null`. No `yes`, `sdkmanager --licenses`, or synthesized license hashes are used. An unaccepted package license must stop setup; resolve it through separately approved owner setup, not by editing this draft to auto-accept. [Official sdkmanager installation and licensing](https://developer.android.com/tools/sdkmanager).
4. Hosted images change. The proposed tool paths are checked, not assumed; the current [official Ubuntu 24.04 image inventory](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md) is a review reference, not a guarantee of future package availability. Android documents the legacy command-line tools used here; any migration to replacement tooling requires review, not an automatic fallback.

## Isolation and execution sequence

After approval, the proposed script would:

1. Verify the exact checked-out commit and recheck acknowledgments/KVM.
2. Create a unique directory under `RUNNER_TEMP`, with fresh HOME, Android preferences, AVD data, and Gradle cache. It does not load a personal AVD, account, or device. Android supports overriding these directories via [documented environment variables](https://developer.android.com/tools/variables).
3. Run the standard-library-only probe and token/helper Python regressions (currently 9 + 4 cases), without installing Python SDK dependencies. Then build host tests, lint, debug app, standalone synthetic fixture, and instrumentation APK **before** starting the emulator. The Gradle wrapper already checks its distribution checksum. No release task is invoked.
4. Require local ports 5037/5556/5557 to be unused, start an owned loopback-only ADB server, and refuse an existing connected device. Create one fresh AVD through [avdmanager](https://developer.android.com/tools/avdmanager). The `no` response is solely for custom hardware-profile creation, not license acceptance.
5. Start one headless x86_64 emulator with mandatory KVM, two cores, 2048 MiB RAM, software **graphics** rendering, wiped data, and no snapshots. Software graphics is not software CPU emulation. Boot is bounded to 180 seconds. The selected serial is fixed to `emulator-5556`; the guest must report the requested API and `ro.kernel.qemu=1`. Every installation/instrumentation command uses that explicit serial. No `adb -d`, public listener, external tunnel, or physical-device installation is used. See [emulator flags](https://developer.android.com/studio/run/emulator-commandline) and the [official ADB manual](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md).
6. Install only the three locally built test APKs. First run `DeviceAccessibilityTest#standaloneFixtureForegroundSmoke`. It must prove the independent `dev.phonemcp.fixture` foreground root. A smoke failure prevents the broader suite.
7. Run only `DeviceAccessibilityTest` and `DeviceSessionTest`. Require a final nonempty JUnit success report **and** individual success statuses for the known required test methods; ADB exit zero alone is insufficient. API36 additionally requires the multi-label case to pass; API35 does not count that skipped case as API36 evidence. The external-client `HostInteropHarness` is intentionally excluded and is **not** validated by this CI proposal.
8. On success, failure, or a trappable cancellation, stop only the owned emulator/ADB server and delete only this invocation's temporary directory. No global kill command is used. Forced runner termination may prevent a shell trap, so hosted-runner disposal remains the final isolation boundary.

There is no artifact-upload step. Test/build output stays in ordinary job logs; temporary emulator files and local raw result files are deleted. Do not put real credentials or personal data into fixtures. The job does not capture screenshots, dump UI trees, or stream general logcat. If future evidence uploads are wanted, authorization, redaction, retention, and billing need a separate review.

## Verification status and next decision

Only source review and static syntax checks are permitted for this draft. It has **not** established hosted KVM access, package-license readiness, successful Actions execution, API36 runtime behavior, physical Samsung compatibility, store eligibility, or a charge-free run.

Before any dispatch, record:
- repository URL/owner and visibility;
- approved exact commit;
- account/organization Actions budget and verified no-paid-overage/stop-usage boundary;
- SDK/JDK and license/setup approval;
- selected API and explicit authorization for one bounded run.

The public repository identity is established; the remaining execution decisions are pending. Recheck all five before a run. Do not infer approval from the existence of these files or from previously passed local tests.

## Toolchain documentation discrepancy

The current successful local build uses AGP 8.9.1 with compileSdk 36.
Google's [AGP 8.9 compatibility table](https://developer.android.com/build/releases/agp-8-9-0-release-notes)
lists maximum API 35, while its [Android 16 setup guide](https://developer.android.com/about/versions/16/setup-sdk)
explicitly permits AGP 8.9.0-rc01 or higher for compileSdk 36. This is conflicting
guidance, not a demonstrated build failure. The [AGP 8.10 table](https://developer.android.com/build/releases/agp-8-10-0-release-notes)
explicitly supports API 36 with the same Gradle 8.11.1/build tools 35.0.0;
a future reviewed upgrade can remove the ambiguity. No plugin change or
runtime qualification is implied by this note.
