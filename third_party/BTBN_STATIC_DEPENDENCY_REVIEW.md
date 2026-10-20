# BtbN FFmpeg static-dependency license evidence — Windows MCP

**Engineering source/notice review; NOT legal redistribution approval.**

This review focuses on the exact BtbN recipes commit
`6c9aec5fc9a72ec3abedd1fa84db141fa18cf52b`, observed in the
`mcp-v0.1.1` Windows Draft build. Recipe declarations and license texts
are evidence of possible dependencies, not proof that individual static
objects were included in each binary. Hashes below are in
`third_party/btbn_static_licenses/MANIFEST.json`.

| Component | Recipe source revision | Declared build/link configuration | Exact upstream notice retrieved |
|---|---|---|---|
| LAME / libmp3lame | SVN r6835, `svn.code.sf.net/p/lame/svn/trunk/lame` | `--disable-shared --enable-static` | `lame/LICENSE` and `lame/COPYING` (historical GNU Library GPL v2 text) |
| dav1d | `9711965b60bb692ae24004659acf61f5c7d9ed61` | Meson `--default-library=static` | `dav1d/COPYING`, BSD 2-Clause |
| AOM / libaom | `b4ce2cd097b00921b0327bfb5f07b5f8361ade99` | CMake `BUILD_SHARED_LIBS=OFF`, `CONFIG_TUNE_VMAF=1` | `aom/LICENSE` (BSD 2-Clause) and `aom/PATENTS` (Alliance for Open Media Patent License 1.0) |
| VMAF (AOM build dependency) | `86da14d0306a138fd3f01319860b905169746516` | Meson `--default-library=static`; source modified in build recipe | `vmaf/LICENSE` (BSD-2-Clause-Patent) |

The exact upstream notice files were fetched from immutable commit URLs
and LAME's Subversion revision-specific `!svn/bc/6835` URL. They are
checked into `third_party/btbn_static_licenses/` with a machine-readable
SHA-256 manifest, and bundled in **future** Portable Windows builds.

### Important checks still outstanding

1. **Actual build graph:** the BtbN scripts declare stages and may enable them
   conditionally. Verify the build execution trace and the exact source
   objects statically linked into FFmpeg/PyAV libraries.
2. **Corresponding source:** the six notice files are *not* source code
   archives, applicable patches, linker objects or reproduction instructions.
   Obtain corresponding pinned source trees and applied patches where necessary.
3. **LAME LGPL linking:** the upstream `LICENSE` refers to LGPL and
   `COPYING` contains historical Library GPL version 2 terms. Determine
   the operative license and any obligations/compatibility for the exact
   static linking arrangement, rather than assuming later LGPL versions.
4. **AOM patent terms:** the upstream `PATENTS` contains specific
   redistribution/availability conditions; binary notices must reproduce
   it where required. Full compliance requires more than copying one file.
5. **VMAF dependency:** AOM's build recipe explicitly depends on and uses
   VMAF, and its source is modified by BtbN's build script. Obtain and review
   that exact source and source modification steps.
6. **Other libraries:** dozens of other FFmpeg options and unrelated
   bundled Python-native dependencies still require review.
7. **Existing Draft:** the `mcp-v0.1.1` ZIP predates this notice fix.
   Do not edit an old checksum-pinned release in place or publish it.
   Release only a freshly built, reviewed and approved later version.

This is a technical audit and an inventory of source-license evidence, not a
legal opinion. The public binary release is **still blocked** under
[Issue #11](https://github.com/nestchao/SonicBrief/issues/11).
