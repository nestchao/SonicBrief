# Third-party notices and release compliance

SonicBrief's original source code uses the [MIT License](LICENSE). Third-party
libraries, executables, models and their licenses remain separate. These
notices are a **release checklist**, not a claim of completed legal review.

## FFmpeg / FFprobe (Windows x64 CPU portable build)

The distributable CI build **no longer uses Gyan's GPL-enabled executable**.
It uses the BtbN **LGPL-shared** Windows x64 build; the dynamic FFmpeg DLLs
are included separately to preserve users' ability to replace those DLLs.

- Upstream: https://ffmpeg.org/
- Binary distributor and reproducible build scripts: https://github.com/BtbN/FFmpeg-Builds
- Source build: `autobuild-2026-09-30-13-08` from
  https://github.com/BtbN/FFmpeg-Builds/releases/tag/autobuild-2026-09-30-13-08
- Binary asset: `ffmpeg-n9.0.2-17-g2a571b6068-win64-lgpl-shared-9.0.zip`
- Pinned SHA-256: `7157177b8a6cb2174c1650ba8c71b363f2c78cba5330f88c4c02cf5b2b880646`
- FFmpeg code revision: `n9.0.2-17-g2a571b6068` (FFmpeg 9.0 development/release branch)
- Runtime `ffmpeg -L` reports **GNU LGPL version 3 or later**.
- The original archive's `LICENSE.txt` is bundled as
  `third_party/ffmpeg/LICENSE.txt` in the generated ZIP.
- The CI asserts SHA-256, absence of `--enable-gpl`, an LGPL `-L`
  output, the presence of DLLs, and bundled license text before packaging.
- FFmpeg project legal information: https://ffmpeg.org/legal.html
- Complete corresponding source and build instructions should be made available
  to recipients of the final binary distribution, using the exact upstream
  revision, BtbN build scripts and all applicable transitive library sources.

### Included pinned notices for statically linked external libraries

The future Windows Portable package now includes version-pinned upstream license
materials for LAME, dav1d, AOM and VMAF under
`third_party/btbn_static_licenses/`. The exact revisions, upstream retrieval
URLs and SHA-256 hashes are recorded in that directory's `MANIFEST.json`.
The package must include both AOM's software `LICENSE` and separate
`PATENTS` patent license, as well as LAME's `LICENSE` and historical
`COPYING` (GNU **Library** General Public License v2) files. CI blocks
missing or modified notice files.

These are **not** complete corresponding-source archives or proof of legal
compliance: LAME's static linking, AOM's transitive VMAF dependency, the
FFmpeg build toolchain, all other enabled libraries and users' ability to
replace LGPL-covered code require further examination. The earlier
`mcp-v0.1.1` Draft ZIP predates these changes and must not be published.

The available FFmpeg license text and the ability to replace FFmpeg shared DLLs
reduce barriers to LGPL compliance, but **source and transitive components
still require a final review before public release**. SonicBrief's MIT License
does not relicense FFmpeg, and this is not legal advice.

## Python / ML dependencies

The portable PyInstaller ZIP also redistributes the Python interpreter and
native dependencies. The installed build contains Python distribution metadata
(`*.dist-info`) for these components; the exact versions may vary between
builds until transitive packages are pinned.

Identified top-level dependencies include FastAPI, Uvicorn, python-multipart,
python-dotenv, faster-whisper, PyAV, yt-dlp, google-genai, MCP SDK, and httpx.
Important native/transitive libraries include CTranslate2, ONNX Runtime,
tokenizers, NumPy and cryptography. Some licenses are MIT/Apache/BSD, but
others have special notices and embedded third-party code.

Where PyInstaller does not preserve a wheel's license file, verify the matching
upstream project license for the **exact** binary version before publishing.
For example:
- CTranslate2 v4.8.2 MIT: https://github.com/OpenNMT/CTranslate2/blob/v4.8.2/LICENSE
- ONNX Runtime v1.31.0 MIT: https://github.com/microsoft/onnxruntime/blob/v1.31.0/LICENSE
- tokenizers v0.23.3 Apache-2.0: https://github.com/huggingface/tokenizers/blob/v0.23.3/LICENSE

PyInstaller packages distribution metadata and many wheel license files in
the runtime ZIP. The build now also creates
`third_party/python/INVENTORY.json`, containing the exact packaged wheel
versions, license metadata and included license-file paths, and fails when a
distribution lacks both a bundled notice and an explicitly reviewed fallback.
Version-pinned upstream license texts are included for CTranslate2 4.8.2,
ONNX Runtime 1.31.0 and tokenizers 0.23.3 under
`third_party/python_licenses/`. The corresponding CPython 3.11.9 license
is included from the official CPython v3.11.9 source tree.

This inventory is **not** a full native binary SBOM. Embedded third-party
dependencies, linking obligations, binary source offers, and package provenance
must still be reviewed before publishing.

### Known GPL-associated native libraries in the release candidate

