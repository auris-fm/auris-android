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

# Stub apksigner: emits a chosen REAL output shape. The shapes below are copied from actual
# `apksigner verify --print-certs` runs -- build-tools 37.0.0 on the runner (labelled signers)
# and 36.1.0 locally ("Signer #N"). MODE selects which failure the real script must survive.
cat > "$WORK/sdk/build-tools/35.0.0/apksigner" <<'STUB'
#!/bin/bash
case "${MODE:-good}" in
  unsigned) exit 1 ;;
  nocerts)  # verify passes, but --print-certs prints NO digest line
    [ "$2" = "--print-certs" ] && { echo "Signer #1 name: CN=x"; exit 0; }
    exit 0 ;;
esac
if [ "$2" = "--print-certs" ]; then
  for a in "$@"; do
    if [ "$a" = "-v" ] || [ "$a" = "--verbose" ]; then
      echo "Verified using v2 scheme (APK Signature Scheme v2): true"
      echo "Verified using v3 scheme (APK Signature Scheme v3): true"
    fi
  done
  cat "$SHAPE_FILE"
fi
exit 0
STUB
chmod +x "$WORK/sdk/build-tools/35.0.0/apksigner"
cat > "$WORK/sdk/build-tools/35.0.0/aapt2" <<'STUB'
#!/bin/bash
echo "package: name='fm.auris' versionCode='${VC:-9522}' versionName='8.20'"
STUB
chmod +x "$WORK/sdk/build-tools/35.0.0/aapt2"

# --- Real output shapes -------------------------------------------------------------
# 36.1.0, single signer, no stamp.
cat > "$WORK/shape-plain" <<'SHAPE'
Signer #1 certificate DN: CN=test
Signer #1 certificate SHA-256 digest: aa:bb:cc
SHAPE
# 36.1.0, app key + a DIFFERENT source stamp. This is the production bug in miniature:
# both lines contain "certificate SHA-256 digest", so a position-based pick takes the stamp.
cat > "$WORK/shape-stamped" <<'SHAPE'
Signer #1 certificate DN: CN=test
Signer #1 certificate SHA-256 digest: aa:bb:cc
Source Stamp Signer certificate DN: CN=stamp
Source Stamp Signer certificate SHA-256 digest: 99:88:77
SHAPE
# 37.0.0 on the runner: two v3.2 hybrid signers, the v3.0 app signer, and a source stamp.
cat > "$WORK/shape-hybrid" <<'SHAPE'
V3.2 Hybrid Classical Signer: (minSdkVersion=37, maxSdkVersion=2147483647) certificate SHA-256 digest: dd:dd:dd
V3.2 Hybrid PQC Signer: (minSdkVersion=37, maxSdkVersion=2147483647) certificate SHA-256 digest: 28:28:28
V3.0 Signer: certificate SHA-256 digest: aa:bb:cc
Source Stamp Signer: certificate SHA-256 digest: 99:88:77
SHAPE
# Same, but the v3.0 app signer is NOT the pin.
cat > "$WORK/shape-hybrid-wrongapp" <<'SHAPE'
V3.2 Hybrid Classical Signer: (minSdkVersion=37, maxSdkVersion=2147483647) certificate SHA-256 digest: dd:dd:dd
V3.2 Hybrid PQC Signer: (minSdkVersion=37, maxSdkVersion=2147483647) certificate SHA-256 digest: 28:28:28
V3.0 Signer: certificate SHA-256 digest: 11:22:33
Source Stamp Signer: certificate SHA-256 digest: 99:88:77
SHAPE
# A hybrid signer that is not one of Google's published pair.
cat > "$WORK/shape-hybrid-wronghybrid" <<'SHAPE'
V3.2 Hybrid Classical Signer: (minSdkVersion=37, maxSdkVersion=2147483647) certificate SHA-256 digest: ee:ee:ee
V3.2 Hybrid PQC Signer: (minSdkVersion=37, maxSdkVersion=2147483647) certificate SHA-256 digest: 28:28:28
V3.0 Signer: certificate SHA-256 digest: aa:bb:cc
Source Stamp Signer: certificate SHA-256 digest: 99:88:77
SHAPE
# A genuine ROTATION (real 36.1.0 output): newest FIRST. The current-range signer carries the pin
# and the superseded range keeps the old certificate. This MUST pass: requiring every signer to
# equal one pin would make a real rotation permanently unpublishable, since no single value can
# satisfy both ends of the lineage.
cat > "$WORK/shape-rotated" <<'SHAPE'
Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: aa:bb:cc
Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: 11:22:33
SHAPE
# Same rotation, but the CURRENT-range signer is off the pin. Must fail.
cat > "$WORK/shape-rotated-wrongcurrent" <<'SHAPE'
Signer (minSdkVersion=33, maxSdkVersion=2147483647) certificate SHA-256 digest: 11:22:33
Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: aa:bb:cc
SHAPE
# Only a superseded-range signer: nothing governs current devices. Must fail.
cat > "$WORK/shape-rotated-oldonly" <<'SHAPE'
Signer (minSdkVersion=24, maxSdkVersion=32) certificate SHA-256 digest: aa:bb:cc
SHAPE
# Only a stamp: no app-signing signer at all.
cat > "$WORK/shape-stamponly" <<'SHAPE'
Source Stamp Signer: certificate SHA-256 digest: 99:88:77
SHAPE
# A label the parser does not know, ALONGSIDE a valid app signer that matches the pin. The valid
# signer matters: without it the run would fail for missing-app-signer instead, and skipping
# unknown labels would look correct while proving nothing.
cat > "$WORK/shape-unknown" <<'SHAPE'
V3.0 Signer: certificate SHA-256 digest: aa:bb:cc
V9 Future Signer: certificate SHA-256 digest: 12:34:56
Source Stamp Signer: certificate SHA-256 digest: 99:88:77
SHAPE
export SHAPE_FILE="$WORK/shape-plain"
export AURIS_PLAY_HYBRID_CLASSICAL_SHA256="DD:DD:DD"
export AURIS_PLAY_HYBRID_PQC_SHA256="28:28:28"

