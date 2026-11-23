#!/bin/bash
# Controls for the native dependency fetch on the release path.
#
# The defect these exist for: the release build clones llama.cpp and oboe at CMake CONFIGURE time,
# once per ABI, from inside the checkout. A clone that fails outright retries three times and then
# fails the build; a clone that TRICKLES never fails at all, so CMake's retry loop waits forever.
# One release build died on the first case and hung on the second, in the same run.
#
# Every case here runs the REAL script against REAL throwaway git repositories, and the wiring
# cases run REAL cmake. Nothing asserts on what the script printed when it can assert on what it
# did: the tree on disk, the exit status, and whether a clone was attempted at all.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/../.." && pwd)"
SCRIPT="$DIR/.buildkite/scripts/native-deps.sh"
CML="$DIR/modules/services/voice/src/main/cpp/CMakeLists.txt"
PASS=0; FAIL=0
ok()  { echo "ok: $1"; PASS=$((PASS + 1)); }
bad() { echo "FAIL: $1"; FAIL=$((FAIL + 1)); }
check() { if [ "$2" = "$3" ]; then ok "$1"; else bad "$1 (got '$3' want '$2')"; fi; }
check_nonzero() { if [ "$2" != 0 ]; then ok "$1"; else bad "$1 (unexpectedly succeeded)"; fi; }

[ -f "$SCRIPT" ] || { echo "FAIL: $SCRIPT missing"; exit 1; }
[ -f "$CML" ] || { echo "FAIL: $CML missing"; exit 1; }
command -v cmake >/dev/null 2>&1 || { echo "FAIL: cmake required"; exit 1; }
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT

# The pins come from the CHECKOUT, never from literals in this control: a hardcoded copy would
# keep passing after a bump and certify a revision nobody builds.
read_pins() {
  ruby - "$CML" <<'RUBY'
    src = File.read(ARGV[0])
    src.scan(/FetchContent_Declare\(\s*(\w+)(.*?)\)/m).each do |name, body|
      repo = body[/GIT_REPOSITORY\s+(\S+)/, 1]
      tag  = body[/GIT_TAG\s+(\S+)/, 1]
      puts "#{name} #{repo} #{tag}"
    end
RUBY
}
PINS="$(read_pins)"
[ -n "$PINS" ] || { echo "FAIL: no FetchContent declarations found in $CML"; exit 1; }
LLAMA_TAG="$(printf '%s\n' "$PINS" | awk '$1=="llama"{print $3}')"
OBOE_TAG="$(printf '%s\n' "$PINS" | awk '$1=="oboe"{print $3}')"

# A real repository with one commit, served over file:// so a clone is a real clone.
make_repo() { # make_repo <dir> <content>
  local r="$1" content="$2"
  mkdir -p "$r"
  (
    cd "$r"
    git init -q -b main
    git config user.email t@t; git config user.name t; git config commit.gpgsign false
    printf '%s\n' "$content" > MARKER
    mkdir -p include
    printf '// %s\n' "$content" > include/header.h
    git add -A; git commit -qm one
  )
  git -C "$r" rev-parse HEAD
}

# Run the real script with both dependencies pointed at local repositories.
run_deps() { # run_deps <cache-root> <llama-url> <llama-sha> <oboe-url> <oboe-sha> [extra env...]
  local root="$1" lu="$2" ls="$3" ou="$4" os="$5"; shift 5
  env "$@" \
    AURIS_NATIVE_DEPS_CACHE="$root" \
    AURIS_NATIVE_LLAMA_URL="$lu" AURIS_NATIVE_LLAMA_SHA="$ls" \
    AURIS_NATIVE_OBOE_URL="$ou" AURIS_NATIVE_OBOE_SHA="$os" \
    bash "$SCRIPT" > "$WORK/deps.out" 2>&1
}

