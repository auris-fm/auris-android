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
OBOE=$(ls -d "$VOICE"/.cxx/Debug/*/arm64-v8a/_deps/oboe-src/include | head -1)
BUILD=${1:-/tmp/vadhost}

# jni.h lives only in the Android NDK sysroot; cmake's glob sees no HOME there, so the shell --
# which definitely has it -- probes the candidates and passes the newest one in explicitly.
JNI_INCLUDE=$(ls -d "$HOME"/Library/Android/sdk/ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include 2>/dev/null | sort | tail -1)
if [ -z "$JNI_INCLUDE" ]; then
    echo "jni_bridge_ownership_test skipped: no Android NDK sysroot with jni.h found" >&2
else
    JNI_FLAG="-DJNI_INCLUDE=$JNI_INCLUDE"
fi

cmake -S "$HERE" -B "$BUILD" \
    -DCPP_DIR="$VOICE/src/main/cpp" \
    -DSHIM_DIR="$HERE/shim" \
    -DOBOE_INCLUDE="$OBOE" \
    ${JNI_FLAG:-}
cmake --build "$BUILD"
"$BUILD/vad_segment_test"
"$BUILD/vad_handle_test"
if [ -x "$BUILD/jni_bridge_ownership_test" ]; then
    "$BUILD/jni_bridge_ownership_test"
fi
