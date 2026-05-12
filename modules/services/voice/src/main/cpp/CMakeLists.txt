cmake_minimum_required(VERSION 3.22)
project("pocketcasts-voice-capture" CXX)

# Oboe is provided via Prefab from the Gradle dependency
find_package(oboe REQUIRED CONFIG)

add_library(pocketcasts_voice_capture SHARED
    OboeAudioCapture.cpp
    jni_bridge.cpp
)

target_include_directories(pocketcasts_voice_capture PRIVATE ${CMAKE_CURRENT_SOURCE_DIR})

target_link_libraries(pocketcasts_voice_capture
    oboe::oboe
    log
    android
)

target_compile_features(pocketcasts_voice_capture PUBLIC cxx_std_17)

# Optimize for size - this is a small fixed-function library
target_compile_options(pocketcasts_voice_capture PRIVATE -Os -fvisibility=hidden)
