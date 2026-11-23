#!/bin/bash
# Prepare the native dependencies BEFORE the build, so CMake never clones at configure time.
#
# Why this exists: the voice module's CMakeLists declares llama.cpp and oboe through FetchContent,
# which clones each of them once PER ABI from inside the checkout. Two release builds showed the
# two ways that fails: a clone that errors retries three times and then fails the build, and a
# clone that trickles never errors at all, so CMake waits on it indefinitely. Neither is a code
# defect; both are an unbounded network fetch on the release critical path.
#
# The shape: one source tree per dependency, keyed by its pinned commit, in a cache directory
# OUTSIDE the checkout (a clean checkout must not lose it). Every ABI then points at the same tree
# through FETCHCONTENT_SOURCE_DIR_<NAME>, which makes CMake skip population entirely.
#
# Pins are read from the CHECKOUT's CMakeLists, never duplicated here: a second copy of a pin is a
# copy that drifts, and the cache would then serve a revision nobody builds.
set -euo pipefail

CML="$(cd "$(dirname "$0")/../.." && pwd)/modules/services/voice/src/main/cpp/CMakeLists.txt"
[ -f "$CML" ] || { echo "FAIL: cannot read $CML" >&2; exit 1; }

CACHE="${AURIS_NATIVE_DEPS_CACHE:-$HOME/.cache/auris-native-deps}"
ATTEMPTS="${AURIS_NATIVE_ATTEMPTS:-4}"
# A stalled transfer must become a FAILURE so the retry below can act on it. CMake's own loop has
# no timeout, and git's default is to wait forever on a connection that still trickles.
TIMEOUT="${AURIS_NATIVE_FETCH_TIMEOUT:-120}"

