#include <jni.h>

#include "hcc2d_codec.h"
#include "hcc2d_detector.h"
#include "hcc2d_encoder_api.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <limits>
#include <mutex>
#include <vector>

namespace {
constexpr int kMaxHccDimension = 179;
constexpr int kMaxHccModules = kMaxHccDimension * kMaxHccDimension;
constexpr int kMaxHccSymbols = 6;
constexpr int kMetricsSize = 21;
// Mirror Decimen's division between full acquisition and cheap tracking.
// A full HCC finder pass builds a binary map, scans the whole frame, tests
// finder triples, searches alignment patterns, and refines quadrilaterals.
// It is intentionally much more expensive than decoding against a cached
// quad, so never schedule it by a short fixed frame count.
constexpr auto kAcquisitionScanInterval = std::chrono::milliseconds(100);
constexpr auto kDegradedScanInterval = std::chrono::milliseconds(250);
constexpr auto kHealthyScanInterval = std::chrono::milliseconds(1500);
constexpr int kForgetAfterMisses = 2;

struct Bindings {
	jclass encoded = nullptr;
	jmethodID encodedCtor = nullptr;
	jclass frame = nullptr;
	jmethodID frameCtor = nullptr;
};

struct DecoderSlot {
	Hcc2dDecoder decoder;
	std::vector<uint8_t> payload;
	bool active = false;
	int modules = 0;
	int missedScans = 0;
	double quad[8]{};
};

struct DecoderSession {
	std::array<DecoderSlot, kMaxHccSymbols> slots{};
	// Preserve the previously working strongest-symbol path as a fallback when
	// a multi-symbol acquisition frame is ambiguous or incomplete.
	Hcc2dDecoder fallbackDecoder;
	std::vector<uint8_t> fallbackPayload;
	bool fallbackDetected = false;
	int fallbackMissedScans = 0;
	int fallbackModules = 0;
	int fallbackFailures = 0;
	bool fallbackValidated = false;
	double fallbackQuad[8]{};
	Hcc2dDetector detector;
	uint64_t cameraFrames = 0;
	std::chrono::steady_clock::time_point lastScanAt{};
	int frameWidth = 0;
	int frameHeight = 0;
	int64_t attempts = 0;
	int64_t decoded = 0;
	int64_t decodeNanos = 0;
	int64_t locked = 0;
	int64_t formatRead = 0;
	int64_t codewordsRead = 0;
	int64_t palette4 = 0;
	int64_t palette8 = 0;
	int64_t acquisitionScans = 0;
	int64_t acquisitionNanos = 0;
	int rawFinders = 0;
	int clusteredFinders = 0;
	int tripleSeeds = 0;
	int hypotheses = 0;
	int acceptedGeometries = 0;
	int blackThreshold = 0;
	int lastColors = 0;
	int lastVersion = 0;
	int lastPayloadCapacity = 0;
	bool lastDetected = false;
	int quadCount = 0;
	std::array<double, kMaxHccSymbols * 8> quads{};
};

struct Candidate {
	int modules = 0;
	double quad[8]{};
	float score = 0.0f;
};

Bindings& bindings(JNIEnv* env)
{
	static Bindings out;
	static std::once_flag once;
	std::call_once(once, [env] {
		auto encodedLocal = env->FindClass("com/android/qttransfer/hcc2d/NativeHcc2dEncoded");
		auto frameLocal = env->FindClass("com/android/qttransfer/hcc2d/NativeHcc2dFrame");
		if (!encodedLocal || !frameLocal) return;
		out.encoded = static_cast<jclass>(env->NewGlobalRef(encodedLocal));
		out.frame = static_cast<jclass>(env->NewGlobalRef(frameLocal));
		env->DeleteLocalRef(encodedLocal);
		env->DeleteLocalRef(frameLocal);
		if (!out.encoded || !out.frame) return;
		out.encodedCtor = env->GetMethodID(out.encoded, "<init>", "([BIIIII)V");
		out.frameCtor = env->GetMethodID(out.frame, "<init>", "([BZZIIIIJ)V");
	});
	return out;
}

const uint8_t* directPlane(JNIEnv* env, jobject buffer, jint offset)
{
	if (!buffer || offset < 0) return nullptr;
	auto* base = static_cast<uint8_t*>(env->GetDirectBufferAddress(buffer));
	const auto capacity = env->GetDirectBufferCapacity(buffer);
	if (!base || offset >= capacity) return nullptr;
	return base + offset;
}

jbyteArray reusableModules(JNIEnv* env, jsize size)
{
	// The largest sender grid retains six displayed cells plus one queued
	// replacement. Eight buffers prevent native output from mutating a cell
	// before Hcc2dDisplayView has rasterised it.
	thread_local std::array<jbyteArray, 8> cached{};
	thread_local std::array<jsize, 8> cachedSize{};
	thread_local size_t next = 0;
	const size_t slot = next++ % cached.size();
	if (cached[slot] && cachedSize[slot] == size)
		return static_cast<jbyteArray>(env->NewLocalRef(cached[slot]));
	if (cached[slot]) env->DeleteGlobalRef(cached[slot]);
	auto local = env->NewByteArray(size);
	if (!local) return nullptr;
	cached[slot] = static_cast<jbyteArray>(env->NewGlobalRef(local));
	cachedSize[slot] = cached[slot] ? size : 0;
	return local;
}

double centerX(const double quad[8]) { return (quad[0] + quad[2] + quad[4] + quad[6]) * .25; }
double centerY(const double quad[8]) { return (quad[1] + quad[3] + quad[5] + quad[7]) * .25; }
double span(const double quad[8])
{
	return std::max({std::hypot(quad[2] - quad[0], quad[3] - quad[1]),
		std::hypot(quad[4] - quad[2], quad[5] - quad[3]),
		std::hypot(quad[6] - quad[4], quad[7] - quad[5]),
		std::hypot(quad[0] - quad[6], quad[1] - quad[7])});
}

bool validQuad(const double quad[8])
{
	for (int i = 0; i < 8; ++i) if (!std::isfinite(quad[i])) return false;
	return span(quad) >= 8.0;
}

/**
 * Build a direct YUV view around a previously known single symbol. This is
 * the native equivalent of Decimen's tracked crop: the detector still owns
 * HCC geometry, but it does not need to rebuild a 1920x1440 black map when
 * the only plausible next code is near last frame's quad.
 */
bool makeTrackedScanCrop(const Hcc2dYuv420& full, const double quad[8], Hcc2dYuv420& crop,
	int& offsetX, int& offsetY)
{
	if (!validQuad(quad) || full.width < 32 || full.height < 32) return false;
	double minX = quad[0], maxX = quad[0], minY = quad[1], maxY = quad[1];
	for (int point = 2; point < 8; point += 2) {
		minX = std::min(minX, quad[point]);
		maxX = std::max(maxX, quad[point]);
		minY = std::min(minY, quad[point + 1]);
		maxY = std::max(maxY, quad[point + 1]);
	}
	const int padding = std::clamp(static_cast<int>(std::lround(span(quad) * .35)), 32,
		std::min(full.width, full.height) / 2);
	int left = std::max(0, static_cast<int>(std::floor(minX)) - padding);
	int top = std::max(0, static_cast<int>(std::floor(minY)) - padding);
	int right = std::min(full.width, static_cast<int>(std::ceil(maxX)) + padding + 1);
	int bottom = std::min(full.height, static_cast<int>(std::ceil(maxY)) + padding + 1);
	// YUV420 chroma samples are shared by a 2x2 luma block. Align the crop to
	// those blocks so all existing row/pixel-stride math remains valid.
	left &= ~1;
	top &= ~1;
	right &= ~1;
	bottom &= ~1;
	const int width = right - left;
	const int height = bottom - top;
	if (width < 32 || height < 32 || width * height >= full.width * full.height * 85 / 100) return false;
	crop = full;
	crop.y = full.y + static_cast<size_t>(top) * full.y_row_stride + static_cast<size_t>(left) * full.y_pixel_stride;
	crop.u = full.u + static_cast<size_t>(top / 2) * full.u_row_stride + static_cast<size_t>(left / 2) * full.u_pixel_stride;
	crop.v = full.v + static_cast<size_t>(top / 2) * full.v_row_stride + static_cast<size_t>(left / 2) * full.v_pixel_stride;
	crop.width = width;
	crop.height = height;
	offsetX = left;
	offsetY = top;
	return true;
}

double maxCornerDelta(const double a[8], const double b[8])
{
	double maximum = 0.0;
	for (int i = 0; i < 8; ++i) maximum = std::max(maximum, std::abs(a[i] - b[i]));
	return maximum;
}

bool sameLocation(const DecoderSlot& slot, const Candidate& candidate)
{
	if (!slot.active || slot.modules != candidate.modules) return false;
	const double slotSpan = span(slot.quad);
	const double candidateSpan = span(candidate.quad);
	if (slotSpan < 8.0 || candidateSpan < 8.0) return false;
	const double ratio = candidateSpan / slotSpan;
	if (ratio < .55 || ratio > 1.8) return false;
	return std::hypot(centerX(slot.quad) - centerX(candidate.quad), centerY(slot.quad) - centerY(candidate.quad))
		< std::max(slotSpan, candidateSpan) * .65;
}

void resetSlots(DecoderSession& session)
{
	for (auto& slot : session.slots) {
		slot.decoder.reset();
		slot.payload.clear();
		slot.active = false;
		slot.modules = 0;
		slot.missedScans = 0;
	}
	session.quadCount = 0;
	session.fallbackDecoder.reset();
	session.fallbackPayload.clear();
	session.fallbackDetected = false;
	session.fallbackMissedScans = 0;
	session.fallbackModules = 0;
	session.fallbackFailures = 0;
	session.fallbackValidated = false;
}

void scanGeometry(DecoderSession& session, const Hcc2dYuv420& image, int fullWidth, int fullHeight,
	int offsetX = 0, int offsetY = 0)
{
	if (session.frameWidth != fullWidth || session.frameHeight != fullHeight) {
		resetSlots(session);
		session.detector.reset();
		session.frameWidth = fullWidth;
		session.frameHeight = fullHeight;
	}

	std::vector<Hcc2dGeometry> found;
	session.detector.detect(image, found);
	const auto& detectorStats = session.detector.stats();
	session.rawFinders = detectorStats.rawFinders;
	session.clusteredFinders = detectorStats.clusteredFinders;
	session.tripleSeeds = detectorStats.tripleSeeds;
	session.hypotheses = detectorStats.hypotheses;
	session.acceptedGeometries = detectorStats.acceptedGeometries;
	session.blackThreshold = detectorStats.darkThreshold;
	std::vector<Candidate> rawCandidates;
	rawCandidates.reserve(found.size());
	for (auto geometry : found) {
		for (int point = 0; point < 8; point += 2) {
			geometry.quad[point] += offsetX;
			geometry.quad[point + 1] += offsetY;
		}
		if (geometry.modules < 21 || geometry.modules > 177 || !validQuad(geometry.quad)) continue;
		Candidate target;
		target.modules = geometry.modules;
		std::memcpy(target.quad, geometry.quad, sizeof(target.quad));
		target.score = geometry.score;
		rawCandidates.push_back(target);
	}
	std::sort(rawCandidates.begin(), rawCandidates.end(), [](const Candidate& a, const Candidate& b) {
		// Opposite-axis hypotheses occupy the same physical area, but only the
		// format-validated orientation has the stronger native detector score.
		// Keep that winner before de-duplicating a one-code frame.
		return a.score != b.score ? a.score > b.score : span(a.quad) > span(b.quad);
	});
	std::vector<Candidate> stableCandidates;
	stableCandidates.reserve(kMaxHccSymbols);
	for (const auto& candidate : rawCandidates) {
		const double candidateSpan = span(candidate.quad);
		const bool duplicate = std::any_of(stableCandidates.begin(), stableCandidates.end(), [&](const Candidate& accepted) {
			// Different version hypotheses of the same physical finder triple
			// occupy the same image region. Retain the score winner rather than
			// letting the globally most frequent (often false) version dictate
			// an entire multi-code grid.
			return std::hypot(centerX(accepted.quad) - centerX(candidate.quad), centerY(accepted.quad) - centerY(candidate.quad))
				< std::min(span(accepted.quad), candidateSpan) * .55;
		});
		if (!duplicate) stableCandidates.push_back(candidate);
	}
	if (stableCandidates.size() > 1) {
		std::vector<double> sizes;
		sizes.reserve(stableCandidates.size());
		for (const auto& candidate : stableCandidates) sizes.push_back(span(candidate.quad));
		std::sort(sizes.begin(), sizes.end());
		const double median = sizes[sizes.size() / 2];
		stableCandidates.erase(std::remove_if(stableCandidates.begin(), stableCandidates.end(), [&](const Candidate& candidate) {
			const double ratio = span(candidate.quad) / median;
			return ratio < .60 || ratio > 1.65;
		}), stableCandidates.end());
	}
	if (stableCandidates.size() > kMaxHccSymbols) stableCandidates.resize(kMaxHccSymbols);
	std::sort(stableCandidates.begin(), stableCandidates.end(), [](const Candidate& a, const Candidate& b) {
		const double dy = centerY(a.quad) - centerY(b.quad);
		return std::abs(dy) > 8.0 ? dy < 0 : centerX(a.quad) < centerX(b.quad);
	});
	// Preserve the single-symbol decoder unless the custom HCC locator sees a
	// real grid. Feed its already-acquired HCC geometry into the fallback so it
	// can sample/validate immediately rather than running the finder detector
	// a second time on the same camera buffer.
	int activeSlotCount = 0;
	for (const auto& slot : session.slots) {
		if (slot.active) ++activeSlotCount;
	}
	if (stableCandidates.size() < 2 && activeSlotCount < 2) {
		if (!rawCandidates.empty()) {
			// Match Hcc2dDecoder::findGeometry(): for one code, use the
			// detector's highest-confidence candidate, not the grid's
			// common-dimension heuristic (which is meaningful only for 2+ codes).
			const auto& candidate = rawCandidates.front();
			double previous[8]{};
			if (session.fallbackModules == candidate.modules && session.fallbackDecoder.quad(previous) &&
				maxCornerDelta(previous, candidate.quad) < span(candidate.quad) * .16) {
				// Full acquisition has unavoidable sub-pixel jitter. Blend a
				// refreshed quad into the existing single-code track rather than
				// moving every module sample to a new noisy position at once.
				for (int point = 0; point < 8; ++point)
					previous[point] += (candidate.quad[point] - previous[point]) * .25;
				session.fallbackDecoder.setGeometry(candidate.modules, previous);
			} else {
				session.fallbackDecoder.setGeometry(candidate.modules, candidate.quad);
				session.fallbackValidated = false;
			}
			session.fallbackModules = candidate.modules;
			session.fallbackMissedScans = 0;
		} else if (++session.fallbackMissedScans >= kForgetAfterMisses) {
			// Do not leave a stale tracked quad on screen after the code has
			// moved away. A fresh finder acquisition will recreate it quickly.
			session.fallbackDecoder.reset();
			session.fallbackPayload.clear();
			session.fallbackDetected = false;
			session.fallbackMissedScans = 0;
			session.fallbackModules = 0;
			session.fallbackFailures = 0;
			session.fallbackValidated = false;
		}
		return;
	}
	// Grid and fallback modes are intentionally exclusive. Otherwise a stale
	// single-code track may compete with active grid slots and make the overlay
	// appear to jump between unrelated squares.
	session.fallbackDecoder.reset();
	session.fallbackPayload.clear();
	session.fallbackDetected = false;
	session.fallbackMissedScans = 0;
	session.fallbackModules = 0;
	session.fallbackFailures = 0;
	session.fallbackValidated = false;

	std::array<bool, kMaxHccSymbols> touched{};
	for (const auto& candidate : stableCandidates) {
		int chosen = -1;
		double closest = std::numeric_limits<double>::max();
		for (int i = 0; i < kMaxHccSymbols; ++i) {
			if (touched[i] || !sameLocation(session.slots[i], candidate)) continue;
			const double distance = std::hypot(centerX(session.slots[i].quad) - centerX(candidate.quad),
				centerY(session.slots[i].quad) - centerY(candidate.quad));
			if (distance < closest) { closest = distance; chosen = i; }
		}
		if (chosen < 0) for (int i = 0; i < kMaxHccSymbols; ++i)
			if (!session.slots[i].active) { chosen = i; break; }
		if (chosen < 0) continue;
		auto& slot = session.slots[chosen];
		const bool newSlot = !slot.active || slot.modules != candidate.modules;
		if (newSlot) {
			slot.decoder.reset();
			slot.active = true;
			slot.modules = candidate.modules;
			std::memcpy(slot.quad, candidate.quad, sizeof(slot.quad));
			slot.decoder.setGeometry(slot.modules, slot.quad);
		} else if (maxCornerDelta(slot.quad, candidate.quad) >= 1.0) {
			// Full acquisition is intentionally occasional. Dampen its small
			// corner jitter so cached sample points remain stable between scans.
			for (int point = 0; point < 8; ++point)
				slot.quad[point] += (candidate.quad[point] - slot.quad[point]) * .25;
			slot.decoder.setGeometry(slot.modules, slot.quad);
		}
		slot.missedScans = 0;
		touched[chosen] = true;
	}
	for (int i = 0; i < kMaxHccSymbols; ++i) {
		auto& slot = session.slots[i];
		if (!slot.active || touched[i]) continue;
		if (++slot.missedScans >= kForgetAfterMisses) {
			slot.decoder.reset();
			slot.payload.clear();
			slot.active = false;
			slot.modules = 0;
		}
	}
}

void publishQuads(DecoderSession& session)
{
	session.quadCount = 0;
	for (const auto& slot : session.slots) {
		if (!slot.active || session.quadCount >= kMaxHccSymbols) continue;
		std::memcpy(session.quads.data() + session.quadCount * 8, slot.quad, sizeof(slot.quad));
		++session.quadCount;
	}
	if (session.fallbackDetected && session.quadCount < kMaxHccSymbols) {
		std::memcpy(session.quads.data() + session.quadCount * 8, session.fallbackQuad, sizeof(session.fallbackQuad));
		++session.quadCount;
	}
	session.lastDetected = session.quadCount > 0;
}

void record(DecoderSession& session, const Hcc2dDecodeInfo& info)
{
	++session.attempts;
	session.decodeNanos += info.decode_nanos;
	if (info.stage >= 1) ++session.locked;
	if (info.stage >= 3) ++session.formatRead;
	if (info.stage >= 4) ++session.codewordsRead;
	if (info.colors == 4) ++session.palette4;
	if (info.colors == 8) ++session.palette8;
	if (info.colors != 0) session.lastColors = info.colors;
	if (info.version != 0) session.lastVersion = info.version;
	if (info.payload_capacity != 0) session.lastPayloadCapacity = info.payload_capacity;
	if (info.valid) ++session.decoded;
}

jobject makeFrame(JNIEnv* env, const Bindings& b, const std::vector<uint8_t>& payloadBytes, const Hcc2dDecodeInfo& info)
{
	auto payload = env->NewByteArray(static_cast<jsize>(payloadBytes.size()));
	if (!payload) return nullptr;
	env->SetByteArrayRegion(payload, 0, static_cast<jsize>(payloadBytes.size()),
		reinterpret_cast<const jbyte*>(payloadBytes.data()));
	return env->NewObject(b.frame, b.frameCtor, payload,
		static_cast<jboolean>(true), static_cast<jboolean>(true),
		info.colors, info.version, info.payload_capacity, info.stage, static_cast<jlong>(info.decode_nanos));
}
}

