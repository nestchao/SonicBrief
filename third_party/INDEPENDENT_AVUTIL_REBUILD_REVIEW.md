# Experimental independent FFmpeg libavutil rebuild and replacement

**Status: EXPERIMENTAL / NOT LEGAL CLEARANCE.** This is a
build-and-load test, not a finished corresponding-source distribution.

## Goal

Test a more meaningful form of LGPL library replacement than the
same-build copy verified in PR #18. Recompile **libavutil**, the smallest
FFmpeg native DLL family used by SonicBrief, from exact FFmpeg source
commit `2a571b606854520cf89804d8030c8b328e621689` in an isolated
Windows CI environment, with an intentional observable source change.
Load its compiled bytes under the PyAV-hashed `avutil-61-<hash>.dll`
name and check whether the actual Portable runtime still initializes.

## Isolation and exact experiment

`.github/workflows/experimental-avutil-rebuild.yml` is a separate
experimental job (not the public release pipeline). It installs MinGW-w64
under a fresh GitHub Actions MSYS2 environment, fetches and verifies the
pinned upstream FFmpeg commit, and changes just one function in
`libavutil/version.c`. The modification adds the literal suffix
`SONICBRIEF_INDEPENDENT_AVUTIL_REBUILD` to the result of
`av_version_info()`, leaving all existing function signatures alone.

The first CI run successfully compiled the modified DLL but the ABI
checker rejected it because seven AMF-related libavutil functions imported
by the existing FFmpeg libraries were absent (`av_amf_*` and
`av_av_to_amf_format`). This is a meaningful compatibility failure,
not a passing runtime replacement. The experiment therefore enables
`--enable-amf` and installs the MSYS2 AMF **headers** for a subsequent
attempt; its actual load and runtime compatibility remain to be tested.

The compilation uses a **minimal LGPLv3 / shared-library configuration**,
with optional codecs, programs and extraneous libraries disabled except
for the necessary AMF hardware API exports. This
is **deliberately not claimed to reproduce** BtbN's full build flags.
The exact applied source patch, configure/build logs, compiled DLL hash
and compatibility-test JSON are retained as a private CI test artifact.

The candidate is compared against the Portable Windows artifact from
the successful pinned baseline [CI #39442074179](https://github.com/nestchao/SonicBrief/actions/runs/39442074179).
The Python test uses a fresh copy of the runtime, checks the symbols
required by modules importing PyAV's private `avutil-61` DLL, replaces
*only the copy's* hashed private DLL, loads `av_version_info()` through
the Windows loader to verify the **modified marker really executed**,
then runs the isolated Portable executable's `--self-test`.

## Interpret the results narrowly

A successful build and test would demonstrate **one** independently
compiled and modified `libavutil` binary is loadable and supports the
tested runtime self-test. This would be stronger than merely renaming
two identical-release DLLs. However, the independently rebuilt variant
might omit optional exports of the original DLL, or require alternate
MinGW runtime DLLs; the test explicitly records such failures rather
than papering over them.

**Even after a passing result, the following remain open:**

- Verify compatibility with all FFmpeg/PyAV APIs and actual Whisper
  speech transcription, not only the executable self-test.
- Establish a reproducible method for end users to rebuild and replace
  all applicable LGPL-covered libraries in both FFmpeg DLL groups.
- Reproduce FFmpeg's complete external static linkage map and sources,
  including LAME/AOM/dav1d/VMAF and other licenses; provide complete
  notices, source and object/relinking materials where applicable.
- Qualified human redistribution compliance review and clean Windows
  first installation for a later fixed-tag Draft candidate.

Any failed experiment is evidence of a compatibility/build gap, not
a reason to publish the obsolete `mcp-v0.1.1` Draft. The main release
remains **UNPUBLISHED**, with Issue #11 open.
