#include "hcc2d_codec.h"
#include "hcc2d_encoder_api.h"
#include "hcc2d_detector.h"

#include "PerspectiveTransform.h"
#include "ReedSolomon.h"

#include <algorithm>
#include <array>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <span>

using namespace ZXing;

namespace {
constexpr int kFullScanPeriod = 120;
constexpr int kMaxTrackedFailures = 3;

struct Format {
	char ec = 'L';
	int mask = 0;
};

int bchCode(int value, int poly)
{
	auto bitLength = [](int v) {
		int n = 0;
		while (v) { ++n; v >>= 1; }
		return n;
	};
	const int degree = bitLength(poly) - 1;
	value <<= degree;
	while (bitLength(value) - 1 >= degree)
		value ^= poly << ((bitLength(value) - 1) - degree);
	return value;
}

int hammingDistance(int a, int b)
{
	unsigned int value = static_cast<unsigned int>(a ^ b);
	int count = 0;
	while (value) { count += int(value & 1); value >>= 1; }
	return count;
}

int formatDistance(int observed, Format& out)
{
	static constexpr std::array<std::pair<char, int>, 4> kEcBits = {{{'L', 1}, {'M', 0}, {'Q', 3}, {'H', 2}}};
	int bestDistance = 16;
	for (const auto& [ec, ecBits] : kEcBits) for (int mask = 0; mask < 8; ++mask) {
		const int type = (ecBits << 3) | mask;
		const int expected = ((type << 10) | bchCode(type, 0x537)) ^ 0x5412;
		const int distance = hammingDistance(observed, expected);
		if (distance < bestDistance) {
			bestDistance = distance;
			out = {ec, mask};
		}
	}
	return bestDistance;
}

bool readFormat(int observedA, int observedB, Format& out)
{
	Format first, second;
	const int distanceA = formatDistance(observedA, first);
	const int distanceB = formatDistance(observedB, second);
	const bool aUsable = distanceA <= 3;
	const bool bUsable = distanceB <= 3;
	if (!aUsable && !bUsable) return false;
	if (aUsable && !bUsable) { out = first; return true; }
	if (bUsable && !aUsable) { out = second; return true; }
	if (first.ec == second.ec && first.mask == second.mask) { out = first; return true; }
	// Both BCH copies are independently protected. An exact/closer read wins;
	// an unresolved tie is a bad geometry/palette lock, not a random mask.
	if (distanceA < distanceB) { out = first; return true; }
	if (distanceB < distanceA) { out = second; return true; }
	return false;
}

bool maskBit(int mask, int x, int y)
{
	switch (mask) {
	case 0: return (y + x) % 2 == 0;
	case 1: return y % 2 == 0;
	case 2: return x % 3 == 0;
	case 3: return (y + x) % 3 == 0;
	case 4: return ((y / 2) + (x / 3)) % 2 == 0;
	case 5: return (y * x) % 6 == 0;
	case 6: return ((y * x) % 6) < 3;
	case 7: return (y + x + ((y * x) % 3)) % 2 == 0;
	default: return false;
	}
}

constexpr std::array<int, 15> kFormatX = {8, 8, 8, 8, 8, 8, 8, 8, 7, 5, 4, 3, 2, 1, 0};
constexpr std::array<int, 15> kFormatY = {0, 1, 2, 3, 4, 5, 7, 8, 8, 8, 8, 8, 8, 8, 8};

uint64_t hashModule(uint64_t hash, uint8_t color)
{
	return (hash ^ color) * 1099511628211ULL;
}
}

Hcc2dDecoder::Hcc2dDecoder()
	: _detector(std::make_unique<Hcc2dDetector>())
{}

Hcc2dDecoder::~Hcc2dDecoder() = default;

void Hcc2dDecoder::reset()
{
	_track = {};
	_functionPattern.clear();
	_moduleSamples.clear();
	_borderSamples.clear();
	_dataModules.clear();
	_functionVersion = 0;
	_sampleWidth = 0;
	_sampleHeight = 0;
	_samplingDirty = true;
	_hasLastDecodedHash = false;
	_frameCount = 0;
	_trackFailures = 0;
	if (_detector) _detector->reset();
}

