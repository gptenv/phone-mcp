#!/usr/bin/env bash
# REVIEW ONLY / UNEXECUTED. Invoked solely by the manually approved GitHub workflow.
# No sudo/chmod, automatic license acceptance, software-CPU fallback, or release signing.
set -euo pipefail
umask 077
fail() { printf 'REFUSED: %s\n' "$*" >&2; exit 1; }
[[ ${GITHUB_ACTIONS:-} == true && ${GITHUB_EVENT_NAME:-} == workflow_dispatch ]] || fail 'Manual GitHub Actions context required.'
[[ ${RUNNER_ENVIRONMENT:-} == github-hosted && ${RUNNER_OS:-} == Linux && ${RUNNER_ARCH:-} == X64 ]] || fail 'Standard hosted Linux x64 runner required.'
[[ ${SETUP_AND_LICENSES_REVIEWED:-} == true && ${EXECUTION_AND_BUDGET_APPROVED:-} == true && ${NO_PAID_OVERAGE_GUARD_REVIEWED:-} == true ]] || fail 'Setup, license, execution and billing review are pending.'
[[ ${EXPECTED_REPOSITORY:-} == "${GITHUB_REPOSITORY:?}" ]] || fail 'Repository mismatch.'
[[ ${REVIEWED_COMMIT:-} =~ ^[0-9a-f]{40}$ && ${REVIEWED_COMMIT} == "${GITHUB_SHA:?}" ]] || fail 'Reviewed commit mismatch.'
[[ ${REVIEWED_VISIBILITY:-} == public || ${REVIEWED_VISIBILITY:-} == private ]] || fail 'Visibility unreviewed.'
[[ $REVIEWED_VISIBILITY == "${ACTUAL_VISIBILITY:?}" ]] || fail 'Visibility changed.'
case ${API_LEVEL:-} in 35|36) ;; *) fail 'Only API35 or API36 is approved by this draft.' ;; esac
case ${INSTALL_MISSING_SDK:-false} in true|false) ;; *) fail 'Invalid SDK setup choice.' ;; esac
[[ $(uname -m) == x86_64 && -c /dev/kvm && -r /dev/kvm && -w /dev/kvm ]] || fail 'Accessible existing KVM required; permissions will not be changed.'

ROOT=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../.." && pwd)
cd "$ROOT"
[[ $(git rev-parse HEAD) == "$REVIEWED_COMMIT" ]] || fail 'Checkout is not the reviewed commit.'
for tool in timeout python3 java git; do command -v "$tool" >/dev/null || fail "Required existing tool missing: $tool"; done
SDK=${ANDROID_HOME:?Official runner Android SDK must already be provisioned}
[[ -d $SDK && -d $SDK/licenses ]] || fail 'SDK or existing license records missing; owner setup needed.'
compgen -G "$SDK/licenses/*" >/dev/null || fail 'No existing SDK license records; this script never accepts licenses.'
SDKMANAGER="$SDK/cmdline-tools/latest/bin/sdkmanager"
AVDMANAGER="$SDK/cmdline-tools/latest/bin/avdmanager"
[[ -x $SDKMANAGER && -x $AVDMANAGER ]] || fail 'Official runner command-line tools missing; review setup outside this workflow.'

