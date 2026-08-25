cmake_minimum_required(VERSION 3.22.1)
project(xfer_native LANGUAGES C CXX)

# Build Decimen's QR-only ZXing fork as a static dependency, then expose the
# two decode entry points through one small JNI bridge.
add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/decimen-codec
                 ${CMAKE_BINARY_DIR}/decimen-codec)

# Official Apache-2.0 HCC2D v0.9.0 reference encoder. Its CLI main() is
# disabled; the narrow module-index API is added locally below that source.
add_library(hcc2d_encoder STATIC
    ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/hcc2d-cli-c-encoder/single_file_c_hcc2d_encoder_v0.9.0.c)
target_compile_definitions(hcc2d_encoder PRIVATE HCC2D_LIBRARY)
find_library(z-lib z)

# The Android variant is built as "Debug" for JNI symbols, which otherwise
# leaves ZXing's internal assert() calls enabled. A bad camera candidate must
# be rejected as a normal decode miss, never abort the whole process.
target_compile_definitions(ZXing PRIVATE NDEBUG)
target_compile_definitions(decimen_codec PRIVATE NDEBUG)

add_library(decimen_jni SHARED decimen_jni.cpp hcc2d_jni.cpp hcc2d_codec.cpp hcc2d_detector.cpp)
target_include_directories(decimen_jni PRIVATE
    ${CMAKE_CURRENT_LIST_DIR}
    ${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/decimen-codec/third_party/zxing-cpp/core/src)
target_link_libraries(decimen_jni decimen_codec hcc2d_encoder ZXing::ZXing ${z-lib} log)

# AGP configures `assembleDebug` with CMAKE_BUILD_TYPE=Debug, which otherwise
# compiles the entire QR detector at -O0. The camera receiver is a production
# hot path even in a debug-signed APK, so optimize these native-only targets
# explicitly. Keep debug symbols and avoid unsafe floating-point options; QR
# geometry must remain bit-for-bit conservative.
if(CMAKE_CXX_COMPILER_ID MATCHES "Clang|GNU")
    target_compile_options(ZXing PRIVATE -O3)
    target_compile_options(decimen_codec PRIVATE -O3)
    target_compile_options(decimen_jni PRIVATE -O3)
    target_compile_options(hcc2d_encoder PRIVATE -O3)
endif()
