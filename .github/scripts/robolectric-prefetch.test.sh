#!/bin/bash
# Controls for the Robolectric prefetch step.
#
# The defect these exist for: an unconditional download made a restored cache worthless,
# so the job still died during a Maven outage — the exact case the cache was added for.
# A control that merely checks "the script exits 0" cannot see that, because a successful
# download also exits 0. So every case here makes the network UNREACHABLE and asserts on
# whether curl was invoked at all.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$DIR/.github/scripts/robolectric-prefetch.sh"
PASS=0; FAIL=0
check() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (exit $3 want $2)"; fi
}
# For failure cases the exact code is curl's, propagated by set -e; what matters is that the
# step does NOT succeed. Pinning 22 would assert the stub's choice rather than the behaviour.
check_nonzero() {
  if [ "$2" != 0 ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (expected non-zero, got 0)"; fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The real resolved Robolectric jar, located the way the job does: from the Gradle cache.
ROBO_VERSION="$(sed -n 's/^robolectric = "org.robolectric:robolectric:\([^"]*\)".*/\1/p' "$DIR/gradle/libs.versions.toml")"
[ -n "$ROBO_VERSION" ] || { echo "FAIL: could not read the Robolectric version"; exit 1; }
ROBO_JAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.robolectric/robolectric/$ROBO_VERSION" -name "robolectric-$ROBO_VERSION.jar" 2>/dev/null | head -1)"
if [ -z "$ROBO_JAR" ]; then
  echo "SKIP: org.robolectric:robolectric:$ROBO_VERSION not in the local Gradle cache."
  echo "      These controls need the resolved artifact; CI resolves it before running them."
  exit 0
fi
SDK="$(sed -n 's/^sdk=\([0-9]*\).*/\1/p' "$DIR/modules/services/voice/src/test/resources/robolectric.properties")"
[ -n "$SDK" ] || { echo "FAIL: could not read the pinned unit-test SDK"; exit 1; }

# A stub curl that RECORDS the call and fails, standing in for an unreachable Maven Central.
mkdir -p "$WORK/bin"
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
echo called >> "$CURL_LOG"
exit 22
STUB
chmod +x "$WORK/bin/curl"

# Build a warm cache with REAL checksums, so validation is exercised rather than bypassed.
VERSION_DIR="$WORK/m2/org/robolectric/android-all-instrumented"
seed_cache() {
  rm -rf "$WORK/m2"
  mkdir -p "$VERSION_DIR/15-robolectric-13954326-i7"
  local d="$VERSION_DIR/15-robolectric-13954326-i7"
  local a="android-all-instrumented-15-robolectric-13954326-i7"
  printf 'jar-bytes' > "$d/$a.jar"
  printf 'pom-bytes' > "$d/$a.pom"
  if command -v sha512sum >/dev/null 2>&1; then
    sha512sum "$d/$a.jar" | cut -d' ' -f1 > "$d/$a.jar.sha512"
    sha512sum "$d/$a.pom" | cut -d' ' -f1 > "$d/$a.pom.sha512"
  else
    shasum -a 512 "$d/$a.jar" | cut -d' ' -f1 > "$d/$a.jar.sha512"
    shasum -a 512 "$d/$a.pom" | cut -d' ' -f1 > "$d/$a.pom.sha512"
  fi
}

run_prefetch() {
  CURL_LOG="$WORK/curl.log" PATH="$WORK/bin:$PATH" \
    ROBOLECTRIC_VERSION="$ROBO_VERSION" ANDROID_SDK="$SDK" \
    ROBOLECTRIC_JAR="$ROBO_JAR" M2_REPO="$WORK/m2" \
    bash "$SCRIPT" >"$WORK/out" 2>&1
}

# 1. WARM CACHE + NO NETWORK => success, and curl never invoked.
#    This is the case the old step failed: it re-downloaded unconditionally.
seed_cache
: > "$WORK/curl.log"
run_prefetch; RC=$?
check "warm cache succeeds with Maven unreachable" 0 "$RC"
if [ -s "$WORK/curl.log" ]; then
  FAIL=$((FAIL+1)); echo "FAIL: warm cache still hit the network ($(wc -l < "$WORK/curl.log") calls)"
else
  PASS=$((PASS+1)); echo "ok: warm cache performs no network I/O"
fi

# 2. COLD CACHE + NO NETWORK => must FAIL, not silently pass. A step that swallowed the
#    error would let the class loader starve later with a confusing failure.
rm -rf "$WORK/m2"
: > "$WORK/curl.log"
run_prefetch; RC=$?
check_nonzero "cold cache fails when Maven is unreachable" "$RC"
if [ -s "$WORK/curl.log" ]; then
  PASS=$((PASS+1)); echo "ok: cold cache attempts the download"
else
  FAIL=$((FAIL+1)); echo "FAIL: cold cache never attempted a download"
fi

# 3. CORRUPT cached jar (checksum mismatch) => must re-fetch, not treat it as warm.
#    Without validation a truncated restore looks present and fails inside the class loader.
seed_cache
printf 'truncated' > "$VERSION_DIR/15-robolectric-13954326-i7/android-all-instrumented-15-robolectric-13954326-i7.jar"
: > "$WORK/curl.log"
run_prefetch; RC=$?
check_nonzero "corrupt cached jar is not treated as warm" "$RC"
if [ -s "$WORK/curl.log" ]; then
  PASS=$((PASS+1)); echo "ok: corrupt cached jar triggers a re-fetch"
else
  FAIL=$((FAIL+1)); echo "FAIL: corrupt cached jar was accepted without re-fetching"
fi

# 4. EMPTY cached file => same, must re-fetch.
seed_cache
: > "$VERSION_DIR/15-robolectric-13954326-i7/android-all-instrumented-15-robolectric-13954326-i7.jar"
: > "$WORK/curl.log"
run_prefetch; RC=$?
check_nonzero "empty cached jar is not treated as warm" "$RC"

# 5. THE PIN IS DERIVED. Ask for an SDK whose android-all differs and confirm the script
#    looks for THAT artifact, not the hand-written one. A stale pin used to leave the job
#    green while Robolectric fetched the real artifact over the network.
seed_cache
: > "$WORK/curl.log"
CURL_LOG="$WORK/curl.log" PATH="$WORK/bin:$PATH" \
  ROBOLECTRIC_VERSION="$ROBO_VERSION" ANDROID_SDK=34 \
  ROBOLECTRIC_JAR="$ROBO_JAR" M2_REPO="$WORK/m2" \
  bash "$SCRIPT" >"$WORK/out34" 2>&1
# Assert on the DIRECTORY the script created, not on its output: a hard-coded pin can print
# nothing at all and still be wrong, so an output-based check passes for the wrong reason.
DERIVED_DIRS="$(ls "$VERSION_DIR" | grep -v '^15-robolectric-13954326-i7$' || true)"
if [ -n "$DERIVED_DIRS" ]; then
  PASS=$((PASS+1)); echo "ok: a different SDK derives a different artifact ($DERIVED_DIRS)"
else
  FAIL=$((FAIL+1)); echo "FAIL: SDK 34 reused SDK 35's pinned artifact"
fi

# 6. An SDK the artifact does not know => fail loudly rather than prefetch nothing.
CURL_LOG="$WORK/curl.log" PATH="$WORK/bin:$PATH" \
  ROBOLECTRIC_VERSION="$ROBO_VERSION" ANDROID_SDK=99 \
  ROBOLECTRIC_JAR="$ROBO_JAR" M2_REPO="$WORK/m2" \
  bash "$SCRIPT" >"$WORK/out99" 2>&1; RC=$?
check "unknown SDK fails closed" 1 "$RC"

# 7. THE WIRED STEP, not just the script. A suite that exercises a helper while the workflow
#    calls something else proves nothing about CI. Extract the step's own shell from the YAML
#    and run it warm + offline: it must succeed without touching curl.
if command -v ruby >/dev/null 2>&1; then
  ruby -ryaml -e '
    y = YAML.load_file(ARGV[0])
    step = y["jobs"]["voice-unit-tests"]["steps"].find { |s| (s["name"] || "").start_with?("Prefetch") }
    abort "no prefetch step" unless step
    File.write(ARGV[1], step["run"])
  ' "$DIR/.github/workflows/voice-unit-tests.yml" "$WORK/step.sh"
  seed_cache
  : > "$WORK/curl.log"
  ( cd "$DIR" && CURL_LOG="$WORK/curl.log" PATH="$WORK/bin:$PATH" M2_REPO="$WORK/m2" \
      bash "$WORK/step.sh" >"$WORK/step.out" 2>&1 )
  RC=$?
  check "the workflow's own prefetch step succeeds offline when warm" 0 "$RC"
  if [ -s "$WORK/curl.log" ]; then
    FAIL=$((FAIL+1)); echo "FAIL: the wired step hit the network on a warm cache"
  else
    PASS=$((PASS+1)); echo "ok: the wired step performs no network I/O when warm"
  fi

  # And the plain-JUnit job must carry NO prefetch at all: it never loads an android-all jar,
  # so a prefetch there is a new way to fail on an outage that previously left it green.
  ruby -ryaml -e '
    y = YAML.load_file(ARGV[0])
    steps = y["jobs"]["cloud-transport-tests"]["steps"]
    bad = steps.select { |s| (s["name"] || "").include?("Robolectric") || (s["uses"] || "").include?("actions/cache") }
    abort "cloud-transport-tests still carries: #{bad.map { |s| s["name"] || s["uses"] }.join(", ")}" unless bad.empty?
  ' "$DIR/.github/workflows/voice-unit-tests.yml" >"$WORK/plain.out" 2>&1
  check "the plain-JUnit job carries no Robolectric cache or prefetch" 0 $?
else
  echo "SKIP: ruby unavailable; the wired-step controls did not run"
fi

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]