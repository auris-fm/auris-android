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

}  // namespace oboe
