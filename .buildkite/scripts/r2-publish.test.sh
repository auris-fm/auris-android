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

# Real JDK for the producer fixture: CI sets its own JAVA_HOME and the release
# gate correctly prefers it, so the fixture must pin one with a working keytool.
PAIR_JAVA_HOME="$(dirname "$(dirname "$(command -v keytool)")")"
export PAIR_JAVA_HOME

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/store" "$WORK/meta" "$WORK/secrets" "$WORK/repo/.buildkite/commands" "$WORK/repo/.buildkite/scripts"
cp "$DIR/.buildkite/commands/auris-r2-apk.sh" "$WORK/repo/.buildkite/commands/"
# Buildkite runs these as EXECUTABLES, so their `#!/bin/bash -eu` shebang applies.
# Invoking them as `bash script` silently drops those flags and the fixture would
# then test a laxer script than production runs.
chmod +x "$WORK/repo/.buildkite/commands/"*.sh
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
[ -n "${MOCK_RCLONE_LSF_FAIL:-}" ] && [ "$1" = lsf ] && exit 7
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
# Read the -K - config from stdin and record it: the control asserts the
# expected dummy credential arrives THERE (and nowhere in argv).
if printf '%s\n' "$@" | grep -q '^-K$'; then cat > "$MOCK_CURL_STDIN"; fi
[ -n "${MOCK_CURL_SLEEP:-}" ] && sleep "$MOCK_CURL_SLEEP"
url=""; data=""; cond=0; out=""
prev=""
for a in "$@"; do
  case "$prev" in -o) out="$a";; --data-binary) data="${a#@}";; esac
  case "$a" in https://*) url="$a";; "If-None-Match: *") cond=1;; esac
  prev="$a"
done
if [ -n "${MOCK_CURL_FAIL_BODY:-}" ]; then
  # Like curl -o: the error body goes to the output file on ANY status.
  [ -n "$out" ] && printf '%s' "$MOCK_CURL_FAIL_BODY" > "$out"
  printf '500'
  exit 0
fi
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
  "meta-data set")
    # MOCK_META_SET_FAILS reproduces a Buildkite meta-data write failure.
    [ -n "${MOCK_META_SET_FAILS:-}" ] && { echo "mock: meta-data write failed" >&2; exit 1; }
    printf '%s' "$4" > "$MOCK_META/$3" ;;
  "artifact download")
    [ -n "${MOCK_DOWNLOAD_FAILS:-}" ] && { echo "mock: download failed" >&2; exit 1; }
    cp "$MOCK_ARTIFACTS/$3" . ;;
  "artifact upload")
    [ -n "${MOCK_UPLOAD_FAILS:-}" ] && { echo "mock: upload failed" >&2; exit 1; }
    cp "$3" "$MOCK_ARTIFACTS/" ;;
esac
MOCK
chmod +x "$WORK/bin/buildkite-agent"

# Mock bundle exec ruby fetcher: copies the fixture APK into place.
cat > "$WORK/bin/bundle" <<'MOCK'
#!/bin/bash
# Require `bundle install` before any `bundle exec` (checkout-local
# vendor/bundle must be prepared in THIS step, like the Play step).
if [ "$1" = install ]; then touch "$MOCK_BUNDLE_INSTALLED"; exit 0; fi
if [ "$1 $2" = "exec ruby" ]; then
  if [ ! -f "$MOCK_BUNDLE_INSTALLED" ]; then
    echo "FAIL: bundle exec without bundle install" >&2
    exit 1
  fi
  cp "$MOCK_APK" "$6"
fi
exit 0
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
export MOCK_CURL_ARGV="$WORK/curl-argv" MOCK_CURL_STDIN="$WORK/curl-stdin"
export MOCK_BUNDLE_INSTALLED="$WORK/bundle-installed"
mkdir -p "$MOCK_ARTIFACTS"
printf 'id' > "$MOCK_SECRETS/R2_APK_ACCESS_KEY_ID"
printf 'secret' > "$MOCK_SECRETS/R2_APK_SECRET_ACCESS_KEY"
export PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{}'
printf '9522' > "$MOCK_META/uploaded-version-code"

run_r2() { (cd "$WORK/repo" && ./.buildkite/commands/auris-r2-apk.sh >/dev/null 2>&1); }

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
grep -q 'user = "id:secret"' "$MOCK_CURL_STDIN" && r=ok || r=missing
check "expected credential arrives via stdin config" ok "$r"

