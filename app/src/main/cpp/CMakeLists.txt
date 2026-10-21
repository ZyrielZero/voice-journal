cmake_minimum_required(VERSION 3.22)
project(whisperjni)

# Two builds of the same JNI library, chosen at load time by WhisperBridge:
#
#   libwhisperjni.so         ggml-cpu at -march=armv8.2-a+dotprod+fp16, so
#                            ggml_vdotq_s32 is a real SDOT instead of the
#                            vmull/vpaddl emulation the q5_1 x q8_1 kernel
#                            otherwise calls. Needs Cortex-A55/A75 or newer.
#   libwhisperjni_compat.so  baseline armv8-a, for A53/A73 phones, which
#                            SIGILL on the first SDOT.
#
# ggml's arch switch is a cache variable on targets with fixed names, so one
# configure cannot hold both. This project configures itself a second time
# as an ExternalProject with WJ_VARIANT=compat; the inner build writes its
# library into the same output directory AGP packages from. Only ggml-cpu
# differs between the two; whisper and ggml-base compile identically.
set(WJ_VARIANT "dotprod" CACHE STRING "Which library this configure builds: dotprod or compat")

# Must be a cache entry: whisper.cpp declares option(BUILD_SHARED_LIBS) under
# cmake_minimum_required(3.5), where policy CMP0077 is OLD and option()
# ignores a plain variable. With a plain set() the build silently produced
# libwhisper.so, libggml*.so and libparakeet.so, and two variants would then
# overwrite each other's same-named libggml-cpu.so in the output directory.
# Static, each variant is one self-contained .so.
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_TESTS OFF)
set(WHISPER_BUILD_EXAMPLES OFF)
set(WHISPER_BUILD_SERVER OFF)
set(GGML_OPENMP OFF)

# AGP passes CMAKE_BUILD_TYPE=Debug for debug variants, which compiles
# ggml at -O0. That makes whisper 30-60x slower and unusable. Force full
# optimization regardless of variant; native debugging isn't needed here.
set(CMAKE_C_FLAGS_DEBUG "-O3 -DNDEBUG")
set(CMAKE_CXX_FLAGS_DEBUG "-O3 -DNDEBUG")

# whisper and ggml are linked statically into the JNI library; nothing
# outside it needs their symbols. Hide them at compile time, and the
# version script below exports only the JNI entry points.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)

if (WJ_VARIANT STREQUAL "dotprod")
    # ggml's own set(... CACHE) will not overwrite this. The configure log
    # prints HAVE_DOTPROD and HAVE_FP16_VECTOR_ARITHMETIC; both must be 1.
    # Not +i8mm: that is Armv8.6 and would SIGILL on every pre-Armv9 core.
    set(GGML_CPU_ARM_ARCH "armv8.2-a+dotprod+fp16" CACHE STRING "" FORCE)
    set(WJ_LIB whisperjni)
elseif (WJ_VARIANT STREQUAL "compat")
    set(GGML_CPU_ARM_ARCH "" CACHE STRING "" FORCE)
    set(WJ_LIB whisperjni_compat)
else()
    message(FATAL_ERROR "WJ_VARIANT must be dotprod or compat, got '${WJ_VARIANT}'")
endif()

add_subdirectory(whisper.cpp)

add_library(${WJ_LIB} SHARED jni.cpp)
target_link_libraries(${WJ_LIB} whisper log android)
target_link_options(${WJ_LIB} PRIVATE
    "-Wl,--version-script=${CMAKE_CURRENT_SOURCE_DIR}/whisperjni.map"
    "-Wl,--exclude-libs,ALL"
    "-Wl,--gc-sections")
set_target_properties(${WJ_LIB} PROPERTIES
    LINK_DEPENDS "${CMAKE_CURRENT_SOURCE_DIR}/whisperjni.map")

if (WJ_VARIANT STREQUAL "dotprod")
    include(ExternalProject)
    # Forward exactly what AGP hands this configure, so the inner build uses
    # the same NDK, ABI, platform, STL, build type and generator.
    set(WJ_FORWARD
        -DWJ_VARIANT=compat
        -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
        -DCMAKE_MAKE_PROGRAM=${CMAKE_MAKE_PROGRAM}
        -DCMAKE_BUILD_TYPE=${CMAKE_BUILD_TYPE}
        -DCMAKE_LIBRARY_OUTPUT_DIRECTORY=${CMAKE_LIBRARY_OUTPUT_DIRECTORY}
        -DCMAKE_SYSTEM_NAME=${CMAKE_SYSTEM_NAME}
        -DCMAKE_SYSTEM_VERSION=${CMAKE_SYSTEM_VERSION}
        -DANDROID_ABI=${ANDROID_ABI}
        -DANDROID_PLATFORM=${ANDROID_PLATFORM}
        -DANDROID_NDK=${ANDROID_NDK}
        -DANDROID_STL=${ANDROID_STL})
    ExternalProject_Add(whisperjni_compat_build
        SOURCE_DIR "${CMAKE_CURRENT_SOURCE_DIR}"
        BINARY_DIR "${CMAKE_CURRENT_BINARY_DIR}/compat"
        CMAKE_GENERATOR "${CMAKE_GENERATOR}"
        CMAKE_ARGS ${WJ_FORWARD}
        BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> --target whisperjni_compat
        INSTALL_COMMAND ""
        # The inner ninja is incremental; always invoking it is how edits to
        # jni.cpp reach the compat library.
        BUILD_ALWAYS ON
        BUILD_BYPRODUCTS "${CMAKE_LIBRARY_OUTPUT_DIRECTORY}/libwhisperjni_compat.so")
    # AGP builds only the targets it knows about; hang the compat build on
    # the main library so it always runs with it.
    add_dependencies(whisperjni whisperjni_compat_build)
endif()
