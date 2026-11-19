#!/bin/bash -eu

# Play internal-track upload for the auris-android pipeline. No-op while the
# service-account key does not exist: the pipeline stops at the signed AAB.

cd "$(dirname "$0")/../.."

cleanup() {
  rm -f google-upload-credentials.json
}
trap cleanup EXIT

if [ -z "${PLAY_SERVICE_ACCOUNT_JSON:-}" ]; then
  echo "--- PLAY_SERVICE_ACCOUNT_JSON not set: stopping at the signed AAB (dry run)"
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
