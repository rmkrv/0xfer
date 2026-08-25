# Contributing

Same policy as
[decimen-optical-transfer](https://github.com/bashalarmistalt/decimen-optical-transfer/blob/main/CONTRIBUTING.md):
pull requests are considered — selectively, and with a signed
[CLA](https://github.com/bashalarmistalt/decimen-optical-transfer/blob/main/CLA.md)
(one signature covers both repos; the bot prompts on your first PR).

**Open an issue before writing code.** This is a ~500-line wrapper where
every change to the decode path needs benchmark evidence: run
`node bench/bench.mjs` and include the parity and drift lines in the PR.
A speedup that costs decode parity is a regression here.

**Bug reports** are the most valuable thing you can send — decode failures
or accuracy regressions with the input that triggers them (image or
synthetic capture), expected vs. actual result, and browser/OS if it's
runtime-specific.

The dual-licensing context (AGPL-3.0-or-later publicly, commercial licenses
available) is explained in the CLA and the
[README's License section](README.md#license).
