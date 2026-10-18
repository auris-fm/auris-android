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
        list(GET -1 NDK_JNI_HEADERS LATEST_NDK_JNI_HEADER)
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
    set(JNI_INCLUDE_DIRS "")
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