bool Hcc2dDecoder::quad(double out[8]) const
{
	if (!_track.active || !out) return false;
	std::memcpy(out, _track.quad, sizeof(_track.quad));
	return true;
}

void Hcc2dDecoder::setGeometry(int modules, const double quadIn[8])
{
	if (!quadIn || modules < 21 || modules > 177) return;
	const bool changed = !_track.active || _track.dim != modules ||
		std::memcmp(_track.quad, quadIn, sizeof(_track.quad)) != 0;
	_track.active = true;
	_track.dim = modules;
	std::memcpy(_track.quad, quadIn, sizeof(_track.quad));
	_trackFailures = 0;
	if (changed) _samplingDirty = true;
}

bool Hcc2dDecoder::hasGeometry() const
{
	return _track.active;
}

bool Hcc2dDecoder::needsAcquisition() const
{
	return !_track.active || _trackFailures >= kMaxTrackedFailures;
}

bool Hcc2dDecoder::restorePayload(int total, int data, int blocks, int ecpb, std::vector<uint8_t>& payload)
{
	const int g2 = total % blocks;
	const int g1 = blocks - g2;
	const int d1 = data / blocks;
	int maxData = 0;
	if (int(_rsBlocks.size()) < blocks) _rsBlocks.resize(blocks);
	if (int(_rsDataLengths.size()) < blocks) _rsDataLengths.resize(blocks);
	for (int b = 0; b < blocks; ++b) {
		const int dataLength = b < g1 ? d1 : d1 + 1;
		_rsDataLengths[b] = dataLength;
		_rsBlocks[b].resize(dataLength + ecpb);
		maxData = std::max(maxData, dataLength);
	}

	int offset = 0;
	for (int i = 0; i < maxData; ++i) for (int b = 0; b < blocks; ++b)
		if (i < _rsDataLengths[b]) _rsBlocks[b][i] = _codewords[offset++];
	for (int i = 0; i < ecpb; ++i) for (int b = 0; b < blocks; ++b)
		_rsBlocks[b][_rsDataLengths[b] + i] = _codewords[offset++];
	if (offset != total) return false;

	_corrected.clear();
	_corrected.reserve(data);
	for (int b = 0; b < blocks; ++b) {
		auto& words = _rsBlocks[b];
		if (!ReedSolomonDecode(RSField::QRCode, std::span<uint8_t>(words), ecpb)) return false;
		_corrected.insert(_corrected.end(), words.begin(), words.begin() + _rsDataLengths[b]);
	}
	if (_corrected.size() < 3 || (_corrected[0] >> 4) != 0x4) return false;
	const int size = ((_corrected[0] & 0x0F) << 12) | (_corrected[1] << 4) | (_corrected[2] >> 4);
	if (size <= 0 || int(_corrected.size()) < size + 3) return false;
	payload.resize(size);
	for (int i = 0; i < size; ++i)
		payload[i] = static_cast<uint8_t>(((_corrected[2 + i] & 0x0F) << 4) | (_corrected[3 + i] >> 4));
	return true;
}

Hcc2dDecoder::Yuv Hcc2dDecoder::sample(const Hcc2dYuv420& frame, SamplePoint point) const
{
	const int uvx = point.x / 2, uvy = point.y / 2;
	return {
		frame.y[point.y * frame.y_row_stride + point.x * frame.y_pixel_stride] & 0xFF,
		frame.u[uvy * frame.u_row_stride + uvx * frame.u_pixel_stride] & 0xFF,
		frame.v[uvy * frame.v_row_stride + uvx * frame.v_pixel_stride] & 0xFF
	};
}

