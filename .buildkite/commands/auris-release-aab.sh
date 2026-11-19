#!/bin/bash -eu

# Release AAB step for the auris-android pipeline. The pipeline YAML calls
# this; the work lives here because Buildkite interpolates `$` in pipeline
# YAML at upload time, which would destroy the shell variables below.

cd "$(dirname "$0")/../.."

# JVM caps: the repo's gradle.properties defaults (10 GB Gradle heap, 4 GB
# Kotlin compiler daemon, 8 workers) exceed an 8 GB agent. A properties file
# keeps the last value for a repeated key, so appending overrides without
# touching the repo's lines. Confirm the daemon `-Xmx` in `ps` on first run.
printf '\norg.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=1g -XX:+UseG1GC -XX:MaxGCPauseMillis=200 -XX:ReservedCodeCacheSize=512m\nkotlin.daemon.jvmargs=-Xmx1536m -XX:MaxMetaspaceSize=512m\norg.gradle.workers.max=4\n' >> gradle.properties
echo "--- effective caps in gradle.properties"
tail -n 3 gradle.properties

# Version code: continue the repo's counter with this build's number so every
# beta upload is strictly higher than the last.
BASE=$(sed -n 's/^versionCode=//p' version.properties)
NEW=$((BASE + BUILDKITE_BUILD_NUMBER))
sed -i '' "s/^versionCode=.*/versionCode=$NEW/" version.properties
echo "versionCode $BASE -> $NEW"

cleanup() {
  rm -f auris-upload.keystore secret.properties
}
trap cleanup EXIT

# Signing, only once the upload keystore exists as agent secrets.
if [ -n "${ANDROID_UPLOAD_KEYSTORE_B64:-}" ]; then
  echo "$ANDROID_UPLOAD_KEYSTORE_B64" | base64 --decode > auris-upload.keystore
  printf 'signingKeyStoreFile=%s\nsigningKeyStorePassword=%s\nsigningKeyAlias=%s\nsigningKeyPassword=%s\n' \
    "$PWD/auris-upload.keystore" \
    "$ANDROID_UPLOAD_KEYSTORE_PASSWORD" \
    "$ANDROID_UPLOAD_KEY_ALIAS" \
    "$ANDROID_UPLOAD_KEY_PASSWORD" > secret.properties
else
  echo "--- no keystore secret yet: building an unsigned release AAB (dry run)"
fi

timeout 3600 ./gradlew :app:bundleRelease --no-daemon

AAB=$(find app/build/outputs/bundle/release -name '*.aab' | head -1)
[ -n "$AAB" ] || { echo "no AAB produced"; exit 1; }
buildkite-agent artifact upload "$AAB"