RUN_ROOT=$(mktemp -d "${RUNNER_TEMP:?}/phone-mcp-ci.XXXXXXXX")
EMU_PID=''
ADB_STARTED=false
ADB="$SDK/platform-tools/adb"
SERIAL=emulator-5556
cleanup() {
  local result=$?
  trap - EXIT INT TERM
  set +e
  if [[ -n $EMU_PID ]]; then
    timeout --kill-after=2s 8s "$ADB" -H 127.0.0.1 -P 5037 -s "$SERIAL" emu kill >/dev/null 2>&1
    if kill -0 "$EMU_PID" 2>/dev/null; then
      kill -TERM "$EMU_PID" 2>/dev/null
      for _ in {1..20}; do kill -0 "$EMU_PID" 2>/dev/null || break; sleep 0.25; done
      kill -0 "$EMU_PID" 2>/dev/null && kill -KILL "$EMU_PID" 2>/dev/null
    fi
    wait "$EMU_PID" 2>/dev/null
  fi
  if [[ $ADB_STARTED == true ]]; then
    timeout --kill-after=2s 8s "$ADB" -H 127.0.0.1 -P 5037 kill-server >/dev/null 2>&1
  fi
  # Only the directory returned by this invocation's mktemp is removed.
  if [[ $RUN_ROOT == "$RUNNER_TEMP"/phone-mcp-ci.* && -d $RUN_ROOT ]]; then rm -rf -- "$RUN_ROOT"; fi
  exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
export HOME="$RUN_ROOT/home" ANDROID_USER_HOME="$RUN_ROOT/home/.android" ANDROID_AVD_HOME="$RUN_ROOT/avd"
export GRADLE_USER_HOME="$RUN_ROOT/gradle" ANDROID_HOME="$SDK"
unset ANDROID_SDK_ROOT ANDROID_SDK_HOME ANDROID_SERIAL ADB_SERVER_SOCKET ANDROID_ADB_SERVER_ADDRESS ANDROID_ADB_SERVER_PORT
mkdir -p "$HOME" "$ANDROID_USER_HOME" "$ANDROID_AVD_HOME" "$GRADLE_USER_HOME"

# Standard-library-only local probe/token regressions; no Python SDK dependency installation.
[[ -f tools/tests/test_mcp_probe.py && -f scripts/tests/test_token_input.py ]] || fail 'Expected Python regression files missing.'
timeout --signal=TERM --kill-after=5s 120s python3 -m unittest discover -s tools/tests -p 'test_*.py' -v
timeout --signal=TERM --kill-after=5s 120s python3 -m unittest discover -s scripts/tests -p 'test_*.py' -v

# Install only missing official stable packages, and only on explicit owner approval.
# EOF cannot answer license prompts. Never run yes, --licenses, or write license hashes.
IMAGE="system-images;android-${API_LEVEL};google_apis;x86_64"
missing=()
[[ -x $SDK/platform-tools/adb ]] || missing+=(platform-tools)
[[ -x $SDK/emulator/emulator ]] || missing+=(emulator)
[[ -f $SDK/platforms/android-36/android.jar ]] || missing+=('platforms;android-36')
[[ -x $SDK/build-tools/35.0.0/aapt2 ]] || missing+=('build-tools;35.0.0')
[[ -f $SDK/system-images/android-${API_LEVEL}/google_apis/x86_64/system.img ]] || missing+=("$IMAGE")
if ((${#missing[@]})); then
  [[ ${INSTALL_MISSING_SDK:-false} == true ]] || fail "Missing SDK packages (${missing[*]}); installation not approved."
  [[ -w $SDK ]] || fail 'SDK is not writable; no privilege escalation will be attempted.'
  timeout --signal=TERM --kill-after=10s 600s "$SDKMANAGER" --sdk_root="$SDK" --channel=0 --install "${missing[@]}" </dev/null
fi
[[ -x $ADB && -x $SDK/emulator/emulator && -f $SDK/platforms/android-36/android.jar && -x $SDK/build-tools/35.0.0/aapt2 && -f $SDK/system-images/android-${API_LEVEL}/google_apis/x86_64/system.img ]] || fail 'SDK setup incomplete or licenses unavailable.'
EMULATOR="$SDK/emulator/emulator"
timeout 20s "$EMULATOR" -accel-check | tee "$RUN_ROOT/acceleration.txt"
grep -Eq 'KVM.*(usable|installed)' "$RUN_ROOT/acceleration.txt" || fail 'KVM acceleration was not confirmed.'
java -version
"$ADB" version
timeout 20s "$EMULATOR" -version >"$RUN_ROOT/emulator-version.txt"
sed -n '1,5p' "$RUN_ROOT/emulator-version.txt"
printf 'Reviewed commit: %s; emulator API: %s\n' "$REVIEWED_COMMIT" "$API_LEVEL"

# Build first: no emulator competes with Gradle/Robolectric for runner resources.
# Only ordinary disposable debug signing is used; no release key or release task exists here.
timeout --signal=TERM --kill-after=15s 1200s bash ./gradlew --no-daemon --no-parallel --max-workers=2 \
  '-Dorg.gradle.jvmargs=-Xmx1536m -Dfile.encoding=UTF-8' -Pkotlin.compiler.execution.strategy=in-process \
  :core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :fixture:assembleDebug :app:assembleDebugAndroidTest

# Fail if the runner already has a server/emulator on our fixed local ports. Never adopt it.
python3 - <<'PY'
import socket
sockets = []
try:
    for port in (5037, 5556, 5557):
        sock = socket.socket()
        sock.bind(('127.0.0.1', port))
        sockets.append(sock)
finally:
    for sock in sockets:
        sock.close()
PY
# Track the attempted owned server before start: it may spawn successfully then time out.
# The preceding port check established that this invocation must not adopt an existing server.
ADB_STARTED=true
timeout 20s "$ADB" -L tcp:127.0.0.1:5037 start-server
[[ $(timeout 15s "$ADB" -H 127.0.0.1 -P 5037 devices | awk 'NR>1 && NF {n++} END {print n+0}') == 0 ]] || fail 'Runner already has another device; refusing all installations.'
# "no" answers only the AVD custom-hardware-profile question, not a license prompt.
printf 'no\n' | timeout --signal=TERM --kill-after=5s 60s "$AVDMANAGER" create avd \
  --name phone_mcp_review --package "$IMAGE" --device pixel_6 --path "$ANDROID_AVD_HOME/phone_mcp_review.avd"
"$EMULATOR" -avd phone_mcp_review -port 5556 -accel on -no-window -no-audio -no-boot-anim \
  -no-snapshot -wipe-data -cores 2 -memory 2048 -gpu software >"$RUN_ROOT/emulator.log" 2>&1 &
EMU_PID=$!
BOOT_DEADLINE=$((SECONDS + 180))
booted=false
while ((SECONDS < BOOT_DEADLINE)); do
  kill -0 "$EMU_PID" 2>/dev/null || fail 'Owned emulator exited during boot.'
  if [[ $(timeout 5s "$ADB" -H 127.0.0.1 -P 5037 -s "$SERIAL" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r') == 1 ]]; then
    booted=true
    break
  fi
  sleep 2
done
[[ $booted == true ]] || fail 'Emulator did not finish booting within 180 seconds.'
adb_owned() { timeout --signal=TERM --kill-after=5s 90s "$ADB" -H 127.0.0.1 -P 5037 -s "$SERIAL" "$@"; }
[[ $(adb_owned shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || fail 'Target does not identify as a synthetic emulator.'
[[ $(adb_owned shell getprop ro.build.version.sdk | tr -d '\r') == "$API_LEVEL" ]] || fail 'Unexpected emulator API.'
[[ $(timeout 15s "$ADB" -H 127.0.0.1 -P 5037 devices | awk 'NR>1 && NF {n++} END {print n+0}') == 1 ]] || fail 'Unexpected additional device appeared.'
for apk in app/build/outputs/apk/debug/app-debug.apk fixture/build/outputs/apk/debug/fixture-debug.apk app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk; do
  [[ -f $apk ]] || fail "APK missing: $apk"
  adb_owned install -r -t "$apk"
done

run_instrumentation() {
  local phase=$1 selection=$2 limit=$3 output="$RUN_ROOT/$1.txt"
  timeout --signal=TERM --kill-after=15s "$limit" "$ADB" -H 127.0.0.1 -P 5037 -s "$SERIAL" shell \
    am instrument -w -r -e class "$selection" dev.phonemcp.android.test/androidx.test.runner.AndroidJUnitRunner | tee "$output"
  # adb may exit zero for failed instrumentation: require per-test success AND final JUnit OK.
  python3 - "$output" "$phase" "$API_LEVEL" <<'PY'
import pathlib, re, sys
text = pathlib.Path(sys.argv[1]).read_text()
phase, api = sys.argv[2], int(sys.argv[3])
if not re.search(r'(?m)^OK \([1-9][0-9]* tests?\)\s*$', text):
    raise SystemExit('Instrumentation did not report a nonempty successful JUnit run')
if re.search(r'FAILURES!!!|INSTRUMENTATION_FAILED|INSTRUMENTATION_ABORTED|Process crashed', text):
    raise SystemExit('Instrumentation reported failure')
passed, current = set(), {}
for line in text.splitlines():
    match = re.match(r'INSTRUMENTATION_STATUS: (class|test)=(.*)', line)
    if match:
        current[match[1]] = match[2]
    match = re.match(r'INSTRUMENTATION_STATUS_CODE: (-?\d+)', line)
    if match:
        code = int(match[1])
        if code in (-1, -2):
            raise SystemExit('Instrumentation reported a failed test status')
        if code == 0 and 'class' in current and 'test' in current:
            passed.add(current['class'] + '#' + current['test'])
        current = {}
a = 'dev.phonemcp.android.DeviceAccessibilityTest#'
s = 'dev.phonemcp.android.DeviceSessionTest#'
expected = {a + 'standaloneFixtureForegroundSmoke'}
if phase == 'suite':
    expected |= {a + name for name in (
        'realTreeRedactsSecretsAndFreshClickInvalidatesOldHandle', 'realReadOnlySessionCannotClickOrNavigate',
        'nonSensitiveTextReplacementAndClearNeverExposeEditableValues', 'wrongNodeTypesAndEditableClicksAreRejected',
        'oneStepScrollUsesFreshHandlesAndCanReverse', 'ownTokenScreenIsNeverExposed')}
    expected |= {s + name for name in ('secureUiAndRevokedSessionStayInactiveAfterRecreation',
        'notificationDeleteIntentRevokesSession', 'localStartStopRotationAndActualHttpAuthentication')}
    if api >= 36:
        expected.add(a + 'api36SecondCredentialLabelPreventsTextHandle')
missing = expected - passed
if missing:
    raise SystemExit('Required test(s) did not pass: ' + ', '.join(sorted(missing)))
print(f'PASS: {phase}; {len(expected)} required test methods confirmed on API{api}')
PY
}
run_instrumentation smoke 'dev.phonemcp.android.DeviceAccessibilityTest#standaloneFixtureForegroundSmoke' 240s
run_instrumentation suite 'dev.phonemcp.android.DeviceAccessibilityTest,dev.phonemcp.android.DeviceSessionTest' 600s
printf 'Manual validation succeeded on API%s. This is not signing, publication, physical-device certification, or billing approval.\n' "$API_LEVEL"
