#!/bin/bash
# Controls for the local AAB handoff between the release step and the Play
# upload step. The release step keeps a per-build copy of the AAB on the agent
# (keyed by BUILDKITE_BUILD_ID under BUILDKITE_BUILD_PATH); the Play step uses
# that copy after checking it against the uploaded artifact's checksum, and
# falls back to the artifact download ONLY when the copy is missing. A copy
# that disagrees with the artifact refuses the step: something is wrong, and
# downloading would mask it. This is what makes publishing independent of the
# agent's bulk-download speed -- a 129 MB re-download of a file the machine
# just built. Drives the real scripts with the same fixture style as
# keystore-decode.test.sh and bundle-install-visibility.test.sh.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PASS=0; FAIL=0
check() {
  if [ "$2" = "$3" ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (exit $3 want $2)"; fi
}
has() { # name file needle
  if grep -qF "$3" "$2" 2>/dev/null; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (no '$3' in $2)"; fi
}
absent() { # name file needle
  if grep -qF "$3" "$2" 2>/dev/null; then FAIL=$((FAIL+1)); echo "FAIL: $1 ('$3' present in $2)";
  else PASS=$((PASS+1)); echo "ok: $1"; fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
export WORK
# A real throwaway keystore: the decode gate validates the decoded shape, so
# the fixture must give it bytes that decode to a working keystore.
keytool -genkeypair -keystore "$WORK/good.keystore" -alias upload -storepass testpass \
  -keypass testpass -dname "CN=Control" -keyalg RSA -keysize 2048 -validity 1 >/dev/null 2>&1
HANDOFF_ROOT="$WORK/build-path"
mkdir -p "$HANDOFF_ROOT"
BUILD_ID="test-build-id"

# ---------- fixture: the release (producer) step ----------
cd "$WORK"
mkdir -p repo && cd repo
mkdir -p app .buildkite/commands .buildkite/scripts
cp "$DIR/.buildkite/commands/auris-release-aab.sh" .buildkite/commands/
cp "$DIR/.buildkite/scripts/native-deps.sh" .buildkite/scripts/
mkdir -p native-trees/llama native-trees/oboe
cat > .buildkite/scripts/native-deps.sh <<'NATIVESTUB'
#!/bin/bash
printf 'FETCHCONTENT_SOURCE_DIR_LLAMA=%s/native-trees/llama\n' "$PWD"
printf 'FETCHCONTENT_SOURCE_DIR_OBOE=%s/native-trees/oboe\n' "$PWD"
NATIVESTUB
chmod +x .buildkite/scripts/native-deps.sh
# Buildkite runs these as EXECUTABLES: `bash script` would drop the `-eu` shebang flags.
chmod +x .buildkite/commands/auris-release-aab.sh
cp "$DIR/.buildkite/commands/auris-require-interpreter.sh" .buildkite/commands/
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
cat > gradlew <<'STUB'
#!/bin/bash
# Produce a NON-empty "bundle": the handoff must copy real bytes, not a stub.
printf 'fixture-aab-bytes' > app/build/outputs/bundle/release/app-release.aab
printf 'produced' > built-version-code.txt
exit 0
STUB
chmod +x gradlew
mkdir -p bin
cat > bin/buildkite-agent <<STUB
#!/bin/bash
if [ "\$1 \$2" = "artifact upload" ]; then
  # The artifact store: a plain directory, so the consumer fixture can offer
  # the uploaded artifact for shasum/download exactly as Buildkite would.
  mkdir -p "$WORK/artifact-store/\$(dirname "\$3")"
  cp "\$3" "$WORK/artifact-store/\$3"
  printf '%s\n' "\$3" >> "$WORK/artifact-uploads"
fi
exit 0
STUB
chmod +x bin/buildkite-agent
cat > .buildkite/commands/auris-verify-bundle-signature.sh <<'STUB'
#!/bin/bash
set -euo pipefail
exit 0
STUB
chmod +x .buildkite/commands/auris-verify-bundle-signature.sh
mkdir -p .buildkite/scripts
cat > .buildkite/scripts/verify-bundle-signature.sh <<'STUB'
#!/bin/bash
exit 0
STUB
mkdir -p app/build/outputs/bundle/release
export PATH="$WORK/repo/pin-bin:$WORK/repo/bin:$PATH"
export AURIS_GOOGLE_SERVICES_JSON_B64="$(printf '{"package":"fm.auris"}' | openssl base64 -A)"
export AURIS_SECRET_PROPERTIES="measureBuildsEnabled=false"
export ANDROID_UPLOAD_KEYSTORE_PASSWORD=testpass
export ANDROID_UPLOAD_KEY_ALIAS=upload
export ANDROID_UPLOAD_KEY_PASSWORD=testpass
# CI jobs set their own JAVA_HOME; pin the fixture to the real JDK so the
# decode gate's keytool exists (keystore-decode.test.sh does the same).
export JAVA_HOME="$(dirname "$(dirname "$(command -v keytool)")")"
export ANDROID_UPLOAD_KEYSTORE_B64="$(openssl base64 -A < "$WORK/good.keystore")"
export BUILDKITE_BUILD_PATH="$HANDOFF_ROOT"
export BUILDKITE_BUILD_ID="$BUILD_ID"
export BUILDKITE_BUILD_NUMBER=9452

# The signature verifier stub must not reject the fixture AAB; the step calls
# it with a pinned cert it can't match, so stub at the invoked path (done above)
# and simply run the release step end to end.
rm -f "$WORK/artifact-uploads"
# A stale handoff from an old build: the prune must remove it, and keep fresh ones.
OLD_DIR="$HANDOFF_ROOT/aab-handoff-old-build"
mkdir -p "$OLD_DIR"
printf 'stale' > "$OLD_DIR/app-release.aab"
touch -t 202011220000 "$OLD_DIR/app-release.aab" "$OLD_DIR"
./.buildkite/commands/auris-release-aab.sh > "$WORK/release.log" 2>&1
check "release step succeeds" 0 "$?"

# 1. Producer side: the per-build copy exists and holds the built bytes.
HANDOFF_DIR="$HANDOFF_ROOT/aab-handoff-$BUILD_ID"
if [ -f "$HANDOFF_DIR/app-release.aab" ]; then
  PASS=$((PASS+1)); echo "ok: release step leaves a per-build AAB copy"
else
  FAIL=$((FAIL+1)); echo "FAIL: no per-build AAB copy at $HANDOFF_DIR"
fi
if cmp -s app/build/outputs/bundle/release/app-release.aab "$HANDOFF_DIR/app-release.aab" 2>/dev/null; then
  PASS=$((PASS+1)); echo "ok: the copy holds the built bytes"
else
  FAIL=$((FAIL+1)); echo "FAIL: the copy differs from the built AAB"
fi
if [ -f "$HANDOFF_DIR/built-version-code.txt" ]; then
  PASS=$((PASS+1)); echo "ok: the carried version rides in the same copy"
else
  FAIL=$((FAIL+1)); echo "FAIL: no built-version-code.txt in the handoff"
fi

# 2. Prune: a handoff older than the retention window goes; this build's stays.
if [ ! -d "$OLD_DIR" ]; then
  PASS=$((PASS+1)); echo "ok: stale handoff copies are pruned"
else
  FAIL=$((FAIL+1)); echo "FAIL: stale handoff $OLD_DIR survived the prune"
fi
[ -d "$HANDOFF_DIR" ] && { PASS=$((PASS+1)); echo "ok: the fresh copy survives the prune"; } \
  || { FAIL=$((FAIL+1)); echo "FAIL: the prune removed the current build's copy"; }

# ---------- fixture: the Play upload (consumer) step ----------
cd "$WORK"
rm -rf play && mkdir play && cd play
mkdir -p .buildkite/commands .buildkite/scripts bin
cp "$DIR/.buildkite/commands/auris-play-upload.sh" .buildkite/commands/
cp "$DIR/.buildkite/commands/auris-require-interpreter.sh" .buildkite/commands/
chmod +x .buildkite/commands/auris-play-upload.sh
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
# NOT --quiet matters to bundle-install-visibility.test.sh; here the stub must
# RUN `bundle exec <cmd>` (fastlane below), or the step greens without ever
# uploading and every handoff assertion passes for the wrong reason.
if [ "$1" = exec ]; then
  shift
  exec "$@"
fi
exit 0
STUB
chmod +x pin-bin/bundle
cat > .buildkite/commands/auris-verify-bundle-signature.sh <<'STUB'
#!/bin/bash
set -euo pipefail
exit 0
STUB
chmod +x .buildkite/commands/auris-verify-bundle-signature.sh
# fastlane succeeds so failures below are only about the handoff.
cat > pin-bin/fastlane <<'STUB'
#!/bin/bash
printf 'uploaded\n' >> "$WORK/supply-ran"
exit 0
STUB
chmod +x pin-bin/fastlane
AAB_REL="app/build/outputs/bundle/release/app-release.aab"
cat > bin/buildkite-agent <<STUB
#!/bin/bash
case "\$1 \$2" in
  "artifact shasum")
    # Real `buildkite-agent artifact shasum` prints the artifact's SHA-1.
    f="$WORK/artifact-store/\$3"
    [ -f "\$f" ] && shasum -a 1 "\$f" | awk '{print \$1}'
    printf '%s\n' "\$3" >> "$WORK/shasum-calls"
    ;;
  "artifact download")
    printf '%s\n' "\$3" >> "$WORK/downloads"
    mkdir -p "\$(dirname "\$3")"
    cp "$WORK/artifact-store/\$3" "\$3"
    ;;
  "meta-data set") printf '%s\n' "\$4" >> "$WORK/meta-writes" ;;
