#pragma once

#include <array>
#include <cstdint>
#include <memory>
#include <vector>

#include "GridSampler.h"

class Hcc2dDetector;

struct Hcc2dYuv420 {
	const uint8_t* y = nullptr;
	const uint8_t* u = nullptr;
	const uint8_t* v = nullptr;
	int width = 0;
	int height = 0;
	int y_row_stride = 0;
	int y_pixel_stride = 0;
	int u_row_stride = 0;
	int u_pixel_stride = 0;
	int v_row_stride = 0;
	int v_pixel_stride = 0;
	// Pixel origin of this direct view in the full camera image. Acquisition
	// geometry remains in full-image coordinates while tracked decoding may
	// operate on a zero-copy crop.
	int origin_x = 0;
	int origin_y = 0;
};

struct Hcc2dDecodeInfo {
	bool detected = false;
	bool valid = false;
	int colors = 0;
	int version = 0;
	int payload_capacity = 0;
	/** Furthest completed native stage: 0=no geometry, 1=geometry,
	 * 2=colours sampled, 3=format, 4=codewords, 5=payload, 6=valid. */
	int stage = 0;
	// This frame is a repeat of an already verified symbol. It is not emitted
	// to the fountain receiver, but remains a healthy geometry lock.
	bool repeated = false;
	int64_t decode_nanos = 0;
};

/** A single camera stream owns one instance and calls it serially. It retains
 * native scratch buffers, palette references and tracked geometry. */
class Hcc2dDecoder {
public:
	Hcc2dDecoder();
	~Hcc2dDecoder();
	Hcc2dDecoder(const Hcc2dDecoder&) = delete;
	Hcc2dDecoder& operator=(const Hcc2dDecoder&) = delete;
	bool decode(const Hcc2dYuv420& frame, std::vector<uint8_t>& payload, Hcc2dDecodeInfo& info);
	/** Decode against geometry acquired by a shared multi-symbol detector. */
	bool decodeTracked(const Hcc2dYuv420& frame, std::vector<uint8_t>& payload, Hcc2dDecodeInfo& info);
	void setGeometry(int modules, const double quad[8]);
	void setSamplingROIs(const ZXing::ROIs& rois);
	/** Whether a previously supplied quadrilateral is still available for the
	 * inexpensive tracked path. */
	bool hasGeometry() const;
	/** True only after the tracked path has lost its geometry anchor for several
	 * consecutive frames. Callers should then schedule a full finder search. */
	bool needsAcquisition() const;
	/** Copies the most recently locked QR-compatible quadrilateral in camera
	 * coordinates. It remains available for overlay updates on repeat frames. */
	bool quad(double out[8]) const;
	void reset();

private:
	struct Yuv { int y, u, v; };
	struct SamplePoint { int16_t x, y; };
	// A 3x3 interior patch produces genuinely different luma/chroma samples
	// on camera YUV420 frames. The old five offsets (±.12 module) commonly
	// rounded into the same 2x2 chroma cell at V40 density.
	using ModuleSamples = std::array<SamplePoint, 9>;
	struct Track {
		bool active = false;
		int dim = 0;
		double quad[8]{};
	};
	std::vector<uint8_t> _luma;
	std::vector<uint8_t> _functionPattern;
	std::array<Yuv, 8> _palette{};
	std::array<int, 8> _paletteCount{};
	std::vector<ModuleSamples> _moduleSamples;
	std::vector<ModuleSamples> _borderSamples;
	std::vector<uint8_t> _borderIndexes;
	std::vector<Yuv> _borderObserved;
	ZXing::ROIs _samplingROIs;
	// Reused robust palette-fit scratch. A physical palette border contains a
	// few samples blurred across neighbouring modules, so its centroid must not
	// be a raw mean.
	std::array<std::vector<int>, 8> _paletteYs;
	std::array<std::vector<int>, 8> _paletteUs;
	std::array<std::vector<int>, 8> _paletteVs;
	std::vector<uint32_t> _dataModules;
	std::vector<uint8_t> _codewords;
	std::vector<uint8_t> _corrected;
	std::vector<std::vector<uint8_t>> _rsBlocks;
	std::vector<int> _rsDataLengths;
	int _functionVersion = 0;
	Track _track;
	int _frameCount = 0;
	int _trackFailures = 0;
	int _sampleWidth = 0;
	int _sampleHeight = 0;
	bool _samplingDirty = true;
	uint64_t _lastDecodedHash = 0;
	bool _hasLastDecodedHash = false;
	std::unique_ptr<Hcc2dDetector> _detector;

	bool findGeometry(const Hcc2dYuv420& frame, Hcc2dDecodeInfo& info);
	/** Cheap tracked-frame correction. It only evaluates the known binary finder
	 * templates near the cached quad, never invokes the full-frame locator. */
	bool reanchorGeometry(const Hcc2dYuv420& frame, int dim);
	bool decodeImpl(const Hcc2dYuv420& frame, std::vector<uint8_t>& payload, Hcc2dDecodeInfo& info, bool acquireGeometry);
	bool prepareSampling(const Hcc2dYuv420& frame, int dim);
	Yuv sample(const Hcc2dYuv420& frame, SamplePoint point) const;
	Yuv sampleMedian(const Hcc2dYuv420& frame, const ModuleSamples& points) const;
	uint8_t classify(const Hcc2dYuv420& frame, const ModuleSamples& points, int colors) const;
	bool restorePayload(int total, int data, int blocks, int ecpb, std::vector<uint8_t>& payload);
};
