#!/bin/bash
# Prefetch the android-all jar Robolectric loads at class-init time, so a Maven Central
# blip cannot fail a test job that never ran a test body.
#
# THE CACHE IS THE POINT. An unconditional download makes a restored cache worthless:
# `curl --fail` still aborts during an outage even when every file is already on disk,
# which is exactly the scenario this exists for. So each file is validated first and
# only fetched when it is missing or corrupt. With a warm cache this script performs
# NO network I/O at all.
#
# The version is DERIVED, not hand-pinned: it comes from the Robolectric artifact on the
# test classpath and the SDK the suite runs at. A bump that changes either fails loudly
# here rather than leaving the job green while Robolectric quietly fetches over the network.
set -euo pipefail

ROBOLECTRIC_VERSION="${ROBOLECTRIC_VERSION:?set by the caller from the resolved dependency}"
ANDROID_SDK="${ANDROID_SDK:?the SDK the unit tests run at}"
M2="${M2_REPO:-$HOME/.m2/repository}"

# Robolectric names the pre-instrumented jar <androidVersion>-robolectric-<build>-i7.
# The build number lives in DefaultSdkProvider inside the robolectric jar; reading it from
# the artifact actually on the classpath is what keeps this honest across a bump.
ROBO_JAR="${ROBOLECTRIC_JAR:?path to the resolved org.robolectric:robolectric jar}"
[ -f "$ROBO_JAR" ] || { echo "robolectric jar not found: $ROBO_JAR" >&2; exit 1; }

# Each SDK entry pushes its API level, then loads androidVersion, build number and "REL".
# Collecting the numeric string constants in order and taking the last two before that
# entry's REL gives both halves without depending on constant-pool indices.
DERIVED="$(javap -p -c -cp "$ROBO_JAR" org.robolectric.plugins.DefaultSdkProvider 2>/dev/null \
  | awk -v sdk="$ANDROID_SDK" '
      /bipush|sipush/ { n=$NF }
      /ldc/ && /String [0-9]+$/ { vals[++c]=$NF }
      /String REL/ { if (n == sdk) { print vals[c-1] " " vals[c]; exit } }
    ')"
[ -n "$DERIVED" ] || { echo "could not derive android-all coordinates for SDK $ANDROID_SDK from $ROBO_JAR" >&2; exit 1; }
ANDROID_VERSION="${DERIVED%% *}"
BUILD="${DERIVED##* }"
[ -n "$ANDROID_VERSION" ] && [ -n "$BUILD" ] || { echo "malformed android-all coordinates: $DERIVED" >&2; exit 1; }

VERSION="$ANDROID_VERSION-robolectric-$BUILD-i7"
ARTIFACT="android-all-instrumented-$VERSION"
DIR="$M2/org/robolectric/android-all-instrumented/$VERSION"
BASE="https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/$VERSION"
mkdir -p "$DIR"

sha512() {
  if command -v sha512sum >/dev/null 2>&1; then sha512sum "$1" | cut -d' ' -f1
  else shasum -a 512 "$1" | cut -d' ' -f1; fi
}

for SUFFIX in jar pom jar.sha512 pom.sha512; do
  FILE="$DIR/$ARTIFACT.$SUFFIX"
  # A cached file is usable only if it is non-empty AND matches its published checksum.
  # Validating (rather than merely testing -s) is what stops a truncated restore from
  # being treated as warm and then failing inside the class loader.
  if [ -s "$FILE" ]; then
    case "$SUFFIX" in
      jar|pom)
        SUM_FILE="$DIR/$ARTIFACT.$SUFFIX.sha512"
        if [ -s "$SUM_FILE" ] && [ "$(sha512 "$FILE")" = "$(tr -d '[:space:]' < "$SUM_FILE")" ]; then
          continue
        fi
        ;;
      *) continue ;;
    esac
  fi
  curl --fail --retry 5 --retry-all-errors --retry-delay 3 -sS -o "$FILE" "$BASE/$ARTIFACT.$SUFFIX"
done