#!/bin/bash
# Controls for the Robolectric android-all cache. Two claims, one control each:
#
#   1. The key changes when either input that decides WHICH jar is needed changes.
#   2. The cached directory is the one Robolectric actually reads.
#   3. EVERY workflow job that runs the voice suite carries that cache -- the release
#      workflow has its own copy of the job and gates the release trigger, so a job
#      without the cache holds releases back while the other workflow stays green.
#
# The seed step and its save-on-failure behaviour are controlled by robolectric-seed.test.sh;
# this file owns the KEY, the PATH and the COVERAGE, which both designs share.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
WF="$DIR/.github/workflows/voice-unit-tests.yml"
# Every workflow with a job that runs the voice suite, found from disk: naming one file here
# is how the release workflow's copy went uncached in the first place.
WFS="$(grep -rl ':modules:services:voice:testDebugUnitTest' "$DIR/.github/workflows" | sort)"
[ -n "$WFS" ] || { echo "FAIL: no workflow runs the voice suite"; exit 1; }
PASS=0; FAIL=0
check() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (exit $3 want $2)"; fi
}

command -v ruby >/dev/null 2>&1 || { echo "FAIL: ruby required"; exit 1; }

# 1. THE KEY IS INPUT-DERIVED, and it responds to EACH input separately.
#    Asserting only "contains hashFiles" would pass for a key that hashes one file and ignores
#    the other, so this evaluates the expression against modified copies of both inputs.
cat > /tmp/rc-key.rb <<'RUBY'
  wf = YAML.load_file(ARGV[0])
  step = wf["jobs"]["voice-unit-tests"]["steps"].find { |s| (s["uses"] || "").include?("actions/cache") }
  abort "no actions/cache step in voice-unit-tests" unless step
  key = step["with"]["key"].to_s
  files = key[/hashFiles\(([^)]*)\)/, 1].to_s.scan(/'([^']+)'/).flatten
  abort "key does not hash files: #{key}" if files.empty?
  %w[gradle/libs.versions.toml modules/services/voice/src/test/resources/robolectric.properties].each do |f|
    abort "key ignores #{f}" unless files.include?(f)
  end
  # Evaluate the key the way Actions does: hash of the concatenated file contents.
  render = ->(contents) { key.sub(/\$\{\{[^}]*\}\}/, Digest::SHA256.hexdigest(contents.join)) }
  base = files.map { |f| File.read(File.join(ARGV[1], f)) }
  baseline = render.call(base)
  files.each_with_index do |f, i|
    changed = base.dup
    changed[i] = changed[i] + "\n# probe\n"
    abort "key did NOT change when #{f} changed" if render.call(changed) == baseline
  end
  puts "key responds to each of #{files.size} inputs"
RUBY
for wf in $WFS; do
  ruby -ryaml -rdigest /tmp/rc-key.rb "$wf" "$DIR" > /tmp/rc-key.out 2>&1
  check "the cache key changes when either input changes ($(basename "$wf"))" 0 $?
  sed 's/^/    /' /tmp/rc-key.out
done

# 2. THE CACHED PATH IS THE ONE ROBOLECTRIC READS.
#    Robolectric's MavenDependencyResolver defaults to $user.home/.m2/repository (overridable by
#    maven.repo.local or settings.xml, neither of which this repo sets). Assert the cached path
#    matches that default, and that the job does not redirect it somewhere else.
cat > /tmp/rc-path.rb <<'RUBY'
  wf = YAML.load_file(ARGV[0])
  steps = wf["jobs"]["voice-unit-tests"]["steps"]
  cache = steps.find { |s| (s["uses"] || "").include?("actions/cache") }
  path = cache["with"]["path"].to_s
  abort "cached path is not the Maven local repository: #{path}" unless path.include?(".m2/repository")
  abort "cached path is not scoped to org/robolectric: #{path}" unless path.include?("org/robolectric")
  # A maven.repo.local override would move the download elsewhere and make the cache useless.
  steps.each do |s|
    run = s["run"].to_s
    abort "job overrides maven.repo.local; the cache would miss" if run.include?("maven.repo.local")
  end
  puts "cached path: #{path}"
