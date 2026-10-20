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

The first CI run compiled the modified DLL but the ABI checker rejected
it because seven AMF-related imported functions were missing. Adding
`--enable-amf` and MSYS2 AMF headers resolved that symbol mismatch.

The next CI runs encountered missing MinGW runtime dependencies. By
recording the **actual** compiler location (`D:\\a\\_temp\\msys64\\mingw64\\bin`)
and recursively staging only the test candidate's required toolchain DLLs
in the isolated copy, the modified `libavutil` **loaded successfully**
and its modified `av_version_info()` marker was verified.

However, CI run [#39460619878](https://github.com/nestchao/SonicBrief/actions/runs/39460619878)
then failed on the real Portable backend's `/api/self-test/audio-decode`
HTTP 500 response. This is a real compatibility/runtime failure **after**
the independent DLL was loaded, not an approved relinking result. The
failure also surfaced a Windows temporary-file cleanup lock, which is
secondary to the backend decoder failure. Further diagnostic evidence
is required to determine whether the minimal independent FFmpeg build
is ABI/feature-compatible with the PyAV decoder.

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

The current experiment now demonstrates that one independently compiled,
source-modified `libavutil` DLL can be **loaded by the Windows loader**
with explicit test-only MinGW dependencies and its modified version function
can execute. It **has not passed** the real runtime audio decoder self-test.
This is stronger than renaming same-build DLLs but not evidence of
compatible end-to-end replacement or full LGPL compliance. However, the independently rebuilt variant
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
