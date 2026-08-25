#include "hcc2d_detector.h"

#include <algorithm>
#include <array>
#include <cmath>
#include <cstdint>
#include <limits>

namespace {
constexpr int kMinDimension = 21;
constexpr int kMaxDimension = 177;
// Never let dense/random HCC data consume the complete finder budget before
// the scan reaches the lower part of the camera frame.  A global row-major
// cap made a V20 symbol's lower-left finder disappear after false 1:1:3:1:1
// runs near the top of the image had filled the vector.  Keep a small
// score-ranked reservoir per scanline instead: it remains bounded while
// guaranteeing that every vertical region can contribute a real finder.
constexpr int kMaxFindersPerScanline = 16;
// Keep enough candidates for a six-code grid plus false finder-like runs.
// A real V40 triple can have a lower raw run score than a small accidental
// pattern in colour data, so do not throw it away before structural scoring.
constexpr int kMaxClusteredFinders = 48;
constexpr int kMaxTripleSeeds = 64;
constexpr int kMaxOutputSymbols = 6;
// Horizontal/vertical scan-line module widths are not the code-axis pitch at
// an arbitrary in-plane rotation. Give the strongest physical finder triples
// a full v1…v40 sweep, then let function-pattern/BCH scoring reject the
// wrong dimensions. The remaining (weaker) triples keep the inexpensive
// local window.
constexpr int kBroadVersionSeedCount = 6;

float distance(float ax, float ay, float bx, float by)
{
	return std::hypot(ax - bx, ay - by);
}

float quadCenterX(const double quad[8])
{
	return static_cast<float>((quad[0] + quad[2] + quad[4] + quad[6]) * .25);
}

float quadCenterY(const double quad[8])
{
	return static_cast<float>((quad[1] + quad[3] + quad[5] + quad[7]) * .25);
}

float quadSpan(const double quad[8])
{
	return std::max({
		static_cast<float>(std::hypot(quad[2] - quad[0], quad[3] - quad[1])),
		static_cast<float>(std::hypot(quad[4] - quad[2], quad[5] - quad[3])),
		static_cast<float>(std::hypot(quad[6] - quad[4], quad[7] - quad[5])),
		static_cast<float>(std::hypot(quad[0] - quad[6], quad[1] - quad[7])),
	});
}

bool convexQuad(const double quad[8])
{
	// A coordinate-descent refinement must never trade a few locally matching
	// timing modules for a folded quadrilateral.  Require four non-trivial
	// edges and one consistent turn direction before accepting a hypothesis.
	double previousCross = 0.0;
	for (int i = 0; i < 4; ++i) {
		const int a = i * 2;
		const int b = ((i + 1) & 3) * 2;
		const int c = ((i + 2) & 3) * 2;
		const double abx = quad[b] - quad[a];
		const double aby = quad[b + 1] - quad[a + 1];
		const double bcx = quad[c] - quad[b];
		const double bcy = quad[c + 1] - quad[b + 1];
		if (std::hypot(abx, aby) < 3.0) return false;
		const double cross = abx * bcy - aby * bcx;
		if (!std::isfinite(cross) || std::abs(cross) < 4.0) return false;
		if (previousCross != 0.0 && cross * previousCross <= 0.0) return false;
		previousCross = cross;
	}
	return true;
}

int percentile(const std::array<int, 256>& histogram, int count, float fraction)
{
	if (count <= 0) return 0;
	const int wanted = std::clamp(static_cast<int>(std::ceil(count * fraction)), 1, count);
	int seen = 0;
	for (int value = 0; value < 256; ++value) {
		seen += histogram[value];
		if (seen >= wanted) return value;
	}
	return 255;
}

bool runRatio(float a, float b, float c, float d, float e, float& module, float& quality)
{
	if (a < 1.0f || b < 1.0f || c < 2.0f || d < 1.0f || e < 1.0f) return false;
	const float total = a + b + c + d + e;
	module = total / 7.0f;
	if (module < 1.15f || module > 128.0f) return false;
	const float error = std::abs(a - module) + std::abs(b - module) +
		std::abs(c - module * 3.0f) + std::abs(d - module) + std::abs(e - module);
	// A phone-display edge softens the outer black modules more than the
	// central block, so this intentionally allows roughly 35% aggregate error.
	if (error > module * 2.45f) return false;
	quality = std::max(0.0f, 1.0f - error / (module * 3.0f));
	return true;
}

int nearestDimension(float measured)
{
	const int version = std::clamp(static_cast<int>(std::lround((measured - 17.0f) / 4.0f)), 1, 40);
	return 17 + version * 4;
}

int bchCode(int value, int polynomial)
{
	auto bitLength = [](int number) {
		int length = 0;
		while (number) { ++length; number >>= 1; }
		return length;
	};
	const int degree = bitLength(polynomial) - 1;
	value <<= degree;
	while (bitLength(value) - 1 >= degree)
		value ^= polynomial << ((bitLength(value) - 1) - degree);
	return value;
}

int hammingDistance(int first, int second)
{
	unsigned int value = static_cast<unsigned int>(first ^ second);
	int count = 0;
	while (value) { count += int(value & 1U); value >>= 1U; }
	return count;
}

int nearestFormatDistance(int observed)
{
	static constexpr std::array<int, 4> kEcBits = {1, 0, 3, 2};
	int best = 15;
	for (const int ec : kEcBits) for (int mask = 0; mask < 8; ++mask) {
		const int type = (ec << 3) | mask;
		const int expected = ((type << 10) | bchCode(type, 0x537)) ^ 0x5412;
		best = std::min(best, hammingDistance(observed, expected));
	}
	return best;
}

/**
 * Build an inner-grid quadrilateral from four known module centres.  Three
 * finder centres are sufficient for an affine seed but not a camera
 * homography; the bottom-right alignment centre supplies the fourth native
 * HCC2D landmark.  Keeping this solver here avoids borrowing QR detector
 * geometry while still returning the same grid-corner convention used by the
 * sampler.
 */
bool solveProjectiveQuad(const std::array<std::array<double, 2>, 4>& source,
	const std::array<std::array<double, 2>, 4>& destination, int dim, double out[8])
{
	if (!out || dim < kMinDimension) return false;
	double augmented[8][9]{};
	for (int i = 0; i < 4; ++i) {
		const double sx = source[i][0], sy = source[i][1];
		const double dx = destination[i][0], dy = destination[i][1];
		if (!std::isfinite(sx) || !std::isfinite(sy) || !std::isfinite(dx) || !std::isfinite(dy)) return false;
		const int row = i * 2;
		augmented[row][0] = sx;
		augmented[row][1] = sy;
		augmented[row][2] = 1.0;
		augmented[row][6] = -dx * sx;
		augmented[row][7] = -dx * sy;
		augmented[row][8] = dx;
		augmented[row + 1][3] = sx;
		augmented[row + 1][4] = sy;
		augmented[row + 1][5] = 1.0;
		augmented[row + 1][6] = -dy * sx;
		augmented[row + 1][7] = -dy * sy;
		augmented[row + 1][8] = dy;
	}
	for (int column = 0; column < 8; ++column) {
		int pivot = column;
		for (int row = column + 1; row < 8; ++row)
			if (std::abs(augmented[row][column]) > std::abs(augmented[pivot][column])) pivot = row;
		if (!std::isfinite(augmented[pivot][column]) || std::abs(augmented[pivot][column]) < 1e-10) return false;
		if (pivot != column) for (int entry = column; entry < 9; ++entry)
			std::swap(augmented[pivot][entry], augmented[column][entry]);
		const double divisor = augmented[column][column];
		for (int entry = column; entry < 9; ++entry) augmented[column][entry] /= divisor;
		for (int row = 0; row < 8; ++row) {
			if (row == column) continue;
			const double factor = augmented[row][column];
			if (factor == 0.0) continue;
			for (int entry = column; entry < 9; ++entry)
				augmented[row][entry] -= factor * augmented[column][entry];
		}
	}
	std::array<double, 8> h{};
	for (int i = 0; i < 8; ++i) h[i] = augmented[i][8];
	auto map = [&](double x, double y, double& px, double& py) {
		const double denominator = h[6] * x + h[7] * y + 1.0;
		if (!std::isfinite(denominator) || std::abs(denominator) < 1e-10) return false;
		px = (h[0] * x + h[1] * y + h[2]) / denominator;
		py = (h[3] * x + h[4] * y + h[5]) / denominator;
		return std::isfinite(px) && std::isfinite(py);
	};
	static constexpr std::array<std::array<double, 2>, 4> corners =
		{{{{0.0, 0.0}}, {{1.0, 0.0}}, {{1.0, 1.0}}, {{0.0, 1.0}}}};
	for (int i = 0; i < 4; ++i) {
		if (!map(corners[i][0] * dim, corners[i][1] * dim, out[i * 2], out[i * 2 + 1])) return false;
	}
	return convexQuad(out);
}

} // namespace

