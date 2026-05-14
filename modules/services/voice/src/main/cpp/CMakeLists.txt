cmake_minimum_required(VERSION 3.22)
project("pocketcasts-voice-capture" CXX)

find_package(oboe REQUIRED CONFIG)

include(FetchContent)

FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggerganov/whisper.cpp.git
    GIT_TAG v1.7.4
    GIT_SHALLOW TRUE
)
set(WHISPER_BUILD_STATIC_LIB ON CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(whispercpp)

FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggerganov/llama.cpp.git
    GIT_TAG b5079
    GIT_SHALLOW TRUE
)
set(LLAMA_STATIC ON CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_MakeAvailable(llamacpp)

add_library(pocketcasts_voice_capture SHARED
    OboeAudioCapture.cpp
    jni_bridge.cpp
    WhisperJni.cpp
    LmJni.cpp
)

target_include_directories(pocketcasts_voice_capture PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${whispercpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/include
    ${llamacpp_BINARY_DIR}/ggml/include
)

target_link_libraries(pocketcasts_voice_capture
    oboe::oboe
    log
    android
    whisper_static
    llama_static
)

target_compile_features(pocketcasts_voice_capture PUBLIC cxx_std_17)
target_compile_options(pocketcasts_voice_capture PRIVATE -Os -fvisibility=hidden)