Hcc2dDecoder::Yuv Hcc2dDecoder::sampleMedian(const Hcc2dYuv420& frame, const ModuleSamples& points) const
{
	std::array<int, 9> ys{};
	std::array<int, 9> us{};
	std::array<int, 9> vs{};
	for (size_t i = 0; i < points.size(); ++i) {
		const auto value = sample(frame, points[i]);
		ys[i] = value.y;
		us[i] = value.u;
		vs[i] = value.v;
	}
	const size_t middle = points.size() / 2;
	std::nth_element(ys.begin(), ys.begin() + middle, ys.end());
	std::nth_element(us.begin(), us.begin() + middle, us.end());
	std::nth_element(vs.begin(), vs.begin() + middle, vs.end());
	return {ys[middle], us[middle], vs[middle]};
}

uint8_t Hcc2dDecoder::classify(const Hcc2dYuv420& frame, const ModuleSamples& points, int colors) const
{
	// Do not manufacture a colour by taking independent Y/U/V medians. Near a
	// physical module edge those three medians can come from different source
	// colours. Classify each real camera sample and make a centre-weighted vote
	// instead; it is much more stable under LCD blur and 4:2:0 chroma bleed.
	std::array<int, 8> votes{};
	std::array<int64_t, 8> residuals{};
	for (size_t i = 0; i < points.size(); ++i) {
		const auto value = sample(frame, points[i]);
		int closest = 0;
		int64_t closestDistance = std::numeric_limits<int64_t>::max();
		for (int color = 0; color < colors; ++color) {
			const int dy = value.y - _palette[color].y;
			const int du = value.u - _palette[color].u;
			const int dv = value.v - _palette[color].v;
			const int64_t distance = int64_t(dy) * dy + int64_t(du) * du + int64_t(dv) * dv;
			if (distance < closestDistance) { closestDistance = distance; closest = color; }
		}
		const int weight = i == points.size() / 2 ? 3 : 1;
		votes[closest] += weight;
		residuals[closest] += closestDistance * weight;
	}
	int best = 0;
	for (int color = 1; color < colors; ++color) {
		if (votes[color] > votes[best] ||
			(votes[color] == votes[best] && residuals[color] < residuals[best]))
			best = color;
	}
	return static_cast<uint8_t>(best);
}

bool Hcc2dDecoder::prepareSampling(const Hcc2dYuv420& frame, int dim)
{
	if (!_samplingDirty && _sampleWidth == frame.width && _sampleHeight == frame.height)
		return true;
	const PerspectiveTransform modToPixel(
		QuadrilateralF{PointF{0,0}, PointF{double(dim),0}, PointF{double(dim),double(dim)}, PointF{0,double(dim)}},
		QuadrilateralF{PointF{_track.quad[0],_track.quad[1]}, PointF{_track.quad[2],_track.quad[3]},
			PointF{_track.quad[4],_track.quad[5]}, PointF{_track.quad[6],_track.quad[7]}});
	if (!modToPixel.isValid()) return false;
	auto project = [&](double x, double y) {
		const auto point = modToPixel(PointF{x, y});
		return SamplePoint{static_cast<int16_t>(std::clamp(int(std::lround(point.x)), 0, frame.width - 1)),
			static_cast<int16_t>(std::clamp(int(std::lround(point.y)), 0, frame.height - 1))};
	};
	static constexpr std::array<std::pair<double, double>, 9> kOffsets =
		{{{-.22,-.22},{0,-.22},{.22,-.22},
		  {-.22,0},{0,0},{.22,0},
		  {-.22,.22},{0,.22},{.22,.22}}};
	auto projectPatch = [&](ModuleSamples& points, double x, double y) {
		for (size_t i = 0; i < kOffsets.size(); ++i)
			points[i] = project(x + kOffsets[i].first, y + kOffsets[i].second);
	};
	_moduleSamples.resize(size_t(dim) * dim);
	for (int y = 0; y < dim; ++y) for (int x = 0; x < dim; ++x) {
		auto& points = _moduleSamples[size_t(y) * dim + x];
		projectPatch(points, x + .5, y + .5);
	}
	// The reference pattern has labelled colour runs on all four outer edges.
	// Sampling all legal runs fixes small versions (where the top edge alone
	// cannot even contain all eight HCC2D8 palette entries) and gives a much
	// stronger per-frame calibration set than one strip.
	_borderSamples.clear();
	_borderIndexes.clear();
	_borderSamples.reserve(size_t(dim) * 4);
	_borderIndexes.reserve(size_t(dim) * 4);
	auto addBorder = [&](double x, double y, int sequenceIndex) {
		ModuleSamples points{};
		projectPatch(points, x, y);
		_borderSamples.push_back(points);
		_borderIndexes.push_back(static_cast<uint8_t>(sequenceIndex & 7));
	};
	for (int x = 8; x < dim - 8; ++x) addBorder(x + .5, -.5, x - 8);       // top
	for (int x = 8; x < dim; ++x) addBorder(x + .5, dim + .5, x - 8);       // bottom
	for (int y = 8; y <= dim - 9; ++y) addBorder(-.5, y + .5, dim - 9 - y); // left
	for (int y = 8; y < dim; ++y) addBorder(dim + .5, y + .5, y - 8);       // right
	_sampleWidth = frame.width;
	_sampleHeight = frame.height;
	_samplingDirty = false;
	return true;
}