struct Hcc2dDetector::Finder {
	float x = 0.0f;
	float y = 0.0f;
	float module = 0.0f;
	float score = 0.0f;
	int count = 1;
};

/** A compact homography from inner-module coordinates to camera pixels. */
struct Hcc2dDetector::Homography {
	double a = 0.0, b = 0.0, c = 0.0;
	double d = 0.0, e = 0.0, f = 0.0;
	double g = 0.0, h = 0.0;

	bool set(const double quad[8])
	{
		const double x0 = quad[0], y0 = quad[1];
		const double x1 = quad[2], y1 = quad[3];
		const double x2 = quad[4], y2 = quad[5];
		const double x3 = quad[6], y3 = quad[7];
		const double aa = x2 - x1;
		const double bb = x2 - x3;
		const double cc = x1 + x3 - x0 - x2;
		const double dd = y2 - y1;
		const double ee = y2 - y3;
		const double ff = y1 + y3 - y0 - y2;
		const double determinant = aa * ee - bb * dd;
		if (!std::isfinite(determinant) || std::abs(determinant) < 1e-8) return false;
		g = (cc * ee - bb * ff) / determinant;
		h = (aa * ff - cc * dd) / determinant;
		a = x1 * (1.0 + g) - x0;
		b = x3 * (1.0 + h) - x0;
		c = x0;
		d = y1 * (1.0 + g) - y0;
		e = y3 * (1.0 + h) - y0;
		f = y0;
		return std::isfinite(a) && std::isfinite(b) && std::isfinite(c) &&
			std::isfinite(d) && std::isfinite(e) && std::isfinite(f) &&
			std::isfinite(g) && std::isfinite(h);
	}

	bool map(double x, double y, float& outX, float& outY) const
	{
		const double denominator = 1.0 + g * x + h * y;
		if (!std::isfinite(denominator) || std::abs(denominator) < 1e-8) return false;
		outX = static_cast<float>((a * x + b * y + c) / denominator);
		outY = static_cast<float>((d * x + e * y + f) / denominator);
		return std::isfinite(outX) && std::isfinite(outY);
	}
};

struct Hcc2dDetector::Hypothesis {
	Hcc2dGeometry geometry;
	// TL, TR, BL finder centres in camera pixels, in the orientation selected
	// for this hypothesis. They are the three exact correspondences used with
	// the discovered bottom-right alignment centre.
	std::array<double, 6> finderPixels{};
	float modulePixels = 0.0f;
	float geometricScore = 0.0f;
};

void Hcc2dDetector::reset()
{
	_black.clear();
	_lumaIntegral.clear();
	_width = 0;
	_height = 0;
	_darkThreshold = 0;
	_neutralU = 128;
	_neutralV = 128;
	_stats = {};
}

