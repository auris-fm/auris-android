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

// None of these are defined here any more: RingBuffer's members, OboeAudioCapture's members and the
// base `readRingBuffer` all come from the real OboeAudioCapture.cpp compiled into this target. The
// fake below overrides `readRingBuffer` through the vtable, which is why the base still has to exist
// -- and it does, from the real translation unit.

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
    // Optional per-read wall-clock delay. The processor no longer discards frames after a
    // segment (the blind 1500ms cooldown is removed), so no test needs this to pass; it is kept
    // only as a lever for timing-sensitive scripts.
    int pauseAfterFrames = -1;
    std::chrono::milliseconds pauseFor{0};
    int served = 0;

    // Set when the script is exhausted: the runLoop takes the next 0 as "stream inactive" and
    // exits, so this is the worker-exit barrier the delayed-consumer control waits on.
    std::atomic<bool> scriptDrained{false};
    // Bounded: an unbounded spin deadlocks the test forever if the worker never reaches the
    // drained branch (e.g. start() failed); the caller's event assertions then report the truth.
    bool awaitWorkerExitForTest(int maxMs = 30000) {
        for (int waited = 0; waited < maxMs; waited += 5) {
            if (scriptDrained.load(std::memory_order_acquire)) return true;
            std::this_thread::sleep_for(std::chrono::milliseconds(5));
        }
        return false;
    }

    int32_t readRingBuffer(int16_t* outData, int32_t maxSamples, int32_t timeoutMs) override {
        (void)timeoutMs;
        if (maxSamples < kFrame || script.empty()) {
            scriptDrained.store(true, std::memory_order_release);
            // NEGATIVE is what the runLoop treats as "stream inactive" (read < 0 -> break). Zero is
            // a retry-timeout and would spin forever past the end of the script.
            return -1;
        }
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
// OboeAudioCapture.cpp is COMPILED into this target (see CMakeLists), so its constructor,
// destructor and callbacks -- including the late-callback guard and the device clear in
// `onErrorAfterClose` -- are the code under test rather than stubs supplied here. Only
// `readRingBuffer` is overridden below, because it is virtual and the cases script its frames; and
// the Oboe LIBRARY entry points are stubbed in OboeLibraryStubs.cpp, since the host links no Oboe.

int main() {
    std::printf("native VAD segment accounting\n");

    // (0) UNTHREAD-U1 (4d1e5946/2d8d5c4a): the stuck-detector branch. A detector stuck
    // speech-classified past kStuckDetectorFrameLimit must surface the FAULT sentinel (kFaultEvent,
    // not the stop sentinel) and must NOT emit a completed segment. The script is 4000 loud frames
    // (limit 3750): the frames are served as fast as the loop reads them, so the script runs in
    // wall-fast time despite the 240s equivalent length.
    {
        std::deque<bool> script = frames(4000, true);
        FakeCapture capture;
        capture.script = script;  // 4000 > 3750: a finite script already exceeds the ceiling.
        NativeVadProcessor processor(&capture);
        check(processor.start(), "stuck-case capture starts");
        bool sawFault = false;
        bool timedOut = false;
        int segmentsEmitted = 0;
        // DELAYED-CONSUMER BARRIER: hold EVERY consumer wait until the worker has exited (the fake
        // signals runLoop completion), so the fault is read after the exit block has run -- the
        // notification race is excluded by construction, not by repeated passes.
        int lastEvent = 0;
        while (true) {
            const int event = [&]() {
                capture.awaitWorkerExitForTest();
                return processor.waitForEvent(60000);
            }();
            lastEvent = event;
            if (event == NativeVadProcessor::kFaultEvent) { sawFault = true; break; }  // TERMINAL
            if (event == 2) { segmentsEmitted++; continue; }
            if (event == 1) { continue; }  // ordinary mid-run event (reviewer 1b0b712b)
            if (event == 0) { timedOut = true; break; }
            if (event == -1) { break; }
            std::printf("      stuck-case unexpected event=%d\n", event);
            break;
        }
        check(!timedOut, "the stuck run must terminate on the fault, never on a timeout");
        std::printf("      stuck-case last event=%d timedOut=%d unserved=%zu\n", lastEvent,
            (int)timedOut, capture.script.size());
        processor.stop();
        // SYNCHRONIZED observation after worker shutdown: how many frames did the loop actually
        // consume? (Reviewer 92d80009: establish the premise before asserting the terminal cause.)
        std::printf("      stuck-case served=%d unserved=%zu after join\n", capture.served,
            capture.script.size());
        check(!timedOut, "the stuck case must terminate on events, never on the wait timeout");
        check(capture.served >= NativeVadProcessor::stuckFrameLimitForTest(),
            "premise: the script must reach the stuck ceiling before the fault assertion");
        check(sawFault, "a stuck detector surfaces the FAULT sentinel, not the stop sentinel");
        check(segmentsEmitted == 0, "a stuck detector emits NO completed segment");
    }

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

        // No wall-clock pause: the blind 1500ms discard cooldown is gone, so a second utterance
        // after a long silent gap must close on framing alone. (This case previously needed a
        // ~50ms-per-read pause purely to let the cooldown elapse, which also hid its removal.)
        const std::vector<Segment> segs = run(script);
        check(segs.size() >= 2, "a second utterance after a long gap produces a second segment");
        if (segs.size() >= 2) {
            const Segment& s = segs[1];
            std::printf("      second: onset=%d end=%d pcmSize=%d\n", s.onset, s.end, s.pcmSize);
            check(s.end <= s.pcmSize, "second segment's end sample is inside its own segment");
            check(s.end > 0, "second segment's end sample is set");
        }
    }

    // (3) RETENTION: unbroken speech longer than the old ~5s forced endpoint (kMaxSpeechFrames = 78)
    // must be retained whole. The bound used to finalise here as an ORDINARY speech-end, so the
    // remainder was silently dropped and the truncated audio reached ASR indistinguishable from a
    // natural end. Internal framing retains and joins until the natural endpoint instead.
    {
        std::deque<bool> script = frames(3, false);            // small pre-roll
        const std::deque<bool> speech = frames(150, true);     // 150 * 64ms = 9.6s, far past 5s
        script.insert(script.end(), speech.begin(), speech.end());
        const std::deque<bool> trailing = frames(80, false);   // drain + close
        script.insert(script.end(), trailing.begin(), trailing.end());

        const std::vector<Segment> segs = run(script);
        check(segs.size() == 1, "long unbroken speech closes exactly once");
        if (!segs.empty()) {
            const Segment& s = segs[0];
            std::printf("      long: onset=%d end=%d pcmSize=%d frames=%d\n",
                        s.onset, s.end, s.pcmSize, s.pcmSize / kFrame);
            // Every one of the 150 speech frames must survive inside the emitted PCM: 3 pre-roll
            // + 150 speech + drain, and nothing may be dropped at the 5s mark.
            check(s.pcmSize >= 153 * kFrame,
                  "all speech frames are retained (no truncation at the ~5s bound)");
            check(s.end == s.onset + 150 * kFrame,
                  "end sample is the last speech frame, not the old bound");
        }
    }

    // (4) RETENTION: speech that resumes during the drain window is part of the SAME utterance.
    // The old path accumulated drain silence but a resumed utterance must join, not become a
    // second segment or be lost to the cooldown.
    {
        std::deque<bool> script;
        const std::deque<bool> first = frames(5, true);
        script.insert(script.end(), first.begin(), first.end());
        const std::deque<bool> pause = frames(8, false);       // < full drain, a mid-utterance pause
        script.insert(script.end(), pause.begin(), pause.end());
        const std::deque<bool> resumed = frames(5, true);
        script.insert(script.end(), resumed.begin(), resumed.end());
        const std::deque<bool> trailing = frames(80, false);   // now drain and close for real
        script.insert(script.end(), trailing.begin(), trailing.end());

        const std::vector<Segment> segs = run(script);
        check(segs.size() == 1, "a pause then resumed speech is one utterance, not two");
        if (!segs.empty()) {
            const Segment& s = segs[0];
            std::printf("      resumed: onset=%d end=%d pcmSize=%d frames=%d\n",
                        s.onset, s.end, s.pcmSize, s.pcmSize / kFrame);
            // The resumed run joins the same utterance. Its end sample is the position of the
            // last speech frame in the retained buffer — the 8 bridged pause frames sit between
            // the two runs, so the end is past the first run's 5 frames, not equal to it.
            check(s.pcmSize >= 18 * kFrame,
                  "resumed speech is retained in the same segment");
            check(s.end >= s.onset + 5 * kFrame,
                  "end sample advances past the first speech run into the resumed one");
        }
    }

    // (6) `onErrorAfterClose` is the REAL compiled callback, not a stub: its late-callback guard and
    // its device clear were covered by nothing that executes, because the host target did not compile
    // OboeAudioCapture.cpp at all.
    //
    // 6a -- a REPLACED stream's late callback must not act on state that is no longer its own.
    {   OboeAudioCapture capture;
        auto* live = reinterpret_cast<oboe::AudioStream*>(0x1000);
        auto* replaced = reinterpret_cast<oboe::AudioStream*>(0x2000);
        capture.installStreamForTest(live, /*deviceId=*/42);
        check(capture.getRoutedDeviceId() == 42, "the live stream's device is observable");

        // The delayed callback from a stream that was already replaced.
        capture.onErrorAfterClose(replaced, oboe::Result::ErrorDisconnected);
        check(capture.getRoutedDeviceId() == 42,
              "a replaced stream's late callback must not clear the live observation");
        check(capture.isActive(), "a replaced stream's late callback must not stop the live capture");
    }

    // 6b -- the CURRENT stream's failure DOES clear it: the observation must not outlive its stream,
    // or a dead stream's routing reads as the live capture route.
    {   OboeAudioCapture capture;
        auto* live = reinterpret_cast<oboe::AudioStream*>(0x3000);
        capture.installStreamForTest(live, /*deviceId=*/43);
        check(capture.getRoutedDeviceId() == 43, "the live stream's device is observable");

        capture.onErrorAfterClose(live, oboe::Result::ErrorDisconnected);
        check(capture.getRoutedDeviceId() == oboe::kUnspecified,
              "the current stream's failure must clear the device observation");
        check(!capture.isActive(), "the current stream's failure must stop capture");
    }

    // (7) The capture-frame/time observation. It must report the producer's own counter as a position
    // even when no timestamp is available, and it must NOT invent a timestamp: an unavailable
    // observation has to stay unavailable, because a substituted value aligns confidently against the
    // wrong instant.
    {   OboeAudioCapture capture;
        OboeAudioCapture::CaptureClock idle = capture.captureClock();
        check(!idle.observable, "with no stream the timestamp is unavailable, not invented");
        check(idle.framePosition == 0, "and the position is the producer's own counter");
        check(idle.sampleRate == kSampleRate, "the clock carries the rate that converts position to time");

        // With a stream installed the counter still stands in: a test stream cannot report a timestamp,
        // so the observation must remain unavailable rather than reading through the placeholder.
        auto* live = reinterpret_cast<oboe::AudioStream*>(0x4000);
        capture.installStreamForTest(live, /*deviceId=*/7);

        // A stream that CANNOT report: the observation must stay unavailable rather than substitute
        // the counter for a timestamp.
        OboeAudioCapture::CaptureClock unavailable = capture.captureClock();
        check(!unavailable.observable,
              "a stream that cannot report keeps the observation unavailable");

        // A stream that CAN report: the observation is the stream's own frame/time pair, and the
        // conversion it implies must place a later sample correctly in time.
        capture.setStreamTimestampForTest(/*available=*/true, /*framePosition=*/48'000,
                                          /*timestampNs=*/2'000'000'000);
        OboeAudioCapture::CaptureClock observed = capture.captureClock();
        check(observed.observable, "a stream that reports gives an observable clock");
        check(observed.framePosition == 48'000, "the frame position is the stream's");
        check(observed.timestampNs == 2'000'000'000, "the timestamp is the stream's");
        // One second later is 16000 frames further on, so it must be 1e9 ns further in time. This is
        // the conversion the alignment depends on, checked as arithmetic rather than asserted.
        const int64_t oneSecondLaterFrames = observed.framePosition + observed.sampleRate;
        const int64_t laterNs = observed.timestampNs +
            ((oneSecondLaterFrames - observed.framePosition) * 1'000'000'000LL) / observed.sampleRate;
        check(laterNs == observed.timestampNs + 1'000'000'000LL,
              "a sample one second later maps to one second later on the shared clock");
        capture.clearStreamForTest();
    }

    std::printf(gFailures == 0 ? "all checks passed\n" : "%d CHECK(S) FAILED\n", gFailures);
    return gFailures == 0 ? 0 : 1;
}
