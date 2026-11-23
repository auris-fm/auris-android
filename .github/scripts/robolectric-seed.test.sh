#!/bin/bash
# Controls for the android-all seed. Each drives the REAL script with a fake Maven repository on
# disk (file:// via a local HTTP server is not needed: curl reads file:// URLs), so no test depends
# on Maven being reachable -- which is the whole point of the change.
#
# The claims:
#   1. The coordinate is DERIVED from the repo's pins, not hardcoded.
#   2. A cold cache is seeded, and the jar lands where Robolectric reads it.
#   3. A corrupt download is refused and nothing is installed.
#   4. An interrupted download leaves no partial jar behind.
#   5. Retries happen, and exhaustion fails closed.
#   6. A present jar means no fetch at all.
#   7. Every workflow job that runs the suite seeds AND saves under if: always().
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SEED="$DIR/.github/scripts/robolectric-seed.sh"
PASS=0; FAIL=0
ok()   { PASS=$((PASS+1)); echo "ok: $1"; }
bad()  { FAIL=$((FAIL+1)); echo "FAIL: $1"; }
check_zero() { if [ "$2" = 0 ]; then ok "$1"; else bad "$1 (exit $2)"; fi; }
check_nonzero() { if [ "$2" != 0 ]; then ok "$1"; else bad "$1 (unexpectedly succeeded)"; fi; }

command -v ruby >/dev/null 2>&1 || { echo "FAIL: ruby required"; exit 1; }
[ -x "$SEED" ] || { echo "FAIL: $SEED missing or not executable"; exit 1; }

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The coordinate the repo's own pins select, computed independently of the script under test.
RV="$(sed -n 's/^robolectric = "org\.robolectric:robolectric:\([0-9.]*\)".*/\1/p' "$DIR/gradle/libs.versions.toml")"
SDK="$(sed -n 's/^sdk=\([0-9][0-9]*\).*/\1/p' "$DIR/modules/services/voice/src/test/resources/robolectric.properties")"
RJAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.robolectric/robolectric/$RV" -name "robolectric-$RV.jar" 2>/dev/null | head -1)"
if [ -z "$RJAR" ]; then
  echo "FAIL: Robolectric $RV not in the Gradle cache; cannot derive the expected coordinate"
  exit 1
fi
T="$WORK/probe"; mkdir -p "$T"
(cd "$T" && unzip -o -q "$RJAR" 'org/robolectric/plugins/DefaultSdkProvider*.class')
javap -p -c "$T/org/robolectric/plugins/DefaultSdkProvider.class" > "$T/prov.txt" 2>&1
AV="$(awk -v sdk="$SDK" '$0 ~ ("bipush *" sdk "$"){w=1;n=0;next} w && /\/\/ String /{n++; if(n==1){print $NF; exit}}' "$T/prov.txt")"
BID="$(awk -v sdk="$SDK" '$0 ~ ("bipush *" sdk "$"){w=1;n=0;next} w && /\/\/ String /{n++; if(n==2){print $NF; exit}}' "$T/prov.txt")"
VERSION="$AV-robolectric-$BID-i7"
ART="android-all-instrumented"
REL="org/robolectric/$ART/$VERSION/$ART-$VERSION"
echo "--- pins: robolectric $RV, sdk $SDK -> $ART $VERSION"

# A fake Maven repository holding a small stand-in jar plus its true checksum. The script must not
# care that the payload is small: it verifies the checksum, not a size.
REPO="$WORK/repo"
mkdir -p "$REPO/$(dirname "$REL")"
printf 'stand-in android-all payload\n' > "$REPO/$REL.jar"
( cd "$REPO/$(dirname "$REL")" && shasum -a 512 "$ART-$VERSION.jar" | awk '{print $1}' > "$ART-$VERSION.jar.sha512" )
printf '<project/>\n' > "$REPO/$REL.pom"
( cd "$REPO/$(dirname "$REL")" && shasum -a 512 "$ART-$VERSION.pom" | awk '{print $1}' > "$ART-$VERSION.pom.sha512" )

seed() { # seed <m2dir> [extra env...]
  local m2="$1"; shift
  ROBOLECTRIC_M2_DIR="$m2" ROBOLECTRIC_MAVEN_REPO="file://$REPO" "$@" bash "$SEED"
}

