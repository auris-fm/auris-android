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

# Play app-signing certificate pin (public; from Play Console's App signing
# page for fm.auris). Env override only for deliberate key rotation. If a real
# run fails on this pin, nothing is published -- correct the pin from the
# actual APK's certificate, never loosen the check.
AURIS_PLAY_APP_SIGNING_SHA256="${AURIS_PLAY_APP_SIGNING_SHA256:-8C:9F:5B:68:3E:BC:0B:F6:87:B6:5A:B7:EA:70:D6:F0:3D:DC:E1:6F:91:7C:49:66:F7:AC:D6:60:B0:0F:BD:E9}"

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
# This step has its own checkout; Bundler is configured for checkout-local
# vendor/bundle, so prepare the bundle here exactly as the Play step does.
bundle install --quiet
bundle exec ruby .buildkite/scripts/fetch-play-apk.rb \
  google-upload-credentials.json "$UPLOADED_VERSION_CODE" play-universal.apk \
  "$AURIS_PLAY_APP_SIGNING_SHA256"

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
# Publication is serialized across builds by the pipeline step's
# concurrency: 1 / concurrency_group (Buildkite runs one publish at a time).
#
# 1. Versioned file, immutable, via an ATOMIC create-only write: a direct
# SigV4-signed single-request PUT with If-None-Match: *. An APK fits in one
# request, so there is no multipart completion where the condition could be
# dropped (rclone's --header-upload is not guaranteed on the committing
# request). A retry after a partial publish resumes ONLY if the existing
# object is byte-identical.
COND_ERR="$(mktemp)"
publish_versioned() {
  # Credentials via a curl config on stdin (-K -), never process arguments:
  # the mini is shared and ps would expose --user values.
  HTTP_PUT="$(curl -sS -K - -o "$COND_ERR" -w '%{http_code}' -X PUT \
    --aws-sigv4 "aws:amz:auto:s3" \
    -H "If-None-Match: *" \
    -H "Content-Type: application/vnd.android.package-archive" \
    -H "Cache-Control: public, max-age=31536000, immutable" \
    --data-binary @play-universal.apk \
    "${R2_ENDPOINT}/${R2_BUCKET}/${VERSIONED_KEY}" \
    <<< "user = \"${R2_APK_ACCESS_KEY_ID}:${R2_APK_SECRET_ACCESS_KEY}\"")" || return 1
  case "$HTTP_PUT" in
    2??) return 0 ;;
    412)
      # Object exists. Resume only if byte-identical.
      EXISTING_SHA="$(rclone cat "r2:${R2_BUCKET}/${VERSIONED_KEY}" | shasum -a 256 | awk '{print $1}')" || return 1
      if [ "$EXISTING_SHA" = "$SHA256" ]; then
        echo "--- ${VERSIONED_KEY} already published with identical bytes; resuming aliases"
        return 0
      fi
      echo "FAIL: ${VERSIONED_KEY} exists with DIFFERENT bytes; refusing to overwrite" >&2
      return 1 ;;
    *)
      # Fixed diagnostic only: the R2 error body can echo request details
      # (including credential-derived strings). Never print it.
      echo "FAIL: create-only PUT returned HTTP $HTTP_PUT (response body withheld)" >&2
      return 1 ;;
  esac
}
publish_versioned || { rm -f "$COND_ERR"; exit 1; }
rm -f "$COND_ERR"

# Alias guard: an older run finishing late must not move latest.* backwards.
# Distinguish ABSENT (normal first publish) from a failed read via an
# explicit listing: lsf succeeds with empty output => absent; lsf itself
# failing => fatal, never publish aliases blind.
ALIAS_LIST="$(rclone lsf "r2:${R2_BUCKET}/apk/" --include "latest.json")" || {
  echo "FAIL: could not list apk/ to check latest.json; refusing to publish aliases blind" >&2
  exit 1
}
CURRENT_VC=""
if [ -n "$ALIAS_LIST" ]; then
  ALIAS_JSON="$(rclone cat "r2:${R2_BUCKET}/apk/latest.json")" || {
    echo "FAIL: latest.json listed but unreadable; refusing to publish aliases blind" >&2
    exit 1
  }
  # Fail closed on malformed metadata: a listed, readable latest.json that
  # is not valid JSON with an integer versionCode must STOP the publish,
  # never fall through and overwrite the aliases.
  CURRENT_VC="$(printf '%s' "$ALIAS_JSON" | python3 -c '
import json, sys
try:
    vc = json.load(sys.stdin)["versionCode"]
    # bool is a subclass of int in Python -- reject it explicitly. Play
    # version codes are positive and must fit the platform range; an
    # out-of-range value could break the Bash comparison and fall through.
    if isinstance(vc, bool) or not isinstance(vc, int):
        raise ValueError("versionCode not an integer")
    if not (0 < vc <= 2100000000):
        raise ValueError("versionCode out of range")
    print(vc)
except Exception:
    sys.exit(1)
')" || {
    echo "FAIL: latest.json is malformed (no integer versionCode); refusing to overwrite aliases" >&2
    exit 1
  }
fi
if [ -n "$CURRENT_VC" ] && [ "$CURRENT_VC" -gt "$UPLOADED_VERSION_CODE" ]; then
  echo "--- latest.json already at newer versionCode $CURRENT_VC; leaving aliases untouched"
  echo "--- Published: ${URL}"
  exit 0
fi

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
