#!/bin/bash
# Verify Play's universal APK before publishing it for manual download:
#  1. signed, with the PINNED Play app-signing certificate SHA-256
#  2. versionCode matches the bundle just uploaded
# Usage: verify-play-apk.sh <apk> <expected-version-code> <play-app-signing-sha256>
# apksigner ships in the Android SDK build-tools; AAPT2 too.
set -euo pipefail

APK="${1:?usage: verify-play-apk.sh <apk> <expected-version-code> <play-sha256>}"
EXPECTED_VC="${2:?expected version code required}"
PLAY_SHA256="${3:?Play app-signing SHA-256 pin required}"

[ -f "$APK" ] || { echo "FAIL: APK not found: $APK" >&2; exit 1; }

BT="$(ls -d "${ANDROID_HOME:-$HOME/Library/Android/sdk}"/build-tools/* | sort -V | tail -1)"
APKSIGNER="$BT/apksigner"
AAPT2="$BT/aapt2"

# 1. Signature must VERIFY (apksigner verify exits non-zero on a bad or
# missing signature), then the certificate must match the pin.
if ! "$APKSIGNER" verify "$APK" >/dev/null 2>&1; then
  echo "FAIL: APK signature does not verify (unsigned or tampered): $APK" >&2
  exit 1
fi
CERT_LINE="$("$APKSIGNER" verify --print-certs "$APK" | grep 'SHA-256 digest' | head -1)" || {
  echo "FAIL: no signer certificate found" >&2; exit 1;
}
ACTUAL="$(printf '%s' "$CERT_LINE" | awk '{print $NF}')"
norm() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]' | tr -d ':'; }
echo "APK signer SHA-256: $ACTUAL"
if [ "$(norm "$ACTUAL")" != "$(norm "$PLAY_SHA256")" ]; then
  echo "FAIL: APK signed with the WRONG certificate (expected Play app-signing key)" >&2
  echo "  expected: $PLAY_SHA256" >&2
  exit 1
fi

# 2. versionCode must equal the bundle we just uploaded.
VC="$("$AAPT2" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
if [ "$VC" != "$EXPECTED_VC" ]; then
  echo "FAIL: APK versionCode $VC != uploaded bundle $EXPECTED_VC" >&2
  exit 1
fi
echo "OK: Play-signed APK verified (cert pin + versionCode $VC)"
