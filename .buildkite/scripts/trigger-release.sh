#!/bin/bash
# Create a Buildkite build for the release pipeline. Prints the API's error
# body on failure (curl -f alone hides it -- the first main trigger failed
# with a bare 422 and no reason). Env: BUILDKITE_API_TOKEN, BUILDKITE_ORG,
# BUILDKITE_PIPELINE, GITHUB_SHA. Optional BUILDKITE_API_BASE for tests.
#
# ignore_pipeline_branch_filters is REQUIRED here, not an optimisation. This
# pipeline deliberately keeps "Build branches" off so the GitHub job is the
# only automatic entry and unmerged code never reaches the signing secrets.
# That switch also refuses API-created branch builds: without this flag every
# main push got `422 Branches have been disabled for this pipeline` and no
# release ever ran. The flag declares a deliberate API-initiated build; the
# pipeline setting stays off.
set -euo pipefail

API_BASE="${BUILDKITE_API_BASE:-https://api.buildkite.com/v2}"
BODY_FILE="$(mktemp)"
trap 'rm -f "$BODY_FILE"' EXIT

HTTP_CODE=$(curl -sS -o "$BODY_FILE" -w '%{http_code}' -X POST \
  "${API_BASE}/organizations/${BUILDKITE_ORG}/pipelines/${BUILDKITE_PIPELINE}/builds" \
  -H "Authorization: Bearer ${BUILDKITE_API_TOKEN}" \
  -H "Content-Type: application/json" \
  -d "{\"commit\": \"${GITHUB_SHA}\", \"branch\": \"main\", \"message\": \"Release build for ${GITHUB_SHA}\", \"ignore_pipeline_branch_filters\": true}")

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
# Field names are response VALUES too -- an unknown key or errors[].field
# string can carry sensitive data. Only recognized names from a fixed
# allowlist are printed; everything else is counted, never echoed.
ALLOWED_TOP = {"message", "errors", "error", "id", "state", "number"}
ALLOWED_ERR_FIELDS = {"commit", "branch", "message", "env", "pipeline",
                      "organization", "base", "head"}
diag = None
shape = []
try:
    b = json.load(open(sys.argv[1]))
    if isinstance(b, dict):
        keys = set(b.keys())
        known = sorted(keys & ALLOWED_TOP)
        unknown = len(keys - ALLOWED_TOP)
        line = "fields: " + (", ".join(known) if known else "(none recognized)")
        if unknown:
            line += f" (+{unknown} unrecognized, withheld)"
        shape.append(line)
        errs = b.get("errors")
        if isinstance(errs, list):
            # Type-check BEFORE any set membership: an object/array field
            # value is unhashable and must not suppress recognized fields.
            raw = [e.get("field") for e in errs if isinstance(e, dict)]
            strs = [n for n in raw if isinstance(n, str)]
            known_f = sorted({n for n in strs if n in ALLOWED_ERR_FIELDS})
            unknown_f = len(raw) - len([n for n in strs if n in ALLOWED_ERR_FIELDS])
            if known_f or unknown_f:
                line = "error fields: " + (", ".join(known_f) if known_f else "(none recognized)")
                if unknown_f:
                    line += f" (+{unknown_f} unrecognized, withheld)"
                shape.append(line)
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
