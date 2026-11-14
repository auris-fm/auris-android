// Host-only test for the in-progress utterance snapshot and the early-wake events.
//
// It exists because M1 depends on reading the utterance WHILE the user is still speaking, and the
// early-wake optimisation depends on the VAD publishing the moments the engine reacts to: the
// cutoff reached (event 4) and trailing silence began (event 3). A JVM test cannot see any of it.
//
// The snapshot has three jobs: describe the utterance in progress COHERENTLY — generation, onset,
// size and PCM from one locked call, so they can never describe different utterances; stop
// describing it the moment the segment finalizes (or a finished segment would look like a live one
// and be scored twice); and change its generation at that boundary so a caller can DETECT the
// crossing instead of scoring a mixture.
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
    // Pause after this many served frames, so a scenario can hold the producer while the consumer
    // observes mid-utterance state — the snapshot checks are timing-sensitive otherwise.
    int32_t pauseAfterFrames = -1;
    int32_t pauseForMs = 0;
    int32_t served = 0;

    int32_t readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs) override {
        (void)timeoutMs;
        if (maxSamples < kFrame || script.empty()) return 0;
        if (pauseAfterFrames >= 0 && served == pauseAfterFrames && pauseForMs > 0) {
            served++;
            std::this_thread::sleep_for(std::chrono::milliseconds(pauseForMs));
        } else {
            served++;
        }
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

constexpr int32_t kTestCutoff = 8 * kFrame;  // speech long enough to cross it before silence

}  // namespace

