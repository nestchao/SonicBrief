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

Whisper model weights are downloaded on first use, rather than shipped
inside the portable Windows runtime; model-specific terms still matter.

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
