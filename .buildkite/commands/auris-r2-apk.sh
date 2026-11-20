#!/bin/bash
set -euo pipefail

# Publish Play's signed universal APK to R2 (auris-download/apk/) for manual
# download. Runs ONLY after a successful Play upload on main with
# PLAY_UPLOAD=1; anything else is a no-op dry run. The APK comes from Play
# (generatedapks), so it carries Google's app-signing key and updates in place
# over a Play install -- never our upload-key-signed bundle.
#
# Inputs (Buildkite pipeline secrets, fetched in THIS step only):
#   R2_APK_ACCESS_KEY_ID / R2_APK_SECRET_ACCESS_KEY  (object R/W on auris-download)
#   PLAY_SERVICE_ACCOUNT_JSON                        (same account as the upload)
#   AURIS_PLAY_APP_SIGNING_SHA256                    (pin from Console App integrity)
#   uploaded-version-code meta-data                  (set by the Play step)
# rclone is installed on the mini; endpoint is not secret.

cd "$(dirname "$0")/../.."

R2_ENDPOINT="https://f8823ef2e78cfe210b4554177000e4da.r2.cloudflarestorage.com"
R2_BUCKET="auris-download"
PUBLIC_BASE="https://download.auris.fm"

if [ "${PLAY_UPLOAD:-0}" != "1" ]; then
  echo "--- PLAY_UPLOAD not 1: no Play upload happened, nothing to publish (dry run)"
  exit 0
fi

cleanup() {
  rm -f google-upload-credentials.json play-universal.apk play-universal.apk.part r2-apk.env
}
trap cleanup EXIT
umask 077

UPLOADED_VERSION_CODE="$(buildkite-agent meta-data get uploaded-version-code 2>/dev/null || true)"
for VAR in PLAY_SERVICE_ACCOUNT_JSON AURIS_PLAY_APP_SIGNING_SHA256 UPLOADED_VERSION_CODE; do
  if [ -z "${!VAR:-}" ]; then
    echo "--- PLAY_UPLOAD=1 but $VAR missing: cannot publish" >&2
    exit 1
  fi
done

R2_APK_ACCESS_KEY_ID="$(buildkite-agent secret get R2_APK_ACCESS_KEY_ID)"
R2_APK_SECRET_ACCESS_KEY="$(buildkite-agent secret get R2_APK_SECRET_ACCESS_KEY)"
if [ -z "$R2_APK_ACCESS_KEY_ID" ] || [ -z "$R2_APK_SECRET_ACCESS_KEY" ]; then
  echo "--- R2 credentials missing: cannot publish" >&2
  exit 1
fi

echo "$PLAY_SERVICE_ACCOUNT_JSON" > google-upload-credentials.json

echo "--- :android: Fetching Play's signed universal APK (versionCode $UPLOADED_VERSION_CODE)"
bundle exec ruby .buildkite/scripts/fetch-play-apk.rb \
  google-upload-credentials.json "$UPLOADED_VERSION_CODE" play-universal.apk

echo "--- Verifying Play signature pin + versionCode"
bash .buildkite/scripts/verify-play-apk.sh \
  play-universal.apk "$UPLOADED_VERSION_CODE" "$AURIS_PLAY_APP_SIGNING_SHA256"

VERSION_NAME="$(sed -n 's/^versionName=//p' version.properties | head -1)"
[ -n "$VERSION_NAME" ] || { echo "FAIL: no versionName in version.properties" >&2; exit 1; }
SHA256="$(shasum -a 256 play-universal.apk | awk '{print $1}')"
SIZE="$(stat -f%z play-universal.apk 2>/dev/null || stat -c%s play-universal.apk)"
VERSIONED_KEY="apk/auris-${VERSION_NAME}-${UPLOADED_VERSION_CODE}.apk"
URL="${PUBLIC_BASE}/${VERSIONED_KEY}"

# rclone reads its config from env; nothing written to disk.
export RCLONE_CONFIG_R2_TYPE=s3
export RCLONE_CONFIG_R2_PROVIDER=Cloudflare
export RCLONE_CONFIG_R2_ACCESS_KEY_ID="$R2_APK_ACCESS_KEY_ID"
export RCLONE_CONFIG_R2_SECRET_ACCESS_KEY="$R2_APK_SECRET_ACCESS_KEY"
export RCLONE_CONFIG_R2_ENDPOINT="$R2_ENDPOINT"
export RCLONE_CONFIG_R2_NO_CHECK_BUCKET=true

echo "--- Publishing to r2:${R2_BUCKET}/apk/"
# 1. Versioned file, immutable. NEVER overwrite an existing one.
if rclone lsf "r2:${R2_BUCKET}/${VERSIONED_KEY}" 2>/dev/null | grep -q .; then
  echo "FAIL: ${VERSIONED_KEY} already exists; refusing to overwrite a published version" >&2
  exit 1
fi
rclone copyto play-universal.apk "r2:${R2_BUCKET}/${VERSIONED_KEY}" \
  --header-upload "Content-Type: application/vnd.android.package-archive" \
  --header-upload "Cache-Control: public, max-age=31536000, immutable"

# 2. latest.json (short cache).
cat > latest.json <<JSON
{
  "versionName": "${VERSION_NAME}",
  "versionCode": ${UPLOADED_VERSION_CODE},
  "sha256": "${SHA256}",
  "size": ${SIZE},
  "url": "${URL}"
}
JSON
rclone copyto latest.json "r2:${R2_BUCKET}/apk/latest.json" \
  --header-upload "Content-Type: application/json" \
  --header-upload "Cache-Control: public, max-age=60"
rm -f latest.json

# 3. latest.apk LAST, so it never points at a half-published set.
rclone copyto play-universal.apk "r2:${R2_BUCKET}/apk/latest.apk" \
  --header-upload "Content-Type: application/vnd.android.package-archive" \
  --header-upload "Cache-Control: public, max-age=60"

echo "--- Published: ${URL}"
echo "    latest: ${PUBLIC_BASE}/apk/latest.apk"