RUBY
for wf in $WFS; do
  ruby -ryaml /tmp/rc-path.rb "$wf" > /tmp/rc-path.out 2>&1
  check "the cached path is Robolectric's Maven local repository ($(basename "$wf"))" 0 $?
  sed 's/^/    /' /tmp/rc-path.out
done

# The repo must not set maven.repo.local anywhere either, or the assertion above is incomplete.
if grep -rn "maven.repo.local" "$DIR/gradle.properties" "$DIR/modules/services/voice/build.gradle.kts" 2>/dev/null | grep -q .; then
  FAIL=$((FAIL+1)); echo "FAIL: the build redirects maven.repo.local"
else
  PASS=$((PASS+1)); echo "ok: the build does not redirect maven.repo.local"
fi

# 3. THE ONLY FETCH IS THE SHARED SEED SCRIPT. An inline curl in a workflow would duplicate the
#    coordinate derivation and drift from it; the plain-JUnit job must still carry no cache at all.
cat > /tmp/rc-noprefetch.rb <<'RUBY'
  wf = YAML.load_file(ARGV[0])
  wf["jobs"].each do |name, job|
    (job["steps"] || []).each do |s|
      run = s["run"].to_s
      next if run.include?("robolectric-seed.sh")
      # A DOWNLOAD is a curl/wget against Maven, not any mention of the artifact: the evidence
      # step legitimately inspects the cached files and must not be mistaken for a fetch.
      abort "#{name} fetches from Maven outside the seed script" if run.include?("repo1.maven.org")
      if run =~ /(curl|wget)/ && run.include?("android-all")
        abort "#{name} downloads android-all outside the seed script"
      end
    end
  end
  # And the plain-JUnit job carries no cache at all: it never loads an android-all jar.
  plain = wf["jobs"]["cloud-transport-tests"]
  if plain
    bad = (plain["steps"] || []).select { |s| (s["uses"] || "").include?("actions/cache") }
    abort "cloud-transport-tests carries a Robolectric cache it never uses" unless bad.empty?
  end
RUBY
for wf in $WFS; do
  ruby -ryaml /tmp/rc-noprefetch.rb "$wf" > /tmp/rc-noprefetch.out 2>&1
  check "the seed script is the only fetch, and the plain-JUnit job has no cache ($(basename "$wf"))" 0 $?
  sed 's/^/    /' /tmp/rc-noprefetch.out
done

# 4. THE CACHE IS ON EVERY JOB THAT RUNS THE SUITE, and the keys agree. Two copies of a job
#    with different keys would restore the outage on whichever copy lost the cache.
cat > /tmp/rc-coverage.rb <<'RUBY'
  keys = {}
  ARGV.each do |path|
    wf = YAML.load_file(path)
    wf["jobs"].each do |name, job|
      steps = job["steps"] || []
      runs_suite = steps.any? { |s| s["run"].to_s.include?(":modules:services:voice:testDebugUnitTest") }
      next unless runs_suite
      cache = steps.select { |s| (s["uses"] || "").include?("actions/cache") }
      abort "#{File.basename(path)}:#{name} runs the voice suite with NO android-all cache" if cache.empty?
      keys["#{File.basename(path)}:#{name}"] = cache.map { |s| s["with"]["key"].to_s }.uniq
    end
  end
  abort "no job found running the voice suite" if keys.empty?
  abort "cache keys disagree across jobs: #{keys.inspect}" if keys.values.flatten.uniq.size != 1
  abort "a job uses different keys for restore and save: #{keys.inspect}" if keys.values.any? { |v| v.size != 1 }
  puts "#{keys.size} job(s) run the suite, all cached with the same key"
RUBY
ruby -ryaml /tmp/rc-coverage.rb $WFS > /tmp/rc-coverage.out 2>&1
check "every job that runs the voice suite caches android-all, with one key" 0 $?
sed 's/^/    /' /tmp/rc-coverage.out

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]