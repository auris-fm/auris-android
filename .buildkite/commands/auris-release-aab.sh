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
# beta upload is strictly higher than the last. Portable in-place edit (BSD
# and GNU sed both accept -i.bak); no `timeout` wrapper -- the step's own
# 70-minute limit bounds the build, and stock macOS has no GNU timeout.
BASE=$(sed -n 's/^versionCode=//p' version.properties)
NEW=$((BASE + BUILDKITE_BUILD_NUMBER))
sed -i.bak "s/^versionCode=.*/versionCode=$NEW/" version.properties
rm -f version.properties.bak
echo "versionCode $BASE -> $NEW"

cleanup() {
  rm -f auris-upload.keystore
}
trap cleanup EXIT

echo "--- :closed_lock_with_key: Installing Secrets"
# configure_apply decrypts .configure-files into google-services.json (the
# google-services plugin aborts without it) and secret.properties (the
# non-signing secrets: sentryAuthToken, develocityToken, ...). It needs the
# repo's encryption key in the environment, as on the other CI queues.
bundle install --quiet
bundle exec fastlane run configure_apply

# Signing, only once the upload keystore exists as agent secrets. APPEND the
# signing keys to the secret.properties configure_apply wrote -- replacing it
# would drop the non-signing secrets.
if [ -n "${ANDROID_UPLOAD_KEYSTORE_B64:-}" ]; then
  echo "$ANDROID_UPLOAD_KEYSTORE_B64" | base64 --decode > auris-upload.keystore
  printf 'signingKeyStoreFile=%s\nsigningKeyStorePassword=%s\nsigningKeyAlias=%s\nsigningKeyPassword=%s\n' \
    "$PWD/auris-upload.keystore" \
    "$ANDROID_UPLOAD_KEYSTORE_PASSWORD" \
    "$ANDROID_UPLOAD_KEY_ALIAS" \
    "$ANDROID_UPLOAD_KEY_PASSWORD" >> secret.properties
else
  echo "--- no keystore secret yet: building an unsigned release AAB (dry run)"
fi

# skipSentryProguardMappingUpload: CI=true enables the mapping upload, but the
# release secrets carry no Sentry token here; same flag as assemble-release-apk.sh.
./gradlew :app:bundleRelease --no-daemon -PskipSentryProguardMappingUpload=true

AAB=$(find app/build/outputs/bundle/release -name '*.aab' | head -1)
[ -n "$AAB" ] || { echo "no AAB produced"; exit 1; }
buildkite-agent artifact upload "$AAB"
