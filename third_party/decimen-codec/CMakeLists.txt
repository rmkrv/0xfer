# SPDX-License-Identifier: AGPL-3.0-or-later
# Copyright (c) 2026 Evan Crawley (Bash Alarmist)
#
# Decimen build of zxing-cpp: QR-only, reader-only, with a wrapper exposing
# the tracked fast path (see wrapper/decimen_codec.cpp). One tree, two targets:
#
#   wasm    ./build.sh (emsdk environment) — the ES6 module the web app ships
#   native  plain cmake, no emscripten — a static library over the same
#           wrapper, exposing the C ABI in wrapper/decimen_codec.h
#
# The native target doubles as the guard for the #ifndef __EMSCRIPTEN__ half
# of the wrapper: CI builds it on every push, so that path cannot rot behind
# a wasm-only workflow.
cmake_minimum_required(VERSION 3.16)

project(decimen-codec CXX)

set(CMAKE_CXX_STANDARD 20)

# Reader-only, QR-only: every other format is compiled OUT, not just skipped
# at runtime — smaller binary, faster instantiation, less per-worker memory.
set(ZXING_READERS ON)
set(ZXING_WRITERS OFF)
set(ZXING_C_API OFF)
set(ZXING_ENABLE_1D OFF)
set(ZXING_ENABLE_AZTEC OFF)
set(ZXING_ENABLE_DATAMATRIX OFF)
set(ZXING_ENABLE_MAXICODE OFF)
set(ZXING_ENABLE_PDF417 OFF)
set(ZXING_ENABLE_QRCODE ON)

if(NOT CMAKE_BUILD_TYPE)
    set(CMAKE_BUILD_TYPE "Release" CACHE STRING "Choose the type of build." FORCE)
endif()

if(EMSCRIPTEN)
    add_definitions("-s DISABLE_EXCEPTION_CATCHING=0")

    # WASM SIMD for the whole tree — zxing-cpp's hot loops (binarize, sampling)
    # are written for auto-vectorization (BinaryBitmap.cpp documents 16× on
    # AVX2) and run scalar without this. iOS Safari has shipped wasm SIMD since
    # 16.4; so has every browser this app supports. Set globally so the
    # LIBRARY, where those loops live, gets it — not just the wrapper. The
    # native branch needs no equivalent: the same loops auto-vectorize for the
    # target ISA (NEON on arm64) without being asked.
    set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -msimd128")
endif()

add_subdirectory(${CMAKE_CURRENT_SOURCE_DIR}/third_party/zxing-cpp/core ${CMAKE_BINARY_DIR}/ZXing)

if(EMSCRIPTEN)
    # ES6 module output usable from a web worker, the vite dev server, and Node
    # (the benchmark). No filesystem, no dynamic execution (CSP-safe).
    set(CMAKE_EXE_LINKER_FLAGS "${CMAKE_EXE_LINKER_FLAGS} --bind \
     -s ENVIRONMENT=web,worker \
     -s DISABLE_EXCEPTION_CATCHING=0 \
     -s FILESYSTEM=0 \
     -s MODULARIZE=1 \
     -s EXPORT_ES6=1 \
     -s DYNAMIC_EXECUTION=0 \
     -s EXPORT_NAME=DecimenCodec \
     -s EXPORTED_FUNCTIONS=\"['_malloc', '_free']\" \
     -s EXPORTED_RUNTIME_METHODS=HEAPU8 \
     -s ALLOW_MEMORY_GROWTH=1")

    add_executable(decimen_codec wrapper/decimen_codec.cpp)
else()
    # The same wrapper with the embind layer compiled out and the C ABI
    # compiled in. Static, because an iOS/Android consumer links it into a
    # build of their own; wrapper/decimen_codec.h is the contract, so it rides
    # along as the public include directory.
    add_library(decimen_codec STATIC wrapper/decimen_codec.cpp)
    target_include_directories(decimen_codec PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/wrapper)
endif()

target_link_libraries(decimen_codec ZXing::ZXing)
# Version + build id stamped into the module (version() / build() exports).
# build.sh passes both; the defaults only appear in by-hand cmake runs.
set(DECIMEN_CODEC_VERSION "0.0.0-dev" CACHE STRING "Version stamped into the module")
set(DECIMEN_CODEC_BUILD "dev" CACHE STRING "Build id stamped into the module")
target_compile_definitions(decimen_codec PRIVATE
    DECIMEN_CODEC_VERSION="${DECIMEN_CODEC_VERSION}"
    DECIMEN_CODEC_BUILD="${DECIMEN_CODEC_BUILD}")
# The wrapper reaches into internal headers (SampleQR, BitMatrixCursor via
# QRDetector.h) whose members are gated behind ZXING_INTERNAL — the same
# define the library target uses privately.
target_compile_definitions(decimen_codec PRIVATE ZXING_INTERNAL)
# The tracked path uses internal headers (GridSampler, PerspectiveTransform,
# QRDecoder) that an installed zxing-cpp does not ship — which is exactly why
# this is a fork build and not a dependency.
target_include_directories(decimen_codec PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/third_party/zxing-cpp/core/src)