bool Hcc2dDetector::prepareBlackMap(const Hcc2dYuv420& frame)
{
	if (!frame.y || !frame.u || !frame.v || frame.width < 32 || frame.height < 32 ||
		frame.y_row_stride <= 0 || frame.u_row_stride <= 0 || frame.v_row_stride <= 0 ||
		frame.y_pixel_stride <= 0 || frame.u_pixel_stride <= 0 || frame.v_pixel_stride <= 0)
		return false;
	_width = frame.width;
	_height = frame.height;

	// A small histogram is considerably less sensitive to a white status bar
	// or a black letterbox than a fixed Y threshold.  The native HCC2D black
	// is the lower luma cluster while the palette/white modules form the upper
	// clusters.
	std::array<int, 256> lumaHistogram{};
	int sampled = 0;
	for (int y = 1; y < frame.height; y += 4) for (int x = 1; x < frame.width; x += 4) {
		const int value = frame.y[y * frame.y_row_stride + x * frame.y_pixel_stride] & 0xFF;
		++lumaHistogram[value];
		++sampled;
	}
	if (sampled == 0) return false;
	const int blackFloor = percentile(lumaHistogram, sampled, 0.025f);
	const int bright = percentile(lumaHistogram, sampled, 0.90f);
	_darkThreshold = std::clamp(blackFloor + std::max(17, static_cast<int>((bright - blackFloor) * .18f)), 18, 92);
	_stats.darkThreshold = _darkThreshold;

	// Black and white display pixels are both nearly neutral chroma.  Estimate
	// that cluster from the near-neutral part of the complete frame, rather
	// than from only its darkest pixels.  HCC2D8 has dark blue/green/red data;
	// those colours can dominate the global lower luma tail and previously made
	// the finder scanner follow a data colour instead of black.
	std::array<int, 16 * 16> chromaHistogram{};
	for (int y = 1; y < frame.height; y += 4) for (int x = 1; x < frame.width; x += 4) {
		const int ux = x >> 1, uy = y >> 1;
		const int u = frame.u[uy * frame.u_row_stride + ux * frame.u_pixel_stride] & 0xFF;
		const int v = frame.v[uy * frame.v_row_stride + ux * frame.v_pixel_stride] & 0xFF;
		// White balance can shift neutral colours modestly, but no reference
		// HCC palette entry is this close to neutral. Keep enough tolerance for
		// an inexpensive one-pass camera calibration.
		if (std::abs(u - 128) + std::abs(v - 128) > 56) continue;
		++chromaHistogram[(u >> 4) * 16 + (v >> 4)];
	}
	const auto mode = std::max_element(chromaHistogram.begin(), chromaHistogram.end());
	if (mode != chromaHistogram.end() && *mode > 2) {
		const int bucket = static_cast<int>(std::distance(chromaHistogram.begin(), mode));
		const int ub = bucket / 16, vb = bucket % 16;
		int sumU = 0, sumV = 0, count = 0;
		for (int y = 1; y < frame.height; y += 4) for (int x = 1; x < frame.width; x += 4) {
			const int ux = x >> 1, uy = y >> 1;
			const int u = frame.u[uy * frame.u_row_stride + ux * frame.u_pixel_stride] & 0xFF;
			const int v = frame.v[uy * frame.v_row_stride + ux * frame.v_pixel_stride] & 0xFF;
			if (std::abs(u - 128) + std::abs(v - 128) > 56 || (u >> 4) != ub || (v >> 4) != vb) continue;
			sumU += u;
			sumV += v;
			++count;
		}
		if (count > 0) {
			_neutralU = sumU / count;
			_neutralV = sumV / count;
		}
	}

	// Build a local-mean image once per acquisition. A 24-pixel radius is
	// larger than a v40 finder at practical capture sizes but remains local
	// enough to follow display glare and auto-exposure gradients. The table is
	// reused by the detector, so there is no per-pixel allocation.
	const int integralStride = frame.width + 1;
	_lumaIntegral.resize(static_cast<size_t>(integralStride) * (frame.height + 1));
	std::fill(_lumaIntegral.begin(), _lumaIntegral.begin() + integralStride, 0U);
	for (int y = 0; y < frame.height; ++y) {
		uint32_t rowSum = 0;
		_lumaIntegral[static_cast<size_t>(y + 1) * integralStride] = 0;
		for (int x = 0; x < frame.width; ++x) {
			rowSum += frame.y[y * frame.y_row_stride + x * frame.y_pixel_stride] & 0xFF;
			_lumaIntegral[static_cast<size_t>(y + 1) * integralStride + x + 1] =
				_lumaIntegral[static_cast<size_t>(y) * integralStride + x + 1] + rowSum;
		}
	}
	auto localMean = [&](int x, int y) {
		constexpr int kRadius = 24;
		const int left = std::max(0, x - kRadius);
		const int top = std::max(0, y - kRadius);
		const int right = std::min(frame.width, x + kRadius + 1);
		const int bottom = std::min(frame.height, y + kRadius + 1);
		const uint32_t sum = _lumaIntegral[static_cast<size_t>(bottom) * integralStride + right]
			- _lumaIntegral[static_cast<size_t>(top) * integralStride + right]
			- _lumaIntegral[static_cast<size_t>(bottom) * integralStride + left]
			+ _lumaIntegral[static_cast<size_t>(top) * integralStride + left];
		return static_cast<int>(sum / std::max(1, (right - left) * (bottom - top)));
	};

	_black.resize(static_cast<size_t>(frame.width) * frame.height);
	for (int y = 0; y < frame.height; ++y) for (int x = 0; x < frame.width; ++x) {
		const int yy = frame.y[y * frame.y_row_stride + x * frame.y_pixel_stride] & 0xFF;
		const int ux = x >> 1, uy = y >> 1;
		const int u = frame.u[uy * frame.u_row_stride + ux * frame.u_pixel_stride] & 0xFF;
		const int v = frame.v[uy * frame.v_row_stride + ux * frame.v_pixel_stride] & 0xFF;
		const int chromaDistance = std::abs(u - _neutralU) + std::abs(v - _neutralV);
		const int mean = localMean(x, y);
		const bool globallyBlack = yy <= _darkThreshold && chromaDistance <= 88;
		// A display's reflected black can be much lighter than a dark object in
		// the surrounding room. Admit a locally dark, neutral pixel too. The
		// chroma guard remains mandatory so dark HCC colour modules cannot form
		// synthetic finder runs after 4:2:0 blending.
		const bool locallyBlack = yy + std::max(16, mean / 7) <= mean && chromaDistance <= 88;
		// A narrowly guarded repair handles a single chroma outlier on a true
		// black ring without reintroducing the old "every very-dark colour is
		// black" failure mode for HCC2D8.
		const bool veryDarkNeutral = yy <= blackFloor + 8 && chromaDistance <= 108;
		_black[static_cast<size_t>(y) * frame.width + x] =
			(globallyBlack || locallyBlack || veryDarkNeutral) ? 1 : 0;
	}
	return true;
}

bool Hcc2dDetector::blackAt(int x, int y) const
{
	return x >= 0 && y >= 0 && x < _width && y < _height &&
		_black[static_cast<size_t>(y) * _width + x] != 0;
}

bool Hcc2dDetector::blackAt(float x, float y) const
{
	if (!std::isfinite(x) || !std::isfinite(y)) return false;
	const int cx = static_cast<int>(std::lround(x));
	const int cy = static_cast<int>(std::lround(y));
	if (cx < 0 || cy < 0 || cx >= _width || cy >= _height) return false;
	// Keep the centre sample authoritative: a timing track contains isolated
	// one-module black cells whose four immediate neighbours are intentionally
	// white.  The neighbour vote is only a repair path for a single Bayer/YUV
	// outlier at an otherwise black module centre.
	if (blackAt(cx, cy)) return true;
	int neighbours = 0;
	neighbours += blackAt(cx - 1, cy) ? 1 : 0;
	neighbours += blackAt(cx + 1, cy) ? 1 : 0;
	neighbours += blackAt(cx, cy - 1) ? 1 : 0;
	neighbours += blackAt(cx, cy + 1) ? 1 : 0;
	return neighbours >= 3;
}

