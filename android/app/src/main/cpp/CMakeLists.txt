# CMake build for the Rapfi native engine (librapfi_jni.so).
#
# Compiles the unmodified Rapfi C++ engine together with the embedding bridge
# and the JNI wrapper. This is the same set of sources as the standalone
# rapfi-android-engine project, cross-compiled with the Android NDK toolchain.

cmake_minimum_required(VERSION 3.18.2)
project(rapfi_jni LANGUAGES C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(RAPFI_DIR   ${CMAKE_CURRENT_SOURCE_DIR}/rapfi/Rapfi)
set(BRIDGE_DIR  ${CMAKE_CURRENT_SOURCE_DIR}/rapfi/bridge)
set(JNI_DIR     ${CMAKE_CURRENT_SOURCE_DIR}/rapfi/jni)

# ---------------------------------------------------------------------------
# SIMD / ISA selection per Android ABI
# ---------------------------------------------------------------------------
set(USE_NEON OFF)
set(USE_SSE OFF)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(USE_NEON ON)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -march=armv8-a+simd")
elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
    set(USE_NEON ON)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -mfpu=neon -march=armv7-a")
elseif(ANDROID_ABI STREQUAL "x86" OR ANDROID_ABI STREQUAL "x86_64")
    set(USE_SSE ON)
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -msse -msse2 -msse3 -mssse3 -msse4 -msse4.1")
endif()

# ---------------------------------------------------------------------------
# Rapfi engine core sources (identical to the upstream CORE_SOURCES, without
# main.cpp - the bridge drives the protocol loop directly).
# ---------------------------------------------------------------------------
set(CORE_SOURCES
    ${RAPFI_DIR}/command/argutils.cpp
    ${RAPFI_DIR}/command/benchmark.cpp
    ${RAPFI_DIR}/command/command.cpp
    ${RAPFI_DIR}/command/dbcommand.cpp
    ${RAPFI_DIR}/command/gomocup.cpp

    ${RAPFI_DIR}/core/compressor.cpp
    ${RAPFI_DIR}/core/hash.cpp
    ${RAPFI_DIR}/core/iohelper.cpp
    ${RAPFI_DIR}/core/utils.cpp
    ${RAPFI_DIR}/core/platform.cpp
    ${RAPFI_DIR}/core/version.cpp

    ${RAPFI_DIR}/database/dbclient.cpp
    ${RAPFI_DIR}/database/dbconfig.cpp
    ${RAPFI_DIR}/database/dbutils.cpp
    ${RAPFI_DIR}/database/dbtypes.cpp
    ${RAPFI_DIR}/database/renlib.cpp
    ${RAPFI_DIR}/database/yxdbstorage.cpp

    ${RAPFI_DIR}/eval/eval.cpp
    ${RAPFI_DIR}/eval/evalconfig.cpp
    ${RAPFI_DIR}/eval/evaluator.cpp
    ${RAPFI_DIR}/eval/mix9svqnnue.cpp
    ${RAPFI_DIR}/eval/mix10nnue.cpp

    ${RAPFI_DIR}/game/board.cpp
    ${RAPFI_DIR}/game/movegen.cpp
    ${RAPFI_DIR}/game/pattern.cpp

    ${RAPFI_DIR}/search/hashtable.cpp
    ${RAPFI_DIR}/search/movepick.cpp
    ${RAPFI_DIR}/search/opening.cpp
    ${RAPFI_DIR}/search/searchcommon.cpp
    ${RAPFI_DIR}/search/searchconfig.cpp
    ${RAPFI_DIR}/search/searchengine.cpp
    ${RAPFI_DIR}/search/searchoutput.cpp
    ${RAPFI_DIR}/search/searchthread.cpp
    ${RAPFI_DIR}/search/timecontrol.cpp
    ${RAPFI_DIR}/search/ab/history.cpp
    ${RAPFI_DIR}/search/ab/search.cpp
    ${RAPFI_DIR}/search/mcts/node.cpp
    ${RAPFI_DIR}/search/mcts/search.cpp

    ${RAPFI_DIR}/config.cpp
    ${RAPFI_DIR}/internalConfig.cpp
)

set(BRIDGE_SOURCES
    ${BRIDGE_DIR}/rapfi_bridge.cpp
    ${JNI_DIR}/rapfi_jni.cpp
)

add_library(rapfi_jni SHARED
    ${CORE_SOURCES}
    ${BRIDGE_SOURCES}
    ${RAPFI_DIR}/external/lz4/src/lz4_all.c
    ${RAPFI_DIR}/external/lz4/src/xxhash.c
)

target_include_directories(rapfi_jni PRIVATE
    ${RAPFI_DIR}
    ${RAPFI_DIR}/external/cpptoml/include
    ${RAPFI_DIR}/external/simde/include
    ${RAPFI_DIR}/external/lz4/include
    ${RAPFI_DIR}/external/cxxopts/include
    ${CMAKE_CURRENT_SOURCE_DIR}/rapfi
)

# Multi-threading enabled (mirrors the web "multi" build).
target_compile_definitions(rapfi_jni PRIVATE MULTI_THREADING)

if(USE_NEON)
    target_compile_definitions(rapfi_jni PRIVATE USE_NEON)
endif()
if(USE_SSE)
    target_compile_definitions(rapfi_jni PRIVATE USE_SSE)
endif()

# Disable floating-point contraction for deterministic evaluation (matches upstream).
target_compile_options(rapfi_jni PRIVATE -ffp-contract=off)

target_link_libraries(rapfi_jni PRIVATE log)