bool Hcc2dDecoder::findGeometry(const Hcc2dYuv420& frame, Hcc2dDecodeInfo& info)
{
	const bool refresh = !_track.active || _trackFailures >= kMaxTrackedFailures ||
		(++_frameCount % kFullScanPeriod == 0);
	if (!refresh) {
		info.detected = true;
		return true;
	}

	if (!_detector) _detector = std::make_unique<Hcc2dDetector>();
	std::vector<Hcc2dGeometry> candidates;
	if (!_detector->detect(frame, candidates) || candidates.empty()) return false;
	const auto best = std::max_element(candidates.begin(), candidates.end(), [](const Hcc2dGeometry& lhs, const Hcc2dGeometry& rhs) {
		return lhs.score < rhs.score;
	});
	if (best == candidates.end()) return false;
	_track.active = true;
	_track.dim = best->modules;
	std::memcpy(_track.quad, best->quad, sizeof(_track.quad));
	_samplingDirty = true;
	_trackFailures = 0;
	_frameCount = 0;
	info.detected = true;
	return true;
}

bool Hcc2dDecoder::reanchorGeometry(const Hcc2dYuv420& frame, int dim)
{
	if (!_track.active || dim < 21 || frame.width <= 1 || frame.height <= 1) return false;
	const PerspectiveTransform modToPixel(
		QuadrilateralF{PointF{0,0}, PointF{double(dim),0}, PointF{double(dim),double(dim)}, PointF{0,double(dim)}},
		QuadrilateralF{PointF{_track.quad[0],_track.quad[1]}, PointF{_track.quad[2],_track.quad[3]},
			PointF{_track.quad[4],_track.quad[5]}, PointF{_track.quad[6],_track.quad[7]}});
	if (!modToPixel.isValid()) return false;

	// Decimen's fast path keeps a cached QR quad and spends a few thousand
	// samples re-anchoring its three 7x7 finder patterns before it considers a
	// global detector pass. HCC2D keeps those same binary finder patterns, so
	// we can apply the architecture without treating coloured data cells as QR.
	auto lumaAt = [&](double moduleX, double moduleY, float offsetX, float offsetY) {
		const PointF point = modToPixel(PointF{moduleX, moduleY});
		const int x = static_cast<int>(std::lround(point.x + offsetX));
		const int y = static_cast<int>(std::lround(point.y + offsetY));
		if (x < 0 || y < 0 || x >= frame.width || y >= frame.height) return -1;
		return frame.y[y * frame.y_row_stride + x * frame.y_pixel_stride] & 0xFF;
	};
	auto finderBlack = [](int x, int y) {
		return std::max(std::abs(x - 3), std::abs(y - 3)) != 2;
	};

	// Fit the black/white threshold solely from known finder cells. This is
	// robust to HCC2D's deliberately dark red/blue data colours, unlike a
	// whole-frame luma threshold.
	std::array<int, 147> blacks{};
	std::array<int, 147> whites{};
	int blackCount = 0;
	int whiteCount = 0;
	for (const auto [originX, originY] : std::array<std::pair<int, int>, 3>{{{0, 0}, {dim - 7, 0}, {0, dim - 7}}})
		for (int y = 0; y < 7; ++y) for (int x = 0; x < 7; ++x) {
			const int value = lumaAt(originX + x + .5, originY + y + .5, 0.0f, 0.0f);
			if (value < 0) continue;
			if (finderBlack(x, y)) blacks[blackCount++] = value;
			else whites[whiteCount++] = value;
		}
	if (blackCount < 48 || whiteCount < 24) return false;
	auto median = [](std::array<int, 147>& values, int count) {
		const int middle = count / 2;
		std::nth_element(values.begin(), values.begin() + middle, values.begin() + count);
		return values[middle];
	};
	const int black = median(blacks, blackCount);
	const int white = median(whites, whiteCount);
	// Flat exposure / a bad quad has no useful binary anchor. Leave the old
	// sampling map in place; normal format/RS health checks decide whether a
	// global reacquisition is actually needed.
	if (white - black < 18) return false;
	const int threshold = (black + white) / 2;
	auto score = [&](float offsetX, float offsetY) {
		int matches = 0;
		for (const auto [originX, originY] : std::array<std::pair<int, int>, 3>{{{0, 0}, {dim - 7, 0}, {0, dim - 7}}})
			for (int y = 0; y < 7; ++y) for (int x = 0; x < 7; ++x) {
				const int value = lumaAt(originX + x + .5, originY + y + .5, offsetX, offsetY);
				matches += value >= 0 && (value <= threshold) == finderBlack(x, y);
			}
		return matches;
	};

	float bestX = 0.0f;
	float bestY = 0.0f;
	int bestScore = score(0.0f, 0.0f);
	for (int y = -2; y <= 2; ++y) for (int x = -2; x <= 2; ++x) {
		const int candidate = score(static_cast<float>(x), static_cast<float>(y));
		if (candidate > bestScore) {
			bestScore = candidate;
			bestX = static_cast<float>(x);
			bestY = static_cast<float>(y);
		}
	}
	const float coarseX = bestX;
	const float coarseY = bestY;
	for (const float y : {-0.5f, 0.0f, 0.5f}) for (const float x : {-0.5f, 0.0f, 0.5f}) {
		const int candidate = score(coarseX + x, coarseY + y);
		if (candidate > bestScore) {
			bestScore = candidate;
			bestX = coarseX + x;
			bestY = coarseY + y;
		}
	}
	// 125/147 is the same conservative finder confidence used by Decimen's
	// tracked path. It avoids moving a good HCC quad to a coloured-data match.
	if (bestScore < 125) return false;
	// A sub-pixel move normally does not alter our 3x3 interior sample cells;
	// preserving their cached coordinates avoids reprojecting 282k v40 points
	// every camera frame for imperceptible jitter.
	if (std::abs(bestX) < .75f && std::abs(bestY) < .75f) return true;
	for (int point = 0; point < 8; point += 2) {
		_track.quad[point] += bestX;
		_track.quad[point + 1] += bestY;
	}
	_samplingDirty = true;
	return true;
}

