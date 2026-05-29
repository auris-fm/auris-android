cmake_minimum_required(VERSION 3.22)
project("pocketcasts-voice-capture" CXX)

# Apply 16KB page size alignment for Android 16
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# Enable ARM v8.2-A dot product instructions for quantized matmul on CPU
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod")
endif()

include(FetchContent)

# Build Oboe from source (statically linked) to get proper 16KB segment alignment.
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.9.0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(oboe)

# --- pocketcasts_voice_capture (Oboe capture + VAD JNI) ---
add_library(pocketcasts_voice_capture SHARED
    OboeAudioCapture.cpp
    jni_bridge.cpp
    VadJni.cpp
    EmbeddingJni.cpp
)

target_include_directories(pocketcasts_voice_capture PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${oboe_SOURCE_DIR}/include
)

target_link_libraries(pocketcasts_voice_capture
    oboe
    log
    android
)

target_compile_features(pocketcasts_voice_capture PUBLIC cxx_std_17)
target_compile_options(pocketcasts_voice_capture PRIVATE -Wall -Werror -Os -fvisibility=hidden)
