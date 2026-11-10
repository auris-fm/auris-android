// Host-only test for the in-progress utterance snapshot.
//
// It exists because M1 depends on reading the utterance WHILE the user is still speaking. Before
// this, mSnapshotBuffer was only ever populated at finalization, so the client could not see any
// partial audio at all and an early wake score was impossible.
//
// The snapshot has two jobs, and both are tested here because a JVM test cannot see either: it
// must describe the utterance in progress, and it must stop describing it the moment the segment
// finalizes (or a finished segment would look like a live one and be scored twice).
//
// Run it with:  src/test/cpp/run.sh   (see run.sh for the required cached Oboe headers)

#include "NativeVadProcessor.h"
#include "OboeAudioCapture.h"

#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <deque>
#include <thread>
#include <vector>

// --- The two externs NativeVadProcessor links against ------------------------

float sileroVadPredict(const int16_t* samples, int32_t count) {
    if (samples == nullptr || count <= 0) return 0.0f;
    int64_t sum = 0;
    for (int32_t i = 0; i < count; i++) {
        sum += static_cast<int64_t>(samples[i]) * samples[i];
    }
    const double rms = __builtin_sqrt(static_cast<double>(sum) / count);
    return rms >= 200.0 ? 1.0f : 0.0f;
}

void sileroVadResetState() {}

namespace {

constexpr int32_t kFrame = 1024;  // NativeVadProcessor::kVadFrameSize

class FakeCapture : public OboeAudioCapture {
public:
    std::deque<bool> script;

    int32_t readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs) override {
        (void)timeoutMs;
        if (maxSamples < kFrame || script.empty()) return 0;
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
    if (!ok) ++gFailures;
}

void appendFrames(std::deque<bool>& f, int count, bool loud) {
    for (int i = 0; i < count; i++) f.push_back(loud);
}

}  // namespace

int main() {
    std::printf("in-progress utterance snapshot\n");

    // A long speech run, so there is plenty of time to observe it mid-utterance.
    std::deque<bool> script;
    appendFrames(script, 40, true);   // speech
    appendFrames(script, 40, false);  // silence, closing the segment

    FakeCapture capture;
    capture.script = script;
    NativeVadProcessor processor(&capture);

    check(processor.getInProgressSampleCount() == 0, "nothing is in progress before start");
    check(processor.getInProgressPcm(nullptr, 0) == 0, "no copy is possible before start");

    if (!processor.start()) {
        std::printf("FAIL  processor did not start\n");
        return 1;
    }

    // Wait for the onset, then look at the in-progress snapshot while speech is definitely running.
    const int started = processor.waitForEvent(10000);
    check(started == 1, "speech onset is reported");

    // Read IMMEDIATELY after the onset event, without sleeping: the script's speech run is short
    // enough that a 50ms pause let the whole segment finalise, so the snapshot was correctly empty
    // and the check was measuring my timing rather than the code. The onset event is published
    // inside the same locked region that marks the utterance active, so by the time it is observed
    // there is at least one frame to read.
    const int32_t inProgress = processor.getInProgressSampleCount();
    check(inProgress > 0, "an utterance in progress reports available samples");

    if (inProgress > 0) {
        std::vector<int16_t> buffer(static_cast<size_t>(inProgress));
        const int32_t copied = processor.getInProgressPcm(buffer.data(), inProgress);
        check(copied > 0, "the in-progress samples can be copied");
        check(copied <= inProgress, "the copy never exceeds the reported count");
        // The script feeds amplitude 4000 during speech, so a copy taken from the utterance is
        // speech rather than silence. Without this, a getter returning zeros would pass.
        bool anyLoud = false;
        for (int32_t i = 0; i < copied; i++) {
            if (buffer[static_cast<size_t>(i)] != 0) { anyLoud = true; break; }
        }
        check(anyLoud, "the copied samples are the utterance's, not zeros");
    }

    // Now let the segment close and confirm the partial view goes away.
    const int ended = processor.waitForEvent(15000);
    check(ended == 2, "speech end is reported");
    std::this_thread::sleep_for(std::chrono::milliseconds(50));
    check(processor.getInProgressSampleCount() == 0, "a finalized segment is not reported as in progress");
    check(processor.getSpeechPcmSize() > 0, "the finalized segment is still available through the usual getter");

    processor.stop();

    std::printf("%s\n", gFailures == 0 ? "PASS" : "FAILED");
    return gFailures == 0 ? 0 : 1;
}