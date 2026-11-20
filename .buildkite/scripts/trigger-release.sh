#!/bin/bash
# Create a Buildkite build for the release pipeline. Prints the API's error
# body on failure (curl -f alone hides it -- the first main trigger failed
# with a bare 422 and no reason). Env: BUILDKITE_API_TOKEN, BUILDKITE_ORG,
# BUILDKITE_PIPELINE, GITHUB_SHA. Optional BUILDKITE_API_BASE for tests.
set -euo pipefail

API_BASE="${BUILDKITE_API_BASE:-https://api.buildkite.com/v2}"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

HTTP_CODE=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' -X POST \
  "${API_BASE}/organizations/${BUILDKITE_ORG}/pipelines/${BUILDKITE_PIPELINE}/builds" \
  -H "Authorization: Bearer ${BUILDKITE_API_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"commit\": \"${GITHUB_SHA}\", \"branch\": \"main\", \"message\": \"Release build for ${GITHUB_SHA}\"}")

if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
  echo "Buildkite build created (HTTP $HTTP_CODE):"
  cat "$BODY_FILE"
  exit 0
fi

echo "Buildkite API returned HTTP $HTTP_CODE; response body:" >&2
cat "$BODY_FILE" >&2
exit 1
