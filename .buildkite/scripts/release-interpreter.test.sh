#!/bin/bash
# Controls for the release steps' interpreter environment. The job's PATH decides which
# ruby and bundler run, and a stock macOS ruby is too old for this repo: the decode helper
# needs base64 as a default gem and fastlane needs the bundler that wrote Gemfile.lock.
# Both steps must ASSERT the pinned versions before using them and fail closed otherwise.
#
# One control per requirement, each driving the real script with a PATH that resolves the
# wrong interpreter -- not a stub of the check itself.
set -u
DIR="$(cd "$(dirname "$0")/../.." && pwd)"
PASS=0; FAIL=0
check_nonzero() { # name exit
  if [ "$2" != 0 ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (exit 0, want non-zero)"; fi
}
check_zero() {
  if [ "$2" = 0 ]; then PASS=$((PASS+1)); echo "ok: $1";
  else FAIL=$((FAIL+1)); echo "FAIL: $1 (exit $2, want 0)"; fi
}

RUBY_PIN="$(cat "$DIR/.ruby-version")"
BUNDLER_PIN="$(sed -n 's/^BUNDLED WITH$//p' "$DIR/Gemfile.lock" >/dev/null; awk '/^BUNDLED WITH/{getline; gsub(/ /,""); print}' "$DIR/Gemfile.lock")"
[ -n "$RUBY_PIN" ] || { echo "FAIL: no .ruby-version"; exit 1; }
[ -n "$BUNDLER_PIN" ] || { echo "FAIL: no BUNDLED WITH in Gemfile.lock"; exit 1; }
echo "pins from the repo: ruby $RUBY_PIN, bundler $BUNDLER_PIN"

SAVED_KEYTOOL="$(command -v keytool)"
WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
cd "$WORK"

keytool -genkeypair -keystore good.keystore -alias upload -storepass testpass \
  -keypass testpass -dname "CN=Control" -keyalg RSA -keysize 2048 -validity 1 >/dev/null 2>&1
GOOD_B64="$(openssl base64 -A < good.keystore)"
FIXTURE_JAVA_HOME="$(dirname "$(dirname "$SAVED_KEYTOOL")")"

mkdir -p app .buildkite/commands .buildkite/scripts bin
cp "$DIR/.buildkite/commands/auris-release-aab.sh" .buildkite/commands/
cp "$DIR/.buildkite/commands/auris-play-upload.sh" .buildkite/commands/
cp "$DIR/.buildkite/commands/auris-require-interpreter.sh" .buildkite/commands/
cp "$DIR/.ruby-version" "$DIR/Gemfile.lock" .
printf 'versionName=7.90\nversionCode=9452\n' > version.properties
printf 'org.gradle.jvmargs=-Xmx1g\n' > gradle.properties
printf 'rootProject.name="x"\n' > settings.gradle.kts
mkdir -p app/build/outputs/bundle/release
touch app/build/outputs/bundle/release/app-release.aab

cat > gradlew <<'STUB'
#!/bin/bash
echo reached > gradlew-reached
exit 0
STUB
chmod +x gradlew
cat > .buildkite/commands/auris-verify-bundle-signature.sh <<'STUB'
#!/bin/bash
exit 0
STUB
chmod +x .buildkite/commands/auris-verify-bundle-signature.sh
cat > bin/buildkite-agent <<'STUB'
#!/bin/bash
exit 0
STUB
chmod +x bin/buildkite-agent

# A recording bundle stub: `bundle -v` reports whatever the case pins, and install/exec
# record that they were REACHED, so a case can assert the gate ran before any gem work.
make_bundle() { # dir reported-version
  mkdir -p "$1"
  cat > "$1/bundle" <<STUB
#!/bin/bash
if [ "\$1" = "-v" ] || [ "\$1" = "--version" ]; then
  echo "Bundler version $2"
  exit 0
fi
echo "\$1" >> "$WORK/bundle-reached"
exit 0
STUB
  chmod +x "$1/bundle"
}

# Real ruby wrappers reporting a chosen version, so the gate sees a plausible interpreter
# rather than a missing one: a missing ruby fails for the wrong reason.
REAL_RUBY="$(command -v ruby)"
make_ruby() { # dir reported-version
  mkdir -p "$1"
  cat > "$1/ruby" <<STUB
#!/bin/bash
if [ "\$1" = "-v" ] || [ "\$1" = "--version" ]; then
  echo "ruby $2 (2023-03-30 revision 23d537de50) [universal.arm64e-darwin23]"
  exit 0
fi
exec "$REAL_RUBY" "\$@"
STUB
  chmod +x "$1/ruby"
}

make_ruby "$WORK/ruby-old" "2.6.10p210"
make_ruby "$WORK/ruby-good" "$RUBY_PIN"
make_bundle "$WORK/bundle-old" "1.17.2"
make_bundle "$WORK/bundle-good" "$BUNDLER_PIN"

export AURIS_GOOGLE_SERVICES_JSON_B64="$(printf '{"package":"fm.auris"}' | openssl base64 -A)"
export AURIS_SECRET_PROPERTIES=""
export ANDROID_UPLOAD_KEYSTORE_B64="$GOOD_B64"
export ANDROID_UPLOAD_KEYSTORE_PASSWORD=testpass
export ANDROID_UPLOAD_KEY_ALIAS=upload
export ANDROID_UPLOAD_KEY_PASSWORD=testpass
export BUILDKITE_BUILD_NUMBER=9452
export JAVA_HOME="$FIXTURE_JAVA_HOME"

aab() { # bin-path out-file
  rm -f gradlew-reached
  PATH="$1:$WORK/bin:$PATH" ./.buildkite/commands/auris-release-aab.sh > "$2" 2>&1
}

# 1. An old ruby on PATH must fail the AAB step BEFORE Gradle runs.
aab "$WORK/ruby-old:$WORK/bundle-good" out-aab-oldruby
check_nonzero "old ruby on PATH fails the AAB step" $?
grep -qi "ruby" out-aab-oldruby && { PASS=$((PASS+1)); echo "ok: the failure names ruby"; } || { FAIL=$((FAIL+1)); echo "FAIL: the failure does not name ruby"; }
[ -f gradlew-reached ] && { FAIL=$((FAIL+1)); echo "FAIL: Gradle ran despite the wrong ruby"; } || { PASS=$((PASS+1)); echo "ok: Gradle never ran with the wrong ruby"; }

# 2. Shims ahead but an OLD bundler: ruby is right, bundler is not. Must still fail.
aab "$WORK/ruby-good:$WORK/bundle-old" out-aab-oldbundler
check_nonzero "old bundler on PATH fails the AAB step even with the right ruby" $?
grep -qi "bundler" out-aab-oldbundler && { PASS=$((PASS+1)); echo "ok: the failure names bundler"; } || { FAIL=$((FAIL+1)); echo "FAIL: the failure does not name bundler"; }

# 3. Both pinned: the step proceeds to Gradle.
aab "$WORK/ruby-good:$WORK/bundle-good" out-aab-good
check_zero "pinned ruby and bundler let the AAB step proceed" $?
[ -f gradlew-reached ] && { PASS=$((PASS+1)); echo "ok: Gradle reached with the pinned pair"; } || { FAIL=$((FAIL+1)); echo "FAIL: Gradle not reached with the pinned pair"; }

# The Play step: same gate, and it must run before any gem work.
export PLAY_UPLOAD=1
export PLAY_SERVICE_ACCOUNT_JSON='{"type":"service_account"}'
play() { # bin-path out-file
  rm -f "$WORK/bundle-reached"
  PATH="$1:$WORK/bin:$PATH" ./.buildkite/commands/auris-play-upload.sh > "$2" 2>&1
}

# 4. Old ruby fails the upload step, and `bundle install` is never reached.
play "$WORK/ruby-old:$WORK/bundle-good" out-play-oldruby
check_nonzero "old ruby on PATH fails the Play upload step" $?
[ -f "$WORK/bundle-reached" ] && { FAIL=$((FAIL+1)); echo "FAIL: bundle ran despite the wrong ruby"; } || { PASS=$((PASS+1)); echo "ok: no gem work with the wrong ruby"; }

# 5. Old bundler fails the upload step before installing anything.
play "$WORK/ruby-good:$WORK/bundle-old" out-play-oldbundler
check_nonzero "old bundler on PATH fails the Play upload step" $?
[ -f "$WORK/bundle-reached" ] && { FAIL=$((FAIL+1)); echo "FAIL: bundle install ran with the wrong bundler"; } || { PASS=$((PASS+1)); echo "ok: no gem work with the wrong bundler"; }

# 6. Both pinned: the upload reaches bundler.
play "$WORK/ruby-good:$WORK/bundle-good" out-play-good
check_zero "pinned ruby and bundler let the Play upload proceed" $?
grep -q "^install$" "$WORK/bundle-reached" 2>/dev/null && { PASS=$((PASS+1)); echo "ok: bundle install reached with the pinned pair"; } || { FAIL=$((FAIL+1)); echo "FAIL: bundle install not reached with the pinned pair"; }

# 7. LOCALE BY VALUE, not by presence: an inherited non-UTF-8 locale must be replaced,
#    because Ruby raises on non-ASCII output under US-ASCII. Assert what the interpreter
#    SEES, not what the script exports.
cat > "$WORK/ruby-good/ruby" <<STUB
#!/bin/bash
if [ "\$1" = "-v" ] || [ "\$1" = "--version" ]; then
  echo "ruby $RUBY_PIN (2023-03-30 revision 23d537de50) [universal.arm64e-darwin23]"
  exit 0
fi
if [ "\$1" = "-rbase64" ]; then
  printf '%s|%s\n' "\${LANG:-unset}" "\${LC_ALL:-unset}" >> "$WORK/locale-seen"
fi
exec "$REAL_RUBY" "\$@"
STUB
chmod +x "$WORK/ruby-good/ruby"
rm -f "$WORK/locale-seen"
LANG=C LC_ALL=C aab "$WORK/ruby-good:$WORK/bundle-good" out-locale-c
check_zero "a C locale does not fail the step" $?
grep -qx "en_US.UTF-8|en_US.UTF-8" "$WORK/locale-seen" 2>/dev/null && { PASS=$((PASS+1)); echo "ok: an inherited C locale is overridden by value"; } || { FAIL=$((FAIL+1)); echo "FAIL: the interpreter still saw a non-UTF-8 locale ($(cat "$WORK/locale-seen" 2>/dev/null))"; }

# 8. An inherited UTF-8 locale is LEFT ALONE -- overriding it would discard a deliberate
#    choice (and a region), which is why the rule is by value and not unconditional.
rm -f "$WORK/locale-seen"
LANG=ja_JP.UTF-8 LC_ALL=ja_JP.UTF-8 aab "$WORK/ruby-good:$WORK/bundle-good" out-locale-utf8
check_zero "an inherited UTF-8 locale does not fail the step" $?
grep -qx "ja_JP.UTF-8|ja_JP.UTF-8" "$WORK/locale-seen" 2>/dev/null && { PASS=$((PASS+1)); echo "ok: an inherited UTF-8 locale is preserved"; } || { FAIL=$((FAIL+1)); echo "FAIL: a UTF-8 locale was overwritten ($(cat "$WORK/locale-seen" 2>/dev/null))"; }

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
