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
# Use the release step's local copy when this agent has one: it verifies the
# copy against the uploaded artifact's checksum (a metadata call, no bulk
# transfer) and copies it into place. A MISSING copy falls back to the artifact
# download, so a foreign agent still works. A copy that DISAGREES with the
# artifact refuses the step: bytes that mismatch the uploaded artifact mean
# something is wrong on this machine, and a download would mask it.
HANDOFF_DIR="${BUILDKITE_BUILD_PATH:+${BUILDKITE_BUILD_PATH%/}/aab-handoff-${BUILDKITE_BUILD_ID:-}}"
AAB_REL="app/build/outputs/bundle/release/app-release.aab"
if [ -n "$HANDOFF_DIR" ] && [ -f "$HANDOFF_DIR/app-release.aab" ]; then
  LOCAL_SHA="$(shasum -a 1 "$HANDOFF_DIR/app-release.aab" | awk '{print $1}')"
  ARTIFACT_SHA="$(buildkite-agent artifact shasum "$AAB_REL")"
  if [ "$LOCAL_SHA" != "$ARTIFACT_SHA" ]; then
    echo "FAIL: local AAB copy does not match the uploaded artifact (local sha1 $LOCAL_SHA, artifact sha1 $ARTIFACT_SHA); refusing to use or re-download it" >&2
    exit 1
  fi
  echo "--- Using the release step's local AAB copy (checksum verified)"
  mkdir -p "$(dirname "$AAB_REL")"
  cp "$HANDOFF_DIR/app-release.aab" "$AAB_REL"
else
  echo "--- No local AAB copy on this agent; downloading the artifact"
  buildkite-agent artifact download "$AAB_REL" .
fi

# Gate on the ARTIFACT's signature, not on which secrets this step can see:
# the AAB step already verified it, and the keystore may be scoped to that
# step only. Re-verify the downloaded bundle against the pinned upload cert.
AURIS_UPLOAD_CERT_SHA256="${AURIS_UPLOAD_CERT_SHA256:-16:89:BD:A2:5A:0B:47:DB:6D:F4:7F:B1:C2:E7:BD:E0:4F:5C:A3:C2:0D:E2:CA:91:AE:52:1A:DB:30:C5:B3:91}"
bash .buildkite/commands/auris-verify-bundle-signature.sh \
  app/build/outputs/bundle/release/app-release.aab \
  "$AURIS_UPLOAD_CERT_SHA256"

# Same gate as the AAB step, before any gem work: an old bundler can resolve a different
# gem set than Gemfile.lock and upload with it, which no later check would notice.
. .buildkite/commands/auris-require-interpreter.sh
auris_require_interpreter

# Validate the carried version BEFORE uploading, not after. Play rejects a
# duplicate versionCode, so a step that uploads and THEN discovers the carried
# version is missing or corrupt cannot be recovered by re-running: the release
# is already live and every retry fails on the duplicate. Fail before the
# irreversible action.
buildkite-agent artifact download built-version-code.txt .
UPLOADED_VC="$(head -1 built-version-code.txt | tr -d '[:space:]')"
case "$UPLOADED_VC" in
  ''|*[!0-9]*) echo "FAIL: carried built-version-code is not a number: '$UPLOADED_VC'" >&2; exit 1 ;;
esac

# Record the version BEFORE uploading, for the same reason it is validated
# before: if this write fails after `fastlane supply` succeeds, Play has the
# bundle, the R2 step never runs (it reads uploaded-version-code), and a re-run
# cannot recover because Play rejects the duplicate versionCode. Every step that
# can fail must precede the irreversible one.
buildkite-agent meta-data set uploaded-version-code "$UPLOADED_VC"

# NOT --quiet: bundler maps it to ui.level "warn", which silences every progress message
# ("Fetching gem metadata from ...", "Resolving dependencies...", "Fetching/Installing <gem>").
# A stalled fetch then prints nothing at all, so the log's last line is whatever ran before
# bundler and the step burns its whole timeout without saying where it stopped. Progress output
# is what makes a hang diagnosable; the step's own timeout still bounds a true hang.
bundle install
bundle exec fastlane supply \
  --package_name fm.auris \
  --track internal \
  --aab app/build/outputs/bundle/release/app-release.aab \
  --json_key google-upload-credentials.json

echo "--- Uploaded versionCode $UPLOADED_VC recorded for the R2 step"

# The handoff copy has served its purpose once the upload succeeded; delete it
# so the agent's disk does not accumulate ~130 MB per build. A step that failed
# earlier leaves it in place, so a manual retry finds it.
rm -rf "$HANDOFF_DIR"