bool Hcc2dDecoder::decode(const Hcc2dYuv420& frame, std::vector<uint8_t>& payload, Hcc2dDecodeInfo& info)
{
	return decodeImpl(frame, payload, info, true);
}

bool Hcc2dDecoder::decodeTracked(const Hcc2dYuv420& frame, std::vector<uint8_t>& payload, Hcc2dDecodeInfo& info)
{
	return decodeImpl(frame, payload, info, false);
}

bool Hcc2dDecoder::decodeImpl(const Hcc2dYuv420& frame, std::vector<uint8_t>& payload, Hcc2dDecodeInfo& info, bool acquireGeometry)
{
	const auto start = std::chrono::steady_clock::now();
	info = {};
	payload.clear();
	auto finish = [&]() {
		if (_track.active && info.detected) {
			// HCC2D's BCH format field is independent of the coloured data
			// planes and is a substantially cheaper, more stable geometry anchor
			// than a full Reed-Solomon correction. A noisy data frame can fail RS
			// even though its quad is perfectly usable on the next camera image.
			// Do not throw that good geometry away and trigger another expensive
			// full-frame finder search merely because this packet did not correct.
			if (info.valid || info.repeated || info.stage >= 3) _trackFailures = 0;
			else _trackFailures = std::min(_trackFailures + 1, kMaxTrackedFailures);
		}
		info.decode_nanos = std::chrono::duration_cast<std::chrono::nanoseconds>(std::chrono::steady_clock::now() - start).count();
		return info.valid;
	};
	if (!frame.y || !frame.u || !frame.v || frame.width <= 0 || frame.height <= 0) return finish();
	if (acquireGeometry) {
		if (!findGeometry(frame, info)) return finish();
	} else {
		if (!_track.active) return finish();
		info.detected = true;
	}
	info.stage = 1;
	const int dim = _track.dim;
	const int version = (dim - 17) / 4;
	if (version < 1 || version > 40 || 17 + 4 * version != dim) { _track.active = false; return finish(); }

	if (_functionVersion != version) {
		_functionPattern.assign(size_t(dim) * dim, 0);
		if (hcc2d_function_modules(version, _functionPattern.data(), _functionPattern.size()) != 0) return finish();
		_functionVersion = version;
		_dataModules.clear();
		_dataModules.reserve(size_t(dim) * dim);
		int direction = -1, x = dim - 1, y = dim - 1;
		while (x > 0) {
			if (x == 6) --x;
			while (y >= 0 && y < dim) {
				for (int xx = x; xx >= x - 1; --xx)
					if (!_functionPattern[y * dim + xx]) _dataModules.push_back(uint32_t(y * dim + xx));
				y += direction;
			}
			direction = -direction;
			y += direction;
			x -= 2;
		}
		_samplingDirty = true;
	}
	// Unlike full acquisition, this only probes the known binary finder cells
	// around the cached geometry. It absorbs normal hand/camera drift before
	// the colour sampler and RS decoder see it.
	reanchorGeometry(frame, dim);
	if (!prepareSampling(frame, dim)) { _track.active = false; return finish(); }

	if (_borderSamples.empty() || _borderSamples.size() != _borderIndexes.size()) return finish();
	_borderObserved.resize(_borderSamples.size());
	for (size_t i = 0; i < _borderSamples.size(); ++i)
		_borderObserved[i] = sampleMedian(frame, _borderSamples[i]);

	struct PaletteFit {
		int colors = 0;
		std::array<Yuv, 8> palette{};
		std::array<int, 8> counts{};
		double error = std::numeric_limits<double>::infinity();
	};
	auto fitPalette = [&](int period) {
		PaletteFit fit;
		fit.colors = period;
		for (int index = 0; index < period; ++index) {
			_paletteYs[index].clear();
			_paletteUs[index].clear();
			_paletteVs[index].clear();
		}
		for (size_t i = 0; i < _borderObserved.size(); ++i) {
			const int index = _borderIndexes[i] % period;
			const auto& value = _borderObserved[i];
			_paletteYs[index].push_back(value.y);
			_paletteUs[index].push_back(value.u);
			_paletteVs[index].push_back(value.v);
			++fit.counts[index];
		}
		auto trimmedMean = [](std::vector<int>& values) {
			if (values.empty()) return 0;
			std::sort(values.begin(), values.end());
			const size_t trim = values.size() >= 8 ? values.size() / 6 : 0;
			const size_t first = trim;
			const size_t last = values.size() - trim;
			int64_t sum = 0;
			for (size_t i = first; i < last; ++i) sum += values[i];
			return static_cast<int>(sum / std::max<size_t>(1, last - first));
		};
		for (int index = 0; index < period; ++index) {
			if (fit.counts[index] == 0) return fit;
			fit.palette[index] = {
				trimmedMean(_paletteYs[index]),
				trimmedMean(_paletteUs[index]),
				trimmedMean(_paletteVs[index])
			};
		}
		double error = 0.0;
		for (size_t i = 0; i < _borderObserved.size(); ++i) {
			const auto& value = _borderObserved[i];
			const auto& center = fit.palette[_borderIndexes[i] % period];
			const int dy = value.y - center.y;
			const int du = value.u - center.u;
			const int dv = value.v - center.v;
			error += double(dy) * dy + double(du) * du + double(dv) * dv;
		}
		fit.error = error / std::max<size_t>(1, _borderObserved.size());
		return fit;
	};
	const PaletteFit four = fitPalette(4);
	const PaletteFit eight = fitPalette(8);
	// With a real four-colour border, splitting each repeated class into eight
	// classes cannot materially reduce residual error. With HCC2D8 it does,
	// because the modulo-four groups otherwise combine two different colours.
	const bool useEight = std::isfinite(eight.error) &&
		(!std::isfinite(four.error) || eight.error < four.error * .72);
	const auto& chosen = useEight ? eight : four;
	if (!std::isfinite(chosen.error)) return finish();
	const int colors = chosen.colors;
	info.colors = colors;
	const int planes = colors == 4 ? 2 : 3;
	_palette = chosen.palette;
	_paletteCount = chosen.counts;
	info.stage = 2;

	int observedFormatA = 0;
	int observedFormatB = 0;
	for (int i = 0; i < 15; ++i) {
		if (classify(frame, _moduleSamples[kFormatY[i] * dim + kFormatX[i]], colors) == 0)
			observedFormatA |= 1 << i;
		const int bx = i < 8 ? dim - 1 - i : 8;
		const int by = i < 8 ? 8 : dim - 7 + (i - 8);
		if (classify(frame, _moduleSamples[by * dim + bx], colors) == 0)
			observedFormatB |= 1 << i;
	}
	Format format;
	if (!readFormat(observedFormatA, observedFormatB, format)) return finish();
	info.stage = 3;
	int total = 0, data = 0, blocks = 0, ecpb = 0;
	if (hcc2d_codeword_layout(colors, format.ec, version, &total, &data, &blocks, &ecpb) != 0) return finish();
	info.version = version;
	info.payload_capacity = data - 3;
	const int perPlane = total * 8 / planes;
	if (int(_dataModules.size()) < perPlane) return finish();

	_codewords.assign(total, 0);
	uint64_t symbolHash = 1469598103934665603ULL;
	for (int moduleIndex = 0; moduleIndex < perPlane; ++moduleIndex) {
		const uint32_t index = _dataModules[moduleIndex];
		const int x = int(index % dim), y = int(index / dim);
		const uint8_t color = classify(frame, _moduleSamples[index], colors);
		symbolHash = hashModule(symbolHash, color);
		for (int p = 0; p < planes; ++p) {
			int bit = (color >> (planes - 1 - p)) & 1;
			if (maskBit(format.mask, x, y)) bit ^= 1;
			const int bitIndex = moduleIndex * planes + p;
			if (bit) _codewords[bitIndex >> 3] |= static_cast<uint8_t>(1 << (7 - (bitIndex & 7)));
		}
	}
	info.stage = 4;
	symbolHash = hashModule(symbolHash, static_cast<uint8_t>(observedFormatA));
	symbolHash = hashModule(symbolHash, static_cast<uint8_t>(observedFormatB));
	if (_hasLastDecodedHash && symbolHash == _lastDecodedHash) {
		info.repeated = true;
		return finish();
	}
	if (!restorePayload(total, data, blocks, ecpb, payload)) return finish();
	info.stage = 5;
	info.valid = true;
	info.stage = 6;
	_lastDecodedHash = symbolHash;
	_hasLastDecodedHash = true;
	return finish();
}
