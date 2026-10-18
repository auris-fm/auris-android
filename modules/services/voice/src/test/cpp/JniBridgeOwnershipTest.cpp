// Host-only control for the capture/VAD start OWNERSHIP in the real JNI entry points.
//
// It exists because the defect it pins lives inside jni_bridge.cpp: which entry starts audio and
// which entry publishes the VAD processor are decisions the .so itself makes, so a Kotlin fake of
// CaptureHardware -- whose contract its own author writes -- passes unchanged against a broken
// library. This target compiles the ACTUAL production translation units (jni_bridge.cpp,
// OboeAudioCapture.cpp, NativeVadProcessor.cpp) and calls the exported Java_...OboeNative_*
// functions in the order production does. Shims exist only at platform boundaries
// (JniBridgeHostPlatformStubs.cpp: JNIEnv, dlopen, the ONNX-backed vad bootstrap) plus two narrow
// observation seams inside jni_bridge.cpp (a publication counter and a capture-factory override),
// neither of which gates any decision.
//
// The ownership sequence under test (baseline f3fd902ee behavior):
//   nativeOpenInputCapture      -> opens ONLY; no audio flows while Kotlin confirms the device
//   nativeStartInputStream      -> starts/reuses the stream ONLY; never touches VAD
//   nativeStartCaptureAndVad    -> the ONE engine-owned VAD attachment; fails closed without an
//                                 active stream
// The branch regression (54e7cdf5b) started capture inside open and published a VAD processor from
// startInputStream, so the engine's combined attach retired and recreated it -- the double attach
// behind the observed restart churn.
//
// Run it with:  src/test/cpp/run.sh            (optionally: run.sh <build-dir>)

#include <jni.h>

#include <cstdio>

#include "OboeAudioCapture.h"

// The JNIEnv implementation lives in JniBridgeHostPlatformStubs.cpp (platform boundary); this
// control only needs its pointer.
JNIEnv* host_jni_env();

// --- The real JNI entry points under test -------------------------------------
// Declared exactly as jni_bridge.cpp defines them (extern "C", JNIEnv* first).
extern "C" {
jboolean Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeIsCapturing(
    JNIEnv*, jclass);
jint Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeOpenInputCapture(
    JNIEnv*, jclass, jint sampleRate, jint channels);
jboolean Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartInputStream(
    JNIEnv*, jclass);
void Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeCloseInputCapture(
    JNIEnv*, jclass);
jboolean Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartCaptureAndVad(
    JNIEnv*, jclass, jint sampleRate, jint channels, jobject assetManager);
void Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStopCaptureAndVad(
    JNIEnv*, jclass);

// Narrow observation seams inside the production TU (see file header). The counter sees every
// gVadProcessor.publish call -- attachment is observed DIRECTLY, not inferred from event emptiness:
// an idle attached VAD emits no events and holds no speech PCM, so "no event" proves nothing.
extern long long gVadAttachesForTest;
}

// Installs the capture object the entries would have created on device (a host cannot open a real
// Oboe stream). Definition lives in jni_bridge.cpp; nullptr restores the pristine path.
void setCaptureFactoryOverrideForTest(OboeAudioCapture* capture);

namespace {

int gFailures = 0;
void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "  ok  " : "FAIL  ", what);
    if (!ok) ++gFailures;
}

constexpr int kTestSampleRate = 16000;   // same value as OboeAudioCapture.h's kSampleRate
constexpr int kChannels = 1;

bool capturing() {
    return Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeIsCapturing(
               host_jni_env(), nullptr) == JNI_TRUE;
}

// A capture whose ring buffer answers like a live-but-silent stream, so the REAL
// NativeVadProcessor thread the engine attaches runs quietly until teardown. readRingBuffer is
// virtual for exactly this substitution (the segment accounting test uses the same seam).
class SilentCapture : public OboeAudioCapture {
public:
    SilentCapture() {
        // An installed placeholder stream reports the device id and keeps mStream safe from
        // close(); deactivateForTest() then clears the active flag installStreamForTest sets, so
        // the entries under test perform the actual start themselves. (stop() cannot be used
        // here: it requestStop()s the placeholder unconditionally.)
        installStreamForTest(reinterpret_cast<oboe::AudioStream*>(0x1000), /*deviceId=*/41);
        deactivateForTest();
    }
    ~SilentCapture() override { clearStreamForTest(); }

