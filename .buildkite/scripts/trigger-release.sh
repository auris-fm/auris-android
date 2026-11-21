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

# Never log the raw API response: a build object can echo pipeline env
# (including secrets). Success prints allowlisted metadata only; failure
# prints the API's validation message, which is the diagnostic we need.
if [ "$HTTP_CODE" -ge 200 ] && [ "$HTTP_CODE" -lt 300 ]; then
  python3 - "$BODY_FILE" "$HTTP_CODE" <<'PY'
import json, sys
try:
    b = json.load(open(sys.argv[1]))
except Exception:
    print(f"Buildkite build created (HTTP {sys.argv[2]}); response not JSON")
    sys.exit(0)
print(f"Buildkite build created (HTTP {sys.argv[2]}):")
for k in ("number", "web_url", "state", "commit", "branch"):
    if k in b:
        print(f"  {k}: {b[k]}")
PY
  exit 0
fi

# Fixed diagnostics only: an unchecked message/error value could itself
# carry credentials (or be a structured object). Known error strings get a
# fixed sentence; anything else gets the HTTP status plus the response's
# SHAPE (top-level field names and errors[].field names -- never values).
python3 - "$BODY_FILE" "$HTTP_CODE" <<'PY' >&2
import json, sys
code = sys.argv[2]
KNOWN = {
    "Validation failed": "request validation failed (commit/branch/message rejected)",
    "Unauthorized": "API token rejected",
    "Forbidden": "API token lacks permission for this pipeline",
    "Not Found": "organization or pipeline not found",
    "Unprocessable Entity": "request unprocessable (see field names below)",
    "No commit found": "commit not found on the repository",
    "Branch": "branch rejected by the pipeline",
}
diag = None
shape = []
try:
    b = json.load(open(sys.argv[1]))
    if isinstance(b, dict):
        shape.append("fields: " + ", ".join(sorted(b.keys())))
        errs = b.get("errors")
        if isinstance(errs, list):
            fields = sorted({e["field"] for e in errs
                             if isinstance(e, dict) and isinstance(e.get("field"), str)})
            if fields:
                shape.append("error fields: " + ", ".join(fields))
        msg = b.get("message")
        if isinstance(msg, str):
            for needle, fixed in KNOWN.items():
                if needle.lower() in msg.lower():
                    diag = fixed
                    break
except Exception:
    pass
if diag:
    print(f"Buildkite API returned HTTP {code}: {diag}")
else:
    print(f"Buildkite API returned HTTP {code} (response values withheld)")
for line in shape:
    print("  " + line)
PY
exit 1
