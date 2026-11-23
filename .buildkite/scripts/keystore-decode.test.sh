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

SAVED_REAL_KEYTOOL_EARLY="$(command -v keytool)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

# A real throwaway keystore.
keytool -genkeypair -keystore good.keystore -alias upload -storepass testpass \
  -keypass testpass -dname "CN=Control" -keyalg RSA -keysize 2048 -validity 1 >/dev/null 2>&1

mkdir -p app .buildkite/commands
cp "$DIR/.buildkite/commands/auris-release-aab.sh" .buildkite/commands/
cp "$DIR/.buildkite/commands/auris-require-interpreter.sh" .buildkite/commands/
# The release step asserts the repo's pinned ruby and bundler before it decodes anything.
# These controls target the DECODE gate, so give the fixture the pinned pair rather than
# whatever this machine's PATH resolves -- the interpreter gate has its own suite.
cp "$DIR/.ruby-version" "$DIR/Gemfile.lock" .
mkdir -p pin-bin
cat > pin-bin/ruby <<'STUB'
#!/bin/bash
if [ "$1" = "-v" ] || [ "$1" = "--version" ]; then
  echo "ruby $(cat .ruby-version) (fixture) [arm64-darwin]"
  exit 0
fi
exec REAL_RUBY_PLACEHOLDER "$@"
STUB
sed -i.bak "s|REAL_RUBY_PLACEHOLDER|$(command -v ruby)|" pin-bin/ruby && rm -f pin-bin/ruby.bak
chmod +x pin-bin/ruby
cat > pin-bin/bundle <<'STUB'
#!/bin/bash
if [ "$1" = "-v" ] || [ "$1" = "--version" ]; then
  echo "Bundler version $(awk '/^BUNDLED WITH/{getline; gsub(/[ \t]/,""); print}' Gemfile.lock)"
  exit 0
fi
exit 0
STUB
chmod +x pin-bin/bundle
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
export PATH="$WORK/pin-bin:$WORK/bin:$PATH"
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

# Pin JAVA_HOME to the REAL JDK for the generic cases: CI jobs set their
# own JAVA_HOME, and the gate (correctly) prefers it -- the fixture must
# not inherit a JAVA_HOME that points somewhere without a working keytool.
FIXTURE_JAVA_HOME="$(dirname "$(dirname "$SAVED_REAL_KEYTOOL_EARLY")")"
run() { # b64-value expected_exit name
  rm -f gradlew-reached
  JAVA_HOME="$FIXTURE_JAVA_HOME" ANDROID_UPLOAD_KEYSTORE_B64="$1" BUILDKITE_BUILD_NUMBER=9452 \
    bash -eu .buildkite/commands/auris-release-aab.sh >/dev/null 2>&1
  check "$3" "$2" "$?"
}

GOOD_B64="$(openssl base64 -A < good.keystore)"
run "$GOOD_B64" 0 "valid base64 keystore passes the gate"
[ -f gradlew-reached ] && { PASS=$((PASS+1)); echo "ok: gradle reached on valid input"; } || { FAIL=$((FAIL+1)); echo "FAIL: gradle not reached on valid input"; }

# Wrapped base64 (GNU base64 wraps at 76 cols): must decode identically.
WRAPPED_B64="$(openssl base64 < good.keystore)"
run "$WRAPPED_B64" 0 "wrapped base64 keystore passes the gate"

# LibreSSL-like PATH openssl: -A on multiline input outputs NOTHING, exit 0.
# The script must not use PATH's openssl at all -- if it did, the wrapped
# case above would produce an empty file and fail. Stub proves independence.
mkdir -p "$WORK/libressl-bin"
cat > "$WORK/libressl-bin/openssl" <<'STUB'
#!/bin/bash
# LibreSSL 3.3.6 behaviour: -A with newlines in input => empty output, exit 0
if [ "$1" = base64 ] && printf '%s\n' "$@" | grep -q -- '-A'; then
  input="$(cat)"
  case "$input" in *$'\n'*) exit 0 ;; esac
  printf '%s' "$input" | /usr/bin/openssl base64 -d -A
  exit 0
fi
exec /usr/bin/openssl "$@"
STUB
chmod +x "$WORK/libressl-bin/openssl"
rm -f gradlew-reached
PATH="$WORK/libressl-bin:$WORK/pin-bin:$PATH" JAVA_HOME="$FIXTURE_JAVA_HOME" ANDROID_UPLOAD_KEYSTORE_B64="$WRAPPED_B64" BUILDKITE_BUILD_NUMBER=9452 \
  bash -eu .buildkite/commands/auris-release-aab.sh >/dev/null 2>&1
