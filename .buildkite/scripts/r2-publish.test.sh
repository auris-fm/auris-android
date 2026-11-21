#!/bin/bash
# Controls for the R2 publish step's identity + atomicity rules:
#  - versioned object is create-only (If-None-Match: *); a failed lookup is
#    NOT treated as absence
#  - retry resumes only when the existing object is byte-identical
#  - an older run finishing late never moves latest.* backwards
#  - the Play step records the BUILT version carried with the artifact,
#    not its own checkout's base version
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PASS=0; FAIL=0
check() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (got '$3' want '$2')"; fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/store" "$WORK/meta" "$WORK/secrets" "$WORK/repo/.buildkite/commands" "$WORK/repo/.buildkite/scripts"
cp "$DIR/.buildkite/commands/auris-r2-apk.sh" "$WORK/repo/.buildkite/commands/"
cp "$DIR/.buildkite/scripts/verify-play-apk.sh" "$WORK/repo/.buildkite/scripts/"
printf 'versionName=8.20\nversionCode=9452\n' > "$WORK/repo/version.properties"
printf 'dummy-apk-bytes-v1' > "$WORK/apk.bin"

# Mock rclone: cat preserves the real exit status (missing object => 4,
# like rclone's "not found"); lsf lists a prefix or a single object and can
# be made to FAIL via MOCK_RCLONE_FAIL to prove read failures stay fatal.
cat > "$WORK/bin/rclone" <<'MOCK'
#!/bin/bash
echo "rclone $*" >> "$MOCK_LOG"
[ -n "${MOCK_RCLONE_FAIL:-}" ] && exit 7
key_of() { printf '%s' "${1#r2:}"; }
case "$1" in
  copyto)
    dst="$MOCK_STORE/$(key_of "$3")"
    mkdir -p "$(dirname "$dst")"; cp "$2" "$dst" ;;
  cat)
    src="$MOCK_STORE/$(key_of "$2")"
    if [ -f "$src" ]; then cat "$src"; else exit 4; fi ;;
  lsf)
    target="$MOCK_STORE/$(key_of "$2")"
    inc=""
    for a in "$@"; do case "$prev" in --include) inc="$a";; esac; prev="$a"; done
    if [ -d "$target" ]; then
      if [ -n "$inc" ]; then [ -f "$target/$inc" ] && echo "$inc"; else ls "$target" 2>/dev/null; fi
    else
      [ -f "$target" ] && basename "$target"
    fi
    exit 0 ;;
esac
exit 0
MOCK
chmod +x "$WORK/bin/rclone"

# Mock curl: the create-only SigV4 PUT. Honors If-None-Match: * against the
# store (412 when the object exists), records the header so the control can
# assert the condition rides the committing request.
cat > "$WORK/bin/curl" <<'MOCK'
#!/bin/bash
echo "curl $*" >> "$MOCK_LOG"
# Record the exact argv for the ps-exposure control; optionally sleep so a
# concurrent ps sample can observe the live process arguments.
printf '%s\n' "$@" > "$MOCK_CURL_ARGV"
[ -n "${MOCK_CURL_SLEEP:-}" ] && sleep "$MOCK_CURL_SLEEP"
url=""; data=""; cond=0; out=""
prev=""
for a in "$@"; do
  case "$prev" in -o) out="$a";; --data-binary) data="${a#@}";; esac
  case "$a" in https://*) url="$a";; "If-None-Match: *") cond=1;; esac
  prev="$a"
done
key="${url#*://*/}"
dst="$MOCK_STORE/${key%%/*}/${key#*/}"
if [ "$cond" = 1 ] && [ -f "$dst" ]; then
  printf '412'
  exit 0
fi
mkdir -p "$(dirname "$dst")"; cp "$data" "$dst"
printf '200'
MOCK
chmod +x "$WORK/bin/curl"

# Mock buildkite-agent: secrets/meta from files; artifact download serves the
# carried built-version-code.txt.
cat > "$WORK/bin/buildkite-agent" <<'MOCK'
#!/bin/bash
case "$1 $2" in
  "secret get") cat "$MOCK_SECRETS/$3" 2>/dev/null ;;
  "meta-data get") cat "$MOCK_META/$3" 2>/dev/null ;;
  "meta-data set") printf '%s' "$4" > "$MOCK_META/$3" ;;
  "artifact download") cp "$MOCK_ARTIFACTS/$3" . ;;
  "artifact upload") cp "$3" "$MOCK_ARTIFACTS/" ;;
esac
MOCK
chmod +x "$WORK/bin/buildkite-agent"

# Mock bundle exec ruby fetcher: copies the fixture APK into place.
cat > "$WORK/bin/bundle" <<'MOCK'
#!/bin/bash
# bundle exec ruby fetch-play-apk.rb <key> <vc> <out> <pin>
cp "$MOCK_APK" "$6"
MOCK
chmod +x "$WORK/bin/bundle"

# Stub verifier (signature pin logic has its own controls elsewhere).
cat > "$WORK/repo/.buildkite/scripts/verify-play-apk.sh" <<'MOCK'
#!/bin/bash
exit 0
MOCK

