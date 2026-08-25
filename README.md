# 0xfer - Optical transfer

0xfer is an Android app for sending files between two devices with a display and a
camera. The sender presents an animated optical code; the receiver scans the
stream and reconstructs the original file without a network connection between
the devices.

Built with [decimen-codec](https://github.com/bashalarmistalt/decimen-codec),
the [HCC2D CLI C encoder](https://github.com/marco-querini/hcc2d-cli-c-encoder),
and [zxing-cpp](https://github.com/zxing-cpp/zxing-cpp). Thank you to their
maintainers and contributors.

## Features

- Transfers files or text as an endless, fountain-coded QR stream.
- Reassembles frames received in any order and verifies each completed file
  with SHA-256.
- Uses a native, tracked QR decoding path through a locally adapted vendored
  copy of [`decimen-codec`](third_party/decimen-codec/) and this project's JNI
  bridge, for camera performance.
- Includes experimental HCC2D4 and HCC2D8 colour-code sender and receiver
  modes using the HCC2D 0.9.0 reference encoder.
- Lets the sender choose QR, HCC2D4, or HCC2D8; QR remains the baseline
  implementation.
- Supports 1, 2, 3, 4, or 6 displayed code cells and reports sender/receiver
  throughput while transferring.

The transfer itself is not encrypted: a camera that can see the sender's
screen can read the transmitted content.

## Requirements

- Android device running Android 8.0 (API 26) or later, with a camera for
  receiving.
- Android SDK with API 37 installed.
- Android NDK 28.2.13676358 and CMake (Android Studio can install these).
- A JDK compatible with Android Gradle Plugin 9.2.1.

## Build

Open the repository in Android Studio and allow Gradle to sync, or build from
PowerShell:

```powershell
.\gradlew.bat assembleDebug
```

The debug APK is written to:

```
app\build\outputs\apk\debug\app-debug.apk
```

The repository's prebuilt debug APK is [app.apk](app.apk).

Install it on a connected device with:

```powershell
.\gradlew.bat installDebug
```

## Usage

1. Open the app on the sending device and choose a file or enter text.
2. Start the transfer to display the animated code stream.
3. Open the receiver on the other device, grant camera access, and point it
   at the sender's display until the file is reconstructed.

Standard QR is the default mode. HCC2D4 and HCC2D8 are experimental and must
be selected on both sides of a transfer.

## Implementation and performance work

### QR baseline

- QR uses Decimen's QR-only native path. A full detector pass acquires symbol
  geometry; subsequent camera images use the cached quadrilateral, an
  inexpensive local finder re-anchor, grid sampling, and error correction.
- The QR receiver retains multiple decoded regions and can track/crop them in
  parallel instead of rediscovering every code from the entire camera image.
- The sender updates a staggered code grid, retaining unchanged cell rasters
  rather than regenerating every displayed QR matrix for every UI frame.

### Experimental HCC2D

- HCC2D packets are binary fountain-code packets. They are passed directly to
  the native encoder/decoder; no Base64 or text conversion is used.
- The HCC2D encoder uses the upstream HCC2D 0.9.0 C reference encoder. The
  Android renderer draws palette-index modules directly, including the
  required palette border and quiet zone.
- The receiver performs HCC2D-specific geometry acquisition, perspective
  correction, module sampling, palette fitting/classification, BCH format
  reading, Reed–Solomon correction, and payload extraction in C++. Kotlin
  owns UI, CameraX lifecycle, permissions, and JNI calls.
- CameraX Y/U/V buffers are passed directly to native code. Decoder sessions,
  sampling maps, palette scratch buffers, and Reed–Solomon buffers are reused
  across frames.
- Tracked HCC2D frames now use a cheap local three-finder re-anchor before
  sampling. Global acquisition runs at a Decimen-style cadence: 100 ms while
  acquiring, 250 ms while degraded, and 1.5 s while healthy. A stale
  single-code track first searches a padded camera crop before falling back to
  a full-frame detector pass.
- A BCH-valid HCC2D format field is treated as a healthy geometry lock even
  when a particular coloured payload frame fails Reed–Solomon correction. This
  avoids throwing away a good quad and repeatedly rebuilding its sampling map.

HCC2D is a draft format and remains an experiment. HCC2D8 is particularly
sensitive to physical module size, display blur, camera YUV 4:2:0 chroma
subsampling, autofocus, exposure, and glare. QR is the recommended mode for
reliable transfers.

### Camera and diagnostics

- Both receiver paths request the fastest advertised camera FPS range and
  continuous-picture autofocus when the device supports them. This keeps a
  flat sender screen at its focal plane; no CameraX setting can make every
  physical distance perfectly sharp.
- Receiver screens stay awake while active.
- The animated overlay accepts 1, 2, 3, 4, or 6 code quadrilaterals and is
  updated independently of per-camera-frame rendering.
- The receiver benchmark shows camera FPS, attempted/decoded symbols, decode
  and acquisition time, lock/format/codeword stages, palette mode, detector
  counters, raw throughput, useful transfer throughput, and bytes per symbol.

### Validation

The Android instrumentation suite includes synthetic native HCC2D tests for
HCC2D4/HCC2D8 binary packets through v40, perspective and quarter-turn poses,
diagonal v40 geometry, uneven display lighting, changing stream frames, and a
two-symbol camera scene.

## Project layout

| Path | Purpose |
| --- | --- |
| [`app/`](app/) | Android application, Kotlin UI/transfer code, and JNI bridge. |
| [`app/src/main/cpp/`](app/src/main/cpp/) | Native QR/HCC2D codec bridge and decoder code. |
| [`third_party/`](third_party/) | Vendored dependency inventory and pinned upstream revisions. |
| [`third_party/decimen-codec/`](third_party/decimen-codec/) | AGPL-licensed tracked QR decoder used by the native library. |
| [`third_party/hcc2d-cli-c-encoder/`](third_party/hcc2d-cli-c-encoder/) | Apache-2.0 HCC2D 0.9.0 reference encoder. |

## License and notices

This project is licensed under the GNU Affero General Public License, version
3 or later (AGPL-3.0-or-later). See [LICENSE](LICENSE).

The app statically links the AGPL-licensed `decimen-codec` and includes
Apache-2.0 components. See [NOTICE](NOTICE),
[third_party/README.md](third_party/README.md), and
[LICENSES/Apache-2.0.txt](LICENSES/Apache-2.0.txt) for the required
third-party attributions, source pins, and license text.
