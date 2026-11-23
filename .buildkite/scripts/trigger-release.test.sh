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
# The posted payload is recorded so controls can assert what we SEND, not
# only how we handle what comes back. A trigger that posts the wrong body
# fails at the API with a 422 and never runs a build.
PAYLOAD_FILE = sys.argv[2]
class H(http.server.BaseHTTPRequestHandler):
    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        raw = self.rfile.read(length)
        with open(PAYLOAD_FILE, 'wb') as f:
            f.write(raw)
        if MODE == 'branches-disabled':
            # What auris-android actually returns: "Build branches" is off, so
            # an API branch build is refused WHOLESALE unless the request sets
            # ignore_pipeline_branch_filters. Bare message, no errors array --
            # a per-branch filter rejection would name the field instead.
            body = json.dumps({"message": "Branches have been disabled for this pipeline"}).encode()
            self.send_response(422)
        elif MODE == 'ok':
            # The response echoes pipeline env with a dummy secret: the
            # trigger must NEVER print it (allowlisted metadata only).
            body = json.dumps({"number": 70, "web_url": "https://buildkite.com/x/y/builds/70",
                               "env": {"DUMMY_SECRET": "canary-do-not-log-9f3e"}}).encode()
            self.send_response(201)
        else:
            if MODE == 'err':
                # Credential embedded INSIDE the message value: the fixed
                # diagnostic must not echo any of it.
                body = json.dumps({"message": "Validation failed: token canary-do-not-log-9f3e rejected",
                                   "env": {"DUMMY_SECRET": "canary-do-not-log-9f3e"}}).encode()
                self.send_response(422)
            elif MODE == 'shape':
                # Unknown message wording + errors array: shape (field names)
                # must print, values (canaries) must not.
                body = json.dumps({"message": "Something odd canary-do-not-log-9f3e",
                                   "canary-do-not-log-9f3e": "unknown-key-canary",
                                   "errors": [{"field": "commit", "code": "canary-do-not-log-9f3e"},
                                              {"field": "canary-do-not-log-9f3e", "message": "x"},
                                              {"field": {"structured": "canary-do-not-log-9f3e"}},
                                              {"field": "branch", "message": "canary-do-not-log-9f3e"}]}).encode()
                self.send_response(422)
            else:
                body = json.dumps({"message": {"nested": "canary-do-not-log-9f3e"}}).encode()
                self.send_response(500)
        self.send_header('Content-Type', 'application/json')
        self.end_headers()
        self.wfile.write(body)
    def log_message(self, *a): pass
# OS picks the port; print it once bound so the caller never guesses.
# bind_and_activate=False + explicit bind avoids HTTPServer.server_bind's
# getfqdn() reverse lookup, which can stall on some hosts.
class NoFqdnServer(http.server.HTTPServer):
    def server_bind(self):
        import socketserver
        socketserver.TCPServer.server_bind(self)
        self.server_name = '127.0.0.1'
        self.server_port = self.server_address[1]
srv = NoFqdnServer(('127.0.0.1', 0), H)
print(srv.server_address[1], flush=True)
srv.serve_forever()
PY

run_case() { # mode
  PORT_FILE="$MOCK_DIR/port"
  PAYLOAD="$MOCK_DIR/payload.json"
  rm -f "$PORT_FILE" "$PAYLOAD"
  python3 "$MOCK_DIR/mock.py" "$1" "$PAYLOAD" > "$PORT_FILE" & MOCK_PID=$!
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
if printf '%s' "$OUT" | grep -q "canary-do-not-log"; then
  FAIL=$((FAIL+1)); echo "FAIL: success log leaks echoed env secret"
else
  PASS=$((PASS+1)); echo "ok: echoed env secret stays out of the log"
fi

run_case err
check "422 fails" 1 "$CODE" "422" "$OUT"
check "422 prints fixed validation diagnostic" 1 "$CODE" "request validation failed" "$OUT"
if printf '%s' "$OUT" | grep -q "canary-do-not-log"; then
  FAIL=$((FAIL+1)); echo "FAIL: failure log leaks credential from message/env"
else
  PASS=$((PASS+1)); echo "ok: failure log keeps message/env credential out"
fi

run_case shape
check "unknown 422 fails" 1 "$CODE" "422" "$OUT"
check "shape prints recognized field names" 1 "$CODE" "fields: errors, message" "$OUT"
check "unknown top-level key counted not printed" 1 "$CODE" "+1 unrecognized, withheld" "$OUT"
check "shape prints recognized error fields" 1 "$CODE" "error fields: branch, commit" "$OUT"
check "unknown error fields counted not printed" 1 "$CODE" "+2 unrecognized, withheld" "$OUT"
if printf '%s' "$OUT" | grep -q "canary-do-not-log"; then
  FAIL=$((FAIL+1)); echo "FAIL: shape log leaks values"
else
  PASS=$((PASS+1)); echo "ok: shape prints names only, no values"
fi

run_case struct
check "structured error fails" 1 "$CODE" "500" "$OUT"
if printf '%s' "$OUT" | grep -q "canary-do-not-log"; then
  FAIL=$((FAIL+1)); echo "FAIL: structured-error log leaks nested credential"
else
  PASS=$((PASS+1)); echo "ok: structured error prints status only"
fi

# --- The posted payload -------------------------------------------------
# Every control above exercises the RESPONSE path. None of them looked at
# what we send, which is how a body missing ignore_pipeline_branch_filters
# shipped: the mock accepted anything, so the suite stayed green while every
# real main push got a 422 and no release ever ran.
run_case ok
payload_check() { # name python-expression-on-b
  if python3 -c "
import json,sys
b = json.load(open(sys.argv[1]))
sys.exit(0 if ($2) else 1)
" "$PAYLOAD" 2>/dev/null; then
    PASS=$((PASS+1)); echo "ok: $1"
  else
    FAIL=$((FAIL+1)); echo "FAIL: $1"
  fi
}
payload_check "posts the commit being released" "b.get('commit') == 'abc123'"
payload_check "posts branch main" "b.get('branch') == 'main'"
# The flag must be the JSON boolean true, not the string \"true\": Buildkite
# treats a non-boolean as absent and still refuses the branch build.
payload_check "posts ignore_pipeline_branch_filters as boolean true" \
  "b.get('ignore_pipeline_branch_filters') is True"

# The real failure this fixes, reproduced: a pipeline with \"Build branches\"
# off answers 422 with a bare message. Our diagnostic must name the cause
# without echoing response values.
run_case branches-disabled
check "branches-disabled 422 fails" 1 "$CODE" "422" "$OUT"
check "branches-disabled prints the branch diagnostic" 1 "$CODE" "branch rejected by the pipeline" "$OUT"

echo "$PASS passed, $FAIL failed"
[ "$FAIL" = 0 ]
