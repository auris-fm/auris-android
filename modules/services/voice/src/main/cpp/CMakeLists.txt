cmake_minimum_required(VERSION 3.22)
project("pocketcasts-voice-capture" CXX)

# Apply 16KB page size alignment for Android 16
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

# Enable ARM v8.2-A dot product instructions for quantized matmul on CPU
if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8.2-a+dotprod")
endif()

include(FetchContent)

# NDK provides vulkan.h but not vulkan.hpp (C++ bindings). The ggml Vulkan
# backend requires vulkan.hpp. We ship matching v1.3.275 headers in vulkan_hpp/.
include_directories(${CMAKE_CURRENT_SOURCE_DIR}/vulkan_hpp)

# Build Oboe from source (statically linked) to get proper 16KB segment alignment.
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.9.0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(oboe)

# Build whisper.cpp from source for on-device ASR with Vulkan GPU backend.
# ggml compiles GLSL shaders to SPIR-V via glslc (from Vulkan SDK, via Homebrew).
set(WHISPER_VULKAN ON CACHE BOOL "" FORCE)
set(WHISPER_SDL2 OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)
set(GGML_VULKAN_DEBUG ON CACHE BOOL "" FORCE)
set(GGML_METAL OFF CACHE BOOL "" FORCE)
set(GGML_CUDA OFF CACHE BOOL "" FORCE)
FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.8.4
    GIT_SHALLOW TRUE
)

FetchContent_GetProperties(whispercpp)
if(NOT whispercpp_POPULATED)
    FetchContent_Populate(whispercpp)
endif()
find_program(PATCH_EXECUTABLE patch REQUIRED)
execute_process(
    COMMAND ${PATCH_EXECUTABLE} -p0 -N -i ${CMAKE_CURRENT_SOURCE_DIR}/ggml-vulkan-pipeline-cache.patch
    WORKING_DIRECTORY ${whispercpp_SOURCE_DIR}
    RESULT_VARIABLE _patch_result
    ERROR_VARIABLE _patch_error
)
if(NOT _patch_result EQUAL 0)
    message(WARNING "pipeline cache patch failed (exit ${_patch_result}): ${_patch_error}")
endif()

set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
add_subdirectory(${whispercpp_SOURCE_DIR} ${whispercpp_BINARY_DIR})

# Force -O2 on ggml targets even in debug builds — without this, ggml matmul at -O0
# takes 12–24s per encode pass on a Pixel 8 instead of well under a second.
target_compile_options(ggml-base PRIVATE -O2)
target_compile_options(ggml-cpu PRIVATE -O2)
target_compile_options(ggml PRIVATE -O2)

# --- pocketcasts_voice_capture (Oboe capture + VAD + Embedding + Whisper JNI) ---
add_library(pocketcasts_voice_capture SHARED
    OboeAudioCapture.cpp
    jni_bridge.cpp
    NativeVadProcessor.cpp
    VadJni.cpp
    EmbeddingJni.cpp
    WhisperJni.cpp
    WakeWordJni.cpp
)

target_include_directories(pocketcasts_voice_capture PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${oboe_SOURCE_DIR}/include
    ${whispercpp_SOURCE_DIR}/include
    ${whispercpp_SOURCE_DIR}/ggml/include
)

target_link_libraries(pocketcasts_voice_capture
    oboe
    log
    android
    whisper
)

target_compile_features(pocketcasts_voice_capture PUBLIC cxx_std_17)
target_compile_options(pocketcasts_voice_capture PRIVATE -Wall -Werror -Os -fvisibility=hidden)
