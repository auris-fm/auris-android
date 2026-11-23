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
if [ "$2" = "--print-certs" ]; then
  echo "Signer #1 certificate SHA-256 digest: aa:bb:cc"
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
  OUT="$(cd "$WORK/repo" && MODE="$1" VC="$2" \
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

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