run() { # mode apk-version expected-version -> sets OUT/CODE
  SHAPE_FILE="$WORK/shape-${4:-plain}"
  OUT="$(cd "$WORK/repo" && MODE="$1" VC="$2" SHAPE_FILE="$SHAPE_FILE" \
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

# THE CASE THIS FIX EXISTS FOR: an app key plus a DIFFERENT source stamp. Both lines carry
# "certificate SHA-256 digest", so picking by position takes the stamp and fails a good APK.
run good 9522 9522 stamped
check "a source stamp different from the app key does not fail the APK" 0 "$CODE"

# The runner's real shape: two hybrid signers, the v3.0 app signer, and a stamp.
run good 9522 9522 hybrid
check "the runner's hybrid + stamp shape passes" 0 "$CODE"

run good 9522 9522 hybrid-wrongapp
check "an app-signing signer off its pin fails" 1 "$CODE"
printf '%s' "$OUT" | grep -qi "app-signing" && r=ok || r=missing
check "the app-signing failure names what mismatched" ok "$r"

run good 9522 9522 hybrid-wronghybrid
check "a hybrid signer outside Google's published pair fails" 1 "$CODE"

run good 9522 9522 stamponly
check "no app-signing signer at all fails" 1 "$CODE"

run good 9522 9522 unknown
check "an unrecognised signer label fails closed rather than being skipped" 1 "$CODE"

# A hybrid signer present with NO pin configured must fail, not be waved through. Without this,
# dropping the pin check for an unconfigured hybrid key would stay green.
run good 9522 9522 hybrid
saved_classical="$AURIS_PLAY_HYBRID_CLASSICAL_SHA256"
AURIS_PLAY_HYBRID_CLASSICAL_SHA256=""
OUT="$(cd "$WORK/repo" && MODE=good VC=9522 SHAPE_FILE="$WORK/shape-hybrid" \
  bash .buildkite/scripts/verify-play-apk.sh "$WORK/app.apk" 9522 "$PIN" 2>&1)"
CODE=$?
AURIS_PLAY_HYBRID_CLASSICAL_SHA256="$saved_classical"
check "a hybrid signer with no pin configured fails closed" 1 "$CODE"

run good 9522 9522 rotated
check "a real rotation whose current signer is the pin passes" 0 "$CODE"

run good 9522 9522 rotated-wrongcurrent
check "a rotation whose CURRENT signer is off the pin fails" 1 "$CODE"

run good 9522 9522 rotated-oldonly
check "a rotation with only a superseded-range signer fails" 1 "$CODE"

AURIS_PLAY_HYBRID_PQC_SHA256_SAVE="$AURIS_PLAY_HYBRID_PQC_SHA256"
AURIS_PLAY_HYBRID_PQC_SHA256=""
OUT="$(cd "$WORK/repo" && MODE=good VC=9522 SHAPE_FILE="$WORK/shape-hybrid" \
  bash .buildkite/scripts/verify-play-apk.sh "$WORK/app.apk" 9522 "$PIN" 2>&1)"
CODE=$?
AURIS_PLAY_HYBRID_PQC_SHA256="$AURIS_PLAY_HYBRID_PQC_SHA256_SAVE"
check "a PQC signer with no pin configured fails closed too" 1 "$CODE"

# For a LABELLED signer the dangerous direction is RAISING the threshold past its range, which
# skips a signer that should be compared; lowering it compares more, not fewer. These cases use
# explicitly labelled signers to pin that. A "skip any bounded range" rule would pass here and
# prove nothing. (The real V3.0 line is UNLABELLED and always compared -- see the case below.)
cat > "$WORK/shape-bounded-current" <<'SHAPE'
Signer (minSdkVersion=24, maxSdkVersion=36) certificate SHA-256 digest: 11:22:33
SHAPE
# A bounded current-range signer that is OFF the pin, alongside a valid unbounded one. RAISING
# the floor to 37 would skip the bounded signer and pass on the strength of the other -- the
# off-pin key would go unchecked. This is what pins the threshold itself. (Lowering it is the
# safe direction: it compares more, so a lowered-floor mutant is not a defect.)
cat > "$WORK/shape-bounded-offpin" <<'SHAPE'
Signer (minSdkVersion=37, maxSdkVersion=2147483647) certificate SHA-256 digest: aa:bb:cc
Signer (minSdkVersion=24, maxSdkVersion=36) certificate SHA-256 digest: 11:22:33
SHAPE
run good 9522 9522 bounded-current
check "a bounded signer at the top of the current range is still compared" 1 "$CODE"

run good 9522 9522 bounded-offpin
check "an off-pin bounded signer is caught even beside a valid unbounded one" 1 "$CODE"

# The real Play output's app-signing line carries NO SDK label, so the threshold must never
# affect it. Run with the threshold raised far past any range: an unlabelled off-pin signer must
# still fail. Without this, "skip anything without a range" would look plausible and stay green.
OUT="$(cd "$WORK/repo" && MODE=good VC=9522 SHAPE_FILE="$WORK/shape-hybrid-wrongapp" \
  AURIS_CURRENT_DEVICE_SDK=999 \
  bash .buildkite/scripts/verify-play-apk.sh "$WORK/app.apk" 9522 "$PIN" 2>&1)"
CODE=$?
check "an UNLABELLED app signer is compared whatever the threshold" 1 "$CODE"

# Diagnostic still printed on a FAILING run -- the run we need to read is a failing one.
run good 9522 9522 hybrid-wrongapp
printf '%s' "$OUT" | grep -qE "APK size: [0-9]+ bytes" && r=ok || r=missing
check "byte count survives a FAILED comparison" ok "$r"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
