// Link-time stubs for the Oboe LIBRARY symbols that `OboeAudioCapture.cpp` references.
//
// The host test target does not link liboboe, so compiling the real translation unit needs these
// provided, the same way the VAD externs are provided in the test. Only what the linked code calls is
// defined: the cases drive `onErrorAfterClose` and the accessors, not `open`/`start`/`stop`.
//
// Kept in its own file rather than in the test, because the test includes `OboeAudioCapture.h` and
// defining the class's members there would collide with the compiled translation unit.
#include "oboe/AudioStream.h"
#include "oboe/AudioStreamBuilder.h"

namespace oboe {

// A template in Oboe, so the explicit specialization is what supplies the referenced symbol.
template <>
const char* convertToText<Result>(Result) {
    return "stub";
}

Result AudioStreamBuilder::openStream(AudioStream** stream) {
    *stream = nullptr;
    return Result::ErrorUnavailable;
}

// AudioStream is abstract, but its destructor and the non-pure members a real subclass inherits are
// defined in liboboe. A test subclass must provide the vtable/typeinfo the compiler emits for it, so
// the destructor is supplied here rather than dragging in the library.
AudioStream::~AudioStream() {}

// The rest of the vtable. These are non-pure members a subclass inherits from liboboe, so the
// compiler emits references to them for ANY concrete stream subclass. The cases here drive
// OboeAudioCapture::start() and the xrun query; nothing else on the stream is exercised, so the stubs
// report the same unavailable/zero answers a stream that cannot do the thing would.
ResultWithValue<FrameTimestamp> AudioStream::getTimestamp(clockid_t) {
    return ResultWithValue<FrameTimestamp>(Result::ErrorUnimplemented);
}
Result AudioStream::close() { return Result::OK; }
Result AudioStream::start(int64_t) { return Result::OK; }
Result AudioStream::pause(int64_t) { return Result::OK; }
Result AudioStream::flush(int64_t) { return Result::OK; }
Result AudioStream::stop(int64_t) { return Result::OK; }
int64_t AudioStream::getFramesWritten() { return 0; }
int64_t AudioStream::getFramesRead() { return 0; }
Result AudioStream::waitForStateTransition(StreamState, StreamState, int64_t) {
    return Result::OK;
}

}  // namespace oboe