esac
exit 0
STUB
chmod +x bin/buildkite-agent
export PATH="$WORK/play/pin-bin:$WORK/play/bin:$PATH"
export PLAY_UPLOAD=1
export PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}'
export BUILDKITE_BUILD_PATH="$HANDOFF_ROOT"
export BUILDKITE_BUILD_ID="$BUILD_ID"
run_play() {
  rm -f "$WORK/supply-ran" "$WORK/downloads" "$WORK/shasum-calls" "$WORK/meta-writes"
  (cd "$WORK/play" && ./.buildkite/commands/auris-play-upload.sh)
}

# 3. Copy present and matching: the step must USE it -- no download of the AAB.
run_play > "$WORK/play-copy.log" 2>&1
check "play step succeeds from the local copy" 0 "$?"
if grep -qx "$AAB_REL" "$WORK/downloads" 2>/dev/null; then
  FAIL=$((FAIL+1)); echo "FAIL: play step downloaded the AAB although a verified copy existed"
else
  PASS=$((PASS+1)); echo "ok: no AAB download when a matching local copy exists"
fi
has "the copy was checksum-verified against the artifact" "$WORK/shasum-calls" "$AAB_REL"
has "the verified bundle reached supply" "$WORK/supply-ran" "uploaded"
has "the version was validated and recorded" "$WORK/meta-writes" "18904"

