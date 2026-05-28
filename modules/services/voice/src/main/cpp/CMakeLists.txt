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

# --- llama.cpp (SmolLM2 intent parsing) ---
FetchContent_Declare(
    llamacpp
    GIT_REPOSITORY https://github.com/ggml-org/llama.cpp.git
    GIT_TAG b9174
    GIT_SHALLOW TRUE
)
set(LLAMA_STATIC ON CACHE BOOL "" FORCE)
set(LLAMA_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_VULKAN ON CACHE BOOL "" FORCE)

# Find ninja path - ggml's vulkan-shaders-gen ExternalProject sub-build can't
# inherit CMAKE_MAKE_PROGRAM from the parent cache.
set(_ninja_path "${CMAKE_MAKE_PROGRAM}")
if(NOT _ninja_path)
    get_filename_component(_cmake_bindir "${CMAKE_COMMAND}" DIRECTORY)
    set(_ninja_path "${_cmake_bindir}/ninja")
endif()

FetchContent_GetProperties(llamacpp)
if(NOT llamacpp_POPULATED)
    FetchContent_Populate(llamacpp)
    if(EXISTS "${_ninja_path}")
        set(_vk_cmake "${llamacpp_SOURCE_DIR}/ggml/src/ggml-vulkan/CMakeLists.txt")
        file(READ "${_vk_cmake}" _vk_content)
        string(REPLACE
            [=[        CMAKE_ARGS -DCMAKE_INSTALL_PREFIX=${CMAKE_BINARY_DIR}/$<CONFIG>
                   -DCMAKE_INSTALL_BINDIR=.
                   -DCMAKE_BUILD_TYPE=$<CONFIG>
                   ${VULKAN_SHADER_GEN_CMAKE_ARGS}

        BUILD_COMMAND]=]
            [=[        CMAKE_ARGS -DCMAKE_INSTALL_PREFIX=${CMAKE_BINARY_DIR}/$<CONFIG>
                   -DCMAKE_INSTALL_BINDIR=.
                   -DCMAKE_BUILD_TYPE=$<CONFIG>
                   -DCMAKE_MAKE_PROGRAM=__NINJA_PATH__
                   ${VULKAN_SHADER_GEN_CMAKE_ARGS}

        BUILD_COMMAND]=]
            _vk_content "${_vk_content}")
        string(REPLACE "__NINJA_PATH__" "${_ninja_path}" _vk_content "${_vk_content}")
        file(WRITE "${_vk_cmake}" "${_vk_content}")
    endif()
    # Vulkan 1.1+ functions are declared in NDK headers but only exported
    # in libvulkan.so at API 28+. Point find_package(Vulkan) to API 28's library.
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(_vk_arch "aarch64-linux-android")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(_vk_arch "x86_64-linux-android")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(_vk_arch "armv7a-linux-androideabi")
    elseif(ANDROID_ABI STREQUAL "x86")
        set(_vk_arch "i686-linux-android")
    endif()
    if(ANDROID_NDK AND _vk_arch)
        set(_vk_lib28 "${ANDROID_NDK}/toolchains/llvm/prebuilt/darwin-x86_64/sysroot/usr/lib/${_vk_arch}/28/libvulkan.so")
        if(EXISTS "${_vk_lib28}")
            set(Vulkan_LIBRARY "${_vk_lib28}" CACHE FILEPATH "Vulkan library (API 28)" FORCE)
        endif()
    endif()
    # Patch ggml-vulkan.cpp to catch exceptions from vulkan.hpp in the fence
    # polling loop.
    set(_vk_src "${llamacpp_SOURCE_DIR}/ggml/src/ggml-vulkan/ggml-vulkan.cpp")
    file(READ "${_vk_src}" _vk_src_content)
    string(REPLACE
[=[    // Spin (w/pause) waiting for the graph to finish executing.
    vk::Result result;
    while ((result = ctx->device->device.getFenceStatus(ctx->fence)) != vk::Result::eSuccess) {
        if (result != vk::Result::eNotReady) {]=]
[=[    // Spin (w/pause) waiting for the graph to finish executing.
    vk::Result result;
    while (true) {
        try {
            result = ctx->device->device.getFenceStatus(ctx->fence);
        } catch (const vk::DeviceLostError &) {
            fprintf(stderr, "ggml_vulkan: device lost at %s:%d\n", __FILE__, __LINE__);
            exit(1);
        } catch (const vk::SystemError & e) {
            fprintf(stderr, "ggml_vulkan: system error at %s:%d: %s\n", __FILE__, __LINE__, e.what());
            exit(1);
        }
        if (result == vk::Result::eSuccess) {
            break;
        }
        if (result != vk::Result::eNotReady) {]=]
        _vk_src_content "${_vk_src_content}")
    file(WRITE "${_vk_src}" "${_vk_src_content}")

    # Patch ggml-vulkan.cpp descriptor set pool capacity floor for Mali-G715
    file(READ "${_vk_src}" _vk_src_content)
    string(REPLACE
[=[    uint32_t needed = std::max(3 * ctx->descriptor_sets.size() / 2, size_t{ctx->pipeline_descriptor_set_requirements});]=]
[=[    uint32_t needed = std::max(std::max(3 * ctx->descriptor_sets.size() / 2, size_t{ctx->pipeline_descriptor_set_requirements}), size_t(768));]=]
    _vk_src_content "${_vk_src_content}")
    file(WRITE "${_vk_src}" "${_vk_src_content}")

    # Guard: fail if the descriptor set floor patch wasn't applied
    string(FIND "${_vk_src_content}" "size_t(768)" _patch_ok)
    if(_patch_ok EQUAL -1)
        message(FATAL_ERROR "\n\n"
            "ggml-vulkan.cpp descriptor-set floor patch FAILED.\n"
            "The REPLACE pattern no longer matches ggml-vulkan.cpp.\n"
            "Update the old_string or remove this patch if upstream has a fix.\n")
    endif()

    add_subdirectory(${llamacpp_SOURCE_DIR} ${llamacpp_BINARY_DIR})
endif()

# Force 16KB page size alignment on all shared library targets
foreach(tgt llama ggml ggml-base ggml-cpu ggml-vulkan omp)
    if(TARGET ${tgt})
        target_link_options(${tgt} PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
    endif()
endforeach()

# vulkan.hpp (C++ bindings) and SPIRV headers
if(TARGET ggml-vulkan)
    find_path(VULKAN_HPP_PATH vulkan/vulkan.hpp
        NO_CMAKE_FIND_ROOT_PATH
        HINTS "/opt/homebrew/include" "/usr/local/include"
    )
    if(VULKAN_HPP_PATH)
        target_include_directories(ggml-vulkan PRIVATE "${VULKAN_HPP_PATH}")
    endif()

    find_path(SPIRV_HEADERS_PATH spirv/unified1/spirv.hpp
        NO_CMAKE_FIND_ROOT_PATH
        HINTS "${ANDROID_NDK}/sources/third_party/shaderc/third_party/spirv-tools/external/spirv-headers/include"
    )
    if(SPIRV_HEADERS_PATH)
        target_include_directories(ggml-vulkan PRIVATE "${SPIRV_HEADERS_PATH}")
    endif()
endif()

# --- pocketcasts_voice_capture (SmolLM2 JNI only) ---
add_library(pocketcasts_voice_capture SHARED
    OboeAudioCapture.cpp
    jni_bridge.cpp
    VadJni.cpp
    LmJni.cpp
)

target_include_directories(pocketcasts_voice_capture PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${llamacpp_SOURCE_DIR}/include
    ${oboe_SOURCE_DIR}/include
)

target_link_libraries(pocketcasts_voice_capture
    oboe
    log
    android
    vulkan
    llama
)

target_compile_features(pocketcasts_voice_capture PUBLIC cxx_std_17)
target_compile_options(pocketcasts_voice_capture PRIVATE -Wall -Werror -Os -fvisibility=hidden)

# FetchContent targets (llama.cpp) override or ignore CMAKE_SHARED_LINKER_FLAGS
# from the parent scope, so apply 16KB alignment explicitly per target.
foreach(tgt llama ggml ggml-base ggml-cpu ggml-vulkan omp)
    if(TARGET ${tgt})
        target_link_options(${tgt} PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
    endif()
endforeach()