# 1 + 2. COLD CACHE: the derived coordinate is fetched into the path Robolectric reads.
M2="$WORK/m2-cold"
seed "$M2" > "$WORK/cold.out" 2>&1
check_zero "a cold cache is seeded" $?
if [ -s "$M2/$REL.jar" ]; then ok "the jar lands at \$M2/$REL.jar"; else bad "no jar at $M2/$REL.jar"; fi
grep -q "$ART $VERSION" "$WORK/cold.out" && ok "the coordinate comes from the repo's pins ($VERSION)" || bad "coordinate not derived: $(cat "$WORK/cold.out")"
for ext in pom pom.sha512 jar.sha512; do
  [ -s "$M2/$REL.$ext" ] && ok "the $ext sidecar is installed too" || bad "missing $ext sidecar"
done

# 1b. THE DERIVATION IS LIVE, not a constant that happens to match today's pins. A copy of the repo
#     with a different sdk pin must ask for that SDK's jar. Without this, hardcoding today's
#     coordinate passes every other case here.
ALT="$WORK/alt"
mkdir -p "$ALT/.github/scripts" "$ALT/gradle" "$ALT/modules/services/voice/src/test/resources"
cp "$SEED" "$ALT/.github/scripts/robolectric-seed.sh"
cp "$DIR/gradle/libs.versions.toml" "$ALT/gradle/libs.versions.toml"
ALT_SDK=34
printf 'sdk=%s\n' "$ALT_SDK" > "$ALT/modules/services/voice/src/test/resources/robolectric.properties"
ALT_AV="$(awk -v sdk="$ALT_SDK" '$0 ~ ("bipush *" sdk "$"){w=1;n=0;next} w && /\/\/ String /{n++; if(n==1){print $NF; exit}}' "$T/prov.txt")"
ALT_BID="$(awk -v sdk="$ALT_SDK" '$0 ~ ("bipush *" sdk "$"){w=1;n=0;next} w && /\/\/ String /{n++; if(n==2){print $NF; exit}}' "$T/prov.txt")"
ALT_VERSION="$ALT_AV-robolectric-$ALT_BID-i7"
if [ -z "$ALT_AV" ] || [ "$ALT_VERSION" = "$VERSION" ]; then
  bad "cannot exercise the derivation: SDK $ALT_SDK yields no distinct coordinate"
else
  ROBOLECTRIC_M2_DIR="$WORK/m2-alt" ROBOLECTRIC_MAVEN_REPO="file://$WORK/no-such-repo" \
    ROBOLECTRIC_SEED_ATTEMPTS=1 bash "$ALT/.github/scripts/robolectric-seed.sh" > "$WORK/alt.out" 2>&1
  if grep -q "$ART $ALT_VERSION" "$WORK/alt.out"; then
    ok "a different sdk pin selects a different coordinate ($ALT_VERSION)"
  else
    bad "the coordinate did not follow the sdk pin: $(head -2 "$WORK/alt.out")"
  fi
fi

# 2b. THE REAL CI START STATE: an EMPTY Gradle home AND an empty ~/.m2. Gradle has not fetched the
#     Robolectric jar yet, so the script must fetch that too -- with the same retries and checksum
#     discipline -- rather than depending on a prior build. This is the case that broke an earlier
#     version of the cache change, so it is asserted, not assumed.
COLD="$WORK/cold-gradle"
mkdir -p "$COLD"
# The fake repository must also serve the Robolectric jar for this to be a true cold start.
RREL="org/robolectric/robolectric/$RV/robolectric-$RV"
mkdir -p "$REPO/$(dirname "$RREL")"
cp "$RJAR" "$REPO/$RREL.jar"
( cd "$REPO/$(dirname "$RREL")" && shasum -a 512 "robolectric-$RV.jar" | awk '{print $1}' > "robolectric-$RV.jar.sha512" )
M2="$WORK/m2-coldgradle"
GRADLE_USER_HOME="$COLD" ROBOLECTRIC_M2_DIR="$M2" ROBOLECTRIC_MAVEN_REPO="file://$REPO" \
  bash "$SEED" > "$WORK/coldgradle.out" 2>&1
check_zero "an empty Gradle home and empty ~/.m2 still seed" $?
if [ -s "$M2/$REL.jar" ]; then ok "the jar lands even with no prior Gradle build"; else bad "cold Gradle home produced no jar"; fi
grep -q "not in the Gradle cache; fetching it" "$WORK/coldgradle.out" \
  && ok "the Robolectric jar is fetched when Gradle has not resolved it" \
  || bad "the script did not fetch the Robolectric jar itself"