# 1c. PUT failure with a canary in the R2 error body: fixed diagnostic only.
# Remove the versioned object first so the PUT actually runs (an existing
# identical object would take the 412 resume path and never exercise it).
rm -f "$MOCK_STORE/auris-download/apk/auris-8.20-9522.apk"
OUTC="$(cd "$WORK/repo" && MOCK_CURL_FAIL_BODY='<Error>canary-r2-secret-77</Error>' ./.buildkite/commands/auris-r2-apk.sh 2>&1)"; RCC=$?
check "PUT 500 fails the step" 1 "$RCC"
printf '%s' "$OUTC" | grep -q "HTTP 500" && r=ok || r=missing
check "PUT failure prints status" ok "$r"
printf '%s' "$OUTC" | grep -q "canary-r2-secret" && r=leaked || r=clean
check "R2 error-body canary stays out of logs" clean "$r"

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
OUT4="$(cd "$WORK/repo" && ./.buildkite/commands/auris-r2-apk.sh 2>&1)"; RC4=$?
check "older run exits cleanly" 0 "$RC4"
printf '%s' "$OUT4" | grep -q "leaving aliases untouched" && r=ok || r=noskip
check "skip path actually taken" ok "$r"
grep -q '"versionCode": 9600' "$MOCK_STORE/auris-download/apk/latest.json" && r=ok || r=moved
check "latest.json NOT moved backwards" ok "$r"
[ "$(cat "$MOCK_STORE/auris-download/apk/latest.apk")" = "newer-apk-bytes" ] && r=ok || r=moved
check "latest.apk NOT moved backwards" ok "$r"

# 4c. Malformed latest.json (listed + readable, but not valid JSON with an
# integer versionCode): fail closed, aliases untouched. Distinct from absent.
printf '{"versionCode": "not-a-number"}' > "$MOCK_STORE/auris-download/apk/latest.json"
run_r2; check "malformed latest.json fails closed" 1 "$?"
grep -q '"versionCode": "not-a-number"' "$MOCK_STORE/auris-download/apk/latest.json" && r=ok || r=overwritten
check "malformed case leaves aliases untouched" ok "$r"
printf 'not json at all' > "$MOCK_STORE/auris-download/apk/latest.json"
run_r2; check "non-JSON latest.json fails closed" 1 "$?"
printf '{"versionCode": true}' > "$MOCK_STORE/auris-download/apk/latest.json"
run_r2; check "boolean versionCode fails closed" 1 "$?"
printf '{"versionCode": 99999999999999999999}' > "$MOCK_STORE/auris-download/apk/latest.json"
run_r2; check "out-of-range versionCode fails closed" 1 "$?"
printf '{"versionCode": 0}' > "$MOCK_STORE/auris-download/apk/latest.json"
run_r2; check "zero versionCode fails closed" 1 "$?"
# restore sane state for later controls
printf '{"versionCode": 9522}' > "$MOCK_STORE/auris-download/apk/latest.json"

# 4b. A failed alias READ (not absence) must be fatal, never publish blind.
# Fail ONLY the listing (the retry byte-check cat must still work), and
# assert the listing-specific diagnostic.
OUTL="$(cd "$WORK/repo" && MOCK_RCLONE_LSF_FAIL=1 ./.buildkite/commands/auris-r2-apk.sh 2>&1)"; RCL=$?
check "failed alias listing is fatal" 1 "$RCL"
printf '%s' "$OUTL" | grep -q "could not list apk/" && r=ok || r=missing
check "listing failure prints its own diagnostic" ok "$r"