bool Hcc2dDetector::verticalCheck(int x, int y, float expectedModule, Finder& out) const
{
	if (!blackAt(x, y)) return false;
	int start = y;
	while (start > 0 && blackAt(x, start - 1)) --start;
	int end = y;
	while (end + 1 < _height && blackAt(x, end + 1)) ++end;
	const int middle = end - start + 1;
	int pos = start - 1;
	int whiteBefore = 0;
	while (pos >= 0 && !blackAt(x, pos)) { ++whiteBefore; --pos; }
	int blackBefore = 0;
	while (pos >= 0 && blackAt(x, pos)) { ++blackBefore; --pos; }
	pos = end + 1;
	int whiteAfter = 0;
	while (pos < _height && !blackAt(x, pos)) { ++whiteAfter; ++pos; }
	int blackAfter = 0;
	while (pos < _height && blackAt(x, pos)) { ++blackAfter; ++pos; }
	float module = 0.0f, quality = 0.0f;
	if (!runRatio(static_cast<float>(blackBefore), static_cast<float>(whiteBefore), static_cast<float>(middle),
		static_cast<float>(whiteAfter), static_cast<float>(blackAfter), module, quality)) return false;
	const float ratio = module / std::max(expectedModule, .5f);
	if (ratio < .45f || ratio > 2.2f) return false;
	out.x = static_cast<float>(x);
	out.y = (start + end) * .5f;
	out.module = (module + expectedModule) * .5f;
	out.score = quality;
	out.count = 1;
	return true;
}

void Hcc2dDetector::findFinders(std::vector<Finder>& output) const
{
	output.clear();
	if (_width < 32 || _height < 32) return;
	// At 1920x1440 this scans only 1.4M samples.  A v40 symbol whose modules
	// are smaller than two camera pixels cannot be colour-decoded reliably, so
	// the two-pixel row stride does not sacrifice practical HCC2D reception.
	const int rowStep = 2;
	for (int y = 0; y < _height; y += rowStep) {
		// Retain the strongest candidates from this one scanline before moving
		// on. A finder occupies several adjacent scanlines, so a clean physical
		// finder survives this reservoir even in a dense/random data area.
		std::array<Finder, kMaxFindersPerScanline> rowFinders{};
		int rowFinderCount = 0;
		auto retain = [&](const Finder& candidate) {
			if (rowFinderCount < kMaxFindersPerScanline) {
				rowFinders[rowFinderCount++] = candidate;
				return;
			}
			int weakest = 0;
			for (int i = 1; i < rowFinderCount; ++i)
				if (rowFinders[i].score < rowFinders[weakest].score) weakest = i;
			// Prefer a later equal-quality run as well: this avoids a left-to-right
			// bias when a perfect finder shares a score with a synthetic run.
			if (candidate.score >= rowFinders[weakest].score)
				rowFinders[weakest] = candidate;
		};
		std::array<int, 5> starts{};
		std::array<int, 5> lengths{};
		std::array<bool, 5> colors{};
		int used = 0;
		bool color = blackAt(0, y);
		int start = 0;
		auto pushRun = [&](bool runColor, int runStart, int length) {
			if (length <= 0) return;
			if (used < 5) {
				starts[used] = runStart;
				lengths[used] = length;
				colors[used] = runColor;
				++used;
			} else {
				for (int i = 0; i < 4; ++i) {
					starts[i] = starts[i + 1];
					lengths[i] = lengths[i + 1];
					colors[i] = colors[i + 1];
				}
				starts[4] = runStart;
				lengths[4] = length;
				colors[4] = runColor;
			}
			if (used != 5 || !colors[0] || colors[1] || !colors[2] || colors[3] || !colors[4]) return;
			float module = 0.0f, quality = 0.0f;
			if (!runRatio(static_cast<float>(lengths[0]), static_cast<float>(lengths[1]),
				static_cast<float>(lengths[2]), static_cast<float>(lengths[3]), static_cast<float>(lengths[4]), module, quality)) return;
			const int centerX = starts[2] + lengths[2] / 2;
			Finder candidate;
			if (!verticalCheck(centerX, y, module, candidate)) return;
			candidate.score *= quality;
			retain(candidate);
		};
		for (int x = 1; x <= _width; ++x) {
			const bool next = x < _width ? blackAt(x, y) : !color;
			if (x < _width && next == color) continue;
			pushRun(color, start, x - start);
			color = next;
			start = x;
		}
		output.insert(output.end(), rowFinders.begin(), rowFinders.begin() + rowFinderCount);
	}
}

void Hcc2dDetector::clusterFinders(std::vector<Finder>& finders) const
{
	std::sort(finders.begin(), finders.end(), [](const Finder& lhs, const Finder& rhs) {
		return lhs.score > rhs.score;
	});
	std::vector<Finder> clustered;
	clustered.reserve(finders.size());
	for (const auto& finder : finders) {
		int best = -1;
		float nearest = std::numeric_limits<float>::max();
		for (int i = 0; i < static_cast<int>(clustered.size()); ++i) {
			const auto& existing = clustered[i];
			const float moduleRatio = finder.module / std::max(existing.module, .5f);
			if (moduleRatio < .45f || moduleRatio > 2.2f) continue;
			const float d = distance(finder.x, finder.y, existing.x, existing.y);
			if (d > std::max(4.0f, (finder.module + existing.module) * 1.75f) || d >= nearest) continue;
			best = i;
			nearest = d;
		}
		if (best < 0) {
			clustered.push_back(finder);
			continue;
		}
		auto& existing = clustered[best];
		const float weight = std::max(.1f, finder.score);
		const float total = std::max(.1f, existing.score) * existing.count + weight;
		existing.x = (existing.x * std::max(.1f, existing.score) * existing.count + finder.x * weight) / total;
		existing.y = (existing.y * std::max(.1f, existing.score) * existing.count + finder.y * weight) / total;
		existing.module = (existing.module * std::max(.1f, existing.score) * existing.count + finder.module * weight) / total;
		existing.score = std::min(1.0f, (existing.score * existing.count + finder.score) / (existing.count + 1));
		++existing.count;
	}
	std::sort(clustered.begin(), clustered.end(), [](const Finder& lhs, const Finder& rhs) {
		return lhs.score * std::sqrt(static_cast<float>(lhs.count)) > rhs.score * std::sqrt(static_cast<float>(rhs.count));
	});
	if (clustered.size() > kMaxClusteredFinders) clustered.resize(kMaxClusteredFinders);
	finders.swap(clustered);
}

