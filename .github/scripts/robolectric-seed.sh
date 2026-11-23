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

fail() {
  echo "FAIL: $*" >&2
  # A failed seed must not read as cacheable. GITHUB_OUTPUT may be unset outside CI.
  if [ -n "${GITHUB_OUTPUT:-}" ]; then echo "jar-present=no" >> "$GITHUB_OUTPUT"; fi
  exit 1
}

# Tell the workflow whether the cache is worth saving. `hashFiles` only sees files under
# GITHUB_WORKSPACE, so a gate written against ~/.m2 is always empty and the save never runs -- which
# is silent, and is how the jar stayed uncached. This output is the readable signal.
report_present() { # report_present <yes|no>
  [ -n "${GITHUB_OUTPUT:-}" ] || return 0
  echo "jar-present=$1" >> "$GITHUB_OUTPUT"
}

# Fetch one Maven artifact and its published SHA-512 into a staging directory, verifying the digest
# before returning. Retries then fails closed: a partial file must never be mistaken for a complete
# artifact, whether it is the android-all jar or the Robolectric jar the coordinate is read from.
#   fetch <relative-path-without-extension> <file-stem> <dest-dir> [extension]
fetch() {
  local rel="$1" stem="$2" dest="$3" ext="${4:-jar}" attempt=1
  mkdir -p "$dest" || return 1
  while [ "$attempt" -le "$ATTEMPTS" ]; do
    if curl -fsS --retry 2 --connect-timeout 20 --max-time 900 \
         -o "$dest/$stem.$ext" "$REPO/$rel.$ext" \
       && curl -fsS --retry 2 --connect-timeout 20 --max-time 120 \
         -o "$dest/$stem.$ext.sha512" "$REPO/$rel.$ext.sha512"; then
      local want got
      want="$(tr -d ' \t\r\n' < "$dest/$stem.$ext.sha512")"
      got="$(shasum -a 512 "$dest/$stem.$ext" | awk '{print $1}')"
      if [ "$want" = "$got" ]; then
        return 0
      fi
      echo "--- SHA-512 mismatch for $stem.$ext on attempt $attempt" >&2
    fi
    echo "--- attempt $attempt/$ATTEMPTS for $stem.$ext failed; retrying" >&2
    rm -f "$dest/$stem.$ext" "$dest/$stem.$ext.sha512"
    attempt=$((attempt + 1))
    sleep $((attempt * 3))
  done
  return 1
}

M2_REPO="$M2/org/robolectric/android-all-instrumented"
if [ -d "$M2_REPO" ]; then
  # Exactly one version directory, holding a complete artifact, means the cache already has what the
  # pins select: the workflow keys this cache on those same two pins, so a restored entry cannot
  # belong to different ones. Verify the digest rather than trusting the file's presence -- a
  # truncated jar is exactly what this change exists to prevent.
  found="$(find "$M2_REPO" -mindepth 1 -maxdepth 1 -type d | sort)"
  if [ "$(printf '%s\n' "$found" | sed '/^$/d' | wc -l | tr -d ' ')" = 1 ] && [ -n "$found" ]; then
    vdir="$found"
    stem="$vdir/$(basename "$vdir")"
    # The directory is named by VERSION; the files are android-all-instrumented-<VERSION>.
    base="android-all-instrumented-$(basename "$vdir")"
    # A cached jar is reused only if its published digest is there AND matches. Missing digest means
    # refetch-and-verify, which is safe, so requiring the file here is an optimisation, not a guard;
    # the comparison below is the guard. The POM is Maven metadata and is not needed to load classes.
    if [ -s "$vdir/$base.jar" ] && [ -s "$vdir/$base.jar.sha512" ]; then
      want="$(tr -d ' \t\r\n' < "$vdir/$base.jar.sha512")"
      got="$(shasum -a 512 "$vdir/$base.jar" | awk '{print $1}')"
      if [ "$want" = "$got" ]; then
        echo "--- already present, nothing to fetch"
        report_present yes
        exit 0
      fi
      echo "--- cached jar fails its checksum; refetching" >&2
    fi
  fi
fi

RV="$(sed -n 's/^robolectric = "org\.robolectric:robolectric:\([0-9.]*\)".*/\1/p' "$DIR/gradle/libs.versions.toml")"
[ -n "$RV" ] || fail "no org.robolectric:robolectric pin in gradle/libs.versions.toml"
SDK="$(sed -n 's/^sdk=\([0-9][0-9]*\).*/\1/p' "$DIR/modules/services/voice/src/test/resources/robolectric.properties")"
[ -n "$SDK" ] || fail "no sdk pin in modules/services/voice/src/test/resources/robolectric.properties"

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# The jar Gradle resolved for that exact version. Searching by version keeps this honest: a stale
# jar from another version cannot supply the coordinate. On a cold runner Gradle has not fetched it
# yet, so fetch it here with the same retries and checksum discipline as the android-all jar --
# otherwise the seed would depend on a prior build having run, which is the dependency this change
# exists to remove.
GRADLE_FILES="${GRADLE_USER_HOME:-$HOME/.gradle}/caches/modules-2/files-2.1"
RJAR="$(find "$GRADLE_FILES/org.robolectric/robolectric/$RV" -name "robolectric-$RV.jar" 2>/dev/null | head -1)"
if [ -z "$RJAR" ]; then
  echo "--- Robolectric $RV is not in the Gradle cache; fetching it to read its SDK table"
  RREL="org/robolectric/robolectric/$RV/robolectric-$RV"
  mkdir -p "$TMP/robolectric"
  fetch "$RREL" "robolectric-$RV" "$TMP/robolectric" \
    || fail "could not fetch robolectric-$RV.jar to derive the android-all coordinate"
  RJAR="$TMP/robolectric/robolectric-$RV.jar"
fi
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
# Fetch to a staging directory and move only after the checksum matches, so an interrupted download
# can never leave a truncated jar that Robolectric would treat as cached.
STAGE="$TMP/stage"
mkdir -p "$STAGE"
# The jar (verified inside fetch), then the POM and its digest. Robolectric reads all four files
# from this directory, so all four are installed -- but only after every one of them is complete.
fetch "$REL" "$ARTIFACT-$VERSION" "$STAGE" jar \
  || fail "could not fetch $ARTIFACT-$VERSION.jar after $ATTEMPTS attempts"
fetch "$REL" "$ARTIFACT-$VERSION" "$STAGE" pom \
  || fail "could not fetch $ARTIFACT-$VERSION.pom after $ATTEMPTS attempts"

for ext in jar jar.sha512 pom pom.sha512; do
  [ -s "$STAGE/$ARTIFACT-$VERSION.$ext" ] || fail "missing $ARTIFACT-$VERSION.$ext after a successful fetch"
  mv "$STAGE/$ARTIFACT-$VERSION.$ext" "$DEST/$ARTIFACT-$VERSION.$ext" || fail "cannot install $ext"
done
echo "--- seeded $ARTIFACT-$VERSION.jar ($(wc -c < "$DEST/$ARTIFACT-$VERSION.jar") bytes)"
report_present yes
