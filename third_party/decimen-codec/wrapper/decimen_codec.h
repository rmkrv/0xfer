/*
 * SPDX-License-Identifier: AGPL-3.0-or-later
 * Copyright (c) 2026 Evan Crawley (Bash Alarmist)
 *
 * Decimen codec — C ABI for native consumers (iOS, Android).
 *
 * The web build talks to this engine through embind and never sees this
 * header; a native build compiles the same wrapper with no emscripten at all
 * and gets these functions instead.
 *
 * Design rules, both aimed at making Swift/Kotlin interop boring:
 *
 *  - Pointers, not heap offsets. The embind entry points take an `int` because
 *    a wasm32 pointer fits in one; a 64-bit pointer does not, so nothing here
 *    passes an image as an integer.
 *  - The caller owns every buffer. No allocation, no free function, no
 *    ownership rules to get wrong across a language boundary. If a buffer is
 *    too small the call fails with DECIMEN_ERR_CAPACITY and writes nothing
 *    past its end.
 *
 * Threading: see the note on decimen_read_tracked.
 */
#ifndef DECIMEN_CODEC_H
#define DECIMEN_CODEC_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define DECIMEN_OK 0
/** A required pointer was null, or a dimension was <= 0. */
#define DECIMEN_ERR_ARGS (-1)
/** Acquisition found nothing to report. */
#define DECIMEN_ERR_NO_SYMBOL (-2)
/** A symbol was located but did not decode (ECC failure, lost anchor). The
 *  output symbol's `quad` is still worth reading — aim the next crop there. */
#define DECIMEN_ERR_DECODE (-3)
/** An output buffer was too small. Nothing was written past its end. */
#define DECIMEN_ERR_CAPACITY (-4)

/**
 * One decode result.
 *
 * `quad` is the module→pixel projection of {0,0}, {dim,0}, {dim,dim}, {0,dim}
 * as x0,y0,x1,y1,x2,y2,x3,y3 — the same convention zxing's GridSampler uses,
 * so feeding it straight back into decimen_read_tracked reconstructs the
 * sampling transform. It is meaningful even when `valid` is 0, which is how
 * the receiver re-aims at a code that detected but failed ECC.
 *
 * `bytes_offset` / `bytes_len` index the caller's bytes buffer. Results are
 * packed back to back in the order reported.
 */
typedef struct decimen_symbol {
	int32_t valid;         /**< 1 when bytes are a good decode. */
	int32_t modules;       /**< Symbol dimension in modules (17 + 4·version); 0 if unknown. */
	double quad[8];        /**< Pixel corners, GridSampler order. */
	uint32_t bytes_offset; /**< Offset into the caller's bytes buffer. */
	uint32_t bytes_len;    /**< Decoded byte count; 0 when !valid. */
} decimen_symbol;

/** Which build is this? Mirrors the version()/build() JS exports. */
const char *decimen_version(void);
const char *decimen_build(void);

/**
 * Stock acquisition: find and decode QR symbols in an RGBA image.
 *
 * Writes up to `symbols_cap` results and stops; the count actually written
 * lands in `out_count`. Returns DECIMEN_ERR_NO_SYMBOL when nothing was found.
 */
int decimen_read_full(const uint8_t *rgba, int32_t width, int32_t height,
                       int32_t try_harder, int32_t max_symbols, int32_t return_errors,
                       decimen_symbol *out_symbols, uint32_t symbols_cap,
                       uint8_t *out_bytes, uint32_t bytes_cap,
                       uint32_t *out_count);

/** Same decoder as [decimen_read_full], for an 8-bit luminance plane. Native
 * camera APIs commonly deliver this directly, avoiding a luminance → RGBA →
 * luminance conversion on every frame. */
int decimen_read_full_lum(const uint8_t *lum, int32_t width, int32_t height,
                          int32_t try_harder, int32_t max_symbols, int32_t return_errors,
                          decimen_symbol *out_symbols, uint32_t symbols_cap,
                          uint8_t *out_bytes, uint32_t bytes_cap,
                          uint32_t *out_count);

/**
 * The tracked fast path: decode a symbol whose position and module count are
 * already known, skipping detection entirely. `quad_in` is a previous result's
 * `quad`; `dim` is its `modules`.
 *
 * Adaptive skip state is thread-local so independent native decode workers
 * behave like the browser's isolated WASM workers.
 */
int decimen_read_tracked(const uint8_t *rgba, int32_t width, int32_t height, int32_t dim,
                          const double quad_in[8],
                          decimen_symbol *out_symbol,
                          uint8_t *out_bytes, uint32_t bytes_cap);

/** Same tracked path as [decimen_read_tracked], for an 8-bit luminance plane. */
int decimen_read_tracked_lum(const uint8_t *lum, int32_t width, int32_t height, int32_t dim,
                             const double quad_in[8],
                             decimen_symbol *out_symbol,
                             uint8_t *out_bytes, uint32_t bytes_cap);

#ifdef __cplusplus
} /* extern "C" */
#endif

#endif /* DECIMEN_CODEC_H */