float Hcc2dDetector::scoreGeometry(const Hcc2dYuv420&, const Hcc2dGeometry& geometry) const
{
	if (geometry.modules < kMinDimension || geometry.modules > kMaxDimension ||
		(geometry.modules - 17) % 4 != 0 || !convexQuad(geometry.quad)) return -1.0f;
	Homography transform;
	if (!transform.set(geometry.quad)) return -1.0f;
	const int dim = geometry.modules;
	float score = 0.0f;
	float weight = 0.0f;
	auto sample = [&](double x, double y, bool& value) {
		float px = 0.0f, py = 0.0f;
		// Homography stores the conventional unit-square coefficients.  All
		// callers below use inner-module coordinates, so normalize here once;
		// the output quad still remains the dim×dim grid contract used by
		// Hcc2dDecoder::prepareSampling.
		if (!transform.map(x / dim, y / dim, px, py) || px < 1.0f || py < 1.0f || px >= _width - 1.0f || py >= _height - 1.0f)
			return false;
		value = blackAt(px, py);
		return true;
	};
	auto known = [&](double x, double y, bool expectedBlack, float importance = 1.0f) {
		bool observed = false;
		if (!sample(x + .5, y + .5, observed)) return;
		score += (observed == expectedBlack ? importance : -importance);
		weight += importance;
	};

	// The three finder patterns alone are enough to locate candidates.  Their
	// complete 7x7 interior lets the refinement converge on sub-module corner
	// placement without seeing any random coloured data module.
	for (const auto [originX, originY] : std::array<std::pair<int, int>, 3>{{{0, 0}, {dim - 7, 0}, {0, dim - 7}}}) {
		for (int y = 0; y < 7; ++y) for (int x = 0; x < 7; ++x) {
			const int radius = std::max(std::abs(x - 3), std::abs(y - 3));
			known(originX + x, originY + y, radius != 2, 1.35f);
		}
	}

	// White separator cells inside the QR-compatible grid pin the finder
	// edges even if a dark HCC data colour lies immediately beside one.  The
	// top-right and bottom-left separators face inward: sampling x/y == dim
	// would instead score the HCC palette border as a QR separator.
	for (int y = 0; y <= 7; ++y) known(7, y, false, 1.0f);                 // TL right
	for (int x = 0; x <= 7; ++x) known(x, 7, false, 1.0f);                 // TL bottom
	for (int y = 0; y <= 7; ++y) known(dim - 8, y, false, 1.0f);           // TR left
	for (int x = dim - 7; x < dim; ++x) known(x, 7, false, 1.0f);          // TR bottom
	for (int y = dim - 7; y < dim; ++y) known(7, y, false, 1.0f);          // BL right
	for (int x = 0; x <= 7; ++x) known(x, dim - 8, false, 1.0f);           // BL top

	// QR timing tracks remain binary in HCC2D plane 0.  They span nearly the
	// entire code and are the principal projective-correction signal.
	for (int i = 8; i < dim - 8; ++i) {
		const bool black = ((i + 1) & 1) != 0;
		known(i, 6, black, 1.20f);
		known(6, i, black, 1.20f);
	}

	// Decode the BCH-protected format fields rather than merely comparing the
	// two copies. Equality alone is rotation/transposition invariant and used
	// to let the swapped-axis hypothesis survive with a perfect structural
	// score. A valid format field is the native orientation signal.
	static constexpr std::array<int, 15> formatX = {8,8,8,8,8,8,8,8,7,5,4,3,2,1,0};
	static constexpr std::array<int, 15> formatY = {0,1,2,3,4,5,7,8,8,8,8,8,8,8,8};
	int observedFormatA = 0;
	int observedFormatB = 0;
	int readableFormatBits = 0;
	for (int i = 0; i < 15; ++i) {
		bool first = false, second = false;
		const bool firstOk = sample(formatX[i] + .5, formatY[i] + .5, first);
		const bool secondOk = i < 8
			? sample(dim - i - .5, 8.5, second)
			: sample(8.5, dim - 6.5 + (i - 8), second);
		if (firstOk) { if (first) observedFormatA |= 1 << i; ++readableFormatBits; }
		if (secondOk) { if (second) observedFormatB |= 1 << i; ++readableFormatBits; }
	}
	if (readableFormatBits >= 20) {
		const int distanceA = nearestFormatDistance(observedFormatA);
		const int distanceB = nearestFormatDistance(observedFormatB);
		const int bestDistance = std::min(distanceA, distanceB);
		// A legal field differs by at most three bits. Reward the margin too,
		// so a strong but invalid structural candidate cannot outrank it.
		const float formatScore = bestDistance <= 3
			? 4.2f - bestDistance * .55f
			: -3.5f - std::min(4, bestDistance - 3) * .45f;
		score += formatScore;
		weight += 4.2f;
	}

	// The dark module and version fields break the remaining finder/timing
	// symmetry.  Version bits are native binary function modules in all planes.
	known(8, dim - 8, true, 2.0f);
	const int version = (dim - 17) / 4;
	if (version >= 2) {
		// Every QR/HCC2D version after v1 has a bottom-right 5x5 alignment
		// pattern centred at dim-7.  It is the strongest native signal for the
		// otherwise unconstrained fourth homography corner.
		const int center = dim - 7;
		for (int y = -2; y <= 2; ++y) for (int x = -2; x <= 2; ++x) {
			const int radius = std::max(std::abs(x), std::abs(y));
			known(center + x, center + y, radius != 1, 1.55f);
		}
	}
	if (version >= 7) {
		const int value = (version << 12) | bchCode(version, 0x1F25);
		int bit = 0;
		for (int row = 0; row < 6; ++row) for (int col = 0; col < 3; ++col) {
			const bool black = ((value >> bit++) & 1) != 0;
			// Matches embed_version_info(): i is the six-module axis and j
			// is the three-module axis in both mirrored version fields.
			known(row, dim - 11 + col, black, 1.2f);
			known(dim - 11 + col, row, black, 1.2f);
		}
	}

	// The HCC2D palette ring is an additional native signal.  Every period
	// begins with black; test both legal periods and retain only the better
	// fit.  It validates that the returned quad denotes the *inner* QR grid,
	// rather than the outer colour border.
	auto paletteAnchors = [&](int period) {
		float localScore = 0.0f;
		float localWeight = 0.0f;
		for (int i = 8; i < dim - 8; i += period) {
			bool observed = false;
			for (const auto& point : std::array<std::pair<double, double>, 3>{{{i + .5, -.5}, {i + .5, dim + .5}, {dim + .5, i + .5}}}) {
				if (!sample(point.first, point.second, observed)) continue;
				localScore += observed ? 1.0f : -1.0f;
				localWeight += 1.0f;
			}
			const int leftY = dim - 9 - (i - 8);
			if (sample(-.5, leftY + .5, observed)) {
				localScore += observed ? 1.0f : -1.0f;
				localWeight += 1.0f;
			}
		}
		return std::pair{localScore, localWeight};
	};
	const auto ring4 = paletteAnchors(4);
	const auto ring8 = paletteAnchors(8);
	const auto ring = ring4.first / std::max(ring4.second, 1.0f) >= ring8.first / std::max(ring8.second, 1.0f) ? ring4 : ring8;
	score += ring.first * .55f;
	weight += ring.second * .55f;

	return weight > 1.0f ? score / weight : -1.0f;
}

