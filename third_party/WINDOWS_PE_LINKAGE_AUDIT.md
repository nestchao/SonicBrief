# Windows Portable native PE import graph — exact Draft candidate

**Status: PE linkage evidence only — NOT LICENSE/RELINKING CLEARANCE.**

## Exact inputs and repeatability

This audit parses the Windows PE import and delay-import directories for each
native executable, DLL and Python extension `*.pyd` in the specific
`mcp-v0.1.1` **unpublished Draft** ZIP, SHA-256
`3c19aad7a32ab5c25289cde77057cd014e95ac6cebb5a2515627f25e9487feed`.

It uses Python `pefile==2024.8.26` **only at build/test time** (not bundled
into the MCP executable). Re-run from the project root:

```powershell
python -m pip install pefile==2024.8.26
python scripts/audit_native_pe_imports.py `
  --zip path/to/sonicbrief-windows-x64-cpu.zip `
  --expected-sha256 3c19aad7a32ab5c25289cde77057cd014e95ac6cebb5a2515627f25e9487feed `
  --output NATIVE_PE_IMPORT_GRAPH.json
```

To review another version, replace the expected hash with that candidate's
**independently verified** ZIP digest. The generated JSON contains hashes
of each scanned native file and direct import relationships. CI verifies
the graph in every future Windows Portable ZIP and includes the JSON with
the private CI artifact, so the evidence follows the exact binary candidate.

## Findings from v0.1.1

- 240 Windows PE files scanned.
- **Seven root FFmpeg DLLs** are dynamically imported by
  `ffmpeg.exe` and `ffprobe.exe` (each imports all seven).
- **Seven additional hashed FFmpeg DLLs** appear under `av.libs/`. At
  least some PyAV `av/*.pyd` extensions dynamically import those
  hashed names; 67 direct PyAV-extension → FFmpeg DLL import edges were
  recorded. This is a second, separately named load-time dependency set.
- **111 FFmpeg-family DLL import edges** (including imports between
  FFmpeg DLLs and from other extensions) resolved by exact bundled
  DLL basename; 0 required FFmpeg-family imports lacked a packaged
  basename.
- 382 non-FFmpeg import edges had no matching filename in the package;
  these include Windows OS/API DLLs and are **not** labeled missing,
  malicious, or license violations.
- The analyzer performs exact filename matching (preferring a file in the
  importing module's folder), not a full simulation of the Windows
  loader's search order, side-by-side assemblies or `LoadLibrary` calls.

### Two runtime FFmpeg families

| Runtime consumer | Directly imported shared library filenames | Distribution review |
|---|---|---|
| `ffmpeg.exe`, `ffprobe.exe` | Plain `avcodec-63.dll`, `avutil-61.dll`, `avformat-63.dll` and four other versioned root DLLs | Verify BtbN FFmpeg source/config, all embedded static external libraries, notices and compatibility with replaced DLLs |
| `av/*.pyd` (PyAV) | Suffix-renamed `av.libs/avcodec-63-<hash>.dll`, `avutil-61-<hash>.dll`, etc. | Verify exact rebuilt PyAV + FFmpeg libraries; hash-based DLL naming and import patching may affect how users replace compatible libraries |

The PE import graph **does not** establish whether LAME, AOM, dav1d,
VMAF or other third-party static code is embedded in `avcodec-63.dll`;
no symbol/string test by itself can be treated as proof of static linkage.
Recover the complete object/linker map and reproduction toolchain for that.

## LGPL and release-gate implications

The [FFmpeg legal checklist](https://ffmpeg.org/legal.html) calls for
dynamic linking and corresponding FFmpeg source, compilation instructions
and review of embedded external LGPL libraries such as LAME. The
[FSF FAQ](https://www.gnu.org/licenses/gpl-faq.en.html#LGPLStaticVsDynamic)
explains that static LGPL linking may require releasable application object
files suitable for relinking; dynamic linking also imposes source obligations
when the distributor ships the LGPL libraries.

**Next evidence needed before binary distribution:**

1. Verify both actual FFmpeg DLL families can be rebuilt from exact source,
   applicable external-library revisions, scripts and patches.
2. Confirm real Windows loader behavior and whether a recipient can replace
   each distributed LGPL-covered DLL with an interface-compatible version.
   In particular, review the PyAV `av.libs` naming and module import
   tables. Dynamic imports are **necessary evidence, not proof** of
   replaceability.
3. Obtain BtbN's actual linker inputs/build logs for `libmp3lame`,
   AOM/VMAF and all other external dependencies. Verify what was statically
   incorporated and applicable relinking/source obligations.
4. Review all other bundled native Python wheels, cryptography/runtime
   components, notices and patent/license requirements with a qualified
   reviewer.
5. Rebuild **new Draft** candidate from currently fixed source code
   (the old v0.1.1 ZIP predates later fixes), verify on a clean Windows
   computer, and obtain compliance approval before any public GitHub
   Release or npm publication.

This file is an engineering dependency graph audit, **not legal advice**.
Track remaining release blockers in
[Issue #11](https://github.com/nestchao/SonicBrief/issues/11).
