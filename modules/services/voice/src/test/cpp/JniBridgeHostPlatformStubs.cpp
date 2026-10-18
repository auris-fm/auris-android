// Platform-boundary link stubs for the host JNI-ownership target.
//
// What is stubbed here is ONLY what a host executable cannot provide and the named control does
// not exercise: the dynamic loader (dlopen/dlclose, used by nativeIsOrtLoaded) and the ONNX-backed
// VAD session bootstrap (vadEnsureInitialized/vadLastError -- their real definitions live in
// VadJni.cpp with its ONNX C-API machinery, which has no host build). The ownership sequence under
// test never crosses those boundaries; it lives entirely in the REAL jni_bridge.cpp +
// OboeAudioCapture.cpp + NativeVadProcessor.cpp compiled into this target.
//
// vadEnsureInitialized returning true mirrors the device precondition the test drives: an engine
// attach happens only after the models are ready. It gates nothing about ordering.
//
// The JNIEnv below is the other platform boundary: a minimal JNINativeInterface table implementing
// exactly the five functions jni_bridge.cpp calls (NewLongArray, SetLongArrayRegion,
// GetArrayLength, GetShortArrayElements, ReleaseShortArrayElements). Signatures follow the NDK's
// jni.h, where the C++ JNIEnv forwards through `functions`.
#include <jni.h>

#include <cstdlib>

extern "C" {

bool vadEnsureInitialized(JNIEnv* /*env*/, jobject /*assetManager*/) { return true; }
const char* vadLastError() { return ""; }

// The two Silero externs NativeVadProcessor links against. Their declarations in
// NativeVadProcessor.cpp:14-15 are plain C++ (no extern "C"), so these definitions must also be
// outside an extern "C" block to match the mangled names. The ownership control never feeds
// audio, so an always-quiet detector is correct.
void* dlopen(const char* /*filename*/, int /*flag*/) { return nullptr; }
int dlclose(void* /*handle*/) { return 0; }

}  // extern "C"

float sileroVadPredict(const int16_t* /*samples*/, int32_t /*count*/) { return 0.0f; }
void sileroVadResetState() {}

namespace {

struct HostJNIEnv {
    JNIEnv env{};
    JNINativeInterface table{};

    HostJNIEnv() {
        table.NewLongArray = [](JNIEnv*, jsize len) -> jlongArray {
            auto* storage = new jlong[len > 0 ? len : 1]();
            return reinterpret_cast<jlongArray>(storage);
        };
        table.SetLongArrayRegion =
            [](JNIEnv*, jlongArray array, jsize start, jsize len, const jlong* values) {
                auto* storage = reinterpret_cast<jlong*>(array);
                for (jsize i = 0; i < len; ++i) storage[start + i] = values[i];
            };
        table.GetArrayLength = [](JNIEnv*, jarray) -> jsize { return 0; };
        table.GetShortArrayElements = [](JNIEnv*, jshortArray, jboolean*) -> jshort* {
            return nullptr;
        };
        table.ReleaseShortArrayElements = [](JNIEnv*, jshortArray, jshort*, jint) {};
        env.functions = &table;
    }
};

HostJNIEnv g_host_env;

}  // namespace

JNIEnv* host_jni_env() { return &g_host_env.env; }
