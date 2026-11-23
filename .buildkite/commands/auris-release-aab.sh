#!/bin/bash -eu

# Release AAB step for the auris-android pipeline. The pipeline YAML calls
# this; the work lives here because Buildkite interpolates `$` in pipeline
# YAML at upload time, which would destroy the shell variables below.

cd "$(dirname "$0")/../.."

# Assert the pinned interpreter before anything uses it: the base64 decode below runs ruby,
# and a stock macOS ruby is too old for this repo. Fails closed rather than decoding with
# whatever PATH happens to resolve.
. .buildkite/commands/auris-require-interpreter.sh
auris_require_interpreter

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

# Newline-proof decode (#71): the hook may export wrapped base64, and
# LibreSSL's `openssl base64 -d -A` silently outputs ZERO bytes (exit 0)
# on multiline input. Strip ALL whitespace, decode with Ruby (the release
# job already requires it via bundle; Base64.decode64 ignores line breaks
# anyway -- never whatever openssl PATH finds), refuse empty.
auris_b64_decode() {
  tr -d '\n\r \t' | ruby -rbase64 -e 'STDOUT.write Base64.decode64(STDIN.read)'
}
if [ -n "${AURIS_GOOGLE_SERVICES_JSON_B64:-}" ]; then
  printf '%s' "$AURIS_GOOGLE_SERVICES_JSON_B64" | auris_b64_decode > app/google-services.json
  [ -s app/google-services.json ] || { echo "--- decoded google-services JSON is empty (bad base64 transport)" >&2; exit 1; }
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
printf '%s' "$ANDROID_UPLOAD_KEYSTORE_B64" | auris_b64_decode > auris-upload.keystore
[ -s auris-upload.keystore ] || { echo "--- decoded keystore is empty (bad base64 transport)" >&2; exit 1; }
# Validate the decoded keystore BEFORE Gradle: a still-encoded, double-encoded
# or truncated secret otherwise fails 10 minutes later inside signReleaseBundle
# (#70). keytool -list opens it with the store password; no contents printed.
# Pin the toolchain: a bare `keytool` resolves via PATH and may be Apple's
# stub or a Homebrew JDK rather than JAVA_HOME's Temurin -- an older JDK
# cannot decrypt PBES2/AES-256 PKCS12 and fails exactly like a bad file.
KEYTOOL="${JAVA_HOME:+$JAVA_HOME/bin/}keytool"
if ! ANDROID_UPLOAD_KEYSTORE_PASSWORD="${ANDROID_UPLOAD_KEYSTORE_PASSWORD:-}" \
    "$KEYTOOL" -list -keystore auris-upload.keystore \
    -storepass:env ANDROID_UPLOAD_KEYSTORE_PASSWORD >/dev/null 2>&1; then
  echo "--- decoded keystore is not a valid keystore (still encoded, double-encoded, truncated, or wrong store password)" >&2
  exit 1
fi
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

# Native dependencies BEFORE gradle: CMake would otherwise clone llama.cpp and oboe once per ABI
# at configure time, from inside the checkout, with no cache and no bound on a stalled transfer.
# The helper puts one tree per pin outside the checkout and hands back the source-dir overrides;
# passing them as Gradle cmake arguments makes every ABI reuse those trees and clone nothing.
echo "--- :package: Preparing native dependencies"
NATIVE_EXPORTS="$(mktemp)"
bash .buildkite/scripts/native-deps.sh > "$NATIVE_EXPORTS"
NATIVE_CMAKE_ARGS=()
while IFS= read -r line; do
  [ -n "$line" ] || continue
  NATIVE_CMAKE_ARGS+=("-D${line%%=*}=${line#*=}")
done < "$NATIVE_EXPORTS"
rm -f "$NATIVE_EXPORTS"
# Gradle reads cmake arguments from the module's build file, so they are passed as a project
# property the build appends; see modules/services/voice/build.gradle.kts.
AURIS_NATIVE_CMAKE_ARGS="$(printf '%s ' "${NATIVE_CMAKE_ARGS[@]}")"
export AURIS_NATIVE_CMAKE_ARGS
echo "--- native cmake args: $AURIS_NATIVE_CMAKE_ARGS"

# skipSentryProguardMappingUpload: CI=true enables the mapping upload, but the
# release secrets carry no Sentry token here; same flag as assemble-release-apk.sh.
# --stacktrace: #70's "Tag number over 30" had no stack, so the failing
# parser was unidentifiable. A failed run must say exactly where it threw.
./gradlew :app:bundleRelease --no-daemon --stacktrace -PskipSentryProguardMappingUpload=true

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

# Carry the BUILT version with the artifact: each step checks out its own
# copy of version.properties, so a later step must never read its own base
# version as "what was built". The Play step validates and records this value
# BEFORE it uploads, so no fallible step follows the irreversible one.
BUILT_VC="$(sed -n 's/^versionCode=//p' version.properties | head -1)"
[ -n "$BUILT_VC" ] || { echo "FAIL: no versionCode after build" >&2; exit 1; }
printf '%s\n' "$BUILT_VC" > built-version-code.txt
buildkite-agent artifact upload built-version-code.txt
