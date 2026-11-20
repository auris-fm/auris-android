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

# Every generated secret file goes on success AND failure: the keystore, the
# secret.properties this script writes from AURIS_SECRET_PROPERTIES with the
# signing passwords appended (signing is unconditional), and the Auris
# google-services.json. NOT configure_apply -- that path is deliberately
# gone (Automattic's secrets are for their package).
cleanup() {
  rm -f auris-upload.keystore secret.properties app/google-services.json
}
trap cleanup EXIT

echo "--- :closed_lock_with_key: Installing Auris secrets"
# NOT configure_apply: .configure-files holds Automattic's pocketcasts-android
# secrets (their Firebase config is for their package, and we have no key for
# it). The release takes Auris's OWN inputs from agent secrets:
#   AURIS_GOOGLE_SERVICES_JSON_B64  google-services.json for package fm.auris
#                                   (auris-fm Firebase project; the release app
#                                   must be registered there first)
#   AURIS_SECRET_PROPERTIES         the non-signing keys the release reads
#                                   (see PR 92's key inventory; most may be
#                                   empty for Auris)
# The ENFORCED inputs are the google-services JSON (missing -> fail here;
# the plugin would abort anyway) and the keystore below (signing is
# unconditional -- no unsigned path). AURIS_SECRET_PROPERTIES may be unset:
# it then writes an empty file and every non-signing key has a default.
# Owner-only permissions for every generated secret file (reviewer: umask 022
# would leave them 0644 while the build runs).
umask 077
if [ -n "${AURIS_GOOGLE_SERVICES_JSON_B64:-}" ]; then
  echo "$AURIS_GOOGLE_SERVICES_JSON_B64" | openssl base64 -d -A > app/google-services.json
else
  echo "--- AURIS_GOOGLE_SERVICES_JSON_B64 not set: the google-services plugin will abort" >&2
  exit 1
fi
printf '%s\n' "${AURIS_SECRET_PROPERTIES:-}" > secret.properties

# Signing is UNCONDITIONAL in this pipeline: one naming scheme,
# ANDROID_UPLOAD_KEYSTORE_B64 (+ _PASSWORD/_ALIAS/_KEY_PASSWORD), exactly
# what the mini's hook exports. #69 went green
# UNSIGNED because two naming schemes existed and neither side matched; a
# missing keystore now fails the step instead of falling back to unsigned.
# APPEND the signing keys to secret.properties so the non-signing keys survive.
if [ -z "${ANDROID_UPLOAD_KEYSTORE_B64:-}" ]; then
  echo "--- ANDROID_UPLOAD_KEYSTORE_B64 not set: this pipeline only builds signed releases" >&2
  exit 1
fi
echo "$ANDROID_UPLOAD_KEYSTORE_B64" | openssl base64 -d -A > auris-upload.keystore
printf 'signingKeyStoreFile=%s\nsigningKeyStorePassword=%s\nsigningKeyAlias=%s\nsigningKeyPassword=%s\n' \
  "$PWD/auris-upload.keystore" \
  "${ANDROID_UPLOAD_KEYSTORE_PASSWORD:-}" \
  "${ANDROID_UPLOAD_KEY_ALIAS:-}" \
  "${ANDROID_UPLOAD_KEY_PASSWORD:-}" >> secret.properties

# Two Automattic-internal defaults are switched off for the Auris queue, by
# appending to the checked-out settings file (last configuration wins, same
# pattern as the gradle.properties caps above; the repo's own lines stay):
# - remote build cache: the CI branch points at Automattic's internal
#   10.0.2.214; a later buildCache block with remote disabled replaces it.
# - build scans: the CI branch publishes unauthenticated to Gradle's public
#   server, which would expose our build environment; publishing forced off.
printf '\nbuildCache { remote<HttpBuildCache> { isEnabled = false } }\ndevelocity { buildScan { publishing.onlyIf { false } } }\n' >> settings.gradle.kts
echo "--- effective Auris CI overrides in settings.gradle.kts"
tail -n 2 settings.gradle.kts

# skipSentryProguardMappingUpload: CI=true enables the mapping upload, but the
# release secrets carry no Sentry token here; same flag as assemble-release-apk.sh.
./gradlew :app:bundleRelease --no-daemon -PskipSentryProguardMappingUpload=true

# Prove the outcome, not just the inputs: when signing was requested the
# bundle must carry a certificate, and it must be THE upload certificate.
# The pin is the public upload cert's SHA-256 (from upload-cert.pem, matches
# Google's upload-key reset); non-secret, so it lives here. An env override
# exists only for deliberate key rotation. Unsigned or wrongly signed fails.
AURIS_UPLOAD_CERT_SHA256="${AURIS_UPLOAD_CERT_SHA256:-16:89:BD:A2:5A:0B:47:DB:6D:F4:7F:B1:C2:E7:BD:E0:4F:5C:A3:C2:0D:E2:CA:91:AE:52:1A:DB:30:C5:B3:91}"
echo "--- Verifying bundle signature"
bash "$(dirname "$0")/auris-verify-bundle-signature.sh" \
  app/build/outputs/bundle/release/app-release.aab \
  "${AURIS_UPLOAD_CERT_SHA256:-}"

# Publish ONLY the verified bundle, and only after verification passed.
buildkite-agent artifact upload app/build/outputs/bundle/release/app-release.aab
