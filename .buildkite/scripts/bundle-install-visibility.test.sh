#!/bin/bash
# Controls for gem-install VISIBILITY in the two release steps that install gems.
#
# Why this exists: both steps ran `bundle install --quiet`. Bundler maps --quiet to
# ui.level "warn", which silences every progress message ("Fetching gem metadata from ...",
# "Resolving dependencies...", "Fetching <gem>", "Installing <gem>"). A stalled fetch then
# prints NOTHING, so the job log's last line is whatever the step printed before bundler
# started -- and the step burns its whole timeout without saying where it stopped.
#
# The controls drive the REAL step scripts with a bundle stub that behaves like bundler:
# silent when asked to be quiet, chatty otherwise. Each case asserts BOTH that the step
# still succeeds AND that the progress text reached the log. Success alone cannot see the
# defect; the log assertion is what distinguishes a visible install from a silent one.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PASS=0; FAIL=0
check_zero() {
  if [ "$2" = 0 ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (exit $2, want 0)"; fi
}
check_has() { # name file needle
  if grep -qF "$3" "$2" 2>/dev/null; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (no '$3' in $2)"; fi
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
mkdir -p "$WORK/bin" "$WORK/artifacts/app/build/outputs/bundle/release" "$WORK/meta" \
         "$WORK/repo/.buildkite/commands" "$WORK/repo/.buildkite/scripts" \
         "$WORK/repo/app/build/outputs/bundle/release" "$WORK/repo/pin-bin"
cd "$WORK/repo"

cp "$DIR/.buildkite/commands/auris-play-upload.sh" .buildkite/commands/
cp "$DIR/.buildkite/commands/auris-r2-apk.sh" .buildkite/commands/
cp "$DIR/.buildkite/commands/auris-require-interpreter.sh" .buildkite/commands/
# Buildkite runs these as EXECUTABLES: `bash script` would drop the `-eu` shebang flags and
# the fixture would test a laxer script than production runs.
chmod +x .buildkite/commands/*.sh
cp "$DIR/.ruby-version" "$DIR/Gemfile.lock" .
printf 'versionName=8.20\nversionCode=9452\n' > version.properties
printf 'aab' > app/build/outputs/bundle/release/app-release.aab
printf 'aab' > "$WORK/artifacts/app/build/outputs/bundle/release/app-release.aab"
printf '9452' > "$WORK/artifacts/built-version-code.txt"

# Signature verification and the APK signature pin have their own suites; stub them here so a
# failure in THIS suite can only be about install visibility.
cat > .buildkite/commands/auris-verify-bundle-signature.sh <<'STUB'
#!/bin/bash
exit 0
STUB
cat > .buildkite/scripts/verify-play-apk.sh <<'STUB'
#!/bin/bash
exit 0
STUB
chmod +x .buildkite/commands/auris-verify-bundle-signature.sh .buildkite/scripts/verify-play-apk.sh

# The interpreter gate must pass, or every case below fails for the wrong reason.
cat > pin-bin/ruby <<'STUB'
#!/bin/bash
if [ "$1" = "-v" ] || [ "$1" = "--version" ]; then
  echo "ruby $(cat .ruby-version) (fixture) [arm64-darwin]"; exit 0
fi
exec REAL_RUBY "$@"
STUB
sed -i.bak "s|REAL_RUBY|$(command -v ruby)|" pin-bin/ruby && rm -f pin-bin/ruby.bak
cat > pin-bin/bundle <<'STUB'
#!/bin/bash
if [ "$1" = "-v" ] || [ "$1" = "--version" ]; then
  echo "Bundler version $(awk '/^BUNDLED WITH/{getline; gsub(/[ \t]/,""); print}' Gemfile.lock)"; exit 0
fi
exec "$WORK/bin/bundle" "$@"
STUB
chmod +x pin-bin/ruby pin-bin/bundle

cat > "$WORK/bin/buildkite-agent" <<'STUB'
#!/bin/bash
case "$1 $2" in
  "artifact download") cp "$WORK/artifacts/$3" . ;;
  "meta-data get") cat "$WORK/meta/$3" 2>/dev/null ;;
  "meta-data set") printf '%s' "$4" > "$WORK/meta/$3" ;;
  "secret get") printf 'dummy' ;;
esac
STUB
chmod +x "$WORK/bin/buildkite-agent"

# The publish itself is exercised by r2-publish.test.sh; here it only has to SUCCEED so the
# step reaches its end. A failing PUT would make every case below fail for an unrelated reason.
cat > "$WORK/bin/curl" <<'STUB'
#!/bin/bash
for a in "$@"; do [ "$a" = "%{http_code}" ] && { printf '200'; exit 0; }; done
exit 0
STUB
cat > "$WORK/bin/rclone" <<'STUB'
#!/bin/bash
printf '%s\n' "${RCLONE_CA_CERT:-UNSET}" >> "$WORK/rclone-ca-seen"
case "$1" in
  lsf) exit 0 ;;
  cat) exit 4 ;;
  copyto) exit 0 ;;
esac
exit 0
STUB
chmod +x "$WORK/bin/curl" "$WORK/bin/rclone"

# A bundle stub that reproduces the ONE behaviour under test: --quiet silences progress.
# Everything else (exec ruby / exec fastlane) succeeds, so the steps run to completion.
cat > "$WORK/bin/bundle" <<'STUB'
#!/bin/bash
if [ "$1" = install ]; then
  shift
  for a in "$@"; do [ "$a" = "--quiet" ] && exit 0; done
  echo "Fetching gem metadata from https://rubygems.org/."
  echo "Resolving dependencies..."
  echo "Installing fastlane 2.238.0"
  exit 0
fi
# The R2 step fetches Play's APK through `bundle exec ruby <fetcher> <creds> <vc> <out> <pin>`.
# Stand in for the fetcher so the step can reach its later stages; the APK's own signature
# checks have their own suite.
if [ "$1 $2" = "exec ruby" ]; then printf 'fixture-apk' > "$6"; exit 0; fi
exit 0
STUB
chmod +x "$WORK/bin/bundle"

export WORK
export PATH="$WORK/repo/pin-bin:$WORK/bin:$PATH"
export PLAY_UPLOAD=1 PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}'

# The R2 step needs a readable CA bundle: on macOS Go ignores SSL_CERT_FILE, so the script
# passes it to rclone explicitly and fails closed when it is missing. Give the fixture a real
# file -- an empty one is enough, since these controls are about the variable reaching rclone,
# not about TLS itself (measured in-job by the runner lane).
printf 'fixture-ca-bundle\n' > "$WORK/ca.pem"
export SSL_CERT_FILE="$WORK/ca.pem"

# 1. Play upload step: gem-install progress must reach the job log.
./.buildkite/commands/auris-play-upload.sh > "$WORK/play.log" 2>&1
check_zero "Play upload step still succeeds" $?
check_has "Play step logs the gem source it is fetching" "$WORK/play.log" "Fetching gem metadata"
check_has "Play step logs dependency resolution" "$WORK/play.log" "Resolving dependencies"

# 2. R2 publish step: same requirement. Its `bundle install` prepares the checkout-local
#    bundle for the Play APK fetcher, and a stall there is just as invisible.
printf '9452' > "$WORK/meta/uploaded-version-code"
./.buildkite/commands/auris-r2-apk.sh > "$WORK/r2.log" 2>&1
check_zero "R2 publish step still succeeds" $?
check_has "R2 step logs the gem source it is fetching" "$WORK/r2.log" "Fetching gem metadata"
check_has "R2 step logs dependency resolution" "$WORK/r2.log" "Resolving dependencies"

# 3. The CA bundle must REACH rclone. Asserting the script exports a variable would pass even
#    if the export came after the calls, or named the wrong variable; record what the real
#    rclone process sees instead.
rm -f "$WORK/rclone-ca-seen"
./.buildkite/commands/auris-r2-apk.sh > "$WORK/r2-ca.log" 2>&1
check_zero "R2 step succeeds with a readable bundle" $?
if [ -s "$WORK/rclone-ca-seen" ] && ! grep -qx "UNSET" "$WORK/rclone-ca-seen"; then
  PASS=$((PASS+1)); echo "ok: every rclone call saw RCLONE_CA_CERT"
else
  FAIL=$((FAIL+1)); echo "FAIL: an rclone call ran without RCLONE_CA_CERT ($(cat "$WORK/rclone-ca-seen" 2>/dev/null | tr '\n' ' '))"
fi
check_has "rclone received the job's own bundle path" "$WORK/rclone-ca-seen" "$SSL_CERT_FILE"

# 4. Missing bundle: fail closed BEFORE any rclone call, with our message -- not an x509 error
#    from a later step. Unset and unreadable are different states and both must fail.
rm -f "$WORK/rclone-ca-seen"
env -u SSL_CERT_FILE ./.buildkite/commands/auris-r2-apk.sh > "$WORK/r2-noca.log" 2>&1
RC=$?
if [ "$RC" != 0 ]; then PASS=$((PASS+1)); echo "ok: unset SSL_CERT_FILE fails the step";
else FAIL=$((FAIL+1)); echo "FAIL: unset SSL_CERT_FILE did not fail the step"; fi
# Assert OUR diagnostic, not merely the variable's name: with the fail-closed block deleted,
# bash's own "unbound variable" abort also exits non-zero and also prints SSL_CERT_FILE, so a
# name-only assertion would pass while the guard was gone. (The rclone-ran assertion catches it
# too; this one makes the message itself load-bearing.)
check_has "the unset case prints our own diagnostic" "$WORK/r2-noca.log" "unset or unreadable"
if [ -f "$WORK/rclone-ca-seen" ]; then
  FAIL=$((FAIL+1)); echo "FAIL: rclone ran despite the missing bundle"
else PASS=$((PASS+1)); echo "ok: no rclone call before the bundle check"; fi

rm -f "$WORK/rclone-ca-seen"
SSL_CERT_FILE="$WORK/absent.pem" ./.buildkite/commands/auris-r2-apk.sh > "$WORK/r2-badca.log" 2>&1
RC=$?
if [ "$RC" != 0 ]; then PASS=$((PASS+1)); echo "ok: an unreadable bundle path fails the step";
else FAIL=$((FAIL+1)); echo "FAIL: an unreadable bundle path did not fail the step"; fi
check_has "the unreadable case prints our own diagnostic" "$WORK/r2-badca.log" "unset or unreadable"
if [ -f "$WORK/rclone-ca-seen" ]; then
  FAIL=$((FAIL+1)); echo "FAIL: rclone ran despite the unreadable bundle"
else PASS=$((PASS+1)); echo "ok: no rclone call with an unreadable bundle"; fi

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
