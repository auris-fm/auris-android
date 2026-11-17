// Host-only probe: each queued end event must deliver ITS OWN segment's audio.
//
// The finished-segment copy is built from the snapshot members (mSnapshotBuffer and its onset /
// end / stream-position companions). Those members are also what waitForEvent OVERWRITES when it
// serves an end event: it moves the queued segment's pcm into mSnapshotBuffer under mSpeechMutex.
// So if the producer builds its CompletedSegment outside that mutex, a consumer serving the
// PREVIOUS end event can move the buffer out from under the copy -- handing recognition the wrong
// utterance's audio, or a torn vector. Building the segment inside the locked scope removes the
// window entirely.
//
// This drives the real frame loop through TWO utterances whose samples differ, consumes both end
// events back to back, and checks each delivered segment carries its own audio and its own
// accounting. A consumer that lags (the realistic case: recognition is slower than capture) is
// exercised by draining both events only after both have been published.
//
// Run it with: src/test/cpp/run.sh
//
// Same substitution trick as VadSegmentAccountingTest: NativeVadProcessor only calls readRingBuffer
// on its capture, so a fake subclass scripts the audio; sileroVadPredict / sileroVadResetState are
// extern and defined here (speech = loud enough for the RMS gate).

#include "NativeVadProcessor.h"
#include "OboeAudioCapture.h"

#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <atomic>
#include <deque>
#include <mutex>
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

namespace {

constexpr int32_t kFrame = 1024;  // NativeVadProcessor::kVadFrameSize

// Two DIFFERENT amplitudes, both above the RMS gate, so a segment carrying the other utterance's
// audio is detectable rather than merely "some speech".
constexpr int16_t kFirstAmplitude = 3000;
constexpr int16_t kSecondAmplitude = 9000;

class FakeCapture : public OboeAudioCapture {
public:
    std::deque<int16_t> script;  // amplitude per frame; 0 = silence
    int served = 0;
    int pauseAfterFrames = 0;
    std::chrono::milliseconds pauseFor{5};
    // Runs on the PRODUCER thread at a chosen frame index: pausing here holds the producer still
    // while a consumer thread drains, which is how the copy window is entered deliberately.
    int stallAtFrame = -1;
    std::chrono::milliseconds stallFor{0};

