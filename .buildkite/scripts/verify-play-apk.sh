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
# With a key-rotation chain apksigner prints one certificate per signer,
# OLDEST first and the CURRENT (newest) signer LAST. The pin is the current
# app-signing key, so compare against the LAST reported digest. All digests
# are printed for the log.
# `|| true` on the pipeline: under `set -euo pipefail` a grep MISS aborts the
# whole script here, so the empty-check below never runs and the step reds with
# no diagnostic at all. The check IS the failure path; let it be reached.
# Diagnostic FIRST, comparison unchanged below. A real run failed this pin with the pinned key
# present but not last in the chain, and `--print-certs` alone does not say which signature scheme
# (v2/v3/v3.1) or SDK range each certificate belongs to -- so it cannot tell a rotation from a
# wrong-field read. Print the verbose form and the byte count BEFORE comparing, so a FAILING run
# still carries the evidence; printing only on success would be useless on the run we need to read.
echo "APK size: $(wc -c < "$APK" | tr -d ' ') bytes"
echo "--- apksigner verify --print-certs -v (schemes and SDK ranges)"
"$APKSIGNER" verify --print-certs -v "$APK" || echo "(verbose verify reported failure; see above)"

CERT_DIGESTS="$("$APKSIGNER" verify --print-certs "$APK" | grep 'certificate SHA-256 digest' | awk '{print $NF}' || true)"
[ -n "$CERT_DIGESTS" ] || { echo "FAIL: no signer certificate found" >&2; exit 1; }
echo "APK signer SHA-256 chain (oldest to newest):"
printf '  %s\n' $CERT_DIGESTS
ACTUAL="$(printf '%s\n' "$CERT_DIGESTS" | tail -1)"
norm() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]' | tr -d ':'; }
if [ "$(norm "$ACTUAL")" != "$(norm "$PLAY_SHA256")" ]; then
  echo "FAIL: APK's newest signer is NOT the pinned Play app-signing key" >&2
  echo "  expected: $PLAY_SHA256" >&2
  echo "  actual:   $ACTUAL" >&2
  exit 1
fi

# 2. versionCode must equal the bundle we just uploaded.
VC="$("$AAPT2" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
if [ "$VC" != "$EXPECTED_VC" ]; then
  echo "FAIL: APK versionCode $VC != uploaded bundle $EXPECTED_VC" >&2
  exit 1
fi
echo "OK: Play-signed APK verified (cert pin + versionCode $VC)"
