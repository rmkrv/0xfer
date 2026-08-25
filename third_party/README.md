# Vendored dependencies

The source in this directory is intentionally vendored so a fresh clone of
this repository can build the Android native library without Git submodule
initialization. The revisions below identify each dependency's upstream base;
the copies here may contain local Android-integration changes. Do not add
nested `.git` directories here; record any update to a dependency in this file
and update the root `NOTICE` if its licensing or attribution changes.

| Dependency | Upstream base revision | License | Upstream |
| --- | --- | --- | --- |
| decimen-codec | `dc2b8c5a4c1b84c1b31d28566fb11dc84f704e4c` | AGPL-3.0-or-later | https://github.com/bashalarmistalt/decimen-codec |
| zxing-cpp (within decimen-codec) | `3e09874a9ca7c191d67101f302a1f53c71a118cc` | Apache-2.0 | https://github.com/zxing-cpp/zxing-cpp |
| HCC2D CLI C encoder | `1c926c862c0919d1ddb45b713c52382d9f89801d` | Apache-2.0 | https://github.com/marco-querini/hcc2d-cli-c-encoder |

`decimen-codec` is built into the app's native library, which is why the root
project is distributed under AGPL-3.0-or-later. The Apache-2.0 license text
and third-party attributions are provided in `../LICENSES/Apache-2.0.txt` and
`../NOTICE`.
