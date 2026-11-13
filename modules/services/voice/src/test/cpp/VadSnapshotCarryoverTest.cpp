// Host-only probe for the snapshot-buffer carry-over between utterances.
//
// Reviewer question on main: `getSpeechPcmSize()` and `getSpeechPcm()` read the native
// finished-utterance snapshot (`mSnapshotBuffer`), and nothing clears that buffer when the NEXT
// utterance starts inside the same capture session. If so, the engine's SpeechStarted admission
// (`speechStartedAdmitting` + `drainSpeechPcm`) collects the PREVIOUS utterance's audio as the
// new utterance's initial chunk. This test drives the real NativeVadProcessor frame loop through
// two utterances in one session and reports what the SpeechStarted caller would see.
//
// Run it with: src/test/cpp/run.sh
//
// Same substitution trick as VadSegmentAccountingTest: NativeVadProcessor only calls
// readRingBuffer on its capture, so a fake subclass scripts the audio; sileroVadPredict /
// sileroVadResetState are extern and defined here (speech = loud enough for the RMS gate).

#include "NativeVadProcessor.h"
#include "OboeAudioCapture.h"

#include <chrono>
#include <cmath>
#include <csignal>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <thread>
#include <vector>

// --- The two externs NativeVadProcessor links against ------------------------

float sileroVadPredict(const int16_t* samples, int32_t count) {
    (void)count;
    if (samples == nullptr || count <= 0) return 0.0f;
    int64_t sum = 0;
    for (int32_t i = 0; i < count; i++) {
        sum += static_cast<int64_t>(samples[i]) * samples[i];
    }
    const double rms = count > 0 ? __builtin_sqrt(static_cast<double>(sum) / count) : 0.0;
    return rms >= 200.0 ? 1.0f : 0.0f;
}

void sileroVadResetState() {}

// --- A capture that serves a scripted sequence of VAD frames -----------------

namespace {

constexpr int32_t kFrame = 1024;  // NativeVadProcessor::kVadFrameSize

class FakeCapture : public OboeAudioCapture {
public:
    std::deque<bool> script;  // loud = speech; quiet = silence
    int served = 0;
    // The host runs the frame loop at full speed, so a single-slot event would be overwritten
    // before the consumer reads it. Throttle every read so events surface one at a time.
    int pauseAfterFrames = 0;
    std::chrono::milliseconds pauseFor{30};

    int32_t readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs) override {
        (void)timeoutMs;
        if (maxSamples < kFrame || script.empty()) return 0;
        if (pauseAfterFrames >= 0 && served >= pauseAfterFrames) {
            std::this_thread::sleep_for(pauseFor);
        }
        served++;
        const bool loud = script.front();
        script.pop_front();
        const int16_t amplitude = loud ? 4000 : 0;
        for (int32_t i = 0; i < kFrame; i++) outData[i] = amplitude;
        return kFrame;
    }
};

int gFailures = 0;
void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "  ok  " : "FAIL  ", what);
    std::fflush(stdout);
    if (!ok) gFailures++;
}

std::deque<bool> frames(int count, bool loud) {
    std::deque<bool> f;
    for (int i = 0; i < count; i++) f.push_back(loud);
    return f;
}

}  // namespace

int main() {
    std::printf("native VAD snapshot carry-over between utterances\n");

    // TWO utterances in ONE capture session: loud / silence / loud, then the stream runs dry and
    // the loop exits. This is the shape of one listening turn with two spoken phrases.
    FakeCapture capture;
    capture.pauseAfterFrames = 0;
    capture.pauseFor = std::chrono::milliseconds(30);
    const int32_t utterance1 = 30;  // frames of speech
    const int32_t gap = 45;         // frames of silence between the phrases
    const int32_t utterance2 = 30;
    auto append = [&capture](const std::deque<bool>& more) {
        for (const bool f : more) capture.script.push_back(f);
    };
    append(frames(utterance1, true));
    append(frames(gap, false));
    append(frames(utterance2, true));

    NativeVadProcessor processor(&capture);
    bool started = processor.start();
    check(started, "processor starts");

    // What a SpeechStarted caller observes at each utterance's onset.
    int32_t sizeAtStart1 = -1;
    int32_t sizeAtStart2 = -1;
    std::vector<int16_t> drainedAtStart2;
    bool sawClose1 = false;
    bool sawClose2 = false;
    int32_t sizeAtClose1 = -1;

    if (started) {
        while (true) {
            const int event = processor.waitForEvent(30000);
            if (event == 1) {
                // THE CALL SITE: this is exactly what VoiceAsrEngine.SpeechStarted does on main —
                // read the size counter, then drain the buffer as the utterance's initial chunk.
                if (sizeAtStart1 < 0) {
                    sizeAtStart1 = processor.getSpeechPcmSize();
                } else {
                    sizeAtStart2 = processor.getSpeechPcmSize();
                    std::vector<int16_t> buf(static_cast<size_t>(sizeAtStart2));
                    const int32_t copied = processor.getSpeechPcm(buf.data(), sizeAtStart2);
                    drainedAtStart2.assign(buf.begin(), buf.begin() + copied);
                }
            } else if (event == 2) {
                if (!sawClose1) {
                    sawClose1 = true;
                    sizeAtClose1 = processor.getSpeechPcmSize();
                    std::printf("      close #1: snapshot size=%d\n", sizeAtClose1);
                } else {
                    sawClose2 = true;
                    std::printf("      close #2: snapshot size=%d\n", processor.getSpeechPcmSize());
                }
            } else if (event == 0) {
                // Timeout: the script is fully served and nothing more is coming.
                if (sizeAtStart2 >= 0 && sawClose2) break;
                std::printf("      waitForEvent timeout (start2=%d close2=%d)\n",
                    sizeAtStart2, sawClose2 ? 1 : 0);
                break;
            } else {
                std::printf("      waitForEvent returned %d\n", event);
                break;
            }
        }
    }
    processor.stop();

    check(sizeAtStart1 == 0, "utterance 1 SpeechStarted sees an EMPTY snapshot (size=0)");
    std::printf("      size at start #1 = %d, size at close #1 = %d\n", sizeAtStart1, sizeAtClose1);
    std::printf(
        "      size at start #2 = %d (utterance 1 was %d frames = %d samples)\n",
        sizeAtStart2, utterance1, utterance1 * kFrame);
    if (sizeAtStart2 > 0) {
        // What fraction of the drained "initial chunk" is actually SPEECH audio (the previous
        // utterance's loud frames) rather than silence?
        int64_t energy = 0;
        for (const int16_t s : drainedAtStart2) energy += static_cast<int64_t>(s) * s;
        const double rms = drainedAtStart2.empty()
            ? 0.0
            : __builtin_sqrt(static_cast<double>(energy) / drainedAtStart2.size());
        std::printf(
            "      drained %zu samples at utterance 2 start; rms=%.0f (4000 = speech, 0 = silence)\n",
            drainedAtStart2.size(), rms);
        check(rms > 1000.0,
            "carry-over CONFIRMED: utterance 2's SpeechStarted drains utterance 1's audio");
    } else {
        check(false, "carry-over NOT observed: utterance 2 SpeechStarted saw an empty snapshot");
    }

    std::printf("%s\n", gFailures == 0 ? "PASS" : "FAIL");
    return gFailures == 0 ? 0 : 1;
}
