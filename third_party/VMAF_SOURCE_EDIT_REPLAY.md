# BtbN VMAF exact source-edit replay — SonicBrief release audit

**Status: SOURCE MODIFICATIONS VERIFIED. Binary/linking/license compliance NOT CLEARED.**

## Inputs and verification

The audit compares the previously pinned assets held under the unpublished
`mcp-v0.1.1` Draft Release:

- Exact VMAF source archive at commit
  `86da14d0306a138fd3f01319860b905169746516`,
  ZIP SHA-256 `db4d65e144fa3d3da6317457dd639ad6a91dccd3d33216d80ae1a78dd90ec847`.
- Exact BtbN FFmpeg-Builds recipe snapshot at commit
  `6c9aec5fc9a72ec3abedd1fa84db141fa18cf52b`.
- VMAF script `scripts.d/45-vmaf.sh`, SHA-256
  `0bd50c0e08e35b9a8326904f51e8f44468abd78e5fc3da6ee4a3e91d788e5e4d`.

Using Git for Windows Bash and GNU sed 4.9, the four **identical upstream recipe
source-edit commands** were executed in an isolated workspace copy of the two
affected source files. The replay also validated both archives and the
complete script command allowlist before executing those known commands.

| Changed source file | Pinned upstream SHA-256 | After BtbN edits SHA-256 |
|---|---|---|
| `libvmaf/src/svm.cpp` | `c66cf547059ece016bfc9b6abb920fdd472b27bc356f9bfb28604d87aca59304` | `769a84ecceef1ce0c7260df34c8c46e99098dade1a96cb42087a6357f03322fd` |
| `libvmaf/tools/meson.build` | `3a9b249c567eaa604db5a0fa4f8fe3753ec30974eb6edb31d83f44abcd098fb7` | `01ba4719c80b6fe911b091a7c05124b64eeece964e09c058ef8f9805daca546b` |

The three `sed -i -E` operations replaced calls to `swap(`, `min(`,
and `max(` with the `libsvm_`-prefixed forms. The recipe's
`echo > libvmaf/tools/meson.build` edit also succeeded. The report is
`VMAF_SOURCE_EDIT_REPLAY_VERIFIED.json` attached to the same **Draft**
release, along with all four archived external component sources.

## Re-run on Windows with Git Bash

```powershell
python scripts/audit_vmaf_source_edits.py `
  --vmaf-source-zip "vmaf-86da14d0306a.zip" `
  --btbn-recipes-zip "btbn-build-scripts-6c9aec5fc9a7.zip" `
  --bash "C:\Program Files\Git\bin\bash.exe" `
  --report "VMAF_SOURCE_EDIT_REPLAY_VERIFIED.json"
```

The tool intentionally extracts *only* the two source files under the current
workspace, verifies pinned source and script hashes, refuses any recipe command
changes, replays the known modifications through GNU Bash, and checks that both
files actually change. Unit tests check the command allowlist and reject
tampered source inputs.

## Remaining blockers

- This **does not** run the complete VMAF Meson/Ninja build, BtbN's
  `libvmaf.pc` linker edit, any static linkage or the FFmpeg build itself.
- It does **not** prove that BtbN's published binary was built from this
  precise modified tree or a given compiler/toolchain version.
- Source availability is necessary but not sufficient for LGPL static-link
  obligations, replacement/relinkability, license notices and patent
  conditions. LAME, AOM, dav1d, VMAF, FFmpeg, PyAV and other native
  dependencies still need full distribution-focused review.
- The **existing** `mcp-v0.1.1` Draft ZIP is obsolete and does not
  incorporate subsequent notice/startup changes. Do not publish it or
  treat this report as release clearance.

Track remaining work in
[Issue #11](https://github.com/nestchao/SonicBrief/issues/11).