LLAMA_REPO="$WORK/llama-remote"; OBOE_REPO="$WORK/oboe-remote"
LLAMA_SHA="$(make_repo "$LLAMA_REPO" llama-one)"
OBOE_SHA="$(make_repo "$OBOE_REPO" oboe-one)"
LLAMA_URL="file://$LLAMA_REPO"; OBOE_URL="file://$OBOE_REPO"
export GIT_CONFIG_COUNT=1 GIT_CONFIG_KEY_0=protocol.file.allow GIT_CONFIG_VALUE_0=always

# --- 1. Cold cache: both trees land, at the pinned revisions, outside the checkout. -------------
CACHE="$WORK/cache"
run_deps "$CACHE" "$LLAMA_URL" "$LLAMA_SHA" "$OBOE_URL" "$OBOE_SHA"; RC=$?
check "cold cache succeeds" 0 "$RC"
sed 's/^/    /' "$WORK/deps.out" | tail -3
LLAMA_DIR="$CACHE/llama/$LLAMA_SHA"
OBOE_DIR="$CACHE/oboe/$OBOE_SHA"
check "llama tree is keyed by its pin" yes "$([ -f "$LLAMA_DIR/MARKER" ] && echo yes || echo no)"
check "oboe tree is keyed by its pin" yes "$([ -f "$OBOE_DIR/MARKER" ] && echo yes || echo no)"
check "llama tree is AT the pinned revision" "$LLAMA_SHA" "$(git -C "$LLAMA_DIR" rev-parse HEAD 2>/dev/null)"
check "oboe tree is AT the pinned revision" "$OBOE_SHA" "$(git -C "$OBOE_DIR" rev-parse HEAD 2>/dev/null)"
# The DEFAULT path is what production uses; asserting only on the override this control passes
# would pass for a default that writes into the checkout and is wiped by the next clean build.
DEFAULT_CACHE="$(env -u AURIS_NATIVE_DEPS_CACHE bash "$SCRIPT" --print-cache 2>/dev/null || true)"
if [ -z "$DEFAULT_CACHE" ]; then
  DEFAULT_CACHE="$(env -u AURIS_NATIVE_DEPS_CACHE bash -c '. '"$SCRIPT"' --print-cache' 2>/dev/null || true)"
