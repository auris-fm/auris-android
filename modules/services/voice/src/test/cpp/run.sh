#!/bin/sh
# Build and run the host-side native tests.
#
# vad_segment_test needs no Oboe library and no Silero: the VAD externs are stubbed in the test,
# readRingBuffer is virtual so a fake capture can serve scripted frames, and shim/ supplies a no-op
# <android/log.h>. The only external input is the cached Oboe headers the Android build already
# fetched.
#
# vad_handle_test covers the JNI VAD processor lifetime guard and needs only the production header.
set -e
HERE=$(cd "$(dirname "$0")" && pwd)
VOICE=$(cd "$HERE/../../.." && pwd)   # src/test/cpp -> the voice module
OBOE_INCLUDE=${OBOE_INCLUDE:-$(ls -d "$VOICE"/.cxx/Debug/*/arm64-v8a/_deps/oboe-src/include 2>/dev/null | head -1)}
if [ ! -f "$OBOE_INCLUDE/oboe/Oboe.h" ]; then
    echo "Cached Oboe headers are required; set OBOE_INCLUDE to their include directory" >&2
    exit 1
fi
BUILD=${1:-/tmp/vadhost}

# jni.h lives only in the Android NDK sysroot; cmake's glob sees no HOME there, so the shell --
# which definitely has it -- probes the candidates and passes the newest one in explicitly.
JNI_INCLUDE=${JNI_INCLUDE:-$(ls -d "$HOME"/Library/Android/sdk/ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include 2>/dev/null | sort | tail -1)}
if [ ! -f "$JNI_INCLUDE/jni.h" ]; then
    echo "JNI headers are required; set JNI_INCLUDE to an Android NDK sysroot include directory" >&2
    exit 1
fi

cmake -S "$HERE" -B "$BUILD" \
    -DCPP_DIR="$VOICE/src/main/cpp" \
    -DSHIM_DIR="$HERE/shim" \
    -DOBOE_INCLUDE="$OBOE_INCLUDE" \
    -DJNI_INCLUDE="$JNI_INCLUDE"
cmake --build "$BUILD"
"$BUILD/vad_segment_test"
"$BUILD/vad_handle_test"
"$BUILD/jni_bridge_ownership_test"