# 2c. That fetch FAILS CLOSED too: an unreachable repository with a cold Gradle home must not
#     silently fall back to a guessed coordinate.
GRADLE_USER_HOME="$COLD" ROBOLECTRIC_M2_DIR="$WORK/m2-coldfail" \
  ROBOLECTRIC_MAVEN_REPO="file://$WORK/no-such-repo" ROBOLECTRIC_SEED_ATTEMPTS=2 \
  bash "$SEED" > "$WORK/coldfail.out" 2>&1
check_nonzero "a cold Gradle home with an unreachable repository fails closed" $?
grep -q "robolectric-$RV.jar" "$WORK/coldfail.out" \
  && ok "the failure names the Robolectric jar it could not fetch" \
  || bad "failure does not name the Robolectric jar"

# 3. CORRUPT PAYLOAD: the checksum disagrees, so nothing may be installed.
BADREPO="$WORK/repo-bad"
mkdir -p "$BADREPO/$(dirname "$REL")"
cp "$REPO/$REL.pom" "$REPO/$REL.pom.sha512" "$REPO/$REL.jar.sha512" "$BADREPO/$(dirname "$REL")/"
printf 'truncated payload\n' > "$BADREPO/$REL.jar"
M2="$WORK/m2-corrupt"
ROBOLECTRIC_M2_DIR="$M2" ROBOLECTRIC_MAVEN_REPO="file://$BADREPO" bash "$SEED" > "$WORK/corrupt.out" 2>&1
check_nonzero "a checksum mismatch fails the step" $?
if [ ! -e "$M2/$REL.jar" ]; then ok "a corrupt jar is never installed"; else bad "a corrupt jar was installed"; fi
grep -qi "SHA-512" "$WORK/corrupt.out" && ok "the failure names the checksum" || bad "failure does not name the checksum"

# 4. INTERRUPTED DOWNLOAD: the jar is absent from the repository, so the fetch cannot complete.
PARTREPO="$WORK/repo-partial"
mkdir -p "$PARTREPO/$(dirname "$REL")"
cp "$REPO/$REL.pom" "$REPO/$REL.pom.sha512" "$REPO/$REL.jar.sha512" "$PARTREPO/$(dirname "$REL")/"
M2="$WORK/m2-partial"
ROBOLECTRIC_M2_DIR="$M2" ROBOLECTRIC_MAVEN_REPO="file://$PARTREPO" ROBOLECTRIC_SEED_ATTEMPTS=2 \
  bash "$SEED" > "$WORK/partial.out" 2>&1
check_nonzero "an incomplete fetch fails the step" $?
if [ -z "$(find "$M2" -name '*.jar' 2>/dev/null)" ]; then ok "no partial jar is left behind"; else bad "a partial jar was left in the cache"; fi
# The sidecars must not be installed either: a half-populated directory is what a later run would
# mistake for a complete artifact. Downloading straight into the cache leaves these behind.
leftovers="$(find "$M2" -type f 2>/dev/null | wc -l | tr -d ' ')"
if [ "$leftovers" = 0 ]; then ok "an incomplete fetch installs nothing at all"; else bad "an incomplete fetch left $leftovers file(s) in the cache"; fi

# 5. RETRIES: an unreachable repository is retried, then fails closed naming the artifact.
M2="$WORK/m2-unreachable"
ROBOLECTRIC_M2_DIR="$M2" ROBOLECTRIC_MAVEN_REPO="file://$WORK/no-such-repo" ROBOLECTRIC_SEED_ATTEMPTS=3 \
  bash "$SEED" > "$WORK/retry.out" 2>&1
check_nonzero "an unreachable repository fails closed" $?
tries="$(grep -c 'attempt .* for android-all-instrumented-.*\.jar failed' "$WORK/retry.out" || true)"
if [ "$tries" -ge 2 ]; then ok "the fetch is retried ($tries attempts logged)"; else bad "no retries logged ($tries)"; fi
grep -q "$ART-$VERSION" "$WORK/retry.out" && ok "the failure names the artifact it could not fetch" || bad "failure does not name the artifact"

