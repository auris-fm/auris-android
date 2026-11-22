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
# Every coordinate is DERIVED from the Robolectric artifact itself — android version, build
# number and the instrumentation suffix — so a bump cannot leave a stale pin here while the
# job stays green and Robolectric quietly fetches something else over the network.
set -euo pipefail

ROBOLECTRIC_VERSION="${ROBOLECTRIC_VERSION:?set by the caller from the version catalog}"
ANDROID_SDK="${ANDROID_SDK:?the SDK the unit tests run at}"
M2="${M2_REPO:-$HOME/.m2/repository}"
GRADLE_HOME_DIR="${GRADLE_USER_HOME:-$HOME/.gradle}"

sha512() {
  if command -v sha512sum >/dev/null 2>&1; then sha512sum "$1" | cut -d' ' -f1
  else shasum -a 512 "$1" | cut -d' ' -f1; fi
}

# The Robolectric jar carries the SDK table. Prefer the one Gradle already resolved; on a COLD
# Gradle cache it is not there, and `gradlew dependencies` resolves METADATA ONLY (poms, no jars),
# so waiting for it would fail every cold run before a single test executed. Fetch it directly
# in that case — it is ~160 KB, and this path only runs when the cache is cold anyway.
ROBO_JAR="$(find "$GRADLE_HOME_DIR/caches/modules-2/files-2.1/org.robolectric/robolectric/$ROBOLECTRIC_VERSION" \
  -name "robolectric-$ROBOLECTRIC_VERSION.jar" 2>/dev/null | head -1 || true)"
if [ -z "$ROBO_JAR" ]; then
  ROBO_JAR="$M2/.robolectric-prefetch/robolectric-$ROBOLECTRIC_VERSION.jar"
  mkdir -p "$(dirname "$ROBO_JAR")"
  if [ ! -s "$ROBO_JAR" ]; then
    curl --fail --retry 5 --retry-all-errors --retry-delay 3 -sS -o "$ROBO_JAR" \
      "https://repo1.maven.org/maven2/org/robolectric/robolectric/$ROBOLECTRIC_VERSION/robolectric-$ROBOLECTRIC_VERSION.jar"
  fi
fi
[ -s "$ROBO_JAR" ] || { echo "robolectric jar unavailable: $ROBO_JAR" >&2; exit 1; }

PROVIDER="$(javap -p -c -cp "$ROBO_JAR" org.robolectric.plugins.DefaultSdkProvider 2>/dev/null || true)"
[ -n "$PROVIDER" ] || { echo "could not read DefaultSdkProvider from $ROBO_JAR" >&2; exit 1; }

# Each SDK entry pushes its API level, then loads androidVersion, build number and "REL".
# Taking the last two numeric string constants before that entry's REL gives both halves
# without depending on constant-pool indices.
DERIVED="$(printf '%s\n' "$PROVIDER" | awk -v sdk="$ANDROID_SDK" '
    /bipush|sipush/ { n=$NF }
    /ldc/ && /String [0-9]+$/ { vals[++c]=$NF }
    /String REL/ { if (n == sdk) { print vals[c-1] " " vals[c]; exit } }
  ')"
[ -n "$DERIVED" ] || { echo "no android-all coordinates for SDK $ANDROID_SDK in $ROBO_JAR" >&2; exit 1; }
ANDROID_VERSION="${DERIVED%% *}"
BUILD="${DERIVED##* }"

# The instrumentation suffix is a literal in DefaultSdk.getAndroidSdkDependency, joined after
# the build number. Read it from the same artifact rather than hard-coding i7, which would
# silently point at a jar Robolectric no longer loads after a bump.
SUFFIX="$(javap -p -c -cp "$ROBO_JAR" 'org.robolectric.plugins.DefaultSdkProvider$DefaultSdk' 2>/dev/null \
  | sed -n 's/.*String \(i[0-9][0-9]*\)$/\1/p' | head -1)"
[ -n "$SUFFIX" ] || { echo "could not derive the instrumentation suffix from $ROBO_JAR" >&2; exit 1; }

VERSION="$ANDROID_VERSION-robolectric-$BUILD-$SUFFIX"
ARTIFACT="android-all-instrumented-$VERSION"
DIR="$M2/org/robolectric/android-all-instrumented/$VERSION"
# Overridable so a control can point at a local mirror; production uses Maven Central.
BASE="${ROBOLECTRIC_BASE_URL:-https://repo1.maven.org/maven2/org/robolectric/android-all-instrumented/$VERSION}"
mkdir -p "$DIR"

for FILE_SUFFIX in jar pom jar.sha512 pom.sha512; do
  FILE="$DIR/$ARTIFACT.$FILE_SUFFIX"
  # A cached file is usable only if it is non-empty AND matches its published checksum.
  # Validating (rather than merely testing -s) is what stops a truncated restore from
  # being treated as warm and then failing inside the class loader.
  if [ -s "$FILE" ]; then
    case "$FILE_SUFFIX" in
      jar|pom)
        SUM_FILE="$DIR/$ARTIFACT.$FILE_SUFFIX.sha512"
        if [ -s "$SUM_FILE" ] && [ "$(sha512 "$FILE")" = "$(tr -d '[:space:]' < "$SUM_FILE")" ]; then
          continue
        fi
        ;;
      *) continue ;;
    esac
  fi
  curl --fail --retry 5 --retry-all-errors --retry-delay 3 -sS -o "$FILE" "$BASE/$ARTIFACT.$FILE_SUFFIX"
done

# Echo the derived coordinates so the caller can key its cache on them.
echo "$VERSION"