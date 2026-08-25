#include <jni.h>
#include <vector>
#include <cstdint>
#include <mutex>
#include "decimen_codec.h"

namespace {
constexpr uint32_t kMaxPayloadBytes = 4096;
// Four grid cells plus detector-only sightings. Error sightings count toward
// the native result cap, so leave headroom or a marginal cell can hide a
// valid neighbour from the next tracked pass.
constexpr uint32_t kMaxSymbols = 12;

struct ResultBindings {
    jclass cls = nullptr;
    jmethodID ctor = nullptr;
};

ResultBindings& resultBindings(JNIEnv* env) {
    static ResultBindings bindings;
    static std::once_flag initialized;
    std::call_once(initialized, [env] {
        auto local = env->FindClass("com/android/xfer/qr/NativeQrResult");
        if (!local) return;
        bindings.cls = static_cast<jclass>(env->NewGlobalRef(local));
        env->DeleteLocalRef(local);
        if (!bindings.cls) return;
        bindings.ctor = env->GetMethodID(bindings.cls, "<init>", "(ZI[D[B)V");
    });
    return bindings;
}

const uint8_t* copyLuma(JNIEnv* env, jbyteArray luma, int width, int height) {
    const auto count = static_cast<size_t>(width) * static_cast<size_t>(height);
    if (env->GetArrayLength(luma) < static_cast<jsize>(count)) return nullptr;
    // Each decoder worker has its own scratch buffer. Reusing it removes an
    // allocation for every camera frame without pinning Java's heap across a
    // potentially long QR decode.
    thread_local std::vector<uint8_t> out;
    out.resize(count);
    env->GetByteArrayRegion(luma, 0, static_cast<jsize>(count),
                            reinterpret_cast<jbyte*>(out.data()));
    return out.data();
}

jobject makeResult(JNIEnv* env, const decimen_symbol& symbol, const uint8_t* bytes) {
    const auto& bindings = resultBindings(env);
    if (!bindings.cls || !bindings.ctor) return nullptr;
    auto quad = env->NewDoubleArray(8);
    env->SetDoubleArrayRegion(quad, 0, 8, symbol.quad);
    auto payload = env->NewByteArray(static_cast<jsize>(symbol.bytes_len));
    if (symbol.bytes_len > 0) {
        env->SetByteArrayRegion(payload, 0, static_cast<jsize>(symbol.bytes_len),
                                reinterpret_cast<const jbyte*>(bytes + symbol.bytes_offset));
    }
    return env->NewObject(bindings.cls, bindings.ctor, static_cast<jboolean>(symbol.valid), symbol.modules, quad, payload);
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_android_xfer_qr_NativeDecimenBridge_readFull(
        JNIEnv* env, jobject, jbyteArray luma, jint width, jint height) {
    if (!luma || width <= 0 || height <= 0) return nullptr;
    const auto* lum = copyLuma(env, luma, width, height);
    if (!lum) return nullptr;
    // A tracked-crop fallback has one expected QR, but a bad detector
    // candidate must not consume its only result slot before that QR. This
    // is Decimen's `readFull(..., maxSymbols=2, returnErrors=false)` path.
    decimen_symbol symbols[2]{};
    uint8_t bytes[kMaxPayloadBytes * 2]{};
    uint32_t count = 0;
    const int result = decimen_read_full_lum(lum, width, height, 1, 2, 0,
                                             symbols, 2, bytes, sizeof(bytes), &count);
    return result == DECIMEN_OK && count >= 1 ? makeResult(env, symbols[0], bytes) : nullptr;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_android_xfer_qr_NativeDecimenBridge_readFullAll(
        JNIEnv* env, jobject, jbyteArray luma, jint width, jint height) {
    if (!luma || width <= 0 || height <= 0) return nullptr;
    const auto* lum = copyLuma(env, luma, width, height);
    if (!lum) return nullptr;
    decimen_symbol symbols[kMaxSymbols]{};
    thread_local std::vector<uint8_t> bytes(static_cast<size_t>(kMaxSymbols) * kMaxPayloadBytes);
    uint32_t count = 0;
    // Keep error results too: their module count and quad are a useful anchor
    // for the cheap tracked/cropped path even before ECC succeeds.
    const int result = decimen_read_full_lum(lum, width, height, 1, kMaxSymbols, 1,
                                             symbols, kMaxSymbols, bytes.data(), bytes.size(), &count);
    if (result != DECIMEN_OK || count == 0) return nullptr;
    const auto& bindings = resultBindings(env);
    if (!bindings.cls || !bindings.ctor) return nullptr;
    auto out = env->NewObjectArray(static_cast<jsize>(count), bindings.cls, nullptr);
    for (uint32_t i = 0; i < count; ++i) {
        env->SetObjectArrayElement(out, static_cast<jsize>(i), makeResult(env, symbols[i], bytes.data()));
    }
    return out;
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_android_xfer_qr_NativeDecimenBridge_readTracked(
        JNIEnv* env, jobject, jbyteArray luma, jint width, jint height, jint modules,
        jdoubleArray quadIn) {
    if (!luma || !quadIn || width <= 0 || height <= 0 || modules <= 0 ||
        env->GetArrayLength(quadIn) != 8) return nullptr;
    const auto* lum = copyLuma(env, luma, width, height);
    if (!lum) return nullptr;
    double quad[8];
    env->GetDoubleArrayRegion(quadIn, 0, 8, quad);
    decimen_symbol symbol{};
    uint8_t bytes[kMaxPayloadBytes]{};
    const int result = decimen_read_tracked_lum(lum, width, height, modules,
                                                quad, &symbol, bytes, kMaxPayloadBytes);
    return result == DECIMEN_OK ? makeResult(env, symbol, bytes) : nullptr;
}
