/* Thin Android-facing API around the official HCC2D 0.9.0 reference encoder.
 * The original source remains Apache-2.0 and retains its copyright header. */
#pragma once

#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/** Encode raw binary bytes to one palette-index module matrix.
 * colors must be 4 or 8; EC uses L/M/Q/H; version is 1..40. */
int hcc2d_encode_modules(const uint8_t* payload, int payload_len, int colors,
                          char ec_level, int version, uint8_t* out_modules,
                          int out_capacity, int* out_full_dim,
                          int* out_mask_pattern, int* out_data_capacity);

/** Write a 0/1 reserved-function-module map for the inner QR-compatible grid. */
int hcc2d_function_modules(int version, uint8_t* out_modules, int out_capacity);

/** Retrieve the final codeword layout for a HCC2D symbol. */
int hcc2d_codeword_layout(int colors, char ec_level, int version,
                          int* out_total_codewords, int* out_data_codewords,
                          int* out_blocks, int* out_ec_per_block);

#ifdef __cplusplus
}
#endif
