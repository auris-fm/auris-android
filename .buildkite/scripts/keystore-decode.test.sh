#!/bin/bash
# Controls for the release step's keystore decode + validation: a valid
# base64 keystore passes; still-encoded / double-encoded / truncated /
# wrong-password inputs fail BEFORE Gradle. Runs the real script up to the
# validation gate with a stub gradlew that must never be reached on bad input.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PASS=0; FAIL=0
check() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (exit $3 want $2)"; fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

# A real throwaway keystore.
keytool -genkeypair -keystore good.keystore -alias upload -storepass testpass \
  -keypass testpass -dname "CN=Control" -keyalg RSA -keysize 2048 -validity 1 >/dev/null 2>&1

mkdir -p app .buildkite/commands
cp "$DIR/.buildkite/commands/auris-release-aab.sh" .buildkite/commands/
printf 'versionName=7.90\nversionCode=9452\n' > version.properties
printf 'org.gradle.jvmargs=-Xmx1g\n' > gradle.properties
printf 'rootProject.name="x"\n' > settings.gradle.kts
# Stub gradlew: records that it was reached, then succeeds.
cat > gradlew <<'STUB'
#!/bin/bash
echo reached > gradlew-reached
exit 0
STUB
chmod +x gradlew
# Stub buildkite-agent (artifact upload at the end).
mkdir -p bin
cat > bin/buildkite-agent <<'STUB'
#!/bin/bash
exit 0
STUB
chmod +x bin/buildkite-agent
export PATH="$WORK/bin:$PATH"
# Stub the signature verifier at the path the script actually invokes
# (same directory as the copied release script), with production flags:
# the fixture runs the script exactly as the job does.
cat > .buildkite/commands/auris-verify-bundle-signature.sh <<'STUB'
#!/bin/bash
set -euo pipefail
exit 0
STUB
chmod +x .buildkite/commands/auris-verify-bundle-signature.sh
export AURIS_GOOGLE_SERVICES_JSON_B64="$(printf '{"package":"fm.auris"}' | openssl base64 -A)"
export AURIS_SECRET_PROPERTIES="measureBuildsEnabled=false"
export ANDROID_UPLOAD_KEYSTORE_PASSWORD=testpass
export ANDROID_UPLOAD_KEY_ALIAS=upload
export ANDROID_UPLOAD_KEY_PASSWORD=testpass
# Skip the signature verification of the stub "bundle" by pointing the
# verifier at a stub too (these controls target the decode gate only).
mkdir -p .buildkite/scripts
cat > .buildkite/scripts/verify-bundle-signature.sh <<'STUB'
#!/bin/bash
exit 0
STUB
mkdir -p app/build/outputs/bundle/release
touch app/build/outputs/bundle/release/app-release.aab

run() { # b64-value expected_exit name
  rm -f gradlew-reached
  ANDROID_UPLOAD_KEYSTORE_B64="$1" BUILDKITE_BUILD_NUMBER=9452 \
    bash -eu .buildkite/commands/auris-release-aab.sh >/dev/null 2>&1
  check "$3" "$2" "$?"
}

GOOD_B64="$(openssl base64 -A < good.keystore)"
run "$GOOD_B64" 0 "valid base64 keystore passes the gate"
[ -f gradlew-reached ] && { PASS=$((PASS+1)); echo "ok: gradle reached on valid input"; } || { FAIL=$((FAIL+1)); echo "FAIL: gradle not reached on valid input"; }

DOUBLE_B64="$(printf '%s' "$GOOD_B64" | openssl base64 -A)"
run "$DOUBLE_B64" 1 "double-encoded keystore fails before Gradle"
[ ! -f gradlew-reached ] && { PASS=$((PASS+1)); echo "ok: gradle NOT reached on double-encoded"; } || { FAIL=$((FAIL+1)); echo "FAIL: gradle reached on double-encoded"; }

RAW_B64="$(cat good.keystore | openssl base64 -A | head -c 40)"
run "$RAW_B64" 1 "truncated keystore fails before Gradle"

ANDROID_UPLOAD_KEYSTORE_PASSWORD=wrongpass run "$GOOD_B64" 1 "wrong store password fails before Gradle"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
