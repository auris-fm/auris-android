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
# Diagnostic FIRST: print the byte count and the verbose form (schemes and SDK ranges) BEFORE
# comparing, so a FAILING run still carries the evidence. The run we need to read is a failing one.
echo "APK size: $(wc -c < "$APK" | tr -d ' ') bytes"
echo "--- apksigner verify --print-certs -v (schemes and SDK ranges)"
"$APKSIGNER" verify --print-certs -v "$APK" || echo "(verbose verify reported failure; see above)"

# Select app-signing signers BY LABEL, never by position.
#
# `--print-certs` prints one block per signer AND one for the Source Stamp, and every block's
# digest line contains the same "certificate SHA-256 digest" substring. Taking the last match
# therefore picks the SOURCE STAMP, which is a provenance stamp and not an APK signing key at
# all -- that is what failed a correctly-signed Play APK. Ordering is not promised either:
# build-tools 37.0.0 prints labelled signers, 36.x prints "Signer #N", and a rotated APK lists
# newest FIRST. Position is meaningless; the label is the field.
#
# Labels seen in real output:
#   Signer #N ...                                  (build-tools 36.x)
#   Signer (minSdkVersion=.., maxSdkVersion=..) .. (rotated, 36.x)
#   V3.0 Signer: ...                               (37.0.0)
#   V3.2 Hybrid Classical Signer: (minSdk..) ...   (37.0.0, Play's quantum-ready pair)
#   V3.2 Hybrid PQC Signer: (minSdk..) ...
#   Source Stamp Signer: ...                       (NOT an app-signing key -- excluded)
# Anything else fails closed: a future tool renaming a line must not silently pass.
#
# `|| true` on the pipeline: under `set -euo pipefail` a grep MISS would abort here, so the
# empty-check below never runs and the step reds with no diagnostic. The check IS the failure path.
CERT_LINES="$("$APKSIGNER" verify --print-certs "$APK" | grep 'certificate SHA-256 digest' || true)"
[ -n "$CERT_LINES" ] || { echo "FAIL: no signer certificate found" >&2; exit 1; }

norm() { printf '%s' "$1" | tr '[:lower:]' '[:upper:]' | tr -d ':'; }
PLAY_NORM="$(norm "$PLAY_SHA256")"
CLASSICAL_NORM="$(norm "${AURIS_PLAY_HYBRID_CLASSICAL_SHA256:-}")"
PQC_NORM="$(norm "${AURIS_PLAY_HYBRID_PQC_SHA256:-}")"

# Which signer counts as CURRENT. Not a claim that lower ranges are unused: a device on API 35
# really does install under a maxSdkVersion=35 signer.
#
# Skipping the older-range signer is safe because `apksigner verify` has ALREADY validated the
# rotation lineage above -- the older certificate is signed over by the key that must equal
# Google's pin. The lineage, not an SDK assumption, is what makes the exemption sound.
#
# This only affects signers that STATE a range. On real Play output the V3.0 app-signing line
# carries no SDK label at all, so it is always compared regardless of this value -- the threshold
# is machinery for a future rotation, not something protecting today's APK. Only the v3.2 hybrids
# state a range, and they are handled by their own labels above.
#
# For a labelled signer the dangerous direction is RAISING the threshold past its range, which
# would skip a signer that should be compared; lowering it compares more, not fewer. A rotation
# splitting at SDK 37 leaves the old key at maxSdkVersion=36, which is not below 36, so it fails
# closed until deliberately re-pinned -- correct behaviour.
CURRENT_DEVICE_SDK="${AURIS_CURRENT_DEVICE_SDK:-36}"

APP_FOUND=0
echo "APK signing certificates by label:"
while IFS= read -r line; do
  [ -n "$line" ] || continue
  label="${line%%certificate SHA-256 digest*}"
  digest="$(printf '%s' "$line" | awk '{print $NF}')"
  printf '  %s-> %s\n' "$label" "$digest"
  case "$label" in
    "Source Stamp Signer"*)
      continue ;;                             # provenance stamp, not a signing key
    "V3.2 Hybrid Classical Signer"*)
      [ -n "$CLASSICAL_NORM" ] || { echo "FAIL: hybrid classical signer present but no pin configured" >&2; exit 1; }
      [ "$(norm "$digest")" = "$CLASSICAL_NORM" ] || {
        echo "FAIL: hybrid classical signer is not Google's published key" >&2
        echo "  expected: $AURIS_PLAY_HYBRID_CLASSICAL_SHA256" >&2
        echo "  actual:   $digest" >&2; exit 1; }
      continue ;;
    "V3.2 Hybrid PQC Signer"*)
      [ -n "$PQC_NORM" ] || { echo "FAIL: hybrid PQC signer present but no pin configured" >&2; exit 1; }
      [ "$(norm "$digest")" = "$PQC_NORM" ] || {
        echo "FAIL: hybrid PQC signer is not Google's published key" >&2
        echo "  expected: $AURIS_PLAY_HYBRID_PQC_SHA256" >&2
        echo "  actual:   $digest" >&2; exit 1; }
      continue ;;
    "Signer #"*|"Signer ("*|"V3.0 Signer"*|"V2 Signer"*|"V3.1 Signer"*)
      # An app-signing signer. After a real rotation the lineage keeps the old certificate for
      # older devices, so requiring EVERY signer to equal one pin would make the check
      # permanently unpublishable -- no pin value satisfies both ends of the lineage. The
      # current-range signer must equal the pin; an older-range one is reported, not compared,
      # because `apksigner verify` has already validated the lineage tying it to that pin.
      maxsdk="$(printf '%s' "$label" | sed -n 's/.*maxSdkVersion=\([0-9]*\).*/\1/p')"
      if [ -n "$maxsdk" ] && [ "$maxsdk" -lt "$CURRENT_DEVICE_SDK" ]; then
        echo "  (older-range signer, superseded above SDK $maxsdk: not the install-governing key)"
        continue
      fi
      APP_FOUND=1
      [ "$(norm "$digest")" = "$PLAY_NORM" ] || {
        echo "FAIL: app-signing signer is NOT the pinned Play app-signing key" >&2
        echo "  label:    $label" >&2
        echo "  expected: $PLAY_SHA256" >&2
        echo "  actual:   $digest" >&2; exit 1; }
      continue ;;
    *)
      echo "FAIL: unrecognised signer label, refusing to guess: $label" >&2
      exit 1 ;;
  esac
done <<< "$CERT_LINES"

[ "$APP_FOUND" = 1 ] || { echo "FAIL: no app-signing signer found (only a source stamp?)" >&2; exit 1; }

# 2. versionCode must equal the bundle we just uploaded.
VC="$("$AAPT2" dump badging "$APK" | sed -n "s/.*versionCode='\([0-9]*\)'.*/\1/p" | head -1)"
if [ "$VC" != "$EXPECTED_VC" ]; then
  echo "FAIL: APK versionCode $VC != uploaded bundle $EXPECTED_VC" >&2
  exit 1
fi
echo "OK: Play-signed APK verified (cert pin + versionCode $VC)"