# 5. Play step records the CARRIED built version, not its checkout's base.
cp "$DIR/.buildkite/commands/auris-play-upload.sh" "$WORK/repo/.buildkite/commands/"
chmod +x "$WORK/repo/.buildkite/commands/"*.sh
cp "$DIR/.buildkite/commands/auris-verify-bundle-signature.sh" "$WORK/repo/.buildkite/commands/" 2>/dev/null || true
cat > "$WORK/repo/.buildkite/commands/auris-verify-bundle-signature.sh" <<'MOCK'
#!/bin/bash
exit 0
MOCK
mkdir -p "$WORK/repo/app/build/outputs/bundle/release"
printf 'aab' > "$WORK/repo/app/build/outputs/bundle/release/app-release.aab"
mkdir -p "$MOCK_ARTIFACTS/app/build/outputs/bundle/release"
printf 'aab' > "$MOCK_ARTIFACTS/app/build/outputs/bundle/release/app-release.aab"
# The carried version must come from the PRODUCER, not a hand-seeded fixture:
# seeding it here would prove only that the consumer reads a file, and would
# stay green if the release step stopped uploading it. Run the real release
# script into the same artifact store the consumer downloads from.
cp "$DIR/.buildkite/commands/auris-release-aab.sh" "$WORK/repo/.buildkite/commands/"
chmod +x "$WORK/repo/.buildkite/commands/"*.sh
(
  cd "$WORK/repo"
  # Producer fixture: the signing gate needs a real keystore; Gradle and the
  # signature verifier are stubbed because this control targets the artifact
  # hand-off, not signing (keystore-decode.test.sh owns that gate).
  keytool -genkeypair -keystore producer.keystore -alias upload -storepass testpass \
    -keypass testpass -dname "CN=PairControl" -keyalg RSA -keysize 2048 -validity 1 >/dev/null 2>&1
  printf 'org.gradle.jvmargs=-Xmx1g\n' > gradle.properties
  printf 'rootProject.name="x"\n' > settings.gradle.kts
  mkdir -p app
  cat > gradlew <<'PRODSTUB'
#!/bin/bash
exit 0
PRODSTUB
  chmod +x gradlew
  cat > .buildkite/commands/auris-verify-bundle-signature.sh <<'PRODSTUB'
#!/bin/bash
exit 0
PRODSTUB
  chmod +x .buildkite/commands/auris-verify-bundle-signature.sh
  # A DIFFERENT base from the consumer's checkout, so a consumer reading its
  # own version.properties cannot coincidentally produce the right answer.
  printf 'versionName=8.20\nversionCode=1000\n' > version.properties
  JAVA_HOME="$PAIR_JAVA_HOME" \
  ANDROID_UPLOAD_KEYSTORE_B64="$(openssl base64 -A < producer.keystore)" \
  ANDROID_UPLOAD_KEYSTORE_PASSWORD=testpass \
  ANDROID_UPLOAD_KEY_ALIAS=upload \
  ANDROID_UPLOAD_KEY_PASSWORD=testpass \
  AURIS_GOOGLE_SERVICES_JSON_B64="$(printf '{"package":"fm.auris"}' | openssl base64 -A)" \
  AURIS_SECRET_PROPERTIES="measureBuildsEnabled=false" \
  BUILDKITE_BUILD_NUMBER=8522 \
    ./.buildkite/commands/auris-release-aab.sh >/dev/null 2>&1
)
check "producer runs and carries its built version" 0 "$?"
# 1000 base + 8522 build number = 9522, produced by the real script.
check "producer uploaded built-version-code.txt to the artifact store" 9522 "$(cat "$MOCK_ARTIFACTS/built-version-code.txt" 2>/dev/null)"
# fastlane stub: success without touching anything.
cat > "$WORK/bin/fastlane-stub" <<'MOCK'
#!/bin/bash
exit 0
MOCK
# The play script calls `bundle exec fastlane supply`; our bundle mock handles
# ruby fetches -- extend it to succeed for fastlane too.
cat > "$WORK/bin/bundle" <<'MOCK'
#!/bin/bash
if [ "$1" = install ]; then touch "$MOCK_BUNDLE_INSTALLED"; exit 0; fi
if [ "$1 $2" = "exec ruby" ]; then
  [ -f "$MOCK_BUNDLE_INSTALLED" ] || { echo "FAIL: exec without install" >&2; exit 1; }
  cp "$MOCK_APK" "$6"
fi
exit 0
MOCK
chmod +x "$WORK/bin/bundle"
rm -f "$MOCK_META/uploaded-version-code"
# The consumer checks out its OWN copy: restore the base version so reading
# its own file would give 9452, not the carried 9522.
printf 'versionName=8.20\nversionCode=9452\n' > "$WORK/repo/version.properties"
rm -f "$WORK/repo/built-version-code.txt"
(cd "$WORK/repo" && PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}' \
  ./.buildkite/commands/auris-play-upload.sh >/dev/null 2>&1)
check "play step succeeds with carried version" 0 "$?"
check "recorded version is the BUILT one (9522), not checkout base (9452)" 9522 "$(cat "$MOCK_META/uploaded-version-code" 2>/dev/null)"

