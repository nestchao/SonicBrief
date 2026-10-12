# Third-party components and release compliance

SonicBrief's project-owned source code is MIT-licensed (see `LICENSE`). That
license **does not replace the licenses** of bundled executables and Python
libraries. These notices are informational and do not by themselves establish
that a distributable build satisfies every license obligation.

## FFmpeg / FFprobe (Windows portable CPU build)

- Project: FFmpeg — https://ffmpeg.org/
- Windows binary distributor currently selected in our CI: Gyan Doshi
  (Gyan.dev *release essentials*, installed by Chocolatey `ffmpeg`).
- Build information: https://www.gyan.dev/ffmpeg/builds/
- License of that distributor's static Windows builds: **GPL version 3**.
- FFmpeg licensing information: https://ffmpeg.org/legal.html
- GNU GPLv3 license text: https://www.gnu.org/licenses/gpl-3.0.html
- Corresponding FFmpeg source: identify the **exact bundled build** using
  `ffmpeg.exe -version` and obtain corresponding source from the distributor
  and FFmpeg upstream. The source for a particular build must match that binary
  and include any required patches and build instructions.

Our packaged runtime invokes FFmpeg/FFprobe as standalone executables for
transcoding and media probing. Whether this distribution meets GPL requirements
(including delivery of license notices and complete corresponding source or a
valid offer where applicable) must be verified **before public publication**.
Publishing an MIT notice for SonicBrief does not make these binaries MIT.

## Python/ML runtime

The frozen Python runtime also redistributes third-party Python wheels and
native libraries (including faster-whisper, CTranslate2, PyAV, ONNX Runtime,
tokenizers, Hugging Face tooling, and their transitive dependencies). Individual
licenses and accompanying notices must be reviewed and included as applicable.

Optional Whisper model weights are fetched on first use rather than shipped in
the Windows runtime. Review the model's license separately if future releases
begin bundling model weights.

## Release checklist

1. Record exact FFmpeg version/configuration, package provenance, licenses and
   a way to fulfill corresponding source obligations.
2. Audit the Python dependency list and native wheel licenses; retain required
   notices and source/redistribution obligations.
3. Ensure these notices, required full third-party license texts and source
   information are available with the final ZIP; don't treat this placeholder
   notice as a completed audit.
4. Install the packaged zip on a clean Windows x64 machine without Python or
   Docker, connect an MCP client, and run a real Whisper transcription.
5. Only after these checks, publish the matching release ZIP and the npm
   tarball containing its recorded SHA-256. Never publish a package with an
   empty runtime manifest SHA-256.