    int32_t readRingBuffer(int16_t* /*outData*/, int32_t /*maxSamples*/, int32_t /*timeoutMs*/) override {
        return 0;  // timeout, like silence on device; -1 would end the VAD loop
    }

    int32_t getDeviceId() override { return 41; }  // the placeholder stream cannot be interrogated
};

}  // namespace

int main() {
    std::printf("native capture/VAD start ownership\n");

    // Scenario A -- the full production cycle: open, confirm (the caller's job), start, attach.
    // Heap-allocated because the real entries own and delete gCapture during teardown.
    {
        auto* capture = new SilentCapture();
        gVadAttachesForTest = 0;
        setCaptureFactoryOverrideForTest(capture);

        JNIEnv* env = host_jni_env();

        // (1) OPEN must not start audio. Kotlin confirms the selected device BETWEEN open and
        //     start; capture begun inside open runs on an unconfirmed input.
        const jint deviceId =
            Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeOpenInputCapture(
                env, nullptr, kTestSampleRate, kChannels);
        check(deviceId == 41, "open returns the stream's device id for confirmation");
        check(!capturing(),
              "opening the input stream must NOT start capture (confirmation comes between open and start)");
        check(gVadAttachesForTest == 0, "open attaches no VAD processor");

        const jboolean beforeStart =
            Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartCaptureAndVad(
                env, nullptr, kTestSampleRate, kChannels, nullptr);
        check(beforeStart == JNI_FALSE, "engine attach before stream start fails closed");
        check(gVadAttachesForTest == 0, "refused early attach leaves VAD unattached");

        // (2) START moves audio only. The VAD processor belongs to the engine's combined attach;
        //     publishing one here leaves nativeStartCaptureAndVad retiring and recreating it.
        const jboolean started =
            Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartInputStream(
                env, nullptr);
        check(started == JNI_TRUE, "starting the opened stream succeeds");
        check(capturing(), "the started stream carries audio");
        check(gVadAttachesForTest == 0,
              "nativeStartInputStream must NOT construct or publish a VAD processor");

        // (3) ATTACH is the ONE engine-owned VAD attachment of the cycle.
        const jboolean attached =
            Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartCaptureAndVad(
                env, nullptr, kTestSampleRate, kChannels, nullptr);
        check(attached == JNI_TRUE, "engine attach succeeds on a running stream");
        check(gVadAttachesForTest == 1, "exactly one VAD attachment at engine start");

        check(Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartInputStream(
                  env, nullptr) == JNI_TRUE, "repeated stream start reuses the running stream");
        check(gVadAttachesForTest == 1, "repeated stream start preserves the engine's VAD attachment");

        // Teardown through the real entries.
        Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStopCaptureAndVad(
            env, nullptr);
        check(!capturing(), "teardown stops capture");
        setCaptureFactoryOverrideForTest(nullptr);
    }

    // Scenario B -- Kotlin rejects the selected input after open. Closing it must never start
    // audio or attach VAD, and a later start must not resurrect the rejected stream.
    {
        setCaptureFactoryOverrideForTest(new SilentCapture());
        gVadAttachesForTest = 0;
        JNIEnv* env = host_jni_env();
        check(Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeOpenInputCapture(
                  env, nullptr, kTestSampleRate, kChannels) == 41, "rejected input still reports its device id");
        Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeCloseInputCapture(env, nullptr);
        check(!capturing(), "rejected input closes without active capture");
        check(gVadAttachesForTest == 0, "rejected input never attaches VAD");
        check(Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartInputStream(
                  env, nullptr) == JNI_FALSE, "closed input cannot be started");
        setCaptureFactoryOverrideForTest(nullptr);
    }

    // Scenario C -- attach fails closed when NO stream is running. This precondition is identical
    // on main and the branch; pinned so the fix cannot trade one ordering violation for another.
    {
        JNIEnv* env = host_jni_env();
        gVadAttachesForTest = 0;
        const jboolean attached =
            Java_au_com_shiftyjelly_pocketcasts_voicecontrol_audio_OboeNative_nativeStartCaptureAndVad(
                env, nullptr, kTestSampleRate, kChannels, nullptr);
        check(attached == JNI_FALSE, "attach without a running stream fails closed");
        check(gVadAttachesForTest == 0, "a refused attach publishes no processor");
    }

    if (gFailures != 0) {
        std::printf("%d FAILURE(S)\n", gFailures);
        return 1;
    }
    std::printf("all ownership checks passed\n");
    return 0;
}
