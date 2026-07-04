cmake_minimum_required(VERSION 3.22)
project(whisperjni)

set(BUILD_SHARED_LIBS OFF)
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES OFF)
set(WHISPER_BUILD_SERVER OFF)
set(GGML_OPENMP OFF)

# AGP passes CMAKE_BUILD_TYPE=Debug for debug variants, which compiles
# ggml at -O0. That makes whisper 30-60x slower and unusable. Force full
# optimization regardless of variant; native debugging isn't needed here.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

add_subdirectory(whisper.cpp)

add_library(whisperjni SHARED jni.cpp)
target_link_libraries(whisperjni whisper log android)