    int32_t readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs) override {
        (void)timeoutMs;
        if (maxSamples < kFrame || script.empty()) return 0;
        if (pauseAfterFrames >= 0 && served >= pauseAfterFrames) {
            std::this_thread::sleep_for(pauseFor);
        }
        served++;
        if (served == stallAtFrame) std::this_thread::sleep_for(stallFor);
        const int16_t amplitude = script.front();
        script.pop_front();
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

void append(std::deque<int16_t>& script, int count, int16_t amplitude) {
    for (int i = 0; i < count; i++) script.push_back(amplitude);
}

/**
 * A delivered segment carries its OWN utterance's speech and NONE of the other's. Silence around
 * the speech is legitimate (the segment includes leading context and trailing silence), so the
 * invariant is about which SPEECH amplitudes appear, not that every sample matches.
 */
bool carriesOnly(const std::vector<int16_t>& pcm, int16_t own, int16_t foreign) {
    bool sawOwn = false;
    for (const int16_t s : pcm) {
        if (s == foreign) return false;   // the other utterance's audio leaked in
        if (s == own) sawOwn = true;
        else if (s != 0) return false;    // anything else is corruption
    }
    return sawOwn;
}

}  // namespace

int main() {
    std::printf("native VAD: each end event delivers its own segment\n");

    // A LAGGING CONSUMER is the exposure: both utterances finish before either end event is read,
    // so two CompletedSegments are queued and the producer's second copy runs while the consumer
    // is still entitled to move the snapshot for the first.
    FakeCapture capture;
    // Throttled reads, as the carry-over control does: at full host speed the frame loop outruns
    // the silence accounting and the stream ends before an utterance closes.
    capture.pauseAfterFrames = 0;
    capture.pauseFor = std::chrono::milliseconds(5);
    append(capture.script, 30, kFirstAmplitude);
    append(capture.script, 45, 0);
    append(capture.script, 30, kSecondAmplitude);
    append(capture.script, 90, 0);

    NativeVadProcessor processor(&capture);
    const bool started = processor.start();
    check(started, "processor starts");

    std::vector<std::vector<int16_t>> delivered;
    std::vector<int32_t> generations;

    if (started) {
        while (true) {
            int32_t generation = -1;
            const int event = processor.waitForEvent(30000, &generation);
            if (event == 2) {
                const int32_t size = processor.getSpeechPcmSize();
                std::vector<int16_t> pcm(size > 0 ? static_cast<size_t>(size) : 0);
                if (size > 0) processor.getSpeechPcm(pcm.data(), size);
                delivered.push_back(pcm);
                generations.push_back(processor.getCompletedGeneration());
                if (delivered.size() == 2) break;
            } else if (event == 1 || event == 3 || event == 4) {
                // Speech-started (1), trailing silence (3) and the length cutoff (4) are PROGRESS
                // reports, not the end of the utterance: keep reading until the end event arrives.
                // Breaking here abandons the script halfway.
                continue;
            } else {
                break;  // -1 or 0: the stream ran dry / nothing more is coming
            }
        }
        processor.stop();
    }

    check(delivered.size() == 2, "both end events were delivered");
    if (delivered.size() == 2) {
        check(carriesOnly(delivered[0], kFirstAmplitude, kSecondAmplitude),
              "the FIRST end event delivers the first utterance's audio and none of the second's");
        check(carriesOnly(delivered[1], kSecondAmplitude, kFirstAmplitude),
              "the SECOND end event delivers the second utterance's audio and none of the first's");
        check(generations[0] != generations[1],
              "the two segments carry different utterance generations");
        check(!delivered[0].empty() && !delivered[1].empty(),
              "neither segment was emptied by the other's delivery");
    }


    // ---------------------------------------------------------------------------------------
    // A CONCURRENT CONSUMER over many utterance pairs, with the producer stalled at swept points.
    //
    // WHAT THIS ESTABLISHES: every delivered segment carries exactly one utterance's speech, never
    // a mixture and never unrecognisable samples, under real concurrency. It fails if delivery is
    // broken wholesale (removing the marking entirely fails it).
    //
    // WHAT IT DOES NOT ESTABLISH: it does not discriminate the lock placement. Moving the copy back
    // outside mSpeechMutex still passes here, and a ThreadSanitizer build of this control reports no
    // race either way -- including with a consumer slow enough to still hold an older queued end
    // event while later utterances complete. TSAN does report a deliberate race in a self-test on
    // this toolchain, so its silence is about this access pair, not about the instrumentation.
    //
    // The reason is in the queue ordering: an end event is published only AFTER its segment has been
    // copied, so a consumer woken by that event cannot be moving the buffer for the segment still
    // being built. The lock placement is therefore verified by reading the code, not by this test.
    {
        std::printf("  a concurrent consumer across many back-to-back utterances\n");
        int mixed = 0;
        int checked = 0;
        for (int round = 0; round < 24 && mixed == 0; round++) {
            FakeCapture c;
            c.pauseAfterFrames = -1;   // full speed between the deliberate stalls
            append(c.script, 24, kFirstAmplitude);
            append(c.script, 40, 0);
            append(c.script, 24, kSecondAmplitude);
            append(c.script, 60, 0);
            // STALL THE PRODUCER on the frame that completes utterance 2's trailing silence. The
            // consumer thread is meanwhile free to serve end #1, whose move of mSnapshotBuffer then
            // lands inside the producer's copy window for end #2. Sweeping the stall index covers
            // the frames around that endpoint without needing to know it exactly.
            c.stallAtFrame = 64 + round;
            c.stallFor = std::chrono::milliseconds(25);

            NativeVadProcessor p(&c);
            if (!p.start()) { mixed++; break; }

            std::atomic<bool> stopConsumer{false};
            std::vector<std::vector<int16_t>> got;
            std::mutex gotLock;
            std::thread consumer([&] {
                while (!stopConsumer.load()) {
                    const int ev = p.waitForEvent(20, nullptr);
                    if (ev != 2) continue;
                    const int32_t n = p.getSpeechPcmSize();
                    std::vector<int16_t> pcm(n > 0 ? static_cast<size_t>(n) : 0);
                    if (n > 0) p.getSpeechPcm(pcm.data(), n);
                    std::lock_guard<std::mutex> g(gotLock);
                    got.push_back(std::move(pcm));
                }
            });
            // Long enough for the whole paced script to run with the consumer live throughout.
            std::this_thread::sleep_for(std::chrono::milliseconds(700));
            stopConsumer.store(true);
            consumer.join();
            p.stop();

            std::lock_guard<std::mutex> g(gotLock);
            for (const auto& pcm : got) {
                checked++;
                bool hasFirst = false, hasSecond = false, junk = false;
                for (const int16_t v : pcm) {
                    if (v == kFirstAmplitude) hasFirst = true;
                    else if (v == kSecondAmplitude) hasSecond = true;
                    else if (v != 0) junk = true;
                }
                if ((hasFirst && hasSecond) || junk || (!hasFirst && !hasSecond)) mixed++;
            }
        }
        std::printf("      segments checked=%d mixed-or-corrupt=%d\n", checked, mixed);
        check(mixed == 0, "no delivered segment mixes two utterances or carries corrupt samples");
    }

    std::printf(gFailures == 0 ? "PASS\n" : "FAILED (%d)\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
