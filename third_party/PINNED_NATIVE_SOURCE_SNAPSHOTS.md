# Pinned native dependency source snapshots — release evidence

**Status: source traceability improved; public binary distribution NOT CLEARED.**

This record concerns the Windows Portable SonicBrief MCP native dependencies
reported by the `mcp-v0.1.1` Draft build. It is not a complete Corresponding
Source distribution, rebuild recipe, verified license compatibility opinion
or public release approval. GitHub Draft Release ID `418253682` remains
unpublished; its Windows ZIP predates newer source/notice changes.

## Four verified upstream snapshots

| Dependency | Exact source version | Private Draft asset | SHA-256 |
| --- | --- | --- | --- |
| LAME | SourceForge SVN trunk/lame r6835, repository UUID `981c7233-bbb0-e711-95a9-001517a2e1a4` | `lame-svn-r6835-source.zip` | `7481e6dbe77de0d030a81cb7fbaa5cf9b04c0dd547a42e68d95a0c9d1a902ab2` |
| dav1d | `9711965b60bb692ae24004659acf61f5c7d9ed61` | `dav1d-9711965b60bb.zip` | `095b17b7029947fcd0a1ad5a0dee427565088990f8f5843bef630638591c3541` |
| AOM / libaom | `b4ce2cd097b00921b0327bfb5f07b5f8361ade99` | `aom-b4ce2cd097b0.tar.gz` | `e178b52d9b4ac8fa38cbfcb3c9e055389b0e9ab542374168efed7480f65b4a8a` |
| VMAF | `86da14d0306a138fd3f01319860b905169746516` | `vmaf-86da14d0306a.zip` | `db4d65e144fa3d3da6317457dd639ad6a91dccd3d33216d80ae1a78dd90ec847` |

The Draft Release also contains `STATIC_SOURCE_SHA256SUMS.txt`,
`STATIC_SOURCE_SNAPSHOTS.json`, and the 435-file manifest
`LAME_R6835_SOURCE_MANIFEST.json`. These were verified against the
local source bytes before attachment.

### Reproducing the exact LAME snapshot

Using an Apache Subversion CLI and an empty directory:

```powershell
svn info --revision 6835 https://svn.code.sf.net/p/lame/svn/trunk/lame
svn export --ignore-externals --revision 6835 https://svn.code.sf.net/p/lame/svn/trunk/lame lame-r6835-source
```

At review time this returned SVN repository UUID
`981c7233-bbb0-e711-95a9-001517a2e1a4`, revision `6835`, and
435 exported files totaling 8,175,917 bytes. The historical r6835
`LICENSE` (503 bytes, SHA-256
`5c1f8d44f1eacbea3e1c31ff6ee11a4e1690ccbed4ee4af16de3b6eb61dc69a8`)
and `COPYING` (25,281 bytes, SHA-256
`e64f9c5a18f56828c10a575df13ade641aa3af4512a7afe6c411256943b57aaf`)
**exactly match** the pinned license texts already bundled by the newer
Portable build configuration.

The export was compressed into a deterministic ZIP with normalized entry
timestamps and per-file SHA-256s. It was **not** built into any Windows
binary or used to establish the exact original toolchain.

### AOM patch and VMAF source modifications

BtbN's pinned recipe archive (commit
`6c9aec5fc9a72ec3abedd1fa84db141fa18cf52b`) has the AOM patch
`patches/aom/0001-Fall-back-to-built-in-vmaf-model-on-load-failure.patch`
(SHA-256 `c3b757903be9b98b52b23ac40f7c49dd562cd999b5151890d19dfaf82f40ddb0`,
714 bytes). It was tested with `git apply --check` and applied in a
**separate isolated git-initialized working directory** containing the
exact pinned AOM source. The affected `aom_dsp/vmaf.c` hash changed
from `10aa4bbe40910c06852515044dc6e32125cf1a56e472daa8b29c0a11da3ac96b`
to `74558cb8637e4ed784647005503b889a34d3924f107335bd31c3c81f8133beba`.

The patch is stored unchanged as `aom-vmaf-btbn.patch` with the
Draft source assets. A successful local patch application **does not
prove** that the released BtbN build used the same patched binary.

BtbN's VMAF recipe also edits
`libvmaf/tools/meson.build` and makes `sed` substitutions in
`libvmaf/src/svm.cpp` before compilation. Those modifications are
recorded in the pinned BtbN recipe archive, but a complete patched
source tree and matching build trace have not yet been independently
verified.

## Known compliance questions remaining

1. LAME's historical `COPYING` and README identify the **GNU Library GPL
   version 2**. BtbN requests a **static** LAME build and disables
   frontend/decoder components. Its true object-level link graph,
   LGPL obligations and user's library-replacement/relink options
   still require review. Bundled source alone is not sufficient.
2. LAME source contains MPGLIB components with additional copyright
   notices. Verify that disabled decoder components are not present in
   shipped binaries, rather than assuming the build configuration is
   dispositive.
3. Check all native external FFmpeg-linked libraries, full corresponding
   source, third-party notice and patent conditions, exact patches and
   actual toolchain against the distributed executables/DLLs.
4. Verify the additional PyAV-linked FFmpeg library set and Python-native
   wheels independently.
5. After qualified review and clean Windows verification, build a **new
   exact-version ZIP and npm package**. The existing `mcp-v0.1.1` Draft
   ZIP does not include the latest fixes, and must not be published.

See [Issue #11](https://github.com/nestchao/SonicBrief/issues/11).