# 4. Success cleans up: the per-build copy is gone, so the disk cannot fill.
if [ ! -d "$HANDOFF_DIR" ]; then
  PASS=$((PASS+1)); echo "ok: successful play step deletes the handoff copy"
else
  FAIL=$((FAIL+1)); echo "FAIL: handoff copy survived a successful play step"
fi

# 5. Copy missing: fall back to the artifact download (existing behaviour).
run_play > "$WORK/play-fallback.log" 2>&1
check "play step falls back when the copy is missing" 0 "$?"
if grep -qx "$AAB_REL" "$WORK/downloads" 2>/dev/null; then
  PASS=$((PASS+1)); echo "ok: the missing copy falls back to the artifact download"
else
  FAIL=$((FAIL+1)); echo "FAIL: no download despite the copy being absent"
fi

# 6. Copy present but WRONG bytes: refuse. No download, no upload -- a local
#    file that disagrees with the uploaded artifact means something is broken,
#    and silently downloading would mask it.
mkdir -p "$HANDOFF_DIR"
printf 'tampered' > "$HANDOFF_DIR/app-release.aab"
run_play > "$WORK/play-mismatch.log" 2>&1
RC=$?
if [ "$RC" != 0 ]; then PASS=$((PASS+1)); echo "ok: a checksum mismatch fails the step";
else FAIL=$((FAIL+1)); echo "FAIL: a tampered copy did not fail the step"; fi
has "the mismatch names the problem" "$WORK/play-mismatch.log" "does not match"
if grep -qx "$AAB_REL" "$WORK/downloads" 2>/dev/null; then
  FAIL=$((FAIL+1)); echo "FAIL: a tampered copy fell back to the download"
