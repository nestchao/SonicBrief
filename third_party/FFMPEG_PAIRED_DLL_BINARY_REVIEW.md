# FFmpeg root/PyAV DLL binary comparison — source/relinkability evidence

**Engineering audit only: NOT LGPL/compliance clearance or public-release approval.**

## Exact package inspected

The **unpublished** `mcp-v0.1.1` Windows x64 ZIP with SHA-256
`3c19aad7a32ab5c25289cde77057cd014e95ac6cebb5a2515627f25e9487feed`.
Existing [Windows PE linkage audit](WINDOWS_PE_LINKAGE_AUDIT.md) established
that FFmpeg's two CLI tools dynamically import seven plain filenames
while PyAV extensions import seven `av.libs/` renamed, hashed filenames.

We compared each hashed DLL with its version-matched root DLL using
`pefile==2024.8.26`, including hashes of every PE section. Source
`scripts/audit_ffmpeg_dll_pairs.py` reproduces the audit, validates the ZIP
digest when supplied and exports `FFMPEG_PAIRED_DLL_SECTIONS.json` with each
file's original SHA-256, changed sections and section digests. From now on
the Windows CI build also includes the report with its exact ZIP artifact.

## Observed paired-binary evidence

| Property | Result |
|---|---|
| Root shared FFmpeg DLLs | 7 |
| PyAV-hashed shared FFmpeg DLLs | 7 |
| Version-matched pairs | **7 / 7** |
| Identical `.text` (executable-code section) SHA-256 | **7 / 7** |
| Identical complete file SHA-256 | **1 / 7** (`avutil-61.dll`) |
| Identical file sizes | **7 / 7** |
| Other 6 pairs | Differs in `.data`, `.idata`; `avfilter` also differs in `.rdata` |

This **strongly supports** the hypothesis that the private PyAV FFmpeg
files are modified or renamed copies of the same BtbN build, rather than
seven distinct native builds. It does **not conclusively prove** the build
provenance; complete bytes differ in six pairs, and the build scripts,
binary fingerprints, patches and actual dynamic loader behavior must
still be reviewed.

Crucially, PE import-name rewriting is relevant to replaceability:
PyAV `.pyd` extension modules encode the hashed imports rather than the
plain FFmpeg DLL names. Copying an interface-compatible replacement to the
root FFmpeg DLL alone would not necessarily replace the PyAV library.
Separately validate replacement of each hashed DLL and its *dependent*
hashed FFmpeg DLLs, without breaking Windows loading or Python decode.
Do not claim user replacement/relinking has been proven merely from
matching `.text` data.

## What remains under Issue #11

- Complete exact-source/toolchain/object and static linkage trace for
  LGPL-covered FFmpeg and embedded external components including LAME.
- Compatibility, import patching and replacement/relinking process for
  both plain root and PyAV hashed dependencies; confirm against applicable
  LGPL version(s) and licenses with a qualified reviewer.
- Complete third-party notices/source, modifications, relinkability and
  source-offer requirements for **all** included native dependencies.
- Independent clean-Windows first use with the later current source fix.
- Approval of **new** checksum-pinned Draft ZIP/npm package before making
  anything public. Old `v0.1.1` predates cold-start/notice changes.

This comparison is valuable provenance and reproducibility evidence, **not
a legal judgment**. Track the review in
[Issue #11](https://github.com/nestchao/SonicBrief/issues/11).