check "wrapped base64 under LibreSSL-like PATH openssl still passes" 0 "$?"

# JAVA_HOME pin: a PATH keytool that always fails must NOT be used when
# JAVA_HOME points at a working one (and vice versa proves the pin).
mkdir -p "$WORK/jh/bin"
SAVED_REAL_KEYTOOL="$(command -v keytool)"
cat > "$WORK/bin/keytool" <<'STUB'
#!/bin/bash
echo "PATH-keytool-used" >> "$WORK/keytool-which"
exit 1
STUB
chmod +x "$WORK/bin/keytool"
# Wrapper, not a copied binary: keytool needs its JDK's libjli via rpath.
REAL_KEYTOOL="$SAVED_REAL_KEYTOOL"
cat > "$WORK/jh/bin/keytool" <<STUB
#!/bin/bash
echo "JAVA_HOME-keytool-used" >> "$WORK/keytool-which"
exec "$REAL_KEYTOOL" "\$@"
STUB
chmod +x "$WORK/jh/bin/keytool"
rm -f "$WORK/keytool-which" gradlew-reached
JAVA_HOME="$WORK/jh" ANDROID_UPLOAD_KEYSTORE_B64="$GOOD_B64" BUILDKITE_BUILD_NUMBER=9452 \
  bash -eu .buildkite/commands/auris-release-aab.sh >/dev/null 2>&1
check "JAVA_HOME keytool selected over failing PATH stub" 0 "$?"
grep -q "JAVA_HOME-keytool-used" "$WORK/keytool-which" 2>/dev/null && ! grep -q "PATH-keytool-used" "$WORK/keytool-which" 2>/dev/null && { PASS=$((PASS+1)); echo "ok: only JAVA_HOME keytool invoked"; } || { FAIL=$((FAIL+1)); echo "FAIL: wrong keytool invoked"; }
# Remove the failing PATH stub so later cases reach REAL keytool.
rm -f "$WORK/bin/keytool"

# Whitespace-only (PRESENT) value decodes to empty: must fail at the
# non-empty check with its diagnostic, and keytool must never run.
# Unquoted heredoc so $WORK expands NOW (the stub runs in a subshell that
# never sees the fixture's unexported variables). Also place the stub in
# JAVA_HOME/bin so the case selects it even when CI sets JAVA_HOME.
mkdir -p "$WORK/empty-jh/bin"
cat > "$WORK/empty-jh/bin/keytool" <<STUB
#!/bin/bash
echo invoked >> "$WORK/keytool-empty-marker"
exit 0
STUB
chmod +x "$WORK/empty-jh/bin/keytool"
cp "$WORK/empty-jh/bin/keytool" "$WORK/bin/keytool"
rm -f "$WORK/keytool-empty-marker"
OUTE=$(JAVA_HOME="$WORK/empty-jh" ANDROID_UPLOAD_KEYSTORE_B64="   " BUILDKITE_BUILD_NUMBER=9452 \
  bash -eu .buildkite/commands/auris-release-aab.sh 2>&1); CODEE=$?
check "whitespace-only value fails" 1 "$CODEE"
printf '%s' "$OUTE" | grep -q "decoded keystore is empty" && r=ok || r=missing
check "empty-decode diagnostic printed" ok "$r"
[ ! -f "$WORK/keytool-empty-marker" ] && { PASS=$((PASS+1)); echo "ok: keytool never invoked on empty decode"; } || { FAIL=$((FAIL+1)); echo "FAIL: keytool invoked on empty decode"; }
rm -f "$WORK/bin/keytool"

DOUBLE_B64="$(printf '%s' "$GOOD_B64" | openssl base64 -A)"
run "$DOUBLE_B64" 1 "double-encoded keystore fails before Gradle"
[ ! -f gradlew-reached ] && { PASS=$((PASS+1)); echo "ok: gradle NOT reached on double-encoded"; } || { FAIL=$((FAIL+1)); echo "FAIL: gradle reached on double-encoded"; }

RAW_B64="$(cat good.keystore | openssl base64 -A | head -c 40)"
run "$RAW_B64" 1 "truncated keystore fails before Gradle"

ANDROID_UPLOAD_KEYSTORE_PASSWORD=wrongpass run "$GOOD_B64" 1 "wrong store password fails before Gradle"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
