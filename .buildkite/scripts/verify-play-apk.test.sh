#!/bin/bash
# Controls for the Play-APK verifier: it must FAIL CLOSED with its own
# diagnostic on every bad input, not die silently from `set -e`.
# Runs the REAL script with stub apksigner/aapt2 so each failure mode can be
# produced exactly; the r2-publish suite stubs this verifier wholesale, so
# without these controls nothing exercises it at all.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PASS=0; FAIL=0
check() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (got '$3' want '$2')"; fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/sdk/build-tools/35.0.0" "$WORK/repo/.buildkite/scripts"
cp "$DIR/.buildkite/scripts/verify-play-apk.sh" "$WORK/repo/.buildkite/scripts/"
printf 'apk-bytes' > "$WORK/app.apk"
export ANDROID_HOME="$WORK/sdk"
PIN="AA:BB:CC"

# Stub apksigner: MODE selects which failure the real script must survive.
cat > "$WORK/sdk/build-tools/35.0.0/apksigner" <<'STUB'
#!/bin/bash
case "${MODE:-good}" in
  unsigned) exit 1 ;;
  nocerts)  # verify passes, but --print-certs prints NO digest line
    [ "$2" = "--print-certs" ] && { echo "Signer #1 name: CN=x"; exit 0; }
    exit 0 ;;
esac
VERBOSE=0
for a in "$@"; do [ "$a" = "-v" ] || [ "$a" = "--verbose" ] && VERBOSE=1; done
if [ "$2" = "--print-certs" ]; then
  if [ "${CHAIN:-single}" = rotated ]; then
    echo "Signer #1 certificate DN: CN=pinned"
    echo "Signer #1 certificate SHA-256 digest: aa:bb:cc"
    echo "Signer #2 certificate DN: CN=added"
    echo "Signer #2 certificate SHA-256 digest: 11:22:33"
  else
    echo "Signer #1 certificate SHA-256 digest: aa:bb:cc"
  fi
  if [ "$VERBOSE" = 1 ]; then
    echo "Verified using v2 scheme (APK Signature Scheme v2): true"
    echo "Verified using v3 scheme (APK Signature Scheme v3): true"
    echo "Number of signers: 1"
    echo "Signer #1 min SDK: 24, max SDK: 33"
  fi
fi
exit 0
STUB
chmod +x "$WORK/sdk/build-tools/35.0.0/apksigner"
cat > "$WORK/sdk/build-tools/35.0.0/aapt2" <<'STUB'
#!/bin/bash
echo "package: name='fm.auris' versionCode='${VC:-9522}' versionName='8.20'"
STUB
chmod +x "$WORK/sdk/build-tools/35.0.0/aapt2"

run() { # mode apk-version expected-version -> sets OUT/CODE
  OUT="$(cd "$WORK/repo" && MODE="$1" VC="$2" CHAIN="${4:-single}" \
    bash .buildkite/scripts/verify-play-apk.sh "$WORK/app.apk" "$3" "$PIN" 2>&1)"
  CODE=$?
}

run good 9522 9522
check "valid APK passes" 0 "$CODE"

run unsigned 9522 9522
check "unsigned APK fails" 1 "$CODE"
printf '%s' "$OUT" | grep -q "signature does not verify" && r=ok || r=missing
check "unsigned failure names its cause" ok "$r"

# THE CONTROL THIS SUITE EXISTS FOR: apksigner verifies but reports no
# certificate digest. Under `set -euo pipefail` a grep miss aborts the whole
# script, so the "no signer certificate found" diagnostic never runs and the
# step reds with no explanation of why.
run nocerts 9522 9522
check "certificate-less APK fails" 1 "$CODE"
printf '%s' "$OUT" | grep -q "no signer certificate found" && r=ok || r=missing
check "certificate-less failure PRINTS its diagnostic (not a silent set -e abort)" ok "$r"

run good 9522 9999
check "versionCode mismatch fails" 1 "$CODE"
printf '%s' "$OUT" | grep -q "versionCode" && r=ok || r=missing
check "versionCode mismatch names both values" ok "$r"

# --- Diagnostic output: the schemes/SDK ranges and the byte count must be printed BEFORE the
# comparison, so a FAILING run still explains which scheme carries which key. A guard that only
# prints on success tells us nothing on the run we actually need to read.
run good 9522 9522
printf '%s' "$OUT" | grep -q "Verified using v2 scheme" && r=ok || r=missing
check "verbose scheme detail is printed" ok "$r"
printf '%s' "$OUT" | grep -qE "APK size: [0-9]+ bytes" && r=ok || r=missing
check "APK byte count is printed" ok "$r"

# The case that matters: a chain whose LAST digest is not the pin fails, and the diagnostic must
# still be there. Without this, moving the print below the comparison would stay green.
run good 9522 9522 rotated
check "a chain ending off the pin still fails" 1 "$CODE"
printf '%s' "$OUT" | grep -q "Verified using v2 scheme" && r=ok || r=missing
check "scheme detail survives a FAILED pin comparison" ok "$r"
printf '%s' "$OUT" | grep -qE "APK size: [0-9]+ bytes" && r=ok || r=missing
check "byte count survives a FAILED pin comparison" ok "$r"
printf '%s' "$OUT" | grep -q "11:22:33" && r=ok || r=missing
check "every certificate in the chain is printed, not just the compared one" ok "$r"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
