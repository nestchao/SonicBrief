# PyAV FFmpeg DLL replacement smoke review (Windows Portable)

**Engineering finding: two successful runtime-replacement transcription trials.**
This is NOT corresponding-source, LGPL relinking, license or binary publication approval.

## Candidate and isolation

All tests below used a copy of the exact **unpublished** `mcp-v0.1.1`
Windows x64 CPU ZIP, SHA-256
`3c19aad7a32ab5c25289cde77057cd014e95ac6cebb5a2515627f25e9487feed`.
The original extracted runtime and Draft ZIP were not modified.

The PyAV wheel contains seven hash-renamed `av.libs/` FFmpeg DLLs.
The package root contains seven version-matched plain FFmpeg DLLs.
See `FFMPEG_PAIRED_DLL_BINARY_REVIEW.md`: all seven pairs have identical
`.text` hashes, but six full-file hashes differ due to other sections.

### Trial A: only the private avcodec DLL

1. Copy the complete Portable runtime into a separate working folder.
2. Replace only
   `av.libs/avcodec-63-18de2bec466db9ec1afebff91d420ac6.dll`
   with **the bundled root `avcodec-63.dll` bytes**, keeping the hashed filename.
3. Verify the replacement file hash matches the root source and differs
   from the original private file. Execute real MCP and local speech
   transcription on this isolated runtime.

**Result: PASS, exit code 0.** MCP initialize, tools/list,
get_health, submit_local_file, get_job, get_transcript, export_transcript
(SRT) and backend shutdown all passed. Whisper engine:
`local-whisper/cpu/int8`. The approximately 8.28-second sample was
correctly recognized, including the phrase
`the quick brown fox jumps over the lazy dog`. JSON-RPC test returned
`ALL_TESTS_PASSED`.

### Trial B: all seven PyAV FFmpeg DLLs

On the same isolated runtime copy, additionally overwrite the six other
PyAV private DLLs using the corresponding original root FFmpeg DLL bytes,
retaining each of the seven hash-suffixed private filenames. Verify
individual SHA-256 hashes match the original root DLLs. Execute the
same complete MCP transcription and subtitle export workflow, using
a fresh job/data directory and a different local port.

**Result: PASS, exit code 0.** MCP initialize and health, real Whisper
Small CPU int8 transcription, two output segments, SRT export and
backend cleanup all passed again. Test returned `ALL_TESTS_PASSED`.

These tests ran on **one Windows host**, not a pristine Windows VM,
with PATH restricted to Windows System32; system `python.exe`,
`py.exe`, and `docker.exe` were not accessible to the child.
The Whisper Small model was cached locally after an earlier
first-model-download E2E test. No model download occurred this time.

### Repeatable automated CI smoke test

`scripts/smoke_test_ffmpeg_dll_replacement.py` copies the complete
Portable runtime to a temporary directory under the same workspace,
performs seven hash-verified substitutions, runs the **copied**
`SonicBrief-MCP.exe --self-test` with a fresh data directory, and
deletes that temporary copy automatically. It emits a minimal report
without local account paths:

```powershell
python scripts/smoke_test_ffmpeg_dll_replacement.py `
  --runtime build/portable/SonicBrief-MCP `
  --report build/release/FFMPEG_DLL_REPLACEMENT_SMOKE.json
```

CI also runs unit tests of duplicate/missing DLL rejection, hash
integrity and source-file preservation. The JSON is attached to future
private Windows CI build artifacts. The **CI self-test is deliberately
lighter than the one-host real transcription E2E** documented above.

## Remaining release blockers

These substitutions use DLLs **from the same FFmpeg binary build**,
not libraries compiled separately from user-modified source. The test
preserves hash-renamed PyAV filenames and does not prove that any
independently compiled, interface-compatible replacement will work.
It also does not prove that every one of the seven libraries was
actually loaded in the particular speech path.

Before public binary distribution:

1. Demonstrate an independently rebuilt, **modified but
   interface-compatible** LGPL-covered DLL can be installed and used,
   including PyAV's hashed imported names, or provide an appropriate
   compliant relinking mechanism based on the applicable license.
2. Provide complete correct corresponding sources, scripts,
   modifications, notices and any required application object
   materials, with qualified compliance review.
3. Audit static LAME, AOM, dav1d, VMAF and other native dependencies
   for their actual compiled/linker inputs and rights.
4. Rebuild and test a **new** release candidate (the old `mcp-v0.1.1`
   Draft ZIP predates later fixes), verify clean Windows first install
   and obtain release approval.

Source reference: [GNU LGPL v3 section 4](https://www.gnu.org/licenses/lgpl-3.0.html).
Track release blockers in [Issue #11](https://github.com/nestchao/SonicBrief/issues/11).

**Do not publish the old Draft ZIP or npm tarball.**
