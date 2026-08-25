#pragma once

#include "hcc2d_codec.h"

#include <vector>

/**
 * Geometry returned by the native HCC2D locator.
 *
 * `modules` is the size of the inner QR-compatible grid (21…177), not the
 * HCC2D colour-palette border.  `quad` follows the convention already used by
 * Hcc2dDecoder: top-left, top-right, bottom-right, bottom-left grid corners
 * in the unrotated CameraX image coordinate system.  Projecting (-.5, y) or
 * (x, -.5) through this quad therefore lands in the HCC2D palette border.
 */
struct Hcc2dGeometry {
	int modules = 0;
	double quad[8]{};
	float score = 0.0f;
};

/** Snapshot of the last native acquisition pass. It is intentionally tiny and
 * read only at the UI's low stats cadence, so it never allocates per frame. */
struct Hcc2dDetectorStats {
	int darkThreshold = 0;
	int rawFinders = 0;
	int clusteredFinders = 0;
	int tripleSeeds = 0;
	int hypotheses = 0;
	int acceptedGeometries = 0;
};

/**
 * Stand-alone HCC2D finder-pattern detector.
 *
 * HCC2D deliberately preserves QR Model 2's black/white function patterns:
 * three 7x7 finder patterns, timing tracks, type-information duplicates and,
 * for versions >= 7, version information.  This detector finds those native
 * HCC2D structures directly from YUV_420_888; it does not call the Decimen
 * or ZXing QR detector and it never tries to QR-decode HCC2D data modules.
 *
 * One instance owns reusable scratch memory and is intended to be called
 * serially for one camera stream.  `detect` returns zero or more physical
 * symbols, sorted by confidence.  In the rare orientation tie it may emit a
 * second, opposite-axis hypothesis for the same physical symbol; callers can
 * cheaply try both against HCC2D format/error correction and retain the one
 * which validates.
 */
class Hcc2dDetector {
public:
	bool detect(const Hcc2dYuv420& frame, std::vector<Hcc2dGeometry>& output);
	void reset();
	const Hcc2dDetectorStats& stats() const { return _stats; }

private:
	struct Finder;
	struct Homography;
	struct Hypothesis;

	std::vector<unsigned char> _black;
	// Reused summed-area table for local luma contrast. A global percentile is
	// not stable when a camera sees both a bright display and a dark room.
	std::vector<uint32_t> _lumaIntegral;
	int _width = 0;
	int _height = 0;
	int _darkThreshold = 0;
	int _neutralU = 128;
	int _neutralV = 128;
	Hcc2dDetectorStats _stats{};

	bool prepareBlackMap(const Hcc2dYuv420& frame);
	bool blackAt(int x, int y) const;
	bool blackAt(float x, float y) const;
	bool verticalCheck(int x, int y, float expectedModule, Finder& out) const;
	void findFinders(std::vector<Finder>& output) const;
	void clusterFinders(std::vector<Finder>& finders) const;
	float scoreGeometry(const Hcc2dYuv420& frame, const Hcc2dGeometry& geometry) const;
	bool findBottomRightAlignment(const Hcc2dGeometry& geometry, float modulePixels,
		float& centerX, float& centerY, float& confidence) const;
	void refineGeometry(const Hcc2dYuv420& frame, Hcc2dGeometry& geometry, float modulePixels) const;
};
