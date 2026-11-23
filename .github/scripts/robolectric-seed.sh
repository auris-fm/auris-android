#!/bin/bash
# Seed the android-all jar Robolectric needs, so a Maven outage at TEST time cannot fail the suite.
#
# Why a download step exists at all: the cache alone can never be seeded on main. When Gradle serves
# the test task from its build cache, Robolectric never runs, ~/.m2 stays empty, and actions/cache's
# post step reports success while saving nothing ("Path Validation Error: paths do not exist"). A
# green job is therefore not evidence that the jar was ever fetched. Seeding must not depend on the
# tests running, so it happens here, before them, and the workflow saves whatever this produced.
#
# The coordinate is DERIVED, never hardcoded: the Robolectric version comes from the version catalog
# and the SDK from robolectric.properties, and the android-all version for that SDK is read out of
# the Robolectric jar those two pins select. A deliberate bump moves the requirement with it.
set -uo pipefail

DIR="$(cd "$(dirname "$0")/../.." && pwd)"
REPO="${ROBOLECTRIC_MAVEN_REPO:-https://repo1.maven.org/maven2}"
M2="${ROBOLECTRIC_M2_DIR:-$HOME/.m2/repository}"
ATTEMPTS="${ROBOLECTRIC_SEED_ATTEMPTS:-4}"

fail() { echo "FAIL: $*" >&2; exit 1; }

RV="$(sed -n 's/^robolectric = "org\.robolectric:robolectric:\([0-9.]*\)".*/\1/p' "$DIR/gradle/libs.versions.toml")"
[ -n "$RV" ] || fail "no org.robolectric:robolectric pin in gradle/libs.versions.toml"
SDK="$(sed -n 's/^sdk=\([0-9][0-9]*\).*/\1/p' "$DIR/modules/services/voice/src/test/resources/robolectric.properties")"
[ -n "$SDK" ] || fail "no sdk pin in modules/services/voice/src/test/resources/robolectric.properties"

# The jar Gradle resolved for that exact version. Searching by version keeps this honest: a stale
# jar from another version cannot supply the coordinate.
RJAR="$(find "$HOME/.gradle/caches/modules-2/files-2.1/org.robolectric/robolectric/$RV" \
  -name "robolectric-$RV.jar" 2>/dev/null | head -1)"
[ -n "$RJAR" ] || fail "Robolectric $RV is not in the Gradle cache; run the build's dependency resolution first"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT
(cd "$TMP" && unzip -o -q "$RJAR" 'org/robolectric/plugins/DefaultSdkProvider*.class') \
  || fail "could not read DefaultSdkProvider from $RJAR"
javap -p -c "$TMP/org/robolectric/plugins/DefaultSdkProvider.class" > "$TMP/prov.txt" 2>&1 \
  || fail "could not disassemble DefaultSdkProvider"
# Each SDK row pushes the API level then loads two strings: the Android version and the build id.
# The FIRST bipush of that value is the map key, the second is the constructor argument; either is
# followed by the same two strings, so taking the first match is enough.
read -r ANDROID_VERSION BUILD_ID <<ROW
$(awk -v sdk="$SDK" '
  $0 ~ ("bipush *" sdk "$") { want=1; n=0; next }
  want && /\/\/ String / { n++; if (n==1) av=$NF; if (n==2) { print av, $NF; exit } }
' "$TMP/prov.txt")
ROW
[ -n "${ANDROID_VERSION:-}" ] && [ -n "${BUILD_ID:-}" ] \
  || fail "Robolectric $RV declares no android-all for SDK $SDK"

# Preinstrumented jars are the default (robolectric.usePreinstrumentedJars=true), which is what the
# suite loads: android-all-instrumented/<androidVersion>-robolectric-<buildId>-i7.
VERSION="$ANDROID_VERSION-robolectric-$BUILD_ID-i7"
ARTIFACT="android-all-instrumented"
REL="org/robolectric/$ARTIFACT/$VERSION/$ARTIFACT-$VERSION"
DEST="$M2/$(dirname "$REL")"
echo "--- seeding $ARTIFACT $VERSION into $DEST"

mkdir -p "$DEST" || fail "cannot create $DEST"
if [ -s "$DEST/$ARTIFACT-$VERSION.jar" ]; then
  echo "--- already present, nothing to fetch"
  exit 0
fi

# Fetch to a staging directory and move only after the checksum matches, so an interrupted download
# can never leave a truncated jar that Robolectric would treat as cached.
STAGE="$TMP/stage"
mkdir -p "$STAGE"
fetch() {
  local name="$1" attempt=1
  while [ "$attempt" -le "$ATTEMPTS" ]; do
    if curl -fsS --retry 2 --connect-timeout 20 --max-time 900 \
         -o "$STAGE/$name" "$REPO/$REL.$name"; then
      return 0
    fi
    echo "--- attempt $attempt/$ATTEMPTS for $name failed; retrying" >&2
    attempt=$((attempt + 1))
    sleep $((attempt * 3))
  done
  return 1
}

for ext in pom pom.sha512 jar.sha512 jar; do
  fetch "$ext" || fail "could not fetch $ARTIFACT-$VERSION.$ext after $ATTEMPTS attempts"
done

expected="$(tr -d ' \t\r\n' < "$STAGE/jar.sha512")"
actual="$(shasum -a 512 "$STAGE/jar" | awk '{print $1}')"
[ "$expected" = "$actual" ] || fail "SHA-512 mismatch for $ARTIFACT-$VERSION.jar"

for ext in pom pom.sha512 jar.sha512 jar; do
  mv "$STAGE/$ext" "$DEST/$ARTIFACT-$VERSION.$ext" || fail "cannot install $ext"
done
echo "--- seeded $ARTIFACT-$VERSION.jar ($(wc -c < "$DEST/$ARTIFACT-$VERSION.jar") bytes)"