export PATH="$WORK/bin:$PATH"
export MOCK_STORE="$WORK/store" MOCK_META="$WORK/meta" MOCK_SECRETS="$WORK/secrets"
export MOCK_LOG="$WORK/rclone.log" MOCK_APK="$WORK/apk.bin" MOCK_ARTIFACTS="$WORK/artifacts"
export MOCK_CURL_ARGV="$WORK/curl-argv"
mkdir -p "$MOCK_ARTIFACTS"
printf 'id' > "$MOCK_SECRETS/R2_APK_ACCESS_KEY_ID"
printf 'secret' > "$MOCK_SECRETS/R2_APK_SECRET_ACCESS_KEY"
export PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{}'
printf '9522' > "$MOCK_META/uploaded-version-code"

run_r2() { (cd "$WORK/repo" && bash .buildkite/commands/auris-r2-apk.sh >/dev/null 2>&1); }

# 1. Fresh publish: versioned + aliases exist, conditional header used.
run_r2; check "fresh publish succeeds" 0 "$?"
[ -f "$MOCK_STORE/auris-download/apk/auris-8.20-9522.apk" ] && r=ok || r=missing
check "versioned object created" ok "$r"
grep -q 'If-None-Match: \*' "$MOCK_LOG" && r=ok || r=missing
check "create-only header sent" ok "$r"
grep -q '"versionCode": 9522' "$MOCK_STORE/auris-download/apk/latest.json" && r=ok || r=missing
check "latest.json at 9522" ok "$r"

# 1b. Credential exposure: the R2 secret must never appear in curl's argv
# (what ps shows). The mock records its exact argv; assert absence.
grep -q 'secret' "$MOCK_CURL_ARGV" && r=leaked || r=clean
check "R2 secret absent from curl process arguments" clean "$r"
grep -q 'If-None-Match' "$MOCK_CURL_ARGV" && r=ok || r=missing
check "condition still passed as header arg" ok "$r"

# 2. Retry with identical bytes: resumes, aliases refreshed, no overwrite.
run_r2; check "identical-byte retry resumes" 0 "$?"

# 3. Existing versioned object with DIFFERENT bytes: refuse.
printf 'different-bytes' > "$MOCK_STORE/auris-download/apk/auris-8.20-9522.apk"
run_r2; check "different-byte collision refused" 1 "$?"

# 4. Older run finishing late: INCOMING 9522 vs EXISTING 9600 -- the skip
# must actually trigger, and BOTH aliases stay at the newer version.
printf 'dummy-apk-bytes-v1' > "$MOCK_STORE/auris-download/apk/auris-8.20-9522.apk"
printf '9522' > "$MOCK_META/uploaded-version-code"
printf '{"versionCode": 9600}' > "$MOCK_STORE/auris-download/apk/latest.json"
printf 'newer-apk-bytes' > "$MOCK_STORE/auris-download/apk/latest.apk"
OUT4="$(cd "$WORK/repo" && bash .buildkite/commands/auris-r2-apk.sh 2>&1)"; RC4=$?
check "older run exits cleanly" 0 "$RC4"
printf '%s' "$OUT4" | grep -q "leaving aliases untouched" && r=ok || r=noskip
check "skip path actually taken" ok "$r"
grep -q '"versionCode": 9600' "$MOCK_STORE/auris-download/apk/latest.json" && r=ok || r=moved
check "latest.json NOT moved backwards" ok "$r"
[ "$(cat "$MOCK_STORE/auris-download/apk/latest.apk")" = "newer-apk-bytes" ] && r=ok || r=moved
check "latest.apk NOT moved backwards" ok "$r"

# 4b. A failed alias READ (not absence) must be fatal, never publish blind.
run_r2_env() { (cd "$WORK/repo" && MOCK_RCLONE_FAIL=1 bash .buildkite/commands/auris-r2-apk.sh >/dev/null 2>&1); }
run_r2_env; check "failed alias listing is fatal" 1 "$?"

# 5. Play step records the CARRIED built version, not its checkout's base.
cp "$DIR/.buildkite/commands/auris-play-upload.sh" "$WORK/repo/.buildkite/commands/"
cp "$DIR/.buildkite/commands/auris-verify-bundle-signature.sh" "$WORK/repo/.buildkite/commands/" 2>/dev/null || true
cat > "$WORK/repo/.buildkite/commands/auris-verify-bundle-signature.sh" <<'MOCK'
#!/bin/bash
exit 0
MOCK
mkdir -p "$WORK/repo/app/build/outputs/bundle/release"
printf 'aab' > "$WORK/repo/app/build/outputs/bundle/release/app-release.aab"
mkdir -p "$MOCK_ARTIFACTS/app/build/outputs/bundle/release"
printf 'aab' > "$MOCK_ARTIFACTS/app/build/outputs/bundle/release/app-release.aab"
printf '9522\n' > "$MOCK_ARTIFACTS/built-version-code.txt"
# fastlane stub: success without touching anything.
cat > "$WORK/bin/fastlane-stub" <<'MOCK'
#!/bin/bash
exit 0
MOCK
# The play script calls `bundle exec fastlane supply`; our bundle mock handles
# ruby fetches -- extend it to succeed for fastlane too.
cat > "$WORK/bin/bundle" <<'MOCK'
#!/bin/bash
if [ "$2" = ruby ]; then cp "$MOCK_APK" "$6"; fi
exit 0
MOCK
chmod +x "$WORK/bin/bundle"
rm -f "$MOCK_META/uploaded-version-code"
(cd "$WORK/repo" && bash .buildkite/commands/auris-play-upload.sh >/dev/null 2>&1)
check "play step succeeds with carried version" 0 "$?"
check "recorded version is the BUILT one (9522), not checkout base (9452)" 9522 "$(cat "$MOCK_META/uploaded-version-code" 2>/dev/null)"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
