#!/bin/bash -eu

# Play internal-track upload for the auris-android pipeline. Only
# PLAY_UPLOAD != 1 is a dry run that stops at the signed AAB. With
# PLAY_UPLOAD=1 the step UPLOADS: a missing service-account key fails it, and
# the downloaded bundle must verify against the pinned upload certificate.
# PLAY_UPLOAD is set on the pipeline (default off) so the upload stays off
# during the upload-key reset window.

cd "$(dirname "$0")/../.."

cleanup() {
  rm -f google-upload-credentials.json
}
trap cleanup EXIT

if [ "${PLAY_UPLOAD:-0}" != "1" ]; then
  echo "--- PLAY_UPLOAD not set to 1: stopping at the signed AAB (dry run)"
  exit 0
fi

# PLAY_UPLOAD=1 means UPLOAD: a missing input fails the step, never a silent
# green no-op. Only PLAY_UPLOAD != 1 is a dry run.
if [ -z "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]; then
  echo "--- PLAY_UPLOAD=1 but PLAY_SERVICE_ACCOUNT_JSON not set: cannot upload" >&2
  exit 1
fi

# Owner-only permissions for the service-account key.
umask 077
echo "$PLAY_SERVICE_ACCOUNT_JSON" > google-upload-credentials.json
buildkite-agent artifact download "app/build/outputs/bundle/release/app-release.aab" .

# Gate on the ARTIFACT's signature, not on which secrets this step can see:
# the AAB step already verified it, and the keystore may be scoped to that
# step only. Re-verify the downloaded bundle against the pinned upload cert.
AURIS_UPLOAD_CERT_SHA256="${AURIS_UPLOAD_CERT_SHA256:-16:89:BD:A2:5A:0B:47:DB:6D:F4:7F:B1:C2:E7:BD:E0:4F:5C:A3:C2:0D:E2:CA:91:AE:52:1A:DB:30:C5:B3:91}"
bash .buildkite/commands/auris-verify-bundle-signature.sh \
  app/build/outputs/bundle/release/app-release.aab \
  "$AURIS_UPLOAD_CERT_SHA256"
bundle install --quiet
bundle exec fastlane supply \
  --package_name fm.auris \
  --track internal \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --json_key google-upload-credentials.json

# Record what was uploaded so the R2 step fetches exactly this version's
# Play-signed APK. The version comes from the artifact the BUILD step
# carried (built-version-code.txt), NOT this checkout's version.properties:
# a fresh checkout still has the base version, not the bumped one.
buildkite-agent artifact download built-version-code.txt .
UPLOADED_VC="$(head -1 built-version-code.txt | tr -d '[:space:]')"
case "$UPLOADED_VC" in
  ''|*[!0-9]*) echo "FAIL: carried built-version-code is not a number: '$UPLOADED_VC'" >&2; exit 1 ;;
esac
buildkite-agent meta-data set uploaded-version-code "$UPLOADED_VC"
echo "--- Uploaded versionCode $UPLOADED_VC recorded for the R2 step"