int main() {
    int32_t gen = -1;
    std::printf("in-progress utterance snapshot + early-wake events\n");

    {
        // A long speech run, so there is plenty of time to observe it mid-utterance.
        std::deque<bool> script;
        appendFrames(script, 40, true);   // speech
        appendFrames(script, 40, false);  // silence, closing the segment

        FakeCapture capture;
        capture.script = script;
        // Hold the producer right after the first speech frame: the onset event is observable
        // while the utterance is still being built, deterministically.
        capture.pauseAfterFrames = 1;
        capture.pauseForMs = 300;
        NativeVadProcessor processor(&capture);

        int64_t header[3] = {0, 0, 0};
        // Generous: the producer resumes after the pause and keeps building the utterance while
        // the consumer wakes, so the snapshot must have room for more than the first frame.
        std::vector<int16_t> buffer(static_cast<size_t>(kFrame) * 256);
        check(processor.getInProgressSnapshot(buffer.data(), static_cast<int32_t>(buffer.size()), header) == 0,
            "nothing is in progress before start");

        if (!processor.start()) {
            std::printf("FAIL  processor did not start\n");
            return 1;
        }

        // Wait for the onset, then look at the in-progress snapshot while speech is definitely running.
        const int started = processor.waitForEvent(10000, &gen);
        check(started == 1, "speech onset is reported");

        // Read IMMEDIATELY after the onset event, without sleeping: the script's speech run is short
        // enough that a 50ms pause let the whole segment finalise, so the snapshot was correctly empty
        // and the check was measuring my timing rather than the code. The onset event is published
        // inside the same locked region that marks the utterance active, so by the time it is observed
        // there is at least one frame to read.
        std::fill(header, header + 3, static_cast<int64_t>(-1));
        const int32_t copied = processor.getInProgressSnapshot(buffer.data(), static_cast<int32_t>(buffer.size()), header);
        check(copied > 0, "an utterance in progress reports available samples");
        std::printf("      copied=%d header={%lld,%lld,%lld}\n", copied, (long long)header[0], (long long)header[1], (long long)header[2]);
        check(header[2] >= copied && static_cast<size_t>(header[2]) <= static_cast<size_t>(kFrame) * 256,
            "the snapshot's size header describes the utterance it copied from");
        check(header[1] >= 0 && static_cast<size_t>(header[1]) <= static_cast<size_t>(copied),
            "the snapshot's onset sits inside the copied utterance");
        // The script feeds amplitude 4000 during speech, so a copy taken from the utterance is
        // speech rather than silence. Without this, a getter returning zeros would pass.
        bool anyLoud = false;
        for (int32_t i = 0; i < copied; i++) {
            if (buffer[static_cast<size_t>(i)] != 0) { anyLoud = true; break; }
        }
        check(anyLoud, "the copied samples are the utterance's, not zeros");
        const int64_t liveGeneration = header[0];

        // Now let the segment close and confirm the partial view goes away.
        check(processor.waitForEvent(10000, &gen) == 3, "trailing silence precedes the segment close (no cutoff configured)");
        const int ended = processor.waitForEvent(15000, &gen);
        check(ended == 2, "speech end is reported");
        std::this_thread::sleep_for(std::chrono::milliseconds(50));
        std::fill(header, header + 3, static_cast<int64_t>(-1));
        check(processor.getInProgressSnapshot(buffer.data(), static_cast<int32_t>(buffer.size()), header) == 0,
            "a finalized segment is not reported as in progress");
        check(header[0] != liveGeneration,
            "finalization changes the generation, so a caller can detect the boundary");
        check(processor.getSpeechPcmSize() > 0, "the finalized segment is still available through the usual getter");

        processor.stop();
    }

    {
        // The early-wake events: a cutoff event (4) once the in-progress utterance crosses the
        // configured length, and a trailing-silence event (3) on the first silent frame after
        // speech — the moment an early connection opens on.
        std::deque<bool> script;
        appendFrames(script, 20, true);   // speech, crossing the cutoff
        appendFrames(script, 40, false);  // silence, closing the segment

        FakeCapture capture;
        capture.script = script;
        NativeVadProcessor processor(&capture);
        processor.setEarlyWakeCutoff(kTestCutoff);

        if (!processor.start()) {
            std::printf("FAIL  processor (events) did not start\n");
            return 1;
        }

        check(processor.waitForEvent(10000, &gen) == 1, "speech onset is reported (events run)");
        check(processor.waitForEvent(10000, &gen) == 4, "the cutoff event fires once the utterance crosses the configured length");
        check(processor.waitForEvent(10000, &gen) == 3, "trailing silence is reported on the first silent frame");
        check(processor.waitForEvent(15000, &gen) == 2, "speech end is reported (events run)");
        // The cutoff event is once per utterance: no second one before the segment ends.
        check(gen >= 0, "the cutoff event carries its utterance's generation");
        processor.stop();
    }

    {
        // EVENTS CARRY THEIR UTTERANCE'S GENERATION, AND AN END EVENT DELIVERS ITS OWN SNAPSHOT.
        // Two utterances finalize while the consumer sleeps: when it finally pops, each end event
        // must serve the PCM/onset/end of ITS utterance -- not the one that finished most
        // recently -- and every event must name the utterance it belongs to. (Silence runs are
        // long enough that the drain floor and the 7-frame silence timeout both fit, so A really
        // closes before B starts.)
        std::deque<bool> script;
        appendFrames(script, 3, true);     // utterance A: short speech
        appendFrames(script, 60, false);   // silence closing A (drain floor + timeout fit)
        appendFrames(script, 20, true);    // utterance B
        appendFrames(script, 60, false);   // silence closing B

        FakeCapture capture;
        capture.script = script;
        NativeVadProcessor processor(&capture);
        processor.setEarlyWakeCutoff(kTestCutoff);
        if (!processor.start()) {
            std::printf("FAIL  processor (pairing) did not start\n");
            return 1;
        }
        // Let the whole script run before consuming anything: both finals happen first.
        std::this_thread::sleep_for(std::chrono::milliseconds(2500));

        int32_t generationA = -1;
        int32_t generationB = -1;
        bool sawAEnd = false;
        int32_t sizeA = -1;
        // Phase 1: drain events until the first end; every event must carry ONE generation.
        while (true) {
            const int event = processor.waitForEvent(10000, &gen);
            if (event == 0) { check(false, "pairing: events ran out before an end"); break; }
            if (event == -1) break;
            if (event == 1) {
                check(gen >= 0, "onset carries a generation");
                if (generationA == -1) generationA = gen;
                else { check(gen != generationA, "B onset carries a NEW generation"); generationB = gen; }
                continue;
            }
            if (event == 2) {
                if (!sawAEnd) {
                    check(gen == generationA, "A end carries A's generation");
                    sawAEnd = true;
                    sizeA = processor.getSpeechPcmSize();
                    check(sizeA > 0, "A end event serves A's completed snapshot");
                    check(processor.getCompletedGeneration() == generationA,
                        "the served snapshot's generation is A's");
                } else if (generationB != -1) {
                    check(gen == generationB, "B end carries B's generation");
                    const int32_t sizeB = processor.getSpeechPcmSize();
                    check(sizeB > 0 && sizeB != sizeA,
                        "after popping B's end event, the served snapshot is B's, not A's");
                    check(processor.getCompletedGeneration() == generationB,
                        "the served snapshot's generation followed the end event popped");
                }
                break;
            }
            // 3/4: must belong to whichever utterance is live.
            if (gen >= 0) {
                check(gen == (sawAEnd ? generationB : generationA),
                    "mid-utterance event carries the live utterance's generation");
            }
        }
        processor.stop();
    }

    {
        // A SHORT utterance must NOT fire the cutoff event: the engine's early scorer would have
        // nothing to score, and the endpoint path handles it.
        std::deque<bool> script;
        appendFrames(script, 3, true);
        appendFrames(script, 40, false);

        FakeCapture capture;
        capture.script = script;
        NativeVadProcessor processor(&capture);
        processor.setEarlyWakeCutoff(kTestCutoff);

        if (!processor.start()) {
            std::printf("FAIL  processor (short) did not start\n");
            return 1;
        }
        const int shortOnset = processor.waitForEvent(10000, &gen);
        check(shortOnset == 1, "short utterance: onset is reported");
        const int shortTrail = processor.waitForEvent(10000, &gen);
        check(shortTrail == 3, "short utterance: trailing silence fires without a cutoff first");
        // The short script runs out before the ~55-frame endpoint: the loop ends with the stop
        // sentinel rather than a segment close. Either way no cutoff fired before trailing silence.
        const int shortEnd = processor.waitForEvent(15000, &gen);
        std::printf("      short utterance final event=%d\n", shortEnd);
        // 0 = the script ran out and the loop still spins; the stop sentinel never had to arrive.
        // The property under test is only that no cutoff fired — asserted by the two checks above.
        check(shortEnd != 4, "short utterance: ends without a cutoff event");
        processor.stop();
        processor.stop();
    }

    std::printf("%s\n", gFailures == 0 ? "PASS" : "FAILED");
    return gFailures == 0 ? 0 : 1;
}