fi
case "$DEFAULT_CACHE" in
  "") bad "the script does not report its default cache path";;
  "$DIR"*) bad "the DEFAULT cache lives inside the checkout ($DEFAULT_CACHE)";;
  /*) ok "the default cache is absolute and outside the checkout";;
  *) bad "the default cache is relative ($DEFAULT_CACHE)";;
esac

# --- 2. Warm cache: ZERO clones. A git that records every clone attempt proves it. --------------
mkdir -p "$WORK/spy"
cat > "$WORK/spy/git" <<'SPY'
#!/bin/bash
for a in "$@"; do
  if [ "$a" = clone ] || [ "$a" = fetch ]; then echo "$a" >> "$GIT_SPY_LOG"; break; fi
done
exec REAL_GIT_PLACEHOLDER "$@"
SPY
sed -i.bak "s|REAL_GIT_PLACEHOLDER|$(command -v git)|" "$WORK/spy/git" && rm -f "$WORK/spy/git.bak"
chmod +x "$WORK/spy/git"
: > "$WORK/spy.log"
run_deps "$CACHE" "$LLAMA_URL" "$LLAMA_SHA" "$OBOE_URL" "$OBOE_SHA" \
  PATH="$WORK/spy:$PATH" GIT_SPY_LOG="$WORK/spy.log"; RC=$?
check "warm cache succeeds" 0 "$RC"
check "warm cache performs NO clone and NO fetch" "" "$(cat "$WORK/spy.log")"

# --- 3. A new pin must NOT reuse the old tree. ---------------------------------------------------
printf 'two\n' >> "$LLAMA_REPO/MARKER"
git -C "$LLAMA_REPO" commit -qam two
LLAMA_SHA2="$(git -C "$LLAMA_REPO" rev-parse HEAD)"
run_deps "$CACHE" "$LLAMA_URL" "$LLAMA_SHA2" "$OBOE_URL" "$OBOE_SHA"; RC=$?
check "a bumped pin succeeds" 0 "$RC"
check "the bumped pin gets its OWN directory" two "$(cat "$CACHE/llama/$LLAMA_SHA2/MARKER" 2>/dev/null | tail -1)"
check "the old pin's tree is untouched" llama-one "$(cat "$LLAMA_DIR/MARKER" 2>/dev/null | tail -1)"

# --- 4. An incomplete fetch leaves NOTHING behind, so the next run refetches. -------------------
#     A half-populated tree that looks present is worse than no tree: the warm path would use it.
BAD_REPO="$WORK/bad-remote"
mkdir -p "$BAD_REPO"
git -C "$BAD_REPO" init -q -b main
MISSING_SHA="0123456789abcdef0123456789abcdef01234567"
run_deps "$CACHE" "file://$BAD_REPO" "$MISSING_SHA" "$OBOE_URL" "$OBOE_SHA"; RC=$?
check_nonzero "an unfetchable revision fails" "$RC"
# The destination must not EXIST, not merely be empty: a directory left behind is what a presence
# check would later read as a cached tree, and an empty one still hides the failed fetch.
check "a failed fetch leaves no destination at all" no "$([ -e "$CACHE/llama/$MISSING_SHA" ] && echo yes || echo no)"
check "no staging directory is left behind" 0 "$(find "$CACHE" -maxdepth 2 -name '.stage-*' 2>/dev/null | wc -l | tr -d ' ')"

# --- 5. A corrupt cached tree is refetched, not trusted. ----------------------------------------
rm -rf "$OBOE_DIR/.git"
run_deps "$CACHE" "$LLAMA_URL" "$LLAMA_SHA2" "$OBOE_URL" "$OBOE_SHA"; RC=$?
check "a cache entry with no git metadata is repaired" 0 "$RC"
check "the repaired tree is at the pinned revision" "$OBOE_SHA" "$(git -C "$OBOE_DIR" rev-parse HEAD 2>/dev/null)"

# --- 6. A TRICKLING transfer must FAIL, not hang. This is the arm64 case. -----------------------
#     CMake's own loop retries a clone that fails and waits forever on one that trickles, so the
#     bound has to be on the transfer itself.
mkdir -p "$WORK/trickle"
cat > "$WORK/trickle/git" <<'TRICKLE'
#!/bin/bash
for a in "$@"; do
  if [ "$a" = clone ] || [ "$a" = fetch ]; then sleep 300; exit 0; fi
done
exec REAL_GIT_PLACEHOLDER "$@"
TRICKLE
sed -i.bak "s|REAL_GIT_PLACEHOLDER|$(command -v git)|" "$WORK/trickle/git" && rm -f "$WORK/trickle/git.bak"
chmod +x "$WORK/trickle/git"
FRESH="$WORK/fresh-cache"
START=$(date +%s)
run_deps "$FRESH" "$LLAMA_URL" "$LLAMA_SHA" "$OBOE_URL" "$OBOE_SHA" \
  PATH="$WORK/trickle:$PATH" AURIS_NATIVE_FETCH_TIMEOUT=3 AURIS_NATIVE_ATTEMPTS=2; RC=$?
ELAPSED=$(( $(date +%s) - START ))
check_nonzero "a trickling transfer fails instead of hanging" "$RC"
if [ "$ELAPSED" -lt 60 ]; then ok "the stall is bounded (${ELAPSED}s)"; else bad "the stall was not bounded (${ELAPSED}s)"; fi
check "a timed-out fetch leaves no tree" 0 "$(find "$FRESH" -type f 2>/dev/null | wc -l | tr -d ' ')"

# --- 7. A failed attempt is RETRIED, and a later success is used. -------------------------------
mkdir -p "$WORK/flaky"
cat > "$WORK/flaky/git" <<'FLAKY'
#!/bin/bash
for a in "$@"; do
  if [ "$a" = clone ]; then
    n=$(cat "$FLAKY_COUNT" 2>/dev/null || echo 0); n=$((n + 1)); echo "$n" > "$FLAKY_COUNT"
    if [ "$n" -lt 2 ]; then exit 128; fi
    break
  fi
done
exec REAL_GIT_PLACEHOLDER "$@"
FLAKY
sed -i.bak "s|REAL_GIT_PLACEHOLDER|$(command -v git)|" "$WORK/flaky/git" && rm -f "$WORK/flaky/git.bak"
chmod +x "$WORK/flaky/git"
echo 0 > "$WORK/flaky-count"
run_deps "$WORK/flaky-cache" "$LLAMA_URL" "$LLAMA_SHA" "$OBOE_URL" "$OBOE_SHA" \
  PATH="$WORK/flaky:$PATH" FLAKY_COUNT="$WORK/flaky-count" AURIS_NATIVE_ATTEMPTS=3; RC=$?
check "a transient clone failure is retried and then succeeds" 0 "$RC"
# Two dependencies are fetched, so a single failed attempt means three clones in total. The count
# is asserted rather than assumed: "it succeeded" alone would also pass with no retry at all.
check "exactly one attempt failed and was retried" 3 "$(cat "$WORK/flaky-count")"

# --- 8. The script prints the pair CMake needs, with ABSOLUTE paths. ----------------------------
EXPORTS="$WORK/exports"
AURIS_NATIVE_DEPS_CACHE="$CACHE" \
AURIS_NATIVE_LLAMA_URL="$LLAMA_URL" AURIS_NATIVE_LLAMA_SHA="$LLAMA_SHA2" \
AURIS_NATIVE_OBOE_URL="$OBOE_URL" AURIS_NATIVE_OBOE_SHA="$OBOE_SHA" \
bash "$SCRIPT" > "$EXPORTS" 2>/dev/null
check "the overrides are printed for the caller" yes "$([ -s "$EXPORTS" ] && echo yes || echo no)"
# shellcheck disable=SC1090
. "$EXPORTS" 2>/dev/null
check "llama source dir is exported" "$CACHE/llama/$LLAMA_SHA2" "${FETCHCONTENT_SOURCE_DIR_LLAMA:-}"
check "oboe source dir is exported" "$OBOE_DIR" "${FETCHCONTENT_SOURCE_DIR_OBOE:-}"
case "${FETCHCONTENT_SOURCE_DIR_LLAMA:-}" in /*) ok "the exported path is absolute";; *) bad "the exported path is relative";; esac

# --- 9. REAL cmake consumes the exported pair and clones NOTHING. -------------------------------
#     An unreachable URL proves it: if cmake still tried to populate, configure would fail.
mkdir -p "$WORK/consumer"
cat > "$WORK/consumer/CMakeLists.txt" <<'CONSUMER'
cmake_minimum_required(VERSION 3.22)
project(native_deps_control NONE)
include(FetchContent)
FetchContent_Declare(llama GIT_REPOSITORY https://example.invalid/unreachable.git GIT_TAG deadbeef GIT_SHALLOW TRUE)
FetchContent_Declare(oboe  GIT_REPOSITORY https://example.invalid/unreachable.git GIT_TAG deadbeef GIT_SHALLOW TRUE)
FetchContent_MakeAvailable(llama)
FetchContent_MakeAvailable(oboe)
message(STATUS "LLAMA_SRC=${llama_SOURCE_DIR}")
message(STATUS "OBOE_SRC=${oboe_SOURCE_DIR}")
CONSUMER
rm -rf "$WORK/consumer/b"
cmake -S "$WORK/consumer" -B "$WORK/consumer/b" \
  -DFETCHCONTENT_SOURCE_DIR_LLAMA="$CACHE/llama/$LLAMA_SHA2" \
  -DFETCHCONTENT_SOURCE_DIR_OBOE="$OBOE_DIR" > "$WORK/cmake.out" 2>&1
check "cmake configures with the cached trees and an UNREACHABLE url" 0 $?
check "cmake used the llama tree" yes "$(grep -q "LLAMA_SRC=$CACHE/llama/$LLAMA_SHA2" "$WORK/cmake.out" && echo yes || echo no)"
check "cmake used the oboe tree" yes "$(grep -q "OBOE_SRC=$OBOE_DIR" "$WORK/cmake.out" && echo yes || echo no)"
check "cmake attempted no clone" no "$(grep -qi 'Cloning into' "$WORK/cmake.out" && echo yes || echo no)"

# --- 9b. The REAL CMakeLists consumes the pair, for BOTH ABIs, cloning nothing. -----------------
#     Case 9 proves cmake honours the mechanism on a stand-in project. This one runs the file the
#     build actually uses, with the real URLs made unreachable: if either declaration were left
#     without an override, or a third dependency were added later, configure would try to clone.
mkdir -p "$WORK/realcml"
sed -e 's#https://github.com/ggerganov/llama.cpp.git#https://example.invalid/unreachable.git#' \
    -e 's#https://github.com/google/oboe.git#https://example.invalid/unreachable.git#' \
    "$CML" > "$WORK/realcml/CMakeLists.txt"
check "both real declarations were redirected for this case" 2 "$(grep -c 'example.invalid' "$WORK/realcml/CMakeLists.txt" | tr -d ' ')"
rm -rf "$WORK/realcml/b"
cmake -S "$WORK/realcml" -B "$WORK/realcml/b" \
  -DFETCHCONTENT_SOURCE_DIR_LLAMA="$CACHE/llama/$LLAMA_SHA2" \
  -DFETCHCONTENT_SOURCE_DIR_OBOE="$OBOE_DIR" \
  -DFETCHCONTENT_FULLY_DISCONNECTED=ON > "$WORK/realcmake.out" 2>&1
RC=$?
# Configure may fail later on Android-specific targets; what must NOT happen is a population attempt.
check "the real CMakeLists attempts no clone with both trees supplied" no "$(grep -qi 'Cloning into\|Performing download step' "$WORK/realcmake.out" && echo yes || echo no)"
if [ "$RC" -ne 0 ]; then
  echo "    (configure stopped later, as expected off-Android: $(grep -m1 'CMake Error' "$WORK/realcmake.out" | cut -c1-100))"
fi

# --- 10. Both declarations carry a TRANSFER BOUND, and oboe is pinned to a commit. --------------
ruby - "$CML" <<'RUBY' > "$WORK/shape.out" 2>&1
  src = File.read(ARGV[0])
  decls = src.scan(/FetchContent_Declare\(\s*(\w+)(.*?)\)/m)
  abort "expected two declarations, found #{decls.size}" unless decls.size == 2
  decls.each do |name, body|
    abort "#{name}: no transfer bound (http.lowSpeedLimit)" unless body.include?("http.lowSpeedLimit")
    abort "#{name}: no transfer bound (http.lowSpeedTime)"  unless body.include?("http.lowSpeedTime")
    tag = body[/GIT_TAG\s+(\S+)/, 1].to_s
    abort "#{name}: GIT_TAG '#{tag}' is not a full commit sha" unless tag.match?(/\A[0-9a-f]{40}\z/)
  end
  puts "both declarations are commit-pinned and transfer-bounded"
RUBY
check "every native declaration is commit-pinned and bounds a stalled transfer" 0 $?
sed 's/^/    /' "$WORK/shape.out"

# --- 11. The release step USES the helper, BEFORE gradle runs. ----------------------------------
ruby - "$DIR" <<'RUBY' > "$WORK/wiring.out" 2>&1
  step = File.read(File.join(ARGV[0], ".buildkite/commands/auris-release-aab.sh"))
  lines = step.lines.reject { |l| l.strip.start_with?("#") }
  body = lines.join
  call = lines.index { |l| l.include?("native-deps.sh") }
  gradle = lines.index { |l| l.include?("./gradlew") }
  abort "the release step never runs the native dependency helper" unless call
  abort "no gradle invocation found" unless gradle
  abort "the helper runs AFTER gradle, so the build would still clone" unless call < gradle
  puts "the release step prepares the native trees before gradle"
RUBY
check "the release step prepares native trees before building" 0 $?
sed 's/^/    /' "$WORK/wiring.out"

# --- 11b. The build actually FORWARDS the overrides to cmake. -----------------------------------
#     Preparing trees and exporting a variable is not enough: if the module never passes them to
#     CMake, every ABI still clones and the whole helper is dead code on the release path.
ruby - "$DIR" <<'RUBY' > "$WORK/forward.out" 2>&1
  gradle = File.read(File.join(ARGV[0], "modules/services/voice/build.gradle.kts"))
  step   = File.read(File.join(ARGV[0], ".buildkite/commands/auris-release-aab.sh"))
  var = step[/export\s+(AURIS_NATIVE_CMAKE_ARGS)/, 1]
  abort "the release step exports no cmake-args variable" unless var
  abort "the voice module never reads #{var}" unless gradle.include?(var)
  # It must reach cmake's arguments, not merely be read into a local.
  block = gradle[/externalNativeBuild\s*\{.*?abiFilters/m]
  abort "#{var} is not added to cmake arguments" unless block && block.include?(var) && block.include?("arguments +=")
  # Reading the variable is not forwarding it: appending the raw string, or splitting on the wrong
  # separator, produces one malformed argument instead of two -D flags. Assert the shape.
  fwd = block[/System\.getenv\("#{var}"\)(.*?)(?=\n\s*\}|\n\s*\/\/)/m].to_s
  abort "the forwarding does not split the string into separate arguments" unless fwd.include?('split(" ")')
  abort "the forwarding does not drop blank entries" unless fwd.include?("isNotBlank")
  abort "the forwarding does not append to arguments" unless fwd.include?("arguments +=")
  puts "the release step's overrides reach cmake arguments"
RUBY
check "the prepared trees are actually handed to cmake" 0 $?
sed 's/^/    /' "$WORK/forward.out"

# --- 12. EVERY job that builds native code gets the same treatment. -----------------------------
#     Enumerated from disk, not from a list here: naming one file is how a second copy of a job
#     goes unguarded while the first stays green.
ruby - "$DIR" <<'RUBY' > "$WORK/copies.out" 2>&1
  require "yaml"
  root = ARGV[0]
  native_tasks = /bundleRelease|assembleRelease|externalNativeBuild|configureCMake|assembleDebug/
  offenders = []
  Dir.glob(File.join(root, ".github/workflows/*.yml")).sort.each do |wf|
    y = YAML.load_file(wf)
    (y["jobs"] || {}).each do |job, spec|
      runs = (spec["steps"] || []).map { |s| s["run"].to_s }.join("\n")
      next unless runs.match?(native_tasks)
      offenders << "#{File.basename(wf)}:#{job}" unless runs.include?("native-deps.sh")
    end
  end
  # Buildkite steps are enumerated from the PIPELINES, not from the commands directory: a script
  # that no pipeline invokes cannot clone anything, and demanding the helper there would be a
  # guard against a build that never happens. Only pipelines running on OUR queue are in scope --
  # the inherited upstream pipeline targets Automattic's agents and secrets, not this runner.
  Dir.glob(File.join(root, ".buildkite/*.yml")).sort.each do |pl|
    y0 = YAML.load_file(pl)
    queue = (y0["agents"] || {})["queue"].to_s
    next unless queue == "auris-android"
    y = YAML.load_file(pl)
    steps = (y["steps"] || []).flat_map { |s| s.is_a?(Hash) ? [s, *(s["group"] && s["steps"] || [])] : [] }
    steps.each do |s|
      cmd = s["command"].to_s
      next if cmd.empty?
      path = File.join(root, cmd.split.first.to_s)
      next unless File.file?(path)
      body = File.read(path)
      next unless body.match?(native_tasks)
      offenders << "#{File.basename(pl)} -> #{cmd}" unless body.include?("native-deps.sh")
    end
  end
  abort "these build native code without preparing the trees: #{offenders.join(', ')}" unless offenders.empty?
  puts "every native-building job prepares the trees"
RUBY
check "no native-building job is left to clone at configure time" 0 $?
sed 's/^/    /' "$WORK/copies.out"

echo
echo "$PASS passed, $FAIL failed"
[ "$FAIL" -eq 0 ]