else
  PASS=$((PASS+1)); echo "ok: a checksum mismatch does NOT fall back to the download"
fi
absent "a tampered copy never reaches supply" "$WORK/supply-ran" "uploaded"

# 7b. No BUILDKITE_* env at all: the step must not abort on an unbound variable
# and must fall back to the download -- an agent without the vars still works.
mkdir -p "$WORK/play-noenv/.buildkite/commands"
cd "$WORK/play-noenv"
cp "$WORK/play/.buildkite/commands/auris-play-upload.sh" \
   "$WORK/play/.buildkite/commands/auris-require-interpreter.sh" \
   "$WORK/play/.buildkite/commands/auris-verify-bundle-signature.sh" \
   .buildkite/commands/
chmod +x .buildkite/commands/auris-play-upload.sh .buildkite/commands/auris-verify-bundle-signature.sh
cp "$WORK/play/.ruby-version" "$WORK/play/Gemfile.lock" .
cp -R "$WORK/play/pin-bin" .
cp -R "$WORK/play/bin" .
chmod +x pin-bin/* bin/*
(env -u BUILDKITE_BUILD_PATH -u BUILDKITE_BUILD_ID PATH="$PWD/pin-bin:$PWD/bin:$PATH" \
   PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}' \
   ./.buildkite/commands/auris-play-upload.sh > "$WORK/play-noenv.log" 2>&1)
check "a play step without BUILDKITE_* env still succeeds (download fallback)" 0 "$?"
has "it says it is downloading" "$WORK/play-noenv.log" "No local AAB copy"

# 7c. A FAILING `rm` after the upload must not fail the step: the bundle is
# already on Play, and a cleanup failure must cost disk, never the release.
# A fresh, MATCHING copy: case 6 left tampered bytes, and this case is about
# the cleanup, not the checksum gate.
mkdir -p "$HANDOFF_DIR"
printf 'fixture-aab-bytes' > "$HANDOFF_DIR/app-release.aab"
printf '18904\n' > "$HANDOFF_DIR/built-version-code.txt"
mkdir -p "$WORK/play-noenv/.buildkite/commands"
cd "$WORK/play-noenv"
cat > bin/rm <<'STUB'
#!/bin/bash
for a in "$@"; do case "$a" in *aab-handoff*) echo "mock: rm failed" >&2; exit 1;; esac; done
exec /bin/rm "$@"
STUB
chmod +x bin/rm
cp "$WORK/artifact-store/built-version-code.txt" . 2>/dev/null
(PATH="$PWD/pin-bin:$PWD/bin:$PATH" \
   PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}' \
   BUILDKITE_BUILD_PATH="$HANDOFF_ROOT" BUILDKITE_BUILD_ID="$BUILD_ID" \
   ./.buildkite/commands/auris-play-upload.sh > "$WORK/play-rmfails.log" 2>&1)
check "a failing cleanup rm does not fail the step" 0 "$?"
has "the failure name says it was the upload succeeding" "$WORK/play-rmfails.log" "recorded for the R2 step"
rm -f bin/rm

# 7. A FAILED play step keeps the copy, so a manual retry can still use it.
cd "$WORK/play"
printf 'fixture-aab-bytes' > "$HANDOFF_DIR/app-release.aab"
cat > pin-bin/fastlane <<'STUB'
#!/bin/bash
echo "mock: supply failed" >&2
exit 1
STUB
chmod +x pin-bin/fastlane
run_play > "$WORK/play-failsupply.log" 2>&1
check "play step fails when supply fails" 1 "$?"
if [ -f "$HANDOFF_DIR/app-release.aab" ]; then
  PASS=$((PASS+1)); echo "ok: a failed play step keeps the copy for a retry"
else
  FAIL=$((FAIL+1)); echo "FAIL: a failed play step deleted the copy a retry would need"
fi

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