bool Hcc2dDetector::findBottomRightAlignment(const Hcc2dGeometry& geometry, float modulePixels,
	float& centerX, float& centerY, float& confidence) const
{
	confidence = 0.0f;
	const int dim = geometry.modules;
	const int version = (dim - 17) / 4;
	if (version < 2 || 17 + 4 * version != dim) return false;
	Homography transform;
	if (!transform.set(geometry.quad)) return false;
	auto project = [&](double x, double y, float& px, float& py) {
		return transform.map(x / dim, y / dim, px, py) &&
			std::isfinite(px) && std::isfinite(py);
	};

	// The last alignment centre is always dim-7 in QR Model 2 and therefore
	// in HCC2D. Add .5 because the homography operates on module boundaries,
	// whereas the observed finder/alignment locations are module centres.
	const double sourceCenter = dim - 6.5;
	float predictedX = 0.0f, predictedY = 0.0f;
	float xNext = 0.0f, yNext = 0.0f;
	float xDown = 0.0f, yDown = 0.0f;
	if (!project(sourceCenter, sourceCenter, predictedX, predictedY) ||
		!project(sourceCenter + 1.0, sourceCenter, xNext, yNext) ||
		!project(sourceCenter, sourceCenter + 1.0, xDown, yDown)) return false;
	const float baseUx = xNext - predictedX, baseUy = yNext - predictedY;
	const float baseVx = xDown - predictedX, baseVy = yDown - predictedY;
	if (std::hypot(baseUx, baseUy) < .65f || std::hypot(baseVx, baseVy) < .65f) return false;

	struct Candidate {
		float x = 0.0f, y = 0.0f;
		float ux = 0.0f, uy = 0.0f;
		float vx = 0.0f, vy = 0.0f;
		int matches = -1;
	};
	Candidate best;
	auto score = [&](float candidateX, float candidateY, float ux, float uy, float vx, float vy) {
		int matches = 0;
		for (int y = -2; y <= 2; ++y) for (int x = -2; x <= 2; ++x) {
			const bool expected = std::max(std::abs(x), std::abs(y)) != 1;
			const bool observed = blackAt(candidateX + x * ux + y * vx, candidateY + x * uy + y * vy);
			matches += observed == expected ? 1 : 0;
		}
		return matches;
	};
	auto consider = [&](float candidateX, float candidateY, float ux, float uy, float vx, float vy) {
		const int matches = score(candidateX, candidateY, ux, uy, vx, vy);
		if (matches > best.matches) best = {candidateX, candidateY, ux, uy, vx, vy, matches};
	};

	// The affine finder seed can miss BR by several modules under perspective.
	// Search in its local module basis rather than raw pixels so the range is
	// stable across versions and resolutions. The larger scan covers real
	// handheld skew; the fine pass supplies sub-module precision for sampling.
	const int radius = std::clamp(static_cast<int>(std::ceil(std::max(7.0f,
		std::min(11.0f, 3.0f + 36.0f / std::max(modulePixels, 2.0f))))), 7, 11);
	for (const float scale : {.82f, .92f, 1.0f, 1.08f, 1.18f}) {
		const float ux = baseUx * scale, uy = baseUy * scale;
		const float vx = baseVx * scale, vy = baseVy * scale;
		for (int row = -radius; row <= radius; ++row) for (int column = -radius; column <= radius; ++column)
			consider(predictedX + column * ux + row * vx, predictedY + column * uy + row * vy, ux, uy, vx, vy);
	}
	if (best.matches < 19) return false;
	const Candidate coarse = best;
	for (const float scaleAdjust : {.94f, 1.0f, 1.06f}) {
		const float ux = coarse.ux * scaleAdjust, uy = coarse.uy * scaleAdjust;
		const float vx = coarse.vx * scaleAdjust, vy = coarse.vy * scaleAdjust;
		for (int row = -4; row <= 4; ++row) for (int column = -4; column <= 4; ++column) {
			const float offset = .25f;
			consider(coarse.x + column * offset * ux + row * offset * vx,
				coarse.y + column * offset * uy + row * offset * vy, ux, uy, vx, vy);
		}
	}
	if (best.matches < 20) return false;
	centerX = best.x;
	centerY = best.y;
	confidence = static_cast<float>(best.matches) / 25.0f;
	return true;
}

void Hcc2dDetector::refineGeometry(const Hcc2dYuv420& frame, Hcc2dGeometry& geometry, float modulePixels) const
{
	float best = scoreGeometry(frame, geometry);
	if (best < -0.5f) return;
	// If the alignment-mark search was inconclusive, allow the free BR corner
	// to make a few coarse, perspective-sized moves before the normal local
	// refinement. The other three corners remain pinned by their full finders.
	for (const float factor : {4.0f, 2.0f}) {
		const float step = std::max(.7f, modulePixels * factor);
		for (int coordinate = 4; coordinate <= 5; ++coordinate) {
			const double original = geometry.quad[coordinate];
			for (const float delta : {-step, step}) {
				geometry.quad[coordinate] = original + delta;
				const float candidate = scoreGeometry(frame, geometry);
				if (candidate > best) {
					best = candidate;
					continue;
				}
				geometry.quad[coordinate] = original;
			}
		}
	}
	for (const float factor : {1.20f, .60f, .28f}) {
		const float step = std::max(.35f, modulePixels * factor);
		for (int pass = 0; pass < 2; ++pass) for (int coordinate = 0; coordinate < 8; ++coordinate) {
			const double original = geometry.quad[coordinate];
			for (const float delta : {-step, step}) {
				geometry.quad[coordinate] = original + delta;
				const float candidate = scoreGeometry(frame, geometry);
				if (candidate > best) {
					best = candidate;
					continue;
				}
				geometry.quad[coordinate] = original;
			}
		}
	}
	geometry.score = best;
}

