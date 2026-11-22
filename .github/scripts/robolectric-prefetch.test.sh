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
# NO SKIP WHEN THE JAR IS ABSENT. That skip is what hid the cold-cache regression: every control
# assumed the artifact was already resolved, so the state CI actually starts in was never tested.
# The script fetches the jar itself when cold, and case 8 below exercises exactly that.
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
    M2_REPO="$WORK/m2" GRADLE_USER_HOME="${TEST_GRADLE_HOME:-$HOME/.gradle}" \
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
  M2_REPO="$WORK/m2" GRADLE_USER_HOME="${TEST_GRADLE_HOME:-$HOME/.gradle}" \
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
  M2_REPO="$WORK/m2" GRADLE_USER_HOME="${TEST_GRADLE_HOME:-$HOME/.gradle}" \
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

# 8. COLD GRADLE HOME + COLD m2, real network. This is the state CI starts in, and the case that
#    was missing: `gradlew dependencies` resolves metadata only, so a step that waited for the
#    resolved jar failed every cold run before a test executed. Uses the REAL curl (no stub).
COLD_HOME="$WORK/cold-gradle-home"
mkdir -p "$COLD_HOME"
# Seed ONLY the small Robolectric jar into the fallback location, so this case tests the cold
# GRADLE HOME path without depending on Maven being reachable (it rate-limits under repetition).
# The android-all jar itself is still fetched by the script, which is what the case asserts.
SEED_JAR="$(find "${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1/org.robolectric/robolectric/$ROBO_VERSION" -name "robolectric-$ROBO_VERSION.jar" 2>/dev/null | head -1)"
if [ -n "$SEED_JAR" ]; then
  mkdir -p "$WORK/cold-m2/.robolectric-prefetch"
  cp "$SEED_JAR" "$WORK/cold-m2/.robolectric-prefetch/robolectric-$ROBO_VERSION.jar"
fi
# A local android-all mirror, when present, keeps this case off the network: repeating a 200 MB
# download on every run draws HTTP 429 and makes the control flaky rather than informative.
# CI has no mirror, so there it exercises the real fetch.
MIRROR="${ROBOLECTRIC_TEST_MIRROR:-$HOME/.m2/repository}"
MIRROR_DIR="$MIRROR/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7"
if [ -s "$MIRROR_DIR/android-all-instrumented-15-robolectric-13954326-i7.jar" ]; then
  export ROBOLECTRIC_BASE_URL="file://$MIRROR_DIR"
fi
( cd "$DIR" && M2_REPO="$WORK/cold-m2" GRADLE_USER_HOME="$COLD_HOME" \
    ROBOLECTRIC_VERSION="$ROBO_VERSION" ANDROID_SDK="$SDK" \
    bash "$SCRIPT" >"$WORK/cold.out" 2>&1 )
RC=$?
check "cold Gradle home and cold cache still prefetch successfully" 0 "$RC"
if [ -s "$WORK/cold-m2/org/robolectric/android-all-instrumented/15-robolectric-13954326-i7/android-all-instrumented-15-robolectric-13954326-i7.jar" ]; then
  PASS=$((PASS+1)); echo "ok: cold run downloaded the android-all jar"
else
  FAIL=$((FAIL+1)); echo "FAIL: cold run produced no android-all jar ($(tail -2 "$WORK/cold.out"))"
fi

# 9. The instrumentation suffix is DERIVED, not literal.
#    Comparing the output against the artifact's declared suffix CANNOT discriminate: both are i7
#    today, so a hard-coded i7 produces the identical string and passes. That is the shared-oracle
#    trap. Instead, run the script against a jar whose declared suffix DIFFERS and require the
#    coordinates to follow it — a literal fails, a derivation follows.
FAKE_HOME="$WORK/fake-gradle-home"
FAKE_DIR="$FAKE_HOME/caches/modules-2/files-2.1/org.robolectric/robolectric/$ROBO_VERSION/probe"
mkdir -p "$FAKE_DIR"
REAL_JAR="$SEED_JAR"
if [ -n "$REAL_JAR" ] && command -v jar >/dev/null 2>&1; then
  # Build a probe jar whose DECLARED suffix differs, by patching the one constant-pool UTF8 entry
  # ("i7" -> "i9", same length) in the extracted class and re-zipping. Comparing the script's output
  # to the real artifact's suffix cannot discriminate -- both are i7, so a hard-coded literal
  # produces the identical string. This makes the two disagree, which is what a control needs.
  mkdir -p "$WORK/probe-x"
  ( cd "$WORK/probe-x" && unzip -qo "$REAL_JAR" 'org/robolectric/plugins/DefaultSdkProvider*.class' )
  ruby -e '
    f = ARGV[0]
    data = File.binread(f)
    pat = [1, 0, 2].pack("C*") + "i7"
    idx = data.index(pat)
    abort "constant not found" unless idx
    data[idx + 4] = "9"
    File.binwrite(f, data)
  ' "$WORK/probe-x/org/robolectric/plugins/DefaultSdkProvider\$DefaultSdk.class" 2>"$WORK/probe.err"
  ( cd "$WORK/probe-x" && jar cf "$FAKE_DIR/robolectric-$ROBO_VERSION.jar" org ) 2>>"$WORK/probe.err"
  DECLARED_FAKE="$(javap -p -c -cp "$FAKE_DIR/robolectric-$ROBO_VERSION.jar" 'org.robolectric.plugins.DefaultSdkProvider$DefaultSdk' 2>/dev/null | sed -n 's/.*String \(i[0-9][0-9]*\)$/\1/p' | head -1)"
  if [ -n "$DECLARED_FAKE" ] && [ "$DECLARED_FAKE" != "i7" ]; then
    # Seed the cache under the DIFFERING coordinate and stub curl to fail: the script must
    # recognise that directory as warm (exit 0, no network). A literal suffix looks elsewhere,
    # tries to download, and fails -- which is exactly the discrimination this case needs.
    FD="$WORK/m2/org/robolectric/android-all-instrumented/15-robolectric-13954326-$DECLARED_FAKE"
    mkdir -p "$FD"
    FA="android-all-instrumented-15-robolectric-13954326-$DECLARED_FAKE"
    printf 'jar-bytes' > "$FD/$FA.jar"; printf 'pom-bytes' > "$FD/$FA.pom"
    if command -v sha512sum >/dev/null 2>&1; then
      sha512sum "$FD/$FA.jar" | cut -d' ' -f1 > "$FD/$FA.jar.sha512"
      sha512sum "$FD/$FA.pom" | cut -d' ' -f1 > "$FD/$FA.pom.sha512"
    else
      shasum -a 512 "$FD/$FA.jar" | cut -d' ' -f1 > "$FD/$FA.jar.sha512"
      shasum -a 512 "$FD/$FA.pom" | cut -d' ' -f1 > "$FD/$FA.pom.sha512"
    fi
    : > "$WORK/curl.log"
    FAKE_OUT="$(M2_REPO="$WORK/m2" GRADLE_USER_HOME="$FAKE_HOME" \
      ROBOLECTRIC_VERSION="$ROBO_VERSION" ANDROID_SDK="$SDK" \
      CURL_LOG="$WORK/curl.log" PATH="$WORK/bin:$PATH" bash "$SCRIPT" 2>&1 || true)"
    case "$(printf '%s' "$FAKE_OUT" | tail -1)" in
      *-"$DECLARED_FAKE") PASS=$((PASS+1)); echo "ok: the suffix follows the artifact ($DECLARED_FAKE), not a literal" ;;
      *) FAIL=$((FAIL+1)); echo "FAIL: suffix did not follow the artifact; got '$(printf '%s' "$FAKE_OUT" | tail -1)' want *-$DECLARED_FAKE" ;;
    esac
  else
    FAIL=$((FAIL+1)); echo "FAIL: differing-suffix probe unavailable ($(head -1 "$WORK/probe.err")); derivation NOT proven"
  fi
else
  FAIL=$((FAIL+1)); echo "FAIL: jar tool or the resolved jar unavailable; derivation NOT proven"
fi

# 10. The cache key must depend on the INPUTS, not a manual version. A static key survives a bump,
#     so the new jar re-downloads every run and an outage fails the job again.
if command -v ruby >/dev/null 2>&1; then
  ruby -ryaml -e '
    y = YAML.load_file(ARGV[0])
    step = y["jobs"]["voice-unit-tests"]["steps"].find { |s| (s["uses"] || "").include?("actions/cache") }
    abort "no cache step" unless step
    key = step["with"]["key"].to_s
    abort "cache key is static: #{key}" unless key.include?("hashFiles")
    %w[libs.versions.toml robolectric.properties].each do |input|
      abort "cache key ignores #{input}" unless key.include?(input)
    end
  ' "$DIR/.github/workflows/voice-unit-tests.yml" >"$WORK/key.out" 2>&1
  check "the cache key is derived from the version catalog and SDK inputs" 0 $?
fi

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]