A separate inspection of the Windows v0.1.0 PyInstaller ZIP found PyAV's
bundled FFmpeg DLLs under `av.libs/`, including `libx264-*.dll` and
`libx265-*.dll`. These are distinct from the independently bundled BtbN
LGPL-shared `ffmpeg.exe`, `ffprobe.exe` and their DLLs. The GPL-associated
libraries and their linkage require a separate redistribution/source review.
**The existing ZIP is not approved for public distribution.**

The LGPL-only release workflow fails on those codec DLLs in both PR and tag
builds. New Windows CPU build candidates now compile **PyAV 18.1.0 from pinned
upstream source commit `7e3d950a8b72062502c1a60d672f8ca565313af5`**
against the separately checksum-verified LGPL-shared FFmpeg 9 SDK, repair the
wheel with those DLLs, then run its audio decode smoke test and the packaged
DLL policy check. This avoids relying on the upstream PyAV binary wheel that
includes GPL-associated codecs. This does **not** constitute final review of
all PyAV, FFmpeg, or transitive native library license/source obligations.
See `third_party/PORTABLE_RELEASE_SOURCE_REVIEW.md` for exact provenance
and remaining gates.

Whisper model weights are downloaded on first use, rather than shipped
inside the portable Windows runtime; model-specific terms still matter.

## Android app (`android/`, GPL-3.0-only)

The standalone Android app is free software under GPL-3.0-only (see
`android/LICENSE`); the rest of this repository stays MIT. The app's media
acquisition runs inside the youtubedl-android runtime, which bundles its own
Python interpreter, yt-dlp executable, QuickJS and FFmpeg binaries. Their
original copyright notices and license terms continue to apply, as do the
licenses of the Android libraries listed here.

- `io.github.junkfood02.youtubedl-android:library:0.18.1` — GNU General Public
  License v3.0 (GPL-3.0). Project: `yausername/youtubedl-android`. Purpose: run
  an updateable yt-dlp executable on Android, provide QuickJS support for
  modern extractor challenges, expose progress/cancellation, allow stable
  yt-dlp runtime updates independently of SonicBrief APK releases, and acquire
  public-media metadata, audio-only streams, and selected manual subtitle
  tracks. The runtime in turn includes or works with additional third-party
  components such as yt-dlp, Python, and QuickJS; section above ("Android
  public-media acquisition" in the V2 notices) describes the same arrangement.
- yt-dlp (executed inside the youtubedl-android runtime on the phone) —
  Unlicense (public domain); see https://github.com/yt-dlp/yt-dlp. The exact
  runtime version is resolved at build time by the youtubedl-android package,
  independently of the APK.
- FFmpeg (executed inside the youtubedl-android runtime on the phone,
  LGPL-licensed builds) — see https://ffmpeg.org/legal.html. The app requests
  existing audio-only streams and remuxes to AAC (falling back to WAV) with the
  device-side FFmpeg; it does not invoke an FFmpeg post-processor for video
  downloads.
- AndroidX libraries (Apache License 2.0): `androidx.compose:compose-bom`
  (BOM 2026.06.00), `activity-compose:1.13.0`, `lifecycle` (runtime-ktx,
  runtime-compose, viewmodel-compose: 2.10.0), `navigation-compose:2.9.8`,
  `datastore-preferences:1.2.1`, `work-runtime-ktx:2.11.2`,
  `room-runtime:2.8.5` + `room-ktx:2.8.5`, `hilt-android:2.60.1` (+
  `hilt-compiler:2.60.1`, `androidx.hilt:hilt-compiler:1.4.0`).
- `com.squareup.okhttp3:okhttp:4.12.0` — Apache License 2.0. Used for direct
  Gemini API calls (resumable Files upload, generateContent, listModels).
- `org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1` — Apache License 2.0.
  Used for Gemini request/response parsing.
- Manrope typeface (bundled under `android/app/src/main/res/font/` as static
  weights 400-800) — SIL Open Font License 1.1, Copyright 2019 The Manrope
  Project Authors. Used for the in-app typography; the full OFL text is
  reproduced in `android/FONTS_OFL.txt`. See
  https://github.com/googlefonts/manrope.

When distributing SonicBrief Android APKs, preserve the notices shipped by
these dependencies and make the corresponding `android/` source available
under GPL-3.0-only.

## Release gates

1. Capture inventory of everything actually bundled (FFmpeg, DLLs, Python
   wheels, native shared libraries), along with version and license notices.
2. Include the applicable **full** license texts and copyright/notice files for
   each redistributed component; validate corresponding source access and
   LGPL obligations, including transitive libraries.
3. Test the proposed npm package and its download SHA-256 with an actual
   GitHub Release, not only via `SONICBRIEF_MCP_BINARY`.
4. Test Windows x64 compatibility beyond the build runner; the current machine
   was also tested with Python removed from the test-process `PATH`.
5. Only then merge, tag, publish the versioned GitHub Release, and publish the
   CI-generated checksum-pinned npm tarball. Never publish the checked-in
   manifest with an empty SHA-256.