# 6. A missing/corrupt carried version must fail BEFORE the upload, not after.
# Play rejects a duplicate versionCode, so "upload then discover the version is
# bad" is unrecoverable: the release is live and every re-run fails on the
# duplicate. The fastlane stub records that it ran, so the assertion is on the
# irreversible ACTION, not on the exit code alone.
cat > "$WORK/bin/bundle" <<'MOCK'
#!/bin/bash
if [ "$1" = install ]; then touch "$MOCK_BUNDLE_INSTALLED"; exit 0; fi
if [ "$1 $2" = "exec fastlane" ]; then touch "$MOCK_FASTLANE_RAN"; exit 0; fi
if [ "$1 $2" = "exec ruby" ]; then
  [ -f "$MOCK_BUNDLE_INSTALLED" ] || { echo "FAIL: exec without install" >&2; exit 1; }
  cp "$MOCK_APK" "$6"
fi
exit 0
MOCK
chmod +x "$WORK/bin/bundle"
export MOCK_FASTLANE_RAN="$WORK/fastlane-ran"

for BAD in missing corrupt; do
  rm -f "$MOCK_FASTLANE_RAN" "$MOCK_META/uploaded-version-code"
  # A fresh checkout per case: the script downloads into its own cwd, so a
  # leftover file from the previous run would satisfy `head` and hide the defect.
  rm -f "$WORK/repo/built-version-code.txt"
  if [ "$BAD" = missing ]; then
    rm -f "$MOCK_ARTIFACTS/built-version-code.txt"
  else
    printf 'not-a-number\n' > "$MOCK_ARTIFACTS/built-version-code.txt"
  fi
  (cd "$WORK/repo" && PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}' \
    ./.buildkite/commands/auris-play-upload.sh >/dev/null 2>&1)
  check "$BAD carried version fails the step" 1 "$?"
  [ ! -f "$MOCK_FASTLANE_RAN" ] \
    && { PASS=$((PASS+1)); echo "ok: $BAD carried version never reaches fastlane (no irreversible upload)"; } \
    || { FAIL=$((FAIL+1)); echo "FAIL: $BAD carried version UPLOADED before failing"; }
done
# Restore a good carried version for any later case.
printf '9522\n' > "$MOCK_ARTIFACTS/built-version-code.txt"

# 7. A meta-data write failure AFTER a successful upload strands the release:
# Play has the bundle, R2 never runs (it needs uploaded-version-code), and a
# re-run cannot recover because Play rejects the duplicate versionCode. So the
# already-validated version must be recorded BEFORE the irreversible upload.
rm -f "$MOCK_FASTLANE_RAN" "$WORK/repo/built-version-code.txt"
printf '9522\n' > "$MOCK_ARTIFACTS/built-version-code.txt"
rm -f "$MOCK_META/uploaded-version-code"
(cd "$WORK/repo" && PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}' \
  MOCK_META_SET_FAILS=1 ./.buildkite/commands/auris-play-upload.sh >/dev/null 2>&1)
check "meta-data write failure fails the step" 1 "$?"
[ ! -f "$MOCK_FASTLANE_RAN" ] \
  && { PASS=$((PASS+1)); echo "ok: meta-data failure happens BEFORE any upload (release not stranded)"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL: uploaded to Play then failed to record the version -- unrecoverable"; }

# 8. A buildkite-agent failure must FAIL the step, not be walked past: a dropped
# artifact strands the NEXT step just as a dropped meta-data write strands this
# one. The scripts rely on their `#!/bin/bash -eu` shebang for this, which is why
# every invocation here runs them as EXECUTABLES -- `bash script` drops those
# flags and would test a laxer script than Buildkite runs.
rm -f "$MOCK_FASTLANE_RAN" "$WORK/repo/built-version-code.txt"
printf '9522\n' > "$MOCK_ARTIFACTS/built-version-code.txt"
(cd "$WORK/repo" && PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}' \
  MOCK_DOWNLOAD_FAILS=1 ./.buildkite/commands/auris-play-upload.sh >/dev/null 2>&1)
check "artifact download failure fails the Play step" 1 "$?"
[ ! -f "$MOCK_FASTLANE_RAN" ] \
  && { PASS=$((PASS+1)); echo "ok: download failure never reaches fastlane"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL: uploaded despite a failed artifact download"; }

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
