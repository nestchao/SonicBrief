# FFmpeg build provenance audit — Portable MCP v0.1.1

**Engineering evidence only. NOT approved for public binary redistribution.**

## Exact reviewed inputs

- Frozen runtime: `mcp-v0.1.1` Draft Release (GitHub release ID `418253682`), Windows x64 CPU.
- Original ZIP SHA-256: `3c19aad7a32ab5c25289cde77057cd014e95ac6cebb5a2515627f25e9487feed`.
- FFmpeg executable was extracted from that ZIP; the audit tool separately records its own file SHA-256.
- BtbN build-script archive: pinned commit `6c9aec5fc9a72ec3abedd1fa84db141fa18cf52b`, originally archived as `btbn-build-scripts-6c9aec5fc9a7.zip`.
- FFmpeg source revision reported by executable: `n9.0.2-17-g2a571b6068`, full source commit `2a571b606854520cf89804d8030c8b328e621689`.
- Tool: `scripts/audit_ffmpeg_build_recipe.py`. It outputs a per-option JSON report and a Markdown table, and is covered by `scripts/test_audit_ffmpeg_build_recipe.py`.

## Concrete findings

The packaged `ffmpeg.exe -hide_banner -buildconf` reports **62 enabled flags**.

- **60** have a *candidate* matching `ffbuild_configure()` in the pinned BtbN `scripts.d/` recipes, with the recipe file and its declared upstream repository/revision where present.
- **2** (`--enable-version3` and `--enable-shared`) are directly declared in the pinned LGPL variant files.
- **0** have neither a candidate configure-hook recipe nor a matching selected-variant declaration.
- The `win64-lgpl-shared` variant declares `COPYING.LGPLv3` and FFmpeg shared libraries; the `50-x264.sh` / `50-x265.sh` recipes explicitly disable themselves for `lgpl*` variants.
- At least one external library recipe builds a **static** library: `scripts.d/50-libmp3lame.sh` requests `--disable-shared --enable-static`, and `scripts.d/50-dav1d.sh` requests `--default-library=static`. `scripts.d/50-aom.sh` uses `BUILD_SHARED_LIBS=OFF`. Static linking needs its own corresponding-source and license review, even though FFmpeg DLLs themselves are shared.
- The matching `--enable-schannel` script does not declare an external source commit, consistent with Windows system integration; this is a review observation, not missing package-source proof.
- The runtime also contains PyAV's separately packaged FFmpeg DLLs in `av.libs/`; this audit only maps the independently bundled `ffmpeg.exe` build options.

## Why the release is still blocked

**Recipe matching does not establish that a recipe actually ran in this exact build.** The source archive contains conditional stages; the audit does not execute Bash or replay the upstream release build. An external recipe's version string is a *declared source hint*, not a verified source archive or exact compiled dependency.

Before public release:

1. Recover the upstream build trace or reproducibly rebuild from its exact dependencies, including toolchain, flags, patches and any static external-library sources.
2. Confirm license compatibility, complete notices, corresponding sources, and any relinking / reverse-engineering rights for both FFmpeg DLL sets (top-level FFmpeg and PyAV).
3. Independently audit all other native packages (`ctranslate2`, `onnxruntime`, `numpy`, `tokenizers`, `cryptography`, etc.).
4. Make exact applicable source archives and notices distributable on the same release host as the corresponding binaries when required.
5. Obtain qualified human compliance review before treating the release as redistributable.

Source policy reference: <https://ffmpeg.org/legal.html>. See also [Issue #11](https://github.com/nestchao/SonicBrief/issues/11).

**No legal clearance has been granted by this script or this review.**
