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
# OS picks the port; print it once bound so the caller never guesses.
srv = http.server.HTTPServer(('127.0.0.1', 0), H)
print(srv.server_address[1], flush=True)
srv.serve_forever()
PY

run_case() { # mode
  PORT_FILE="$MOCK_DIR/port"
  rm -f "$PORT_FILE"
  python3 "$MOCK_DIR/mock.py" "$1" > "$PORT_FILE" & MOCK_PID=$!
  # Poll until the mock prints its port and accepts connections (no fixed
  # ports, no blind sleep).
  PORT=
  for _ in $(seq 1 100); do
    PORT="$(cat "$PORT_FILE" 2>/dev/null)"
    # Portable readiness probe: TCP connect via python (the same interpreter
    # that runs the mock), not bash-only /dev/tcp. Any HTTP response means
    # the listener is up; only connection failure keeps us waiting.
    if [ -n "$PORT" ] && python3 -c "
import socket,sys
try:
    socket.create_connection(('127.0.0.1', int(sys.argv[1])), timeout=1).close()
except OSError:
    sys.exit(1)
" "$PORT" 2>/dev/null; then
      break
    fi
    PORT=
    sleep 0.1
  done
  if [ -z "$PORT" ]; then
    echo "FAIL: mock API ($1) never came up" >&2
    kill "$MOCK_PID" 2>/dev/null; wait "$MOCK_PID" 2>/dev/null; MOCK_PID=
    OUT="mock never started"; CODE=99
    return
  fi
  OUT=$(BUILDKITE_API_BASE="http://127.0.0.1:$PORT/v2" BUILDKITE_API_TOKEN=t \
    BUILDKITE_ORG=o BUILDKITE_PIPELINE=p GITHUB_SHA=abc123 \
    bash "$DIR/trigger-release.sh" 2>&1)
  CODE=$?
  kill "$MOCK_PID" 2>/dev/null; wait "$MOCK_PID" 2>/dev/null; MOCK_PID=
}

run_case ok
check "success prints build info" 0 "$CODE" "builds/70" "$OUT"

run_case err
check "422 fails" 1 "$CODE" "422" "$OUT"
check "422 prints API error body" 1 "$CODE" "Validation failed" "$OUT"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
