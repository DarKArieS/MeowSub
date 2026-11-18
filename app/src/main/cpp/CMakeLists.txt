cmake_minimum_required(VERSION 3.22.1)
project(meowsub_whisper LANGUAGES C CXX)

set(WHISPER_DIR ${CMAKE_CURRENT_SOURCE_DIR}/../../../../third_party/whisper.cpp)

# ggml without optimisation is far too slow to be usable, so debug builds of the app
# still compile the native code optimised.
if (CMAKE_BUILD_TYPE STREQUAL "Debug")
    add_compile_options(-O3)
endif ()

# whisper.cpp as a static library: CPU only, no OpenMP (ggml's own thread pool instead,
# so libomp need not be shipped), tuned for ARMv8.2 with dot-product and FP16 arithmetic
# (every arm64 phone that runs Android 16 has both).
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(WHISPER_ALL_WARNINGS OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
add_subdirectory(${WHISPER_DIR} whisper EXCLUDE_FROM_ALL)

add_library(meowsub_whisper SHARED whisper_jni.cpp)
target_link_libraries(meowsub_whisper PRIVATE whisper android log)
target_link_options(meowsub_whisper PRIVATE -Wl,--gc-sections -Wl,--exclude-libs,ALL)
