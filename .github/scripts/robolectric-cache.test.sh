#!/bin/bash
# Controls for the Robolectric android-all cache. Two claims, one control each:
#
#   1. The key changes when either input that decides WHICH jar is needed changes.
#   2. The cached directory is the one Robolectric actually reads.
#
# Nothing else is asserted, because nothing else is done: there is no prefetch step.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
WF="$DIR/.github/workflows/voice-unit-tests.yml"
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
ruby -ryaml -rdigest /tmp/rc-key.rb "$WF" "$DIR" > /tmp/rc-key.out 2>&1
check "the cache key changes when either input changes" 0 $?
sed 's/^/    /' /tmp/rc-key.out

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
ruby -ryaml /tmp/rc-path.rb "$WF" > /tmp/rc-path.out 2>&1
check "the cached path is Robolectric's Maven local repository" 0 $?
sed 's/^/    /' /tmp/rc-path.out

# The repo must not set maven.repo.local anywhere either, or the assertion above is incomplete.
if grep -rn "maven.repo.local" "$DIR/gradle.properties" "$DIR/modules/services/voice/build.gradle.kts" 2>/dev/null | grep -q .; then
  FAIL=$((FAIL+1)); echo "FAIL: the build redirects maven.repo.local"
else
  PASS=$((PASS+1)); echo "ok: the build does not redirect maven.repo.local"
fi

# 3. NO PREFETCH. The design is cache-only; a reintroduced download step would restore the
#    failure modes this PR removed (unconditional fetch, cold-Gradle-home dependency).
cat > /tmp/rc-noprefetch.rb <<'RUBY'
  wf = YAML.load_file(ARGV[0])
  wf["jobs"].each do |name, job|
    (job["steps"] || []).each do |s|
      run = s["run"].to_s
      # A DOWNLOAD is a curl/wget against Maven, not any mention of the artifact: the evidence
      # step legitimately inspects the cached files and must not be mistaken for a prefetch.
      abort "#{name} still fetches from Maven" if run.include?("repo1.maven.org")
      if run =~ /(curl|wget)/ && run.include?("android-all")
        abort "#{name} still downloads android-all"
      end
    end
  end
  # And the plain-JUnit job carries no cache at all: it never loads an android-all jar.
  plain = wf["jobs"]["cloud-transport-tests"]["steps"]
  bad = plain.select { |s| (s["uses"] || "").include?("actions/cache") }
  abort "cloud-transport-tests carries a Robolectric cache it never uses" unless bad.empty?
RUBY
ruby -ryaml /tmp/rc-noprefetch.rb "$WF" > /tmp/rc-noprefetch.out 2>&1
check "no job downloads android-all, and the plain-JUnit job has no cache" 0 $?
sed 's/^/    /' /tmp/rc-noprefetch.out

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]