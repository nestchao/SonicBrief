# Portable MCP v0.1.0: source provenance and release clearance checklist

**State: NOT APPROVED FOR PUBLIC BINARY DISTRIBUTION.**
This document records exact source identifiers and known follow-up work, not legal approval
or a complete corresponding-source package.

## Verified release identity

- Product: SonicBrief Portable MCP `mcp-v0.1.0`, Windows x64, CPU.
- Tagged project source: `f5b94e7cc40325499cccbe2871e160eb2b647c65`.
- Candidate Windows ZIP SHA-256: `d99d2a37dd0f3411c57d30e9f7406a92dea943f6ee1470d9f06d68bc6bcab7ed`.
- Original build artifact: `sonicbrief-windows-x64-cpu.zip`; draft GitHub Release ID `417072280`.
- A machine-generated file inventory with hashes is provided separately as `PORTABLE_PROVENANCE.json`.
  The tool `python scripts/audit_portable_release.py --help` regenerates it from the matching ZIP and npm tarball.

## Source snapshots retrieved for the independently built FFmpeg executables

1. FFmpeg's own source revision:
   `2a571b606854520cf89804d8030c8b328e621689`
   ([upstream commit](https://github.com/FFmpeg/FFmpeg/commit/2a571b606854520cf89804d8030c8b328e621689)).
   This matches the runtime's `n9.0.2-17-g2a571b6068` short revision.
   - Archived snapshot: `ffmpeg-source-2a571b606854.zip`
   - Snapshot SHA-256: `25c3b714ebbb3d43ffd2a5126d8ce34217dade0d8cd4829c618666e3e19b3858`.
2. BtbN's build recipes at the release-tag commit
   `6c9aec5fc9a72ec3abedd1fa84db141fa18cf52b`
   ([source](https://github.com/BtbN/FFmpeg-Builds/tree/6c9aec5fc9a72ec3abedd1fa84db141fa18cf52b)).
   - Archived snapshot: `btbn-build-scripts-6c9aec5fc9a7.zip`
   - Snapshot SHA-256: `a3c33d42d85429cf42b93b31d4fad9b186ac343ff66cb9452ecfb47b4bca73d8`.
3. Exact upstream LGPL-shared Windows binary artifact:
   `ffmpeg-n9.0.2-17-g2a571b6068-win64-lgpl-shared-9.0.zip`,
   SHA-256 `7157177b8a6cb2174c1650ba8c71b363f2c78cba5330f88c4c02cf5b2b880646`.
   Released under BtbN tag `autobuild-2026-09-30-13-08`.
   This binary reports `--enable-version3 --enable-shared --disable-static`, without
   `--enable-gpl` in its FFmpeg configure flags.
   Its LGPLv3 license text is bundled under `third_party/ffmpeg/LICENSE.txt`.

**Important:** These two snapshots do not include all exact transitive sources/toolchains
used by BtbN, nor prove all LGPLv3 Corresponding Source and build/relinking requirements.
Consult BtbN's `scripts.d` and `makeimage.sh` / `build.sh` to inventory the pinned
dependencies and their corresponding sources.

## Newly identified blocking risk: the separately bundled PyAV wheel

The PyInstaller ZIP **also includes PyAV's own FFmpeg libraries** under `av.libs/`.
This is distinct from the approved BtbN LGPL-shared `ffmpeg.exe` / `ffprobe.exe`.
The shipping candidate contains both:

- `av.libs/libx264-165-f3a909470ddc2d85ed21eda3d0fb7954.dll`
- `av.libs/libx265-efe48a158520a59ef99c0a0b3eb835ae.dll`

x264/x265 are GPL-associated codecs. Their inclusion and the PyAV FFmpeg linkage
**require separate license and source compliance review**. The LGPL status of the
standalone FFmpeg executable does not clear this wheel. In the current
**LGPL-only portable-release policy**, those libraries must not be present.

Before public publication, either rebuild/test the PyAV dependency from properly
licensed non-GPL dependencies (verify all load-time dependencies and a real Whisper
transcription), or obtain competent GPL/compliance review and meet all resulting
obligations for the full binary distribution. Do not merely delete DLLs from the
ZIP without checking whether imported libraries and transcription still work.

## Open release gates

- [ ] Resolve GPL-associated DLLs in PyAV (and inspect **all** native DLL dependencies).
- [ ] Audit all transitive licenses, notices, and copyright attributions (not only
      Python `*.dist-info` license presence).
- [ ] Provide applicable complete corresponding source, exact build instructions,
      and appropriate LGPLv3/GPLv3 text, source links or offers as required.
- [ ] Review relink/replaceability for all LGPL-covered native libraries.
- [ ] Confirm first-run model download's model license/terms.
- [ ] Verify a clean Windows PC/VM where system Python, Docker, and FFmpeg are absent.
- [ ] Authenticate npm account locally (`npm login`); never share npm tokens.
- [ ] Once compliance review passes: publish the GitHub release and verify its
      real download URL, then publish the *CI-generated checksum-pinned* npm `.tgz`,
      and test genuine `npx -y @nestchao/sonicbrief-mcp@0.1.0` on a fresh cache.

The release remains **Draft**. The project MIT license does not relicense bundled
third-party dependencies. This document is an engineering review checklist,
not legal advice.