# 6. WARM CACHE: a present jar means NO fetch, proven against a repository that does not exist.
M2="$WORK/m2-warm"
mkdir -p "$M2/$(dirname "$REL")"
cp "$REPO/$REL.jar" "$M2/$REL.jar"
ROBOLECTRIC_M2_DIR="$M2" ROBOLECTRIC_MAVEN_REPO="file://$WORK/no-such-repo" bash "$SEED" > "$WORK/warm.out" 2>&1
check_zero "a warm cache needs no repository" $?
grep -q "already present" "$WORK/warm.out" && ok "a warm cache performs no fetch" || bad "a warm cache still fetched"

# 7. EVERY JOB THAT RUNS THE SUITE SEEDS BEFORE THE TESTS AND SAVES UNDER if: always().
WFS="$(grep -rl ':modules:services:voice:testDebugUnitTest' "$DIR/.github/workflows" | sort)"
[ -n "$WFS" ] || { echo "FAIL: no workflow runs the voice suite"; exit 1; }
cat > "$WORK/wf.rb" <<'RUBY'
  keys = {}
  ARGV.each do |path|
    wf = YAML.load_file(path)
    wf["jobs"].each do |name, job|
      steps = job["steps"] || []
      ti = steps.index { |s| s["run"].to_s.include?(":modules:services:voice:testDebugUnitTest") }
      next unless ti
      si = steps.index { |s| s["run"].to_s.include?("robolectric-seed.sh") }
      abort "#{File.basename(path)}:#{name} runs the suite with NO seed step" unless si
      abort "#{File.basename(path)}:#{name} seeds AFTER the tests" unless si < ti
      save = steps.find { |s| (s["uses"] || "").include?("actions/cache/save") }
      abort "#{File.basename(path)}:#{name} has no actions/cache/save" unless save
      abort "#{File.basename(path)}:#{name} saves only on success" unless save["if"].to_s.include?("always()")
      abort "#{File.basename(path)}:#{name} save is not gated on the jar being present" unless save["if"].to_s.include?("hashFiles")
      restore = steps.find { |s| (s["uses"] || "").to_s.strip == "actions/cache/restore@v4" }
      abort "#{File.basename(path)}:#{name} must restore without saving (use cache/restore)" unless restore
      abort "#{File.basename(path)}:#{name} restores after seeding" unless steps.index(restore) < si
      keys["#{File.basename(path)}:#{name}"] = [restore["with"]["key"], save["with"]["key"]]
    end
  end
  abort "no job found running the voice suite" if keys.empty?
  flat = keys.values.flatten.uniq
  abort "restore/save keys disagree: #{keys.inspect}" if flat.size != 1
  puts "#{keys.size} job(s): restore -> seed -> tests -> save(always, if jar present), one key"
RUBY
ruby -ryaml "$WORK/wf.rb" $WFS > "$WORK/wf.out" 2>&1
check_zero "every voice job restores, seeds before the tests, and saves unconditionally" $?
sed 's/^/    /' "$WORK/wf.out"

# 8. THE CONTROLS RUN WHEN THE PINS CHANGE. The seed derives its coordinate from the version catalog
#    and robolectric.properties; if a bump does not trigger this job, the derivation is unguarded
#    exactly when it matters. Both the PR path filter and the in-job queue scope must cover them.
cat > "$WORK/scope.rb" <<'RUBY'
  wf = YAML.load_file(ARGV[0])
  on = wf["on"] || wf[true]
  paths = on["pull_request"]["paths"]
  pins = %w[gradle/libs.versions.toml modules/services/voice/src/test/resources/robolectric.properties]
  pins.each do |p|
    abort "PR path filter misses #{p}" unless paths.include?(p)
  end
  # The in-job scope decides for merge_group, where there is no path filter at all.
  scope = wf["jobs"]["controls"]["steps"].map { |s| s["run"].to_s }.find { |r| r.include?("run=true") }
  abort "no in-job scope decision found" unless scope
  regex = scope[/'(\^[^']*)'/, 1]
  abort "cannot read the scope regex" unless regex
  re = Regexp.new(regex)
  pins.each { |p| abort "queue scope misses #{p}" unless re.match?(p) }
  puts "both pins trigger the controls, on PRs and in the queue"
RUBY
ruby -ryaml "$WORK/scope.rb" "$DIR/.github/workflows/release-script-controls.yml" > "$WORK/scope.out" 2>&1
check_zero "a pin bump runs the seed control on PRs and in the merge queue" $?
sed 's/^/    /' "$WORK/scope.out"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
