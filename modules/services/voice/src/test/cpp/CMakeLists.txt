cmake_minimum_required(VERSION 3.18)
project(vad_host_tests CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

find_package(Threads REQUIRED)

# jni.h ships with the Android NDK sysroot, not the macOS toolchain. The JNI ownership target
# compiles the real jni_bridge.cpp (which includes <jni.h>) on the host, so point at the NDK
# headers when they exist; the other targets never include jni.h and ignore this.
if(NOT DEFINED JNI_INCLUDE OR JNI_INCLUDE STREQUAL "")
    file(GLOB_RECURSE NDK_JNI_HEADERS "$ENV{HOME}/Library/Android/sdk/ndk/*/toolchains/llvm/prebuilt/*/sysroot/usr/include/jni.h")
    if(NDK_JNI_HEADERS)
        list(SORT NDK_JNI_HEADERS)
        list(GET NDK_JNI_HEADERS -1 LATEST_NDK_JNI_HEADER)
        get_filename_component(JNI_INCLUDE_RESOLVED "${LATEST_NDK_JNI_HEADER}" DIRECTORY)
    else()
        set(JNI_INCLUDE_RESOLVED "")
    endif()
else()
    set(JNI_INCLUDE_RESOLVED "${JNI_INCLUDE}")
endif()
message(STATUS "host jni include dir: ${JNI_INCLUDE_RESOLVED}")
# The NDK sysroot include dir also contains linux/stdio.h and friends, which would HIJACK the
# host's own libc headers and break on asm/types.h. Instead, generate a wrapper directory whose
# jni.h includes the NDK header by ABSOLUTE path: <jni.h> resolves to the wrapper, everything
# else keeps resolving to the macOS SDK.
if(JNI_INCLUDE_RESOLVED)
    set(JNI_SHIM_DIR "${CMAKE_BINARY_DIR}/jni_shim")
    file(MAKE_DIRECTORY "${JNI_SHIM_DIR}")
    file(WRITE "${JNI_SHIM_DIR}/jni.h" "#include \"${JNI_INCLUDE_RESOLVED}/jni.h\"\n")
    set(JNI_INCLUDE_DIRS "${JNI_SHIM_DIR}")
else()
    message(FATAL_ERROR "JNI headers are required for the native ownership control; set JNI_INCLUDE")
endif()

# Host-only target: no Oboe library, no Android libraries. The shim dir covers logging, and
# NativeVadProcessor uses only std threads and containers otherwise.
add_executable(vad_segment_test
    VadSegmentAccountingTest.cpp
    ${CPP_DIR}/NativeVadProcessor.cpp
    # Compiled so its real callbacks are the ones under test, rather than an empty stub in the test
    # file: `onErrorAfterClose` carries a late-callback guard and a device clear, and with the stub it
    # was covered by nothing that executes.
    ${CPP_DIR}/OboeAudioCapture.cpp
    OboeLibraryStubs.cpp
)

target_include_directories(vad_segment_test PRIVATE
    ${SHIM_DIR}
    ${CPP_DIR}
    ${OBOE_INCLUDE}
)

target_compile_options(vad_segment_test PRIVATE -Wall -Wextra -O1 -g)

target_link_libraries(vad_segment_test PRIVATE Threads::Threads)

# The in-progress utterance snapshot: what the client can read while the user is still speaking.
# Same shape as vad_segment_test, because both drive the real frame loop through a scripted capture.
add_executable(vad_in_progress_test
    VadInProgressSnapshotTest.cpp
    ${CPP_DIR}/NativeVadProcessor.cpp
    ${CPP_DIR}/OboeAudioCapture.cpp
    OboeLibraryStubs.cpp
)

target_include_directories(vad_in_progress_test PRIVATE
    ${SHIM_DIR}
    ${CPP_DIR}
    ${OBOE_INCLUDE}
)

target_compile_options(vad_in_progress_test PRIVATE -Wall -Wextra -O1 -g)

target_link_libraries(vad_in_progress_test PRIVATE Threads::Threads)

# The JNI processor lifetime guard is header-only and templated on the processor type, so this
# target needs only the production header — no Oboe, no JNI, no NativeVadProcessor.cpp.
add_executable(vad_handle_test
    VadProcessorHandleTest.cpp
)

target_include_directories(vad_handle_test PRIVATE
    ${CPP_DIR}
)

target_compile_options(vad_handle_test PRIVATE -Wall -Wextra -O1 -g)

target_link_libraries(vad_handle_test PRIVATE Threads::Threads)

# The snapshot carry-over probe drives the real processor's frame loop through two utterances
# in one session and reports what a SpeechStarted caller would drain. Same shape as the segment
# target: no Oboe library, VAD externs stubbed in the test file.
add_executable(vad_snapshot_carryover_test
    VadSnapshotCarryoverTest.cpp
    ${CPP_DIR}/NativeVadProcessor.cpp
    ${CPP_DIR}/OboeAudioCapture.cpp
    OboeLibraryStubs.cpp
)

target_include_directories(vad_snapshot_carryover_test PRIVATE
    ${SHIM_DIR}
    ${CPP_DIR}
    ${OBOE_INCLUDE}
)

target_compile_options(vad_snapshot_carryover_test PRIVATE -Wall -Wextra -O1 -g)

target_link_libraries(vad_snapshot_carryover_test PRIVATE Threads::Threads)

# Each queued end event must deliver its OWN segment. The producer builds the finished-segment copy
# from the same snapshot members waitForEvent overwrites when it serves an end event, so the copy has
# to happen under mSpeechMutex. Same shape as the carry-over target: no Oboe library, VAD externs
# stubbed in the test file.
add_executable(vad_segment_delivery_test
    VadSegmentDeliveryTest.cpp
    ${CPP_DIR}/NativeVadProcessor.cpp
    ${CPP_DIR}/OboeAudioCapture.cpp
    OboeLibraryStubs.cpp
)

target_include_directories(vad_segment_delivery_test PRIVATE
    ${SHIM_DIR}
    ${CPP_DIR}
    ${OBOE_INCLUDE}
)

target_compile_options(vad_segment_delivery_test PRIVATE -Wall -Wextra -O1 -g)

target_link_libraries(vad_segment_delivery_test PRIVATE Threads::Threads)

# The same control built with ThreadSanitizer, so the race question is answerable by anyone rather
# than only by a hand-run command. TSAN reports an unsynchronised access pair from the vector's own
# history, without needing the bad interleaving to occur.
#
# WHAT IT SHOWS TODAY: nothing, either with the segment copy inside mSpeechMutex or with it moved back
# outside that scope -- including with a consumer busy for 30 ms after each delivery, so later
# utterances complete while it is still on an older queued end event. The two versions differ exactly
# as the fix intended: the copy of mSnapshotBuffer holds mSpeechMutex in this build, and did not before
# it. The sanitizer reports no race in either, and this build's code does not explain that silence, so
# it is not offered as evidence for the placement -- which stays verified against the source. TSAN does
# report a deliberate race in a self-test on this toolchain, so the silence concerns this access pair
# rather than the instrumentation.
add_executable(vad_segment_delivery_tsan_test
    VadSegmentDeliveryTest.cpp
    ${CPP_DIR}/NativeVadProcessor.cpp
    ${CPP_DIR}/OboeAudioCapture.cpp
    OboeLibraryStubs.cpp
)

target_include_directories(vad_segment_delivery_tsan_test PRIVATE
    ${SHIM_DIR}
    ${CPP_DIR}
    ${OBOE_INCLUDE}
)

# Sanitizer in BOTH compile and link, or the runtime is not instrumented.
target_compile_options(vad_segment_delivery_tsan_test PRIVATE -Wall -Wextra -O1 -g -fsanitize=thread)
target_link_options(vad_segment_delivery_tsan_test PRIVATE -fsanitize=thread)

target_link_libraries(vad_segment_delivery_tsan_test PRIVATE Threads::Threads)

# The capture/VAD start-ownership control compiles the REAL jni_bridge.cpp entry points together
# with the real capture and processor translation units. JniBridgeHostPlatformStubs.cpp supplies
# ONLY the platform boundaries a host lacks (the five JNIEnv calls jni_bridge.cpp makes, dlopen,
# ANativeActivity, the ONNX-backed vad bootstrap); OboeLibraryStubs.cpp supplies the Oboe library
# symbols, as in the segment target above.
add_executable(jni_bridge_ownership_test
    JniBridgeOwnershipTest.cpp
    JniBridgeHostPlatformStubs.cpp
    ${CPP_DIR}/jni_bridge.cpp
    ${CPP_DIR}/NativeVadProcessor.cpp
    # Compiled so its real callbacks are the ones under test, rather than an empty stub in the test
    # file: `onErrorAfterClose` carries a late-callback guard and a device clear, and with the stub it
    # was covered by nothing that executes.
    ${CPP_DIR}/OboeAudioCapture.cpp
    OboeLibraryStubs.cpp
)

target_include_directories(jni_bridge_ownership_test PRIVATE
    ${SHIM_DIR}
    ${CPP_DIR}
    ${OBOE_INCLUDE}
    ${JNI_INCLUDE_DIRS}
)

target_compile_options(jni_bridge_ownership_test PRIVATE -Wall -Wextra -O1 -g)

target_link_libraries(jni_bridge_ownership_test PRIVATE Threads::Threads)
