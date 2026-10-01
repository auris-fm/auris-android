// Host-only test for the production VAD segment accounting.
//
// It exists because the end-of-speech sample must be in the *emitted segment's* coordinates: the
// engine compares it against the segment it receives, and an absolute frame count throws there by
// the second utterance or after a long idle stretch. An engine test with fabricated sample values
// cannot catch that, so this drives the real NativeVadProcessor frame loop instead.
//
// Run it with:  src/test/cpp/run.sh            (optionally: run.sh <build-dir>)
//
// How it works without Oboe or Silero: NativeVadProcessor only ever calls readRingBuffer on its
// capture, so a fake subclass supplies the audio; and sileroVadPredict / sileroVadResetState are
// extern, so this translation unit defines them.

#include "NativeVadProcessor.h"
// The fake derives from this, so the complete type must be visible — NativeVadProcessor.h only
// forward-declares the capture.
#include "OboeAudioCapture.h"

#include <cstdint>
#include <cstdio>
#include <chrono>
#include <deque>
#include <thread>
#include <vector>

// --- The two externs NativeVadProcessor links against ------------------------

// Stands in for the model: the frames this test feeds are unambiguous, so speech is simply
// "loud enough to have passed the energy gate". Silence stays below kSpeechThreshold.
float sileroVadPredict(const int16_t* samples, int32_t count) {
    (void)count;
    if (samples == nullptr || count <= 0) return 0.0f;
    int64_t sum = 0;
    for (int32_t i = 0; i < count; i++) {
        sum += static_cast<int64_t>(samples[i]) * samples[i];
    }
    const double rms = count > 0 ? __builtin_sqrt(static_cast<double>(sum) / count) : 0.0;
    return rms >= 200.0 ? 1.0f : 0.0f;  // kRmsThreshold in the processor
}

void sileroVadResetState() {}

// OboeAudioCapture holds a RingBuffer member, so constructing the stub above runs its constructor.
// The host test never touches it — the VAD loop reads through readRingBuffer, which the fake
// overrides — so trivial definitions are all that is needed.
RingBuffer::RingBuffer() {}
RingBuffer::~RingBuffer() {}

// The vtable references the base implementation even though the fake overrides it; never called.
int32_t OboeAudioCapture::readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs) {
    (void)outData; (void)maxSamples; (void)timeoutMs;
    return 0;
}

// --- A capture that serves a scripted sequence of VAD frames -----------------

namespace {

constexpr int32_t kFrame = 1024;  // NativeVadProcessor::kVadFrameSize

// OboeAudioCapture is only needed as a base for the fake, and NativeVadProcessor calls exactly one
// method on it — now virtual, which is what makes this substitution possible. The constructor and
// destructor are defined here because the real ones live in OboeAudioCapture.cpp, which calls the
// Oboe API.
class FakeCapture : public OboeAudioCapture {
public:
    // loud = speech; quiet = silence. Consumed in order, one frame per readRingBuffer.
    std::deque<bool> script;
    // A close starts a wall-clock cooldown (kCooldownMs = 1500) during which frames are discarded,
    // and this loop only burns real time on its waits — so a scripted burst would land inside the
    // cooldown. The test parks here to let it elapse.
    int pauseAfterFrames = -1;
    std::chrono::milliseconds pauseFor{0};
    int served = 0;

    int32_t readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs) override {
        (void)timeoutMs;
        if (maxSamples < kFrame || script.empty()) return 0;  // stream inactive, like a timeout
        // Sleep on every read from pauseAfterFrames onward, so the wall-clock cooldown elapses no
        // matter where the segment happens to close. The cost is wall time — the pause stretches
        // the whole script — which is why waitForEvent's timeout has to be generous above.
        if (pauseAfterFrames >= 0 && served >= pauseAfterFrames) {
            std::this_thread::sleep_for(pauseFor);
        }
        served++;
        const bool loud = script.front();
        script.pop_front();
        const int16_t amplitude = loud ? 4000 : 0;  // far above / below the RMS gate
        for (int32_t i = 0; i < kFrame; i++) outData[i] = amplitude;
        return kFrame;
    }
};

int gFailures = 0;
void check(bool ok, const char* what) {
    std::printf("%s %s\n", ok ? "  ok  " : "FAIL  ", what);
    if (!ok) gFailures++;
}

