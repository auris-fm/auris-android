#!/bin/bash
# Verify a release AAB is signed, optionally with THE expected certificate.
# Usage: auris-verify-bundle-signature.sh <bundle.aab> [expected-sha256]
# expected-sha256: colon-separated hex (keytool format), case-insensitive.
# Fails on: missing bundle, unsigned bundle, jarsigner verification failure,
# fingerprint mismatch. Prints the actual fingerprint for the build log.
set -euo pipefail

BUNDLE="${1:?usage: auris-verify-bundle-signature.sh <bundle.aab> [expected-sha256]}"
EXPECTED="${2:-}"

if [ ! -f "$BUNDLE" ]; then
  echo "FAIL: bundle not found: $BUNDLE" >&2
  exit 1
fi

# jarsigner reads AABs (zip/jar signing), but exits 0 on an UNSIGNED jar
# ("jar is unsigned") -- the output text, not the exit code, decides.
VERIFY_OUT="$(jarsigner -verify "$BUNDLE" 2>&1)" || {
  echo "FAIL: jarsigner -verify errored on $BUNDLE" >&2
  echo "$VERIFY_OUT" >&2
  exit 1
}
if printf '%s' "$VERIFY_OUT" | grep -qi "unsigned"; then
  echo "FAIL: bundle is not signed: $BUNDLE" >&2
  exit 1
fi

# Extract the signer certificate's SHA-256 fingerprint.
ACTUAL="$(keytool -printcert -jarfile "$BUNDLE" | awk '/SHA256:/ {print $2; exit}')"
if [ -z "$ACTUAL" ]; then
  echo "FAIL: no SHA-256 fingerprint found in bundle certificate" >&2
  exit 1
fi
echo "Bundle signer SHA-256: $ACTUAL"

if [ -n "$EXPECTED" ]; then
  norm() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]' | tr -d ':'; }
  if [ "$(norm "$ACTUAL")" != "$(norm "$EXPECTED")" ]; then
    echo "FAIL: bundle signed with the WRONG certificate" >&2
    echo "  expected: $EXPECTED" >&2
    echo "  actual:   $ACTUAL" >&2
    exit 1
  fi
  echo "OK: bundle signed with the expected upload certificate"
else
  echo "OK: bundle is signed (no pinned fingerprint provided)"
fi