case "$CACHE" in /*) ;; *) echo "FAIL: the cache path must be absolute: $CACHE" >&2; exit 1;; esac

# Report where the trees live, so a control can assert the DEFAULT is outside the checkout rather
# than only checking the path it passed in itself.
if [ "${1:-}" = "--print-cache" ]; then printf '%s\n' "$CACHE"; exit 0; fi

# name<TAB>url<TAB>sha, from the declarations themselves. An env override exists for the controls,
# which point at local repositories; production reads the file.
declarations() {
  ruby - "$CML" <<'RUBY'
    src = File.read(ARGV[0])
    src.scan(/FetchContent_Declare\(\s*(\w+)(.*?)\)/m).each do |name, body|
      repo = body[/GIT_REPOSITORY\s+(\S+)/, 1]
      tag  = body[/GIT_TAG\s+(\S+)/, 1]
      abort "FAIL: #{name} has no GIT_REPOSITORY or GIT_TAG" unless repo && tag
      abort "FAIL: #{name} is pinned to '#{tag}', not a full commit sha" unless tag.match?(/\A[0-9a-f]{40}\z/)
      puts "#{name}\t#{repo}\t#{tag}"
    end
RUBY
}

# Bound the transfer itself: git aborts when throughput stays under the limit for the given time.
# Passed as runtime config so it applies to every clone and fetch this script makes, including the
# ones CMake would otherwise run unbounded.
export GIT_CONFIG_COUNT=3
export GIT_CONFIG_KEY_0=http.lowSpeedLimit  GIT_CONFIG_VALUE_0=1000
export GIT_CONFIG_KEY_1=http.lowSpeedTime   GIT_CONFIG_VALUE_1="$TIMEOUT"
export GIT_CONFIG_KEY_2=advice.detachedHead GIT_CONFIG_VALUE_2=false

# The ONLY bound is git's own stall detection (http.lowSpeedLimit / http.lowSpeedTime above): a
# transfer that stops moving fails and is retried. There is deliberately no cap on TOTAL duration --
# one killed a large clone that was still progressing and would have finished, failing every release
# build. "Slow" and "hung" are different states and only the stall bound tells them apart.

prepare() { # prepare <name> <url> <sha>
  local name="$1" url="$2" sha="$3"
  local dest="$CACHE/$name/$sha"

  # Warm: the tree is present AND at the pinned revision. Presence alone is not enough -- a tree
  # whose git metadata is gone cannot be verified, and an unverifiable tree must be refetched.
  if [ -d "$dest" ] && [ "$(git -C "$dest" rev-parse HEAD 2>/dev/null || true)" = "$sha" ]; then
    echo "--- native: $name $sha already cached" >&2
    return 0
  fi

  # Stage OUTSIDE the destination, so an interrupted fetch leaves nothing that a later warm check
  # could mistake for a complete tree.
  mkdir -p "$CACHE/$name"
  local stage
  stage="$(mktemp -d "$CACHE/$name/.stage-XXXXXX")"
  rm -rf "$dest"

  local attempt=1 rc=0
  while [ "$attempt" -le "$ATTEMPTS" ]; do
    rm -rf "$stage/src"
    rc=0
    # Fetch ONLY the pinned commit. `clone --depth 1 --no-single-branch` downloads the tip of every
    # branch before the pin is asked for, which on llama.cpp is most of the transfer and is what made
    # a cold fetch too slow to finish. GitHub serves a fetch by sha, so this is one commit's tree --
    # the minimum -- and it needs no separate fallback fetch.
    mkdir -p "$stage/src"
    git -C "$stage/src" init --quiet || rc=$?
    [ "$rc" -eq 0 ] && { git -C "$stage/src" remote add origin "$url" || rc=$?; }
    [ "$rc" -eq 0 ] && { git -C "$stage/src" fetch --quiet --depth 1 origin "$sha" || rc=$?; }
    [ "$rc" -eq 0 ] && { git -C "$stage/src" checkout --quiet "$sha" || rc=$?; }
    if [ "$rc" -eq 0 ] && [ "$(git -C "$stage/src" rev-parse HEAD 2>/dev/null || true)" = "$sha" ]; then
      mv "$stage/src" "$dest"
      rm -rf "$stage"
      echo "--- native: $name $sha fetched" >&2
      return 0
    fi
    echo "--- native: $name attempt $attempt/$ATTEMPTS failed (exit $rc)" >&2
    attempt=$((attempt + 1))
    [ "$attempt" -le "$ATTEMPTS" ] && sleep $((attempt * 2))
  done

  rm -rf "$stage" "$dest"
  echo "FAIL: could not fetch $name at $sha from $url after $ATTEMPTS attempts" >&2
  return 1
}

# Capture the declarations BEFORE looping. Reading them through a process substitution would hide
# the ruby guard's exit status: an abort would leave the loop with no lines and this script would
# exit 0 with empty stdout, so the release would run with no overrides and clone per ABI again --
# silently undoing everything above. `set -e` stops here instead, and an empty result is a failure.
DECLS="$(declarations)"
if [ -z "$DECLS" ]; then
  echo "FAIL: no native dependency declarations found in $CML" >&2
  exit 1
fi

EXPORTS=""
while IFS=$'\t' read -r name url sha; do
  [ -n "$name" ] || continue
  upper="$(printf '%s' "$name" | tr '[:lower:]' '[:upper:]')"
  case "$name" in
    llama) url="${AURIS_NATIVE_LLAMA_URL:-$url}"; sha="${AURIS_NATIVE_LLAMA_SHA:-$sha}";;
    oboe)  url="${AURIS_NATIVE_OBOE_URL:-$url}";  sha="${AURIS_NATIVE_OBOE_SHA:-$sha}";;
  esac
  prepare "$name" "$url" "$sha"
  EXPORTS="${EXPORTS}FETCHCONTENT_SOURCE_DIR_${upper}=$CACHE/$name/$sha"$'\n'
done <<< "$DECLS"

# The overrides are printed once, as NAME=path lines with NO quotes: the caller turns each into a
# -D argument and nothing between Gradle and cmake would strip quote characters from the value.
# Progress goes to stderr so stdout carries only these lines. CMake reads them as CACHE variables, so
# the caller turns each into a -D argument; this script does not need to know how the build runs.
printf '%s' "$EXPORTS"
