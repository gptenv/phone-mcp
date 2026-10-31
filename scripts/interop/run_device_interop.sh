#!/usr/bin/env bash
# Explicitly opt-in, installed debug/test APKs on a DEDICATED EMULATOR only.
# No install/activation on a personal phone; no user token in process arguments.
set -euo pipefail
command -v timeout >/dev/null || { echo "GNU timeout is required for bounded emulator commands" >&2; exit 1; }
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
ADB=${ADB:-adb}
PYTHON=${MCP_PYTHON:-python3}
SERIAL=${ANDROID_SERIAL:-emulator-5554}
[[ "$SERIAL" =~ ^emulator-[0-9]+$ ]] || { echo 'Dedicated emulator serial required' >&2; exit 1; }
[[ $(timeout 20 "$ADB" -s "$SERIAL" shell getprop ro.kernel.qemu | tr -d '\r') == 1 ]] || { echo 'Dedicated emulator required' >&2; exit 1; }
OUT=${PHONE_MCP_EVIDENCE_DIR:-"$ROOT/docs/verification/interop"}
mkdir -p "$OUT"
TESTPID=''
cleanup() {
 timeout 20 "$ADB" -s "$SERIAL" shell run-as dev.phonemcp.android touch files/interop-finish >/dev/null 2>&1 || true
 timeout 20 "$ADB" -s "$SERIAL" forward --remove tcp:8765 >/dev/null 2>&1 || true
 if [[ -n "$TESTPID" ]] && kill -0 "$TESTPID" 2>/dev/null; then
  timeout 20 "$ADB" -s "$SERIAL" shell am force-stop dev.phonemcp.android >/dev/null 2>&1 || true
  kill "$TESTPID" 2>/dev/null || true
 fi
 unset PHONE_MCP_TOKEN PHONE_MCP_OLD_TOKEN
}
trap cleanup EXIT
wait_file() {
 local name=$1 end=$((SECONDS+180))
 while ((SECONDS<end)); do
  if timeout 20 "$ADB" -s "$SERIAL" shell run-as dev.phonemcp.android test -f "files/$name" >/dev/null 2>&1; then return; fi
  sleep 1
 done
 echo 'Emulator coordination timed out' >&2; return 1
}
timeout 20 "$ADB" -s "$SERIAL" forward tcp:8765 tcp:8765 >/dev/null
timeout 900 "$ADB" -s "$SERIAL" shell am instrument -w -r -e hostInterop true -e class dev.phonemcp.android.HostInteropHarness dev.phonemcp.android.test/androidx.test.runner.AndroidJUnitRunner > "$OUT/device-coordinator.log" 2>&1 &
TESTPID=$!
wait_file interop-token
export PHONE_MCP_TOKEN
PHONE_MCP_TOKEN=$(timeout 20 "$ADB" -s "$SERIAL" shell run-as dev.phonemcp.android cat files/interop-token | tr -d '\r')
[[ ${#PHONE_MCP_TOKEN} == 43 ]]
"$PYTHON" "$ROOT/scripts/interop/sdk_client.py" --label dedicated-emulator-active --output "$OUT/emulator-active.json"
export PHONE_MCP_OLD_TOKEN="$PHONE_MCP_TOKEN"
timeout 20 "$ADB" -s "$SERIAL" shell run-as dev.phonemcp.android touch files/interop-stop
wait_file interop-stopped
timeout 20 "$ADB" -s "$SERIAL" shell run-as dev.phonemcp.android cat files/interop-stopped > "$OUT/emulator-stop-device-proof.json"
"$PYTHON" - "$OUT/emulator-stop-device-proof.json" <<'PY'
import json,sys
p=json.load(open(sys.argv[1]))
assert p.get('revocation_processed') is True and p.get('active') is False and p.get('token_present') is False
assert p.get('listener_closed') is True and p.get('connection_error') == 'ECONNREFUSED'
PY
# A healthy device/forward and device-local refusal are separate evidence from host EOF.
timeout 20 "$ADB" -s "$SERIAL" shell getprop sys.boot_completed | tr -d '\r' | grep -qx 1
timeout 20 "$ADB" forward --list | grep -F "$SERIAL tcp:8765 tcp:8765" > "$OUT/forward-health.txt"
set +e
"$PYTHON" "$ROOT/scripts/interop/sdk_client.py" --label dedicated-emulator-stopped --expect-stopped --output "$OUT/emulator-stopped.json"
STOP_RESULT=$?
set -e
[[ "$STOP_RESULT" == 0 || "$STOP_RESULT" == 2 ]] || exit "$STOP_RESULT"
# Preserve an INCONCLUSIVE host EOF as-is; never rewrite it to PASS.
timeout 20 "$ADB" -s "$SERIAL" shell run-as dev.phonemcp.android touch files/interop-restart
wait_file interop-restarted
PHONE_MCP_TOKEN=$(timeout 20 "$ADB" -s "$SERIAL" shell run-as dev.phonemcp.android cat files/interop-token | tr -d '\r')
"$PYTHON" "$ROOT/scripts/interop/sdk_client.py" --label dedicated-emulator-restarted --output "$OUT/emulator-restarted.json"
timeout 20 "$ADB" -s "$SERIAL" shell run-as dev.phonemcp.android touch files/interop-finish
wait "$TESTPID"
TESTPID=''
grep -q 'OK (1 test)' "$OUT/device-coordinator.log"
echo 'Coordinated emulator evidence recorded; inspect device proof and distinct host Stop observation. No physical-device certification.'
