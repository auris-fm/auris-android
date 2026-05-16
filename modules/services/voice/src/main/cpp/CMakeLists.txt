cmake_minimum_required(VERSION 3.22)
project("pocketcasts-voice-capture" CXX)

# Apply 16KB page size alignment for all shared libraries (Android 16 requirement)
set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384")

include(FetchContent)

# Build Oboe from source (statically linked) to get proper 16KB segment alignment.
# The Maven prebuilt liboboe.so has 4KB alignment and triggers the Android 16
# compatibility warning. When used via FetchContent, Oboe detects it is a
# subproject and builds as a static library.
FetchContent_Declare(
    oboe
    GIT_REPOSITORY https://github.com/google/oboe.git
    GIT_TAG 1.9.0
    GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(oboe)

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
        # Inject -DCMAKE_MAKE_PROGRAM into the ExternalProject's CMAKE_ARGS so the
        # vulkan-shaders-gen sub-build uses the same build tool (ninja) as the parent.
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
    # Vulkan 1.1+ functions (vkEnumerateInstanceVersion, etc.) are declared in
    # NDK headers but only exported in libvulkan.so at API 28+. Point
    # find_package(Vulkan) to API 28's library so ggml links correctly.
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
    # polling loop. The code uses exception-throwing vulkan.hpp calls without
    # a try/catch, so a vk::DeviceLostError bypasses the error check and
    # terminates as an uncaught exception.
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

    add_subdirectory(${llamacpp_SOURCE_DIR} ${llamacpp_BINARY_DIR})
endif()

# whisper.cpp is intentionally added AFTER llama.cpp so llama's ggml target
# is created first. When whisper's CMakeLists.txt checks `if (NOT TARGET ggml)`,
# it finds llama's ggml already exists, so it skips its own bundled ggml and
# uses llama's ggml instead. This avoids target name collisions in the same
# build tree. whisper.cpp v1.8.4's ggml-cpp.h header (required by whisper.cpp
# for ggml_backend_ptr RAII wrapper) lives in its bundled ggml/include dir;
# since we skip that subdirectory, we add the include path manually below.
FetchContent_Declare(
    whispercpp
    GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
    GIT_TAG v1.8.4
    GIT_SHALLOW TRUE
)
set(WHISPER_BUILD_STATIC_LIB ON CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
FetchContent_GetProperties(whispercpp)
if(NOT whispercpp_POPULATED)
    FetchContent_Populate(whispercpp)
    add_subdirectory(${whispercpp_SOURCE_DIR} ${whispercpp_BINARY_DIR})
    # whisper.cpp v1.8.4 uses ggml_backend_ptr (RAII wrapper from ggml-cpp.h)
    # which lives in its bundled ggml include dir. Since llama's ggml target
    # pre-empts the subdirectory, ggml-cpp.h isn't in the include path.
    target_include_directories(whisper PRIVATE ${whispercpp_SOURCE_DIR}/ggml/include)
endif()

# Force 16KB page size alignment on all shared library targets for Android 16
foreach(tgt llama whisper ggml ggml-base ggml-cpu ggml-vulkan omp)
    if(TARGET ${tgt})
        target_link_options(${tgt} PRIVATE -Wl,-z,max-page-size=16384 -Wl,-z,common-page-size=16384)
    endif()
endforeach()

# vulkan.hpp (C++ bindings) and SPIRV headers are not in the NDK. Find them
# on the host system. ggml-vulkan in b9174+ also needs spirv/unified1/spirv.hpp
# for SPIRV reflection of compiled shaders.
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

add_library(pocketcasts_voice_capture SHARED
    OboeAudioCapture.cpp
    jni_bridge.cpp
    WhisperJni.cpp
    LmJni.cpp
)

# NB: Do NOT add ${whispercpp_SOURCE_DIR}/ggml/include or
# ${llamacpp_SOURCE_DIR}/ggml/include here. Both whisper and llama bundle their
# own ggml headers with incompatible struct/enum definitions. Adding both to
# the include path causes whichever comes first to shadow the other, leading
# to redefinition errors in either whisper.cpp or LmJni.cpp depending on order.
# The ggml headers are propagated transitively via each library's INTERFACE
# include directories when we target_link_libraries(whisper) and
# target_link_libraries(llama).
target_include_directories(pocketcasts_voice_capture PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}
    ${whispercpp_SOURCE_DIR}/include
    ${llamacpp_SOURCE_DIR}/include
)

target_link_libraries(pocketcasts_voice_capture
    oboe
    log
    android
    vulkan
    whisper
    llama
)

target_compile_features(pocketcasts_voice_capture PUBLIC cxx_std_17)
target_compile_options(pocketcasts_voice_capture PRIVATE -Wall -Werror -Os -fvisibility=hidden)