extern "C" JNIEXPORT jobject JNICALL
Java_com_android_qttransfer_hcc2d_NativeHcc2dBridge_encode(
		JNIEnv* env, jobject, jbyteArray payload, jint colors, jint version)
{
	if (!payload || (colors != 4 && colors != 8) || version < 1 || version > 40) return nullptr;
	const auto length = env->GetArrayLength(payload);
	if (length <= 0) return nullptr;
	auto* input = env->GetByteArrayElements(payload, nullptr);
	if (!input) return nullptr;
	thread_local std::vector<uint8_t> modules(kMaxHccModules);
	int fullDim = 0, mask = 0, capacity = 0;
	const int result = hcc2d_encode_modules(reinterpret_cast<const uint8_t*>(input), length, colors, 'L', version,
		modules.data(), modules.size(), &fullDim, &mask, &capacity);
	env->ReleaseByteArrayElements(payload, input, JNI_ABORT);
	if (result != 0) return nullptr;
	auto& b = bindings(env);
	if (!b.encoded || !b.encodedCtor) return nullptr;
	auto output = reusableModules(env, fullDim * fullDim);
	if (!output) return nullptr;
	env->SetByteArrayRegion(output, 0, fullDim * fullDim, reinterpret_cast<const jbyte*>(modules.data()));
	return env->NewObject(b.encoded, b.encodedCtor, output, fullDim, colors, version, mask, capacity);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_android_qttransfer_hcc2d_NativeHcc2dBridge_createDecoder(JNIEnv*, jobject)
{
	return reinterpret_cast<jlong>(new DecoderSession());
}

extern "C" JNIEXPORT void JNICALL
Java_com_android_qttransfer_hcc2d_NativeHcc2dBridge_releaseDecoder(JNIEnv*, jobject, jlong handle)
{
	delete reinterpret_cast<DecoderSession*>(handle);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_android_qttransfer_hcc2d_NativeHcc2dBridge_decodeYuv(
		JNIEnv* env, jobject, jlong handle,
		jobject y, jint yOffset, jint yRowStride, jint yPixelStride,
		jobject u, jint uOffset, jint uRowStride, jint uPixelStride,
		jobject v, jint vOffset, jint vRowStride, jint vPixelStride,
		jint width, jint height)
{
	auto* session = reinterpret_cast<DecoderSession*>(handle);
	const auto* yData = directPlane(env, y, yOffset);
	const auto* uData = directPlane(env, u, uOffset);
	const auto* vData = directPlane(env, v, vOffset);
	if (!session || !yData || !uData || !vData || width <= 0 || height <= 0 ||
		yRowStride <= 0 || uRowStride <= 0 || vRowStride <= 0 ||
		yPixelStride <= 0 || uPixelStride <= 0 || vPixelStride <= 0) return nullptr;
	Hcc2dYuv420 image{yData, uData, vData, width, height, yRowStride, yPixelStride,
		uRowStride, uPixelStride, vRowStride, vPixelStride};
	++session->cameraFrames;
	const bool hadGridSlots = std::any_of(session->slots.begin(), session->slots.end(), [](const DecoderSlot& slot) {
		return slot.active && slot.decoder.hasGeometry();
	});
	const bool hadFallback = !hadGridSlots && session->fallbackDecoder.hasGeometry();
	bool needsReacquisition = false;
	if (hadGridSlots) {
		for (const auto& slot : session->slots) {
			if (slot.active && slot.decoder.needsAcquisition()) {
				needsReacquisition = true;
				break;
			}
		}
	} else if (hadFallback) {
		needsReacquisition = session->fallbackDecoder.needsAcquisition();
	}
	const auto now = std::chrono::steady_clock::now();
	const auto interval = !hadGridSlots && !hadFallback
		? kAcquisitionScanInterval
		: needsReacquisition ? kDegradedScanInterval : kHealthyScanInterval;
	if (session->lastScanAt.time_since_epoch().count() == 0 || now - session->lastScanAt >= interval) {
		Hcc2dYuv420 scanImage = image;
		int scanOffsetX = 0;
		int scanOffsetY = 0;
		if (needsReacquisition && !hadGridSlots && hadFallback) {
			double fallbackQuad[8]{};
			if (session->fallbackDecoder.quad(fallbackQuad))
				makeTrackedScanCrop(image, fallbackQuad, scanImage, scanOffsetX, scanOffsetY);
		}
		const auto scanStarted = std::chrono::steady_clock::now();
		scanGeometry(*session, scanImage, width, height, scanOffsetX, scanOffsetY);
		session->acquisitionNanos += std::chrono::duration_cast<std::chrono::nanoseconds>(
			std::chrono::steady_clock::now() - scanStarted).count();
		++session->acquisitionScans;
		// Timestamp after the scan. Otherwise a 150 ms acquisition immediately
		// satisfies a 100/250 ms period and repeats on the next camera callback.
		session->lastScanAt = std::chrono::steady_clock::now();
	}
	std::array<int, kMaxHccSymbols> validSlots{};
	std::array<Hcc2dDecodeInfo, kMaxHccSymbols> infos{};
	int validCount = 0;
	for (int i = 0; i < kMaxHccSymbols; ++i) {
		auto& slot = session->slots[i];
		if (!slot.active) continue;
		slot.decoder.decodeTracked(image, slot.payload, infos[i]);
		record(*session, infos[i]);
		if (infos[i].valid) validSlots[validCount++] = i;
	}
	Hcc2dDecodeInfo fallbackInfo;
	bool fallbackValid = false;
	session->fallbackDetected = false;
	const bool haveGridSlots = std::any_of(session->slots.begin(), session->slots.end(), [](const DecoderSlot& slot) {
		return slot.active;
	});
	if (validCount == 0 && !haveGridSlots) {
		// scanGeometry() is the sole HCC acquisition owner. Calling decode()
		// here would run another complete finder scan on every camera frame when
		// no code is visible, starving the camera and hiding the actual scan
		// cadence. Once scanGeometry has supplied a single-code quad, this path
		// only samples that tracked geometry.
		session->fallbackDecoder.decodeTracked(image, session->fallbackPayload, fallbackInfo);
		record(*session, fallbackInfo);
		fallbackValid = fallbackInfo.valid;
		if (fallbackInfo.stage >= 3) {
			session->fallbackValidated = true;
			session->fallbackFailures = 0;
		} else if (session->fallbackValidated && ++session->fallbackFailures >= kForgetAfterMisses) {
			// A previously verified track that loses its format is no longer
			// trustworthy. Drop it so the next camera frame immediately returns
			// to full acquisition instead of decoding a stale square.
			session->fallbackDecoder.reset();
			session->fallbackPayload.clear();
			session->fallbackDetected = false;
			session->fallbackModules = 0;
			session->fallbackFailures = 0;
			session->fallbackValidated = false;
		}
		session->fallbackDetected = fallbackInfo.detected && session->fallbackDecoder.quad(session->fallbackQuad);
	}
	publishQuads(*session);
	if (validCount == 0 && !fallbackValid) return nullptr;
	auto& b = bindings(env);
	if (!b.frame || !b.frameCtor) return nullptr;
	auto output = env->NewObjectArray(validCount + (fallbackValid ? 1 : 0), b.frame, nullptr);
	if (!output) return nullptr;
	for (int i = 0; i < validCount; ++i) {
		const int index = validSlots[i];
		auto frame = makeFrame(env, b, session->slots[index].payload, infos[index]);
		if (frame) env->SetObjectArrayElement(output, i, frame);
	}
	if (fallbackValid) {
		auto frame = makeFrame(env, b, session->fallbackPayload, fallbackInfo);
		if (frame) env->SetObjectArrayElement(output, validCount, frame);
	}
	return output;
}

extern "C" JNIEXPORT jlongArray JNICALL
Java_com_android_qttransfer_hcc2d_NativeHcc2dBridge_readStats(JNIEnv* env, jobject, jlong handle)
{
	auto* session = reinterpret_cast<DecoderSession*>(handle);
	if (!session) return nullptr;
	const std::array<jlong, kMetricsSize> values = {
		static_cast<jlong>(session->cameraFrames), session->attempts, session->decoded, session->decodeNanos,
		session->locked, session->formatRead, session->codewordsRead, session->palette4, session->palette8,
		session->lastColors, session->lastVersion, session->lastPayloadCapacity, session->lastDetected ? 1 : 0,
		session->acquisitionScans, session->acquisitionNanos, session->rawFinders, session->clusteredFinders,
		session->tripleSeeds, session->hypotheses, session->acceptedGeometries, session->blackThreshold
	};
	auto output = env->NewLongArray(kMetricsSize);
	if (output) env->SetLongArrayRegion(output, 0, kMetricsSize, values.data());
	return output;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_android_qttransfer_hcc2d_NativeHcc2dBridge_readQuads(JNIEnv* env, jobject, jlong handle)
{
	auto* session = reinterpret_cast<DecoderSession*>(handle);
	if (!session || session->quadCount == 0) return nullptr;
	auto doubleArrayClass = env->FindClass("[D");
	if (!doubleArrayClass) return nullptr;
	auto output = env->NewObjectArray(session->quadCount, doubleArrayClass, nullptr);
	for (int i = 0; i < session->quadCount; ++i) {
		auto quad = env->NewDoubleArray(8);
		if (quad) {
			env->SetDoubleArrayRegion(quad, 0, 8, session->quads.data() + i * 8);
			env->SetObjectArrayElement(output, i, quad);
			env->DeleteLocalRef(quad);
		}
	}
	env->DeleteLocalRef(doubleArrayClass);
	return output;
}