// Feed a script, collect the segments the loop emits, and report each one's accounting.
struct Segment {
    int32_t onset = 0;
    int32_t end = 0;
    int32_t pcmSize = 0;
};
std::vector<Segment> run(const std::deque<bool>& script, int pauseAfter = -1,
                        std::chrono::milliseconds pauseFor = std::chrono::milliseconds(0)) {
    FakeCapture capture;
    capture.script = script;
    capture.pauseAfterFrames = pauseAfter;
    capture.pauseFor = pauseFor;
    NativeVadProcessor processor(&capture);
    std::vector<Segment> segments;
    if (!processor.start()) return segments;
    // waitForEvent: 1 = speech started, 2 = speech ended, 0 = timeout, -1 = stopped.
    while (true) {
        const int event = // Generous on purpose: the per-read pause stretches the script to ~10s, and a timeout here
        // aborts mid-script rather than failing a check — which is exactly what an 8s value did.
        processor.waitForEvent(30000);;
        if (event == 2) {
            segments.push_back({processor.getSpeechOnsetSample(), processor.getSpeechEndSample(),
                                processor.getSpeechPcmSize()});
            std::printf("      close #%zu onset=%d end=%d pcm=%d\n", segments.size(),
                        segments.back().onset, segments.back().end, segments.back().pcmSize);
        } else if (event <= 0) {
            // 0 = timeout, -1 = stopped. A timeout means the loop is still spinning without an event,
            // which points at the frame script rather than the VAD.
            std::printf("      waitForEvent returned %d after %zu segment(s); %zu script frames unserved\n",
                        event, segments.size(), capture.script.size());
            break;
        }
    }
    processor.stop();
    return segments;
}

std::deque<bool> frames(int count, bool loud) {
    std::deque<bool> f;
    for (int i = 0; i < count; i++) f.push_back(loud);
    return f;
}

}  // namespace

// Defined at file scope: a member definition must sit in a namespace that encloses the class, and
// the class is in the global namespace. The real ones live in OboeAudioCapture.cpp, which calls the
// Oboe API — the host test never links that, so it supplies its own.
OboeAudioCapture::OboeAudioCapture() {}
OboeAudioCapture::~OboeAudioCapture() {}

// The vtable needs every virtual, including the Oboe callbacks this class overrides. They are
// never invoked here — the VAD loop only calls readRingBuffer — so they are no-ops.
oboe::DataCallbackResult OboeAudioCapture::onAudioReady(oboe::AudioStream* stream, void* audioData,
                                                       int32_t numFrames) {
    (void)stream; (void)audioData; (void)numFrames;
    return oboe::DataCallbackResult::Continue;
}
void OboeAudioCapture::onErrorAfterClose(oboe::AudioStream* stream, oboe::Result error) {
    (void)stream; (void)error;
}

int main() {
    std::printf("native VAD segment accounting\n");

    // (1) A single timed utterance: the end sample is the end of the last *speech* frame, in the
    // segment's coordinates — which is also what makes it fit inside the emitted PCM.
    {
        std::deque<bool> script = frames(3, false);   // pre-roll, retained
        const std::deque<bool> speech = frames(5, true);
        script.insert(script.end(), speech.begin(), speech.end());
        const std::deque<bool> trailing = frames(80, false);  // > kTargetTotalFrames (55) to drain and close
        script.insert(script.end(), trailing.begin(), trailing.end());

        const std::vector<Segment> segs = run(script);
        check(segs.size() >= 1, "one utterance produces a segment");
        if (!segs.empty()) {
            const Segment& s = segs[0];
            std::printf("      onset=%d end=%d pcmSize=%d\n", s.onset, s.end, s.pcmSize);
            // Read as the engine does: the end sample must be addressable in the segment it came with.
            check(s.end > 0, "end sample is set");
            check(s.end <= s.pcmSize, "end sample is inside the emitted segment");
            check(s.end == s.onset + 5 * 1024, "end sample is exactly the last speech frame's end");
        }
    }

    // (2) The case that breaks an absolute cursor: a completed segment, a long silent gap, then
    // another segment. With a run-wide counter the second segment's end sample counts the silence
    // and lands outside its own PCM.
    {
        std::deque<bool> script;
        const std::deque<bool> first = frames(5, true);
        script.insert(script.end(), first.begin(), first.end());
        const std::deque<bool> gap = frames(120, false);  // long idle, far past kMaxContextFrames
        script.insert(script.end(), gap.begin(), gap.end());
        const std::deque<bool> second = frames(5, true);
        script.insert(script.end(), second.begin(), second.end());
        const std::deque<bool> trailing = frames(80, false);
        script.insert(script.end(), trailing.begin(), trailing.end());

        // ~20ms per read from after the burst: enough for the 1500ms cooldown to elapse while the
        // gap plays out, without encoding where the segment closes.
        const std::vector<Segment> segs = run(script, /*pauseAfter=*/5,
                                             std::chrono::milliseconds(50));
        check(segs.size() >= 2, "a second utterance after a long gap produces a second segment");
        if (segs.size() >= 2) {
            const Segment& s = segs[1];
            std::printf("      second: onset=%d end=%d pcmSize=%d\n", s.onset, s.end, s.pcmSize);
            check(s.end <= s.pcmSize, "second segment's end sample is inside its own segment");
            check(s.end > 0, "second segment's end sample is set");
        }
    }

    std::printf(gFailures == 0 ? "all checks passed\n" : "%d CHECK(S) FAILED\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
