cmake_minimum_required(VERSION 3.18)
project(vad_segment_test CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Host-only target: no Oboe library, no Android libraries. The shim dir covers logging, and
# NativeVadProcessor uses only std threads and containers otherwise.
add_executable(vad_segment_test
    VadSegmentAccountingTest.cpp
    ${CPP_DIR}/NativeVadProcessor.cpp
)

target_include_directories(vad_segment_test PRIVATE
    ${SHIM_DIR}
    ${CPP_DIR}
    ${OBOE_INCLUDE}
)

target_compile_options(vad_segment_test PRIVATE -Wall -Wextra -O1 -g)

find_package(Threads REQUIRED)
target_link_libraries(vad_segment_test PRIVATE Threads::Threads)
