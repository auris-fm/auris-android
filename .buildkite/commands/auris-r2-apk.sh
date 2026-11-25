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
# run fails on this pin, nothing is published -- re-pin from Play Console's
# own certificate download, never from the APK and never by loosening the check.
AURIS_PLAY_APP_SIGNING_SHA256="${AURIS_PLAY_APP_SIGNING_SHA256:-8C:9F:5B:68:3E:BC:0B:F6:87:B6:5A:B7:EA:70:D6:F0:3D:DC:E1:6F:91:7C:49:66:F7:AC:D6:60:B0:0F:BD:E9}"

# Play's quantum-ready (v3.2) hybrid pair, applied at SDK 37+. These are DIFFERENT keys by design,
# so they cannot be compared against the app-signing pin above. Read from Play Console's
# "Download certificates" bundle (hybrid_classical_cert.der / hybrid_pqc_cert.der) and fingerprinted
# with `openssl x509 -inform DER -noout -fingerprint -sha256` -- Google's own certificates, never
# taken from a build's output. A rotation by Google fails this closed, which is correct: re-pin
# from the same download, not from an APK.
AURIS_PLAY_HYBRID_CLASSICAL_SHA256="${AURIS_PLAY_HYBRID_CLASSICAL_SHA256:-DD:DC:9D:F1:60:3B:E2:11:18:F3:13:3D:35:97:F6:75:47:A8:E4:BE:BA:FE:7D:C8:1F:29:F5:86:CF:9E:CC:A2}"
AURIS_PLAY_HYBRID_PQC_SHA256="${AURIS_PLAY_HYBRID_PQC_SHA256:-28:A1:74:6C:33:AA:1B:DE:14:0C:27:E6:1C:B1:B7:DF:37:97:6A:C7:A4:89:2F:FF:D5:CB:81:42:51:81:CE:AD}"
export AURIS_PLAY_HYBRID_CLASSICAL_SHA256 AURIS_PLAY_HYBRID_PQC_SHA256

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
# NOT --quiet: bundler maps it to ui.level "warn", which silences every progress message
# ("Fetching gem metadata from ...", "Resolving dependencies...", "Fetching/Installing <gem>").
# A stalled fetch then prints nothing at all, so the log's last line is whatever ran before
# bundler and the step burns its whole timeout without saying where it stopped. Progress output
# is what makes a hang diagnosable; the step's own timeout still bounds a true hang.
bundle install
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

# rclone is a Go program, and on macOS Go's crypto/x509 does NOT read SSL_CERT_FILE (its
# root_unix.go excludes darwin); it calls the system trust evaluator instead. That evaluator
# fails in this job -- the agent runs as a system daemon with no GUI session -- so every rclone
# call died with `x509: OSStatus -26276` even though the hook exports SSL_CERT_FILE into the job.
# Passing an explicit bundle makes rclone build its own root pool and skip that evaluator.
# Measured in-job: rclone with the bundle reaches R2, without it fails; curl succeeds either way,
# so the direct SigV4 PUT below needs nothing.
#
# ONE export, not a flag per call, so a later rclone call cannot miss it. The bundle comes from
# the job's own SSL_CERT_FILE -- never a hardcoded path, which would mask a runner where the hook
# did not run. Missing or unreadable => fail closed HERE, before any rclone call, with our own
# message rather than an x509 error three steps later.
if [ -z "${SSL_CERT_FILE:-}" ] || [ ! -r "${SSL_CERT_FILE}" ]; then
  echo "FAIL: SSL_CERT_FILE is unset or unreadable ('${SSL_CERT_FILE:-}'); rclone on macOS needs an" >&2
  echo "      explicit CA bundle (Go ignores SSL_CERT_FILE there). Nothing published." >&2
  exit 1
fi
export RCLONE_CA_CERT="${SSL_CERT_FILE}"

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
  # Ruby, not python3: the publish job already requires Ruby (bundle), and
  # python3 is not guaranteed on the runner's PATH. Strict integer check:
  # JSON true/false are not Integers in Ruby; range is Play's positive one.
  CURRENT_VC="$(printf '%s' "$ALIAS_JSON" | ruby -rjson -e '
begin
  vc = JSON.parse(STDIN.read)["versionCode"]
  raise "not an integer" unless vc.is_a?(Integer)
  raise "out of range" unless vc > 0 && vc <= 2100000000
  puts vc
rescue Exception
  exit 1
end
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
