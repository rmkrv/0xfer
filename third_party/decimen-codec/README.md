# decimen-codec

A Decimen-specific build of [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp):
QR-only, reader-only, plus a **tracked decode path** the stock library cannot
offer — the receiver's whole reason to fork.

## Why this exists

Decimen's receiver decodes the *same* codes at the *same* positions dozens of
times per second. Stock zxing re-runs detection — finder pattern search,
transform estimation — on every single frame, and detection is the expensive
half of a decode. This fork adds `readTracked`: rebuild the module→pixel
homography from the previous decode's position quad, binarize, sample the
grid, Reed–Solomon decode. No detection at all.

Measured (Node, synthetic captures at 3 px/module, `bench/bench.mjs`):

| input | stock full | tracked | speedup |
|---|---|---|---|
| V27 clean | 2.6 ms | 1.8 ms | 1.5× |
| V40 clean | 8.2 ms | 3.1 ms | 2.6× |
| V40 blurred | 7.4 ms | 3.7 ms | 2.0× |

Byte-for-byte parity with the stock path on every tested input. The binary is
a fraction of the size of upstream zxing-wasm's (all non-QR formats compiled
out).

Drift tolerance: the cached quad may be off by ~1 px (at 3 px/module) before
tracked decode fails. The receiver refreshes the quad on every successful
decode and falls back to `readFull` (which re-anchors) on any failure, so
tracked is purely opportunistic — it can be slower under motion, never wrong.

## API (embind, ES6 module)

- `readFull(ptr, width, height, tryHarder, maxSymbols, returnErrors)` →
  `vector<DecimenResult>` — stock acquisition, QR-only, invert/rotate sweeps
  hard-off. Error results carry positions (crop-seeding sightings).
- `readTracked(ptr, width, height, dim, x0,y0, x1,y1, x2,y2, x3,y3)` →
  `DecimenResult` — the fast path. Quad = a previous result's `position`
  (topLeft, topRight, bottomRight, bottomLeft), `dim` = module count.
- `DecimenResult`: `{ valid, error, bytes: Uint8Array, position }`.
- Debug helpers: `trackedMatrix`, `projectPoint`, `binarizedRow`.

Buffers are RGBA in wasm heap memory (`_malloc` + `HEAPU8.set`). Internally
the wrapper extracts a luminance plane before binarizing — HybridBinarizer
fed raw RGBA silently misreads the buffer (ReadBarcode.cpp does the same
extraction; see `toLum` in the wrapper).

## Building

```
./build.sh              # → dist/ (bootstraps the pinned emsdk on first run)
node bench/bench.mjs    # verify — parity and drift lines are the gate
```

The emscripten toolchain is pinned in `build.sh` (`EMSDK_VERSION`) — same
source + same toolchain reproduces the same binary. Bump it deliberately and
re-run the bench.

`third_party/zxing-cpp` is a git submodule pinned to an upstream commit —
clone with `--recurse-submodules`, or run `git submodule update --init` after
a plain clone. Upstream updates: check out a newer commit in the submodule,
rebuild, re-run the bench — parity and drift lines are the regression gate.

## Native builds (iOS / Android)

The same `wrapper/decimen_codec.cpp` compiles with no emscripten at all. The
emscripten includes, the `DecimenResult`/`Uint8Array` conversion and the
`EMSCRIPTEN_BINDINGS` block sit behind `#ifdef __EMSCRIPTEN__`; the decode
paths themselves (`readFullPtr`, `readTrackedPtr`) are plain C++20 over a
`const uint8_t*`, so there is exactly one implementation of the engine rather
than one per platform.

Native consumers use the C ABI in [`wrapper/decimen_codec.h`](wrapper/decimen_codec.h):
`decimen_read_full`, `decimen_read_tracked`, `decimen_version`,
`decimen_build`. Two rules keep Swift/Kotlin interop boring — the image is a
real pointer (a wasm32 offset fits in an `int`; a 64-bit pointer does not), and
the caller owns every buffer, so there is no allocation contract to get wrong
across the language boundary. A short buffer is a clean
`DECIMEN_ERR_CAPACITY`, never an overrun.

The same CMake tree builds it. Configured outside the emsdk environment it
skips every wasm-specific flag and produces `libdecimen_codec.a` plus the C
ABI instead of the ES6 module:

```
cmake -S . -B build-native -DCMAKE_BUILD_TYPE=Release
cmake --build build-native
```

CMake also covers the two things a by-hand compile trips over: the wrapper
uses zxing *internal* headers whose members are gated behind `ZXING_INTERNAL`,
and zxing generates its `Version.h` at configure time. `-msimd128` exists only
in the wasm branch of the build — on arm64 the same auto-vectorized zxing
loops use NEON without being asked. iOS/Android builds are the standard CMake
toolchain-file dance over this same target.

CI compiles the native target on every push, so the `#ifndef __EMSCRIPTEN__`
half of the wrapper cannot rot behind the wasm-only workflow.

**Threading:** `readTracked` keeps its adaptive-skip state in function-local
statics. Under wasm that is per-worker state (each worker owns its module
instance), but a native decode pool shares one address space and concurrent
calls race on it — confirmed under ThreadSanitizer. Serialise calls, or give
each thread its own copy, until that state moves into a caller-owned handle.

### What this library does not do

The engine decodes QR *symbols* — it returns the bytes inside a code and knows
nothing about what they mean. Decimen's framing sits a layer above: a 22-byte
header (magic, wire version, feature flags, fountain parameters) wrapped around
a Luby-transform block, reassembled into a file container. A native client has
to implement that layer itself.

The contract is specified, versioned, and has conformance vectors, in
[decimen-optical-transfer](https://github.com/bashalarmistalt/decimen-optical-transfer):

- `docs/technical/versioning.md` — magic bytes, wire version, the must-understand vs
  ignorable flag split, and what a receiver owes the user when it meets a format
  it cannot read. Read this before writing a parser; a client that silently
  ignores an unknown version is the failure mode the format exists to prevent.
- `docs/technical/golden-vectors.md` — the bytes to test against. A native decoder is
  correct when it agrees with them.

## License

[AGPL-3.0-or-later](LICENSE). Incorporates
[zxing-cpp](https://github.com/zxing-cpp/zxing-cpp) under the Apache License
2.0 (`third_party/zxing-cpp/LICENSE`, an unmodified pinned submodule); see
[NOTICE](NOTICE).

For commercial licensing outside the AGPL, open an issue or contact the
author.
