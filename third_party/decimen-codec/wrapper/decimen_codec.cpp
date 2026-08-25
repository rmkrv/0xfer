/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (c) 2026 Evan Crawley (Bash Alarmist)
 *
 * Decimen-specific zxing-cpp WASM wrapper.
 *
 * Two decode paths:
 *
 *  readFull — stock acquisition through the public ReadBarcodes API, QR-only,
 *  with the sweeps a closed system never needs (invert, rotate) hard-off.
 *  Returns every symbol's bytes AND its position quad; error results ride
 *  along (position only) so the receiver can aim crops at codes that
 *  detected but failed ECC.
 *
 *  readTracked — the Decimen fast path. The receiver already knows where a
 *  code is (last decode's quad) and how big it is (module count), so
 *  detection — the expensive half of every decode — is skipped entirely:
 *  rebuild the module→pixel homography from the cached quad, binarize,
 *  sample the grid, Reed–Solomon decode. Sender screens are flat, so a
 *  single homography is exact up to lens distortion; zxing-cpp itself falls
 *  back to the plain projection when alignment fitting fails, for the same
 *  reason (see GridSampler.cpp). Any failure here is cheap and the caller
 *  falls back to readFull, which also re-anchors the quad.
 *
 * The quad convention matches GridSampler exactly: the reported position IS
 * mod2Pix applied to {0,0},{dim,0},{dim,dim},{0,dim}, so feeding a previous
 * result's position back in reconstructs the sampling transform.
 */

#include "ReadBarcode.h"
#include "HybridBinarizer.h"
#include "GridSampler.h"
#include "PerspectiveTransform.h"
#include "DecoderResult.h"
#include "DetectorResult.h"
#include "Content.h"
#include "qrcode/QRDecoder.h"
#include "qrcode/QRDetector.h"

#include <algorithm>
#include <cmath>

// The JS binding layer only. A native build (iOS/Android) compiles this same
// file with no emscripten in sight and exposes the C ABI at the bottom.
#ifdef __EMSCRIPTEN__
#include <emscripten/bind.h>
#include <emscripten/val.h>
#endif
#include <cstring>
#include <string>
#include <vector>

using namespace ZXing;

// Stamped by the build (CMake definitions fed from build.sh, whose version
// source of truth is package.json). The defaults only appear in by-hand
// compiles.
#ifndef DECIMEN_CODEC_VERSION
#define DECIMEN_CODEC_VERSION "0.0.0-dev"
#endif
#ifndef DECIMEN_CODEC_BUILD
#define DECIMEN_CODEC_BUILD "dev"
#endif

/** Which build is this? version() is the package.json version; build() is
 *  the git short hash, "-dirty" when built from an uncommitted tree. */
static std::string codecVersion() { return DECIMEN_CODEC_VERSION; }
static std::string codecBuild() { return DECIMEN_CODEC_BUILD; }

/**
 * A decode result in portable form. No JS types here, which is the whole
 * reason the engine is reusable natively: the native entry points return this
 * directly, and the embind layer converts it to a JS object.
 */
struct DecimenSymbol
{
	bool valid{};
	std::string error{};
	std::vector<uint8_t> bytes{};
	Position position{};
	/** Symbol dimension in modules (17 + 4·version). The receiver feeds this
	 *  back into readTracked; 0 when unknown (errors, non-QR). */
	int modules{};
};

/** RGBA → packed luminance. HybridBinarizer's block averaging assumes a
 *  single-channel plane; feeding it raw RGBA shifts the whole binarized
 *  matrix (~36 px on a 400 px image — found the hard way). ReadBarcode.cpp
 *  does this exact extraction internally (SetupLumImageView); the tracked
 *  path has to do it for itself. */
static std::vector<uint8_t> toLum(const ImageView& iv)
{
	std::vector<uint8_t> lum(iv.width() * iv.height());
	auto* dst = lum.data();
	for (int y = 0; y < iv.height(); y++) {
		const uint8_t* src = iv.data(0, y);
		for (int x = 0; x < iv.width(); x++, src += 4)
			*dst++ = RGBToLum(src[0], src[1], src[2]);
	}
	return lum;
}

#ifdef __EMSCRIPTEN__
static emscripten::val toUint8Array(const std::vector<uint8_t>& bytes)
{
	thread_local const emscripten::val Uint8Array = emscripten::val::global("Uint8Array");
	// Uint8Array.new_ COPIES out of the wasm heap synchronously, so the view
	// over a local ByteArray is safe.
	return Uint8Array.new_(emscripten::typed_memory_view(bytes.size(), bytes.data()));
}
#endif

static std::vector<DecimenSymbol> readFullImage(const ImageView& iv, bool tryHarder, int maxSymbols,
												 bool returnErrors)
{
	try {
		auto opts = ReaderOptions()
						.formats(BarcodeFormat::QRCode)
						.tryHarder(tryHarder)
						.tryRotate(false)
						.tryInvert(false)
						.tryDownscale(tryHarder)
						.returnErrors(returnErrors)
						.maxNumberOfSymbols(maxSymbols);

		auto barcodes = ReadBarcodes(iv, opts);

		std::vector<DecimenSymbol> results;
		results.reserve(barcodes.size());
		for (auto&& barcode : barcodes) {
			// symbol() is the sampled module matrix — its width IS the QR
			// dimension. (Barcode::version() would give the same number via
			// extra(), but that call links ~290 KB of metadata machinery.)
			results.push_back({barcode.isValid(), ToString(barcode.error()), barcode.bytes(),
							   barcode.position(), barcode.symbol().width()});
		}
		return results;
	} catch (const std::exception& e) {
		return {{false, e.what(), {}, {}}};
	} catch (...) {
		return {{false, "unknown error", {}, {}}};
	}
}

std::vector<DecimenSymbol> readFullPtr(const uint8_t* rgba, int width, int height, bool tryHarder, int maxSymbols,
									bool returnErrors)
{
	return readFullImage(ImageView(rgba, width, height, ImageFormat::RGBA), tryHarder, maxSymbols, returnErrors);
}

/** Android camera analysis is already an 8-bit luminance plane. Avoid
 * expanding it to RGBA only for ReadBarcodes to extract the same luminance
 * plane again. The browser binding still calls the RGBA entry point above. */
std::vector<DecimenSymbol> readFullLumPtr(const uint8_t* lum, int width, int height, bool tryHarder, int maxSymbols,
									   bool returnErrors)
{
	return readFullImage(ImageView(lum, width, height, ImageFormat::Lum), tryHarder, maxSymbols, returnErrors);
}

/** How well the three finder patterns match at a candidate offset: sampled
 *  through the transform, compared against the ideal 7×7 template. Max 147.
 *  This is the cheap anchor — 147 point samples per candidate versus a full
 *  31K-point grid sample, so a 5×5 pixel search costs microseconds. */
static int finderScore(const BitMatrix& img, const PerspectiveTransform& mod2Pix, int dim, PointF off)
{
	// Ideal finder module: black border ring, white ring, black 3×3 core.
	auto ideal = [](int x, int y) {
		return x == 0 || x == 6 || y == 0 || y == 6 || (x >= 2 && x <= 4 && y >= 2 && y <= 4);
	};
	const PointI corners[3] = {{0, 0}, {dim - 7, 0}, {0, dim - 7}};
	int score = 0;
	for (auto c : corners)
		for (int my = 0; my < 7; my++)
			for (int mx = 0; mx < 7; mx++) {
				PointF p = mod2Pix(centered(PointI{c.x + mx, c.y + my})) + off;
				if (img.isIn(p) && img.get(p) == ideal(mx, my))
					score++;
			}
	return score;
}

// ---- Point-sampled fast path -----------------------------------------------
// The tracked geometry names ~31K module centers; binarizing the whole crop
// first (~200K-pixel luminance pass + HybridBinarizer) just to read those
// points was the dominant remaining per-decode cost. This path reads RGBA
// luminance directly at each projected center and thresholds it against a
// coarse tile grid built from ~1K sparse probes — an order of magnitude
// fewer pixel touches. Misses fall through to the binarized pipeline below,
// so lighting conditions this crude thresholding can't handle only cost the
// attempt, never the decode.

constexpr int THRESH_TILES = 8;
constexpr int TILE_SAMPLES = 4; // 4×4 probes per tile

static inline int lumAt(const uint8_t* pixels, int width, int height, int pixelStride, PointF p)
{
	int x = int(p.x), y = int(p.y);
	if (x < 0 || y < 0 || x >= width || y >= height)
		return -1;
	const uint8_t* px = pixels + (size_t(y) * width + x) * pixelStride;
	return pixelStride == 1 ? px[0] : RGBToLum(px[0], px[1], px[2]);
}

struct ThresholdGrid
{
	int t[THRESH_TILES][THRESH_TILES];
	bool ok = false;
};

static ThresholdGrid buildThresholds(const uint8_t* pixels, int width, int height, int pixelStride,
									 const PerspectiveTransform& mod2Pix, int dim)
{
	ThresholdGrid grid;
	const double tile = double(dim) / THRESH_TILES;
	int gmin = 255, gmax = 0;
	int lo[THRESH_TILES][THRESH_TILES], hi[THRESH_TILES][THRESH_TILES];
	for (int ty = 0; ty < THRESH_TILES; ty++)
		for (int tx = 0; tx < THRESH_TILES; tx++) {
			lo[ty][tx] = 255;
			hi[ty][tx] = 0;
			for (int sy = 0; sy < TILE_SAMPLES; sy++)
				for (int sx = 0; sx < TILE_SAMPLES; sx++) {
					double mx = (tx + (sx + 0.5) / TILE_SAMPLES) * tile;
					double my = (ty + (sy + 0.5) / TILE_SAMPLES) * tile;
					int l = lumAt(pixels, width, height, pixelStride, mod2Pix(PointF{mx, my}));
					if (l < 0)
						continue;
					lo[ty][tx] = std::min(lo[ty][tx], l);
					hi[ty][tx] = std::max(hi[ty][tx], l);
				}
			gmin = std::min(gmin, lo[ty][tx]);
			gmax = std::max(gmax, hi[ty][tx]);
		}
	if (gmax - gmin < 24)
		return grid; // flat image — no code under this quad
	for (int ty = 0; ty < THRESH_TILES; ty++)
		for (int tx = 0; tx < THRESH_TILES; tx++) {
			// QR data is dense enough that every tile normally sees both
			// colors; a low-contrast tile (glare washout) borrows the
			// global threshold rather than inventing one from noise.
			grid.t[ty][tx] =
				hi[ty][tx] - lo[ty][tx] >= 24 ? (lo[ty][tx] + hi[ty][tx]) / 2 : (gmin + gmax) / 2;
		}
	grid.ok = true;
	return grid;
}

static inline bool darkAt(const uint8_t* pixels, int width, int height, int pixelStride, const ThresholdGrid& grid,
						  const PerspectiveTransform& mod2Pix, int dim, PointF off, double mx, double my)
{
	int l = lumAt(pixels, width, height, pixelStride, mod2Pix(PointF{mx, my}) + off);
	if (l < 0)
		return false;
	int tx = std::clamp(int(mx * THRESH_TILES / dim), 0, THRESH_TILES - 1);
	int ty = std::clamp(int(my * THRESH_TILES / dim), 0, THRESH_TILES - 1);
	return l <= grid.t[ty][tx];
}

/** finderScore's twin for the point-sampled path — same ideal template, same
 *  max of 147, but reading RGBA + tile thresholds instead of a BitMatrix. */
static int finderScorePoints(const uint8_t* pixels, int width, int height, int pixelStride, const ThresholdGrid& grid,
							 const PerspectiveTransform& mod2Pix, int dim, PointF off)
{
	auto ideal = [](int x, int y) {
		return x == 0 || x == 6 || y == 0 || y == 6 || (x >= 2 && x <= 4 && y >= 2 && y <= 4);
	};
	const PointI corners[3] = {{0, 0}, {dim - 7, 0}, {0, dim - 7}};
	int score = 0;
	for (auto c : corners)
		for (int my = 0; my < 7; my++)
			for (int mx = 0; mx < 7; mx++)
				if (darkAt(pixels, width, height, pixelStride, grid, mod2Pix, dim, off, c.x + mx + 0.5, c.y + my + 0.5) ==
					ideal(mx, my))
					score++;
	return score;
}

static DecimenSymbol readTrackedImage(const uint8_t* pixels, int width, int height, ImageFormat format, int pixelStride,
									  int dim, double x0, double y0, double x1, double y1,
									  double x2, double y2, double x3, double y3)
{
	try {
		PerspectiveTransform mod2Pix(
			QuadrilateralF{PointF{0, 0}, PointF{double(dim), 0}, PointF{double(dim), double(dim)},
						   PointF{0, double(dim)}},
			QuadrilateralF{PointF{x0, y0}, PointF{x1, y1}, PointF{x2, y2}, PointF{x3, y3}});

		// Point-sampled attempt first: its own finder-anchor search, then a
		// direct grid read and one RS decode. Everything below it survives as
		// the fallback chain. Same adaptive skip as the plain path below:
		// rigid point geometry loses to lens bow exactly like the plain
		// homography does, and a doomed attempt every call is pure heat.
		// Native Android uses a decode pool in one address space, unlike the
		// browser where each worker owns a WASM module. Keep the adaptive state
		// per worker thread so concurrent tracked calls neither race nor poison
		// each other's probe/loss history.
		thread_local int pointLossStreak = 0;
		thread_local int pointCallsSinceProbe = 0;
		const bool tryPoints = pointLossStreak < 4 || ++pointCallsSinceProbe >= 64;
		if (tryPoints && pointCallsSinceProbe >= 64)
			pointCallsSinceProbe = 0;
		bool pointsAttempted = false;
		if (auto grid = buildThresholds(pixels, width, height, pixelStride, mod2Pix, dim); tryPoints && grid.ok) {
			pointsAttempted = true;
			PointF pBest{0, 0};
			int pScore = -1;
			for (int dy = -2; dy <= 2; dy++)
				for (int dx = -2; dx <= 2; dx++) {
					int s = finderScorePoints(pixels, width, height, pixelStride, grid, mod2Pix, dim, PointF(dx, dy));
					if (s > pScore) {
						pScore = s;
						pBest = PointF(dx, dy);
					}
				}
			const PointF pCoarse = pBest;
			for (double fy = -0.5; fy <= 0.5; fy += 0.5)
				for (double fx = -0.5; fx <= 0.5; fx += 0.5) {
					PointF off = pCoarse + PointF(fx, fy);
					int s = finderScorePoints(pixels, width, height, pixelStride, grid, mod2Pix, dim, off);
					if (s > pScore) {
						pScore = s;
						pBest = off;
					}
				}
			if (pScore >= 125) {
				BitMatrix sampled(dim, dim);
				for (int y = 0; y < dim; y++)
					for (int x = 0; x < dim; x++)
						if (darkAt(pixels, width, height, pixelStride, grid, mod2Pix, dim, pBest, x + 0.5, y + 0.5))
							sampled.set(x, y);
				auto decoded = QRCode::Decode(sampled);
				if (decoded.isValid()) {
					pointLossStreak = 0;
					// Same corner convention as GridSampler's projectCorner,
					// so the returned quad feeds the next tracked call.
					auto proj = [&](PointI p) { return PointI(mod2Pix(PointF(p)) + pBest + PointF(0.5, 0.5)); };
					Position pos{proj({0, 0}), proj({dim, 0}), proj({dim, dim}), proj({0, dim})};
					return {true, "", decoded.content().bytes, pos, dim};
				}
			}
		}

		ImageView iv(pixels, width, height, format);
		const auto lum = pixelStride == 1 ? std::vector<uint8_t>{} : toLum(iv);
		ImageView lumView(pixelStride == 1 ? pixels : lum.data(), width, height, ImageFormat::Lum);
		HybridBinarizer binarized(lumView);
		auto bits = binarized.getBitMatrix();
		if (!bits)
			return {false, "binarization produced no matrix", {}, {}};

		// Finder-anchored refinement: the cached quad is one or more frames
		// old, and a raw homography tolerates barely 1 px of drift before RS
		// falls over. Slide the transform over a ±2 px window (then ±0.5 px
		// around the winner), scoring the three finder patterns at each
		// offset, and sample at the best. Extends drift tolerance to ~2.5 px
		// for microseconds of scoring — the full sample + RS runs once.
		PointF best{0, 0};
		int bestScore = -1;
		for (int dy = -2; dy <= 2; dy++)
			for (int dx = -2; dx <= 2; dx++) {
				int s = finderScore(*bits, mod2Pix, dim, PointF(dx, dy));
				if (s > bestScore) {
					bestScore = s;
					best = PointF(dx, dy);
				}
			}
		const PointF coarse = best;
		for (double fy = -0.5; fy <= 0.5; fy += 0.5)
			for (double fx = -0.5; fx <= 0.5; fx += 0.5) {
				PointF off = coarse + PointF(fx, fy);
				int s = finderScore(*bits, mod2Pix, dim, off);
				if (s > bestScore) {
					bestScore = s;
					best = off;
				}
			}
		// Below ~85% the anchor is gone (code moved past the window, or the
		// frame is trash) — bail cheaply and let the caller run detection.
		if (bestScore < 125)
			return {false, "finder anchor lost", {}, {}};

		PerspectiveTransform refined(
			QuadrilateralF{PointF{0, 0}, PointF{double(dim), 0}, PointF{double(dim), double(dim)},
						   PointF{0, double(dim)}},
			QuadrilateralF{PointF{x0 + best.x, y0 + best.y}, PointF{x1 + best.x, y1 + best.y},
						   PointF{x2 + best.x, y2 + best.y}, PointF{x3 + best.x, y3 + best.y}});

		// First try: one plain-homography sample. Exact for flat, undistorted
		// geometry, and the cheapest decode there is. Real lenses bow the
		// interior of a 177-module code by over half a module even when the
		// corners are pinned — a field run measured 550 tracked attempts, 550
		// RS failures on exactly this — so an RS reject here is EXPECTED on
		// real captures and falls through to the alignment-fitted path below.
		// ADAPTIVE: once the plain path keeps losing to the fitted one, its
		// wasted RS attempt gets skipped, with periodic re-probes — scene
		// geometry changes, and the statics are per-worker (wasm instances
		// are single-threaded and private to each worker).
		thread_local int plainLossStreak = 0;
		thread_local int callsSinceProbe = 0;
		const bool tryPlain = plainLossStreak < 4 || ++callsSinceProbe >= 64;
		if (tryPlain && callsSinceProbe >= 64)
			callsSinceProbe = 0;
		if (tryPlain) {
			if (auto detected = SampleGrid(*bits, dim, dim, refined); detected.isValid()) {
				auto decoded = QRCode::Decode(detected.bits());
				if (decoded.isValid()) {
					plainLossStreak = 0;
					if (pointsAttempted)
						pointLossStreak++;
					return {true, "", decoded.content().bytes, detected.position(), dim};
				}
			}
		}

		// Second try: zxing's own SampleQR, seeded with finder patterns
		// SYNTHESIZED from the refined transform instead of found by the
		// global detector search — which is the expensive half of a stock
		// decode. SampleQR re-derives dimension, traces edges, and fits the
		// alignment-pattern grid from the actual image, exactly like the full
		// path, so it survives the lens distortion the plain homography
		// cannot. Detection skipped, robustness kept.
		auto fpCenter = [&](double mx, double my) { return mod2Pix(PointF{mx, my}) + best; };
		auto fpSize = [&](double mx, double my) {
			auto a = mod2Pix(PointF{mx - 3.5, my});
			auto b = mod2Pix(PointF{mx + 3.5, my});
			return std::hypot(b.x - a.x, b.y - a.y);
		};
		auto makeFp = [&](double mx, double my) {
			ConcentricPattern cp;
			static_cast<PointF&>(cp) = fpCenter(mx, my);
			cp.size = fpSize(mx, my);
			return cp;
		};
		QRCode::FinderPatternSet fp{makeFp(3.5, dim - 3.5), makeFp(3.5, 3.5), makeFp(dim - 3.5, 3.5)};
		for (auto&& detected : QRCode::SampleQR(*bits, fp)) {
			// The stream's version is locked — a candidate at any other
			// dimension is a mis-estimate, not our code.
			if (!detected.isValid() || detected.bits().width() != dim)
				continue;
			auto decoded = QRCode::Decode(detected.bits());
			if (decoded.isValid()) {
				if (tryPlain)
					plainLossStreak++;
				if (pointsAttempted)
					pointLossStreak++;
				return {true, "", decoded.content().bytes, detected.position(), dim};
			}
		}
		return {false, "tracked sample failed", {}, {}};
	} catch (const std::exception& e) {
		return {false, e.what(), {}, {}};
	} catch (...) {
		return {false, "unknown error", {}, {}};
	}
}

DecimenSymbol readTrackedPtr(const uint8_t* rgba, int width, int height, int dim, double x0, double y0, double x1, double y1,
						  double x2, double y2, double x3, double y3)
{
	return readTrackedImage(rgba, width, height, ImageFormat::RGBA, 4, dim, x0, y0, x1, y1, x2, y2, x3, y3);
}

DecimenSymbol readTrackedLumPtr(const uint8_t* lum, int width, int height, int dim, double x0, double y0, double x1, double y1,
							 double x2, double y2, double x3, double y3)
{
	return readTrackedImage(lum, width, height, ImageFormat::Lum, 1, dim, x0, y0, x1, y1, x2, y2, x3, y3);
}

// ---------------------------------------------------------------------------
// JS binding layer. Everything from here to the #endif is emscripten-only; a
// native build skips it and picks up the C ABI at the bottom of the file.
#ifdef __EMSCRIPTEN__

/** The JS-facing shape of DecimenSymbol, with `bytes` as a Uint8Array. Field
 *  names and types are load-bearing — the app's decode worker reads them. */
struct DecimenResult
{
	bool valid{};
	std::string error{};
	emscripten::val bytes;
	Position position{};
	int modules{};
};

static DecimenResult toJs(const DecimenSymbol& s)
{
	return {s.valid, s.error, toUint8Array(s.bytes), s.position, s.modules};
}

/** The JS entry points, unchanged: a wasm32 heap offset in, Uint8Array out.
 *  They delegate to the pointer-taking core so the native pointer-width fix
 *  could not disturb the shipping web build — same names, same arguments,
 *  same results. */
std::vector<DecimenResult> readFull(int bufferPtr, int width, int height, bool tryHarder, int maxSymbols,
											  bool returnErrors)
{
	auto symbols = readFullPtr(reinterpret_cast<const uint8_t*>(bufferPtr), width, height, tryHarder, maxSymbols,
									   returnErrors);
	std::vector<DecimenResult> out;
	out.reserve(symbols.size());
	for (const auto& symbol : symbols)
		out.push_back(toJs(symbol));
	return out;
}

DecimenResult readTracked(int bufferPtr, int width, int height, int dim, double x0, double y0, double x1, double y1,
						  double x2, double y2, double x3, double y3)
{
	return toJs(readTrackedPtr(reinterpret_cast<const uint8_t*>(bufferPtr), width, height, dim, x0, y0, x1, y1, x2, y2,
									   x3, y3));
}

/** Debug: the raw sampled module grid for a quad, row-major 0/1 — lets a
 *  test diff the sample against ground truth to localize errors. */
emscripten::val trackedMatrix(int bufferPtr, int width, int height, int dim, double x0, double y0, double x1,
							  double y1, double x2, double y2, double x3, double y3)
{
	ImageView iv(reinterpret_cast<uint8_t*>(bufferPtr), width, height, ImageFormat::RGBA);
	auto lum = toLum(iv);
	ImageView lumView(lum.data(), width, height, ImageFormat::Lum);
	HybridBinarizer binarized(lumView);
	auto bits = binarized.getBitMatrix();
	if (!bits)
		return emscripten::val::null();
	PerspectiveTransform mod2Pix(
		QuadrilateralF{PointF{0, 0}, PointF{double(dim), 0}, PointF{double(dim), double(dim)},
					   PointF{0, double(dim)}},
		QuadrilateralF{PointF{x0, y0}, PointF{x1, y1}, PointF{x2, y2}, PointF{x3, y3}});
	auto detected = SampleGrid(*bits, dim, dim, mod2Pix);
	if (!detected.isValid())
		return emscripten::val::null();
	std::vector<uint8_t> out(dim * dim);
	for (int y = 0; y < dim; y++)
		for (int x = 0; x < dim; x++)
			out[y * dim + x] = detected.bits().get(x, y);
	return toUint8Array(out);
}

/** Debug: project one module-space point through the same transform the
 *  tracked path builds — isolates transform construction from sampling. */
emscripten::val projectPoint(int dim, double x0, double y0, double x1, double y1, double x2, double y2, double x3,
							 double y3, double mx, double my)
{
	PerspectiveTransform mod2Pix(
		QuadrilateralF{PointF{0, 0}, PointF{double(dim), 0}, PointF{double(dim), double(dim)},
					   PointF{0, double(dim)}},
		QuadrilateralF{PointF{x0, y0}, PointF{x1, y1}, PointF{x2, y2}, PointF{x3, y3}});
	auto p = mod2Pix(PointF{mx, my});
	emscripten::val out = emscripten::val::object();
	out.set("x", p.x);
	out.set("y", p.y);
	out.set("valid", mod2Pix.isValid());
	return out;
}

/** Debug: one row of the binarized matrix, 0/1 per pixel. */
emscripten::val binarizedRow(int bufferPtr, int width, int height, int y)
{
	ImageView iv(reinterpret_cast<uint8_t*>(bufferPtr), width, height, ImageFormat::RGBA);
	auto lum = toLum(iv);
	ImageView lumView(lum.data(), width, height, ImageFormat::Lum);
	HybridBinarizer binarized(lumView);
	auto bits = binarized.getBitMatrix();
	if (!bits)
		return emscripten::val::null();
	std::vector<uint8_t> out(width);
	for (int x = 0; x < width; x++)
		out[x] = bits->get(x, y);
	return toUint8Array(out);
}

EMSCRIPTEN_BINDINGS(DecimenCodec)
{
	using namespace emscripten;

	value_object<DecimenResult>("DecimenResult")
		.field("valid", &DecimenResult::valid)
		.field("error", &DecimenResult::error)
		.field("bytes", &DecimenResult::bytes)
		.field("position", &DecimenResult::position)
		.field("modules", &DecimenResult::modules);

	value_object<PointI>("Point").field("x", &PointI::x).field("y", &PointI::y);

	value_object<Position>("Position")
		.field("topLeft", emscripten::index<0>())
		.field("topRight", emscripten::index<1>())
		.field("bottomRight", emscripten::index<2>())
		.field("bottomLeft", emscripten::index<3>());

	register_vector<DecimenResult>("vector<DecimenResult>");

	function("version", &codecVersion);
	function("build", &codecBuild);
	function("readFull", &readFull);
	function("readTracked", &readTracked);
	function("trackedMatrix", &trackedMatrix);
	function("projectPoint", &projectPoint);
	function("binarizedRow", &binarizedRow);
};

#endif // __EMSCRIPTEN__

// ---------------------------------------------------------------------------
// C ABI for native consumers (iOS, Android). Contract and error codes live in
// decimen_codec.h; this is the thin marshalling layer over the same core the
// web build uses, so there is exactly one implementation of the decode paths.
#ifndef __EMSCRIPTEN__

#include "decimen_codec.h"

namespace {

/** Position is integer pixel corners; the C ABI reports doubles so a caller
 *  can feed a refined quad straight back in without a lossy round trip. */
void fillQuad(const Position& p, double out[8])
{
	for (int i = 0; i < 4; i++) {
		out[i * 2] = p[i].x;
		out[i * 2 + 1] = p[i].y;
	}
}

void describe(const DecimenSymbol& s, decimen_symbol& out, uint32_t bytesOffset)
{
	out = decimen_symbol{};
	out.valid = s.valid ? 1 : 0;
	out.modules = s.modules;
	fillQuad(s.position, out.quad);
	out.bytes_offset = bytesOffset;
	out.bytes_len = 0;
}

} // namespace

extern "C" {

const char* decimen_version(void) { return DECIMEN_CODEC_VERSION; }
const char* decimen_build(void) { return DECIMEN_CODEC_BUILD; }

int decimen_read_full(const uint8_t* rgba, int32_t width, int32_t height, int32_t tryHarder, int32_t maxSymbols,
					  int32_t returnErrors, decimen_symbol* outSymbols, uint32_t symbolsCap, uint8_t* outBytes,
					  uint32_t bytesCap, uint32_t* outCount)
{
	if (!rgba || !outSymbols || !outCount || width <= 0 || height <= 0 || symbolsCap == 0)
		return DECIMEN_ERR_ARGS;
	*outCount = 0;

	const auto symbols = readFullPtr(rgba, width, height, tryHarder != 0, maxSymbols, returnErrors != 0);
	uint32_t offset = 0;
	uint32_t written = 0;
	for (const auto& symbol : symbols) {
		if (written >= symbolsCap)
			break;
		decimen_symbol& dst = outSymbols[written];
		describe(symbol, dst, offset);
		if (!symbol.bytes.empty()) {
			// Checked before the copy, and the count reported so far stays
			// valid — a short buffer must not leave the caller guessing.
			if (!outBytes || symbol.bytes.size() > bytesCap - offset) {
				*outCount = written;
				return DECIMEN_ERR_CAPACITY;
			}
			std::memcpy(outBytes + offset, symbol.bytes.data(), symbol.bytes.size());
			dst.bytes_len = static_cast<uint32_t>(symbol.bytes.size());
			offset += dst.bytes_len;
		}
		written++;
	}
	*outCount = written;
	return written == 0 ? DECIMEN_ERR_NO_SYMBOL : DECIMEN_OK;
}

int decimen_read_full_lum(const uint8_t* lum, int32_t width, int32_t height, int32_t tryHarder, int32_t maxSymbols,
						  int32_t returnErrors, decimen_symbol* outSymbols, uint32_t symbolsCap, uint8_t* outBytes,
						  uint32_t bytesCap, uint32_t* outCount)
{
	if (!lum || !outSymbols || !outCount || width <= 0 || height <= 0 || symbolsCap == 0)
		return DECIMEN_ERR_ARGS;
	*outCount = 0;

	const auto symbols = readFullLumPtr(lum, width, height, tryHarder != 0, maxSymbols, returnErrors != 0);
	uint32_t offset = 0;
	uint32_t written = 0;
	for (const auto& symbol : symbols) {
		if (written >= symbolsCap)
			break;
		decimen_symbol& dst = outSymbols[written];
		describe(symbol, dst, offset);
		if (!symbol.bytes.empty()) {
			if (!outBytes || symbol.bytes.size() > bytesCap - offset) {
				*outCount = written;
				return DECIMEN_ERR_CAPACITY;
			}
			std::memcpy(outBytes + offset, symbol.bytes.data(), symbol.bytes.size());
			dst.bytes_len = static_cast<uint32_t>(symbol.bytes.size());
			offset += dst.bytes_len;
		}
		written++;
	}
	*outCount = written;
	return written == 0 ? DECIMEN_ERR_NO_SYMBOL : DECIMEN_OK;
}

int decimen_read_tracked(const uint8_t* rgba, int32_t width, int32_t height, int32_t dim, const double quadIn[8],
						 decimen_symbol* outSymbol, uint8_t* outBytes, uint32_t bytesCap)
{
	if (!rgba || !outSymbol || !quadIn || width <= 0 || height <= 0 || dim <= 0)
		return DECIMEN_ERR_ARGS;

	const auto symbol = readTrackedPtr(rgba, width, height, dim, quadIn[0], quadIn[1], quadIn[2], quadIn[3], quadIn[4],
											   quadIn[5], quadIn[6], quadIn[7]);
	describe(symbol, *outSymbol, 0);
	// The quad is reported either way: a detected-but-undecoded symbol is still
	// where the next crop should aim.
	if (!symbol.valid)
		return DECIMEN_ERR_DECODE;
	if (!outBytes || symbol.bytes.size() > bytesCap)
		return DECIMEN_ERR_CAPACITY;
	std::memcpy(outBytes, symbol.bytes.data(), symbol.bytes.size());
	outSymbol->bytes_len = static_cast<uint32_t>(symbol.bytes.size());
	return DECIMEN_OK;
}

int decimen_read_tracked_lum(const uint8_t* lum, int32_t width, int32_t height, int32_t dim, const double quadIn[8],
							 decimen_symbol* outSymbol, uint8_t* outBytes, uint32_t bytesCap)
{
	if (!lum || !outSymbol || !quadIn || width <= 0 || height <= 0 || dim <= 0)
		return DECIMEN_ERR_ARGS;

	const auto symbol = readTrackedLumPtr(lum, width, height, dim, quadIn[0], quadIn[1], quadIn[2], quadIn[3], quadIn[4],
										  quadIn[5], quadIn[6], quadIn[7]);
	describe(symbol, *outSymbol, 0);
	if (!symbol.valid)
		return DECIMEN_ERR_DECODE;
	if (!outBytes || symbol.bytes.size() > bytesCap)
		return DECIMEN_ERR_CAPACITY;
	std::memcpy(outBytes, symbol.bytes.data(), symbol.bytes.size());
	outSymbol->bytes_len = static_cast<uint32_t>(symbol.bytes.size());
	return DECIMEN_OK;
}

} // extern "C"

#endif // !__EMSCRIPTEN__
