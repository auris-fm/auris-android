#!/bin/sh
# Build and run the host VAD segment-accounting test.
#
# It needs no Oboe library and no Silero: the VAD externs are stubbed in the test, readRingBuffer is
# virtual so a fake capture can serve scripted frames, and shim/ supplies a no-op <android/log.h>.
# The only external input is the cached Oboe headers the Android build already fetched.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
VOICE=$(cd "$HERE/../../.." && pwd)   # src/test/cpp -> the voice module
OBOE=$(ls -d "$VOICE"/.cxx/Debug/*/arm64-v8a/_deps/oboe-src/include | head -1)
BUILD=${1:-/tmp/vadhost}

cmake -S "$HERE" -B "$BUILD" \
    -DCPP_DIR="$VOICE/src/main/cpp" \
    -DSHIM_DIR="$HERE/shim" \
    -DOBOE_INCLUDE="$OBOE"
cmake --build "$BUILD"
"$BUILD/vad_segment_test"
