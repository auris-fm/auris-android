#!/bin/bash -eu

# Play internal-track upload for the auris-android pipeline. The upload runs
# only when PLAY_UPLOAD=1 AND the service-account key exists; anything else is
# a dry run that stops at the signed AAB. PLAY_UPLOAD is set on the pipeline
# (default off) so the upload stays off during the upload-key reset window.

cd "$(dirname "$0")/../.."

cleanup() {
  rm -f google-upload-credentials.json
}
trap cleanup EXIT

if [ "${PLAY_UPLOAD:-0}" != "1" ]; then
  echo "--- PLAY_UPLOAD not set to 1: stopping at the signed AAB (dry run)"
  exit 0
fi

if [ -z "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]; then
  echo "--- PLAY_SERVICE_ACCOUNT_JSON not set: stopping at the signed AAB (dry run)"
  exit 0
fi

# Never upload an unsigned dry-run AAB: Play rejects it with a signature error.
if [ -z "${ANDROID_UPLOAD_KEYSTORE_B64:-}" ]; then
  echo "--- ANDROID_UPLOAD_KEYSTORE_B64 not set: the AAB is unsigned, stopping (dry run)"
  exit 0
fi

echo "$PLAY_SERVICE_ACCOUNT_JSON" > google-upload-credentials.json
buildkite-agent artifact download "app/build/outputs/bundle/release/*.aab" .
bundle install --quiet
bundle exec fastlane supply \
  --package_name fm.auris \
  --track internal \
  --aab app/build/outputs/bundle/release/*.aab \
  --json_key google-upload-credentials.json