bool Hcc2dDetector::detect(const Hcc2dYuv420& frame, std::vector<Hcc2dGeometry>& output)
{
	output.clear();
	_stats = {};
	if (!prepareBlackMap(frame)) return false;
	std::vector<Finder> finders;
	findFinders(finders);
	_stats.rawFinders = static_cast<int>(finders.size());
	if (finders.size() < 3) return false;
	clusterFinders(finders);
	_stats.clusteredFinders = static_cast<int>(finders.size());
	if (finders.size() < 3) return false;

	struct TripleSeed {
		const Finder* corner = nullptr;
		const Finder* first = nullptr;
		const Finder* second = nullptr;
		int nominalDimension = 0;
		int versionWindow = 2;
		float modulePixels = 0.0f;
		float score = 0.0f;
	};
	std::vector<TripleSeed> seeds;
	for (int i = 0; i < static_cast<int>(finders.size()); ++i) for (int j = i + 1; j < static_cast<int>(finders.size()); ++j)
		for (int k = j + 1; k < static_cast<int>(finders.size()); ++k) {
			const Finder* points[3] = {&finders[i], &finders[j], &finders[k]};
			const float d01 = distance(points[0]->x, points[0]->y, points[1]->x, points[1]->y);
			const float d02 = distance(points[0]->x, points[0]->y, points[2]->x, points[2]->y);
			const float d12 = distance(points[1]->x, points[1]->y, points[2]->x, points[2]->y);
		// The right-angle finder is opposite the longest side.  d01 is the
		// default longest side, so its opposite point is index 2.
		int cornerIndex = 2;
		if (d02 >= d01 && d02 >= d12) cornerIndex = 1;
		else if (d12 >= d01 && d12 >= d02) cornerIndex = 0;
			const int firstIndex = (cornerIndex + 1) % 3;
			const int secondIndex = (cornerIndex + 2) % 3;
			const auto* corner = points[cornerIndex];
			const auto* first = points[firstIndex];
			const auto* second = points[secondIndex];
			const float legA = distance(corner->x, corner->y, first->x, first->y);
			const float legB = distance(corner->x, corner->y, second->x, second->y);
			if (legA < 12.0f || legB < 12.0f) continue;
			const float legRatio = legA / legB;
			if (legRatio < .40f || legRatio > 2.5f) continue;
			const float dot = ((first->x - corner->x) * (second->x - corner->x) +
				(first->y - corner->y) * (second->y - corner->y)) / (legA * legB);
			if (std::abs(dot) > .45f) continue;
			const float localA = (corner->module + first->module) * .5f;
			const float localB = (corner->module + second->module) * .5f;
			if (localA < 1.0f || localB < 1.0f) continue;
			const float measuredA = legA / localA + 7.0f;
			const float measuredB = legB / localB + 7.0f;
			const float measured = (measuredA + measuredB) * .5f;
			const int nominal = nearestDimension(measured);
			const float dimensionSpread = std::abs(measuredA - measuredB);
			const float dimensionError = dimensionSpread + std::abs(measured - nominal);
			// A finder is measured on horizontal and vertical image scanlines,
			// while its legs can be strongly foreshortened in opposite directions.
			// Treat their disagreement as a perspective cue, not a hard reject.
			// The previous .22 limit discarded the genuine v20 triple in the
			// synthetic camera scene (110.8 versus 83.2 modules, mean = 97).
			if (dimensionError > std::max(12.0f, (nominal - 7) * .72f)) continue;
			const int versionWindow = std::clamp(2 + static_cast<int>(std::ceil(
				dimensionSpread / std::max(18.0f, (nominal - 7) * .22f))), 2, 5);
			const float shape = std::max(0.0f, 1.0f - std::abs(dot) / .45f);
			const float balance = std::min(legRatio, 1.0f / legRatio);
			const int support = std::min({corner->count, first->count, second->count});
			// A genuine finder persists over several scanlines. Random HCC data
			// runs often produce one perfect 1:1:3:1:1 hit, so use bounded
			// cluster support to rank seeds without rejecting small/distant code.
			const float supportScore = .55f + .45f * std::min(1.0f, support / 4.0f);
			const float confidence = (corner->score + first->score + second->score) / 3.0f *
				(.45f + .55f * shape) * (.45f + .55f * balance) /
				(1.0f + dimensionError / std::max(12.0f, (nominal - 7) * .60f)) * supportScore;
			seeds.push_back({corner, first, second, nominal, versionWindow, (localA + localB) * .5f, confidence});
		}
	if (seeds.empty()) return false;
	std::sort(seeds.begin(), seeds.end(), [](const TripleSeed& lhs, const TripleSeed& rhs) { return lhs.score > rhs.score; });
	if (seeds.size() > kMaxTripleSeeds) seeds.resize(kMaxTripleSeeds);
	_stats.tripleSeeds = static_cast<int>(seeds.size());

	std::vector<Hypothesis> hypotheses;
	for (int seedIndex = 0; seedIndex < static_cast<int>(seeds.size()); ++seedIndex) {
		const auto& seed = seeds[seedIndex];
		const int centerVersion = (seed.nominalDimension - 17) / 4;
		std::array<bool, 41> versions{};
		for (int version = std::max(1, centerVersion - seed.versionWindow);
			version <= std::min(40, centerVersion + seed.versionWindow); ++version)
			versions[version] = true;
		if (seedIndex < kBroadVersionSeedCount && centerVersion >= 24) {
			// A 30–45 degree handheld roll widens a horizontal finder run by
			// roughly 15–40%, which can make a v40 seed look like v25–v34.
			// Scoring all legal dimensions for these few strongest triples is
			// both safer and cheaper than trying to infer camera roll from a
			// single 1:1:3:1:1 scanline.
			versions.fill(true);
			versions[0] = false;
		}
		for (int version = 1; version <= 40; ++version) {
			if (!versions[version]) continue;
			const int dim = 17 + 4 * version;
			for (int swapped = 0; swapped < 2; ++swapped) {
				const Finder* topRight = swapped == 0 ? seed.first : seed.second;
				const Finder* bottomLeft = swapped == 0 ? seed.second : seed.first;
				const float scale = 1.0f / static_cast<float>(dim - 7);
				const float ux = (topRight->x - seed.corner->x) * scale;
				const float uy = (topRight->y - seed.corner->y) * scale;
				const float vx = (bottomLeft->x - seed.corner->x) * scale;
				const float vy = (bottomLeft->y - seed.corner->y) * scale;
				Hcc2dGeometry geometry;
				geometry.modules = dim;
				// Finder centres are at (3.5, 3.5), (dim-3.5, 3.5),
				// (3.5, dim-3.5) in inner-grid coordinates.  This affine
				// estimate is deliberately only a seed; timing tracks refine it
				// into a projective quadrilateral below.
				geometry.quad[0] = seed.corner->x - 3.5f * (ux + vx);
				geometry.quad[1] = seed.corner->y - 3.5f * (uy + vy);
				geometry.quad[2] = topRight->x + 3.5f * ux - 3.5f * vx;
				geometry.quad[3] = topRight->y + 3.5f * uy - 3.5f * vy;
				geometry.quad[6] = bottomLeft->x - 3.5f * ux + 3.5f * vx;
				geometry.quad[7] = bottomLeft->y - 3.5f * uy + 3.5f * vy;
				// The top-right finder is at (dim - 3.5, 3.5), so reaching
				// the bottom-right grid corner needs the remaining long V leg,
				// not merely the finder-radius offset. Using 3.5 for both legs
				// folded high-version quads into a thin strip.
				geometry.quad[4] = topRight->x + 3.5f * ux + (dim - 3.5f) * vx;
				geometry.quad[5] = topRight->y + 3.5f * uy + (dim - 3.5f) * vy;
				geometry.score = scoreGeometry(frame, geometry);
				// Perspective moves the fourth corner while the first three finder
				// centres remain accurate. Keep plausible weak affine seeds so the
				// alignment landmark can turn them into a real homography below.
				if (geometry.score < -.25f) continue;
				Hypothesis hypothesis;
				hypothesis.geometry = geometry;
				hypothesis.finderPixels = {seed.corner->x, seed.corner->y,
					topRight->x, topRight->y, bottomLeft->x, bottomLeft->y};
				hypothesis.modulePixels = seed.modulePixels;
				hypothesis.geometricScore = seed.score;
				hypotheses.push_back(hypothesis);
			}
		}
		}
	if (hypotheses.empty()) return false;
	_stats.hypotheses = static_cast<int>(hypotheses.size());
	std::sort(hypotheses.begin(), hypotheses.end(), [](const Hypothesis& lhs, const Hypothesis& rhs) {
		return lhs.geometry.score + lhs.geometricScore * .08f > rhs.geometry.score + rhs.geometricScore * .08f;
	});
	// Three finder centres only determine an affine seed. Search the known
	// bottom-right alignment mark for the best candidates, then solve the
	// complete four-point camera homography from HCC2D's own structures.
	const int alignmentCount = std::min<int>(24, hypotheses.size());
	for (int i = 0; i < alignmentCount; ++i) {
		auto& hypothesis = hypotheses[i];
		const int dim = hypothesis.geometry.modules;
		float alignmentX = 0.0f, alignmentY = 0.0f, alignmentConfidence = 0.0f;
		if (!findBottomRightAlignment(hypothesis.geometry, hypothesis.modulePixels,
			alignmentX, alignmentY, alignmentConfidence)) continue;
		const std::array<std::array<double, 2>, 4> source = {{
			{{3.5, 3.5}},
			{{double(dim) - 3.5, 3.5}},
			{{3.5, double(dim) - 3.5}},
			{{double(dim) - 6.5, double(dim) - 6.5}},
		}};
		const auto& finder = hypothesis.finderPixels;
		const std::array<std::array<double, 2>, 4> destination = {{
			{{finder[0], finder[1]}},
			{{finder[2], finder[3]}},
			{{finder[4], finder[5]}},
			{{alignmentX, alignmentY}},
		}};
		Hcc2dGeometry projective;
		projective.modules = dim;
		if (!solveProjectiveQuad(source, destination, dim, projective.quad)) continue;
		projective.score = scoreGeometry(frame, projective);
		// A valid 5x5 match must also agree with the broader native function
		// pattern. This prevents a random coloured data patch from replacing a
		// good affine candidate in a dense multi-code frame.
		if (alignmentConfidence >= .80f && projective.score >= hypothesis.geometry.score - .015f)
			hypothesis.geometry = projective;
	}
	std::sort(hypotheses.begin(), hypotheses.end(), [](const Hypothesis& lhs, const Hypothesis& rhs) {
		return lhs.geometry.score + lhs.geometricScore * .08f > rhs.geometry.score + rhs.geometricScore * .08f;
	});
	// Refining every false triangle is wasteful.  The structural scorer has
	// already reduced candidates to a few likely symbols, so refine only the
	// strongest candidates; each refinement samples only native function cells.
	const int refineCount = std::min<int>(16, hypotheses.size());
	for (int i = 0; i < refineCount; ++i)
		refineGeometry(frame, hypotheses[i].geometry, hypotheses[i].modulePixels);
	std::sort(hypotheses.begin(), hypotheses.end(), [](const Hypothesis& lhs, const Hypothesis& rhs) {
		return lhs.geometry.score > rhs.geometry.score;
	});

	for (const auto& hypothesis : hypotheses) {
		if (hypothesis.geometry.score < .36f) continue;
		if (!convexQuad(hypothesis.geometry.quad)) continue;
		const float cx = quadCenterX(hypothesis.geometry.quad);
		const float cy = quadCenterY(hypothesis.geometry.quad);
		const float s = quadSpan(hypothesis.geometry.quad);
		if (s < 12.0f) continue;
		const bool duplicate = std::any_of(output.begin(), output.end(), [&](const Hcc2dGeometry& existing) {
			if (existing.modules != hypothesis.geometry.modules) return false;
			const float distanceRatio = distance(cx, cy, quadCenterX(existing.quad), quadCenterY(existing.quad)) /
				std::max(quadSpan(existing.quad), s);
			const float scaleRatio = s / std::max(quadSpan(existing.quad), 1.0f);
			return distanceRatio < .30f && scaleRatio > .65f && scaleRatio < 1.55f;
		});
		if (duplicate) continue;
		output.push_back(hypothesis.geometry);
		if (static_cast<int>(output.size()) >= kMaxOutputSymbols) break;
	}
	_stats.acceptedGeometries = static_cast<int>(output.size());
	return !output.empty();
}
