#!/bin/bash
# Controls for trigger-release.sh against a local mock API.
set -u
DIR="$(cd "$(dirname "$0")" && pwd)"
PASS=0; FAIL=0
check() { # name expected_exit actual_exit output_expected_substring output
  if [ "$2" = "$3" ] && printf '%s' "$5" | grep -q "$4"; then
    PASS=$((PASS+1)); echo "ok: $1"
  else
    FAIL=$((FAIL+1)); echo "FAIL: $1 (exit $3 want $2; output: $5)"
  fi
}

MOCK_DIR="$(mktemp -d)"
trap 'rm -rf "$MOCK_DIR"; [ -n "${MOCK_PID:-}" ] && kill "$MOCK_PID" 2>/dev/null' EXIT

cat > "$MOCK_DIR/mock.py" <<'PY'
import http.server, sys, json
MODE = sys.argv[1]
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        self.rfile.read(length)
        if MODE == 'ok':
            body = json.dumps({"number": 70, "web_url": "https://buildkite.com/x/y/builds/70"}).encode()
            self.send_response(201)
        else:
            body = json.dumps({"message": "Validation failed: branch is not allowed"}).encode()
            self.send_response(422)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass
http.server.HTTPServer(('127.0.0.1', int(sys.argv[2])), H).serve_forever()
PY

run_case() { # mode port
  python3 "$MOCK_DIR/mock.py" "$1" "$2" & MOCK_PID=$!
  sleep 0.5
  OUT=$(BUILDKITE_API_BASE="http://127.0.0.1:$2/v2" BUILDKITE_API_TOKEN=t \
    BUILDKITE_ORG=o BUILDKITE_PIPELINE=p GITHUB_SHA=abc123 \
    bash "$DIR/trigger-release.sh" 2>&1)
  CODE=$?
  kill "$MOCK_PID" 2>/dev/null; wait "$MOCK_PID" 2>/dev/null; MOCK_PID=
}

run_case ok 18711
check "success prints build info" 0 "$CODE" "builds/70" "$OUT"

run_case err 18712
check "422 fails" 1 "$CODE" "422" "$OUT"
check "422 prints API error body" 1 "$CODE" "Validation failed" "$OUT"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
