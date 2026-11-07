# SonicBrief Windows desktop app: handoff for the next agent

Written 2026-11-07. Branch `feat/desktop-tray-and-extension`. **Nothing is committed**; every change below is in the working tree.

**Update 2026-11-08 00:05** — phase 3 is done: the installer builds, installs, and runs. The native window shows the full UI and the tray icon appears (both user-confirmed). The old "never built or run" and "may be blocked like the Python launcher" caveats no longer apply. See sections 3 and 4 (quirks 11-13 are new and important).

## 1. Goal

The user (Windows 11, nestchao) wants SonicBrief to be **convenient to use**: install it and open it like a normal program, and send a video to it from Chrome with one click. After discussion they chose:

- Build from **this local-first repo** (not SonicBrief-V2's Supabase app).
- A **native Windows installer** ("APK" in their words means a Windows `.exe` installer).
- **NVIDIA GPU included**, and **release quality** so others can install it.

The user writes English, sometimes asks for a Chinese explanation. Keep replies short and say plainly what is verified and what is not.

## 2. Two deliverables and their status

### A. Python tray launcher + Chrome extension (works, with limits on this PC)
- `desktop/sonicbrief_desktop.py` starts the API and the Vite dev server hidden, then opens `http://127.0.0.1:3000` in the **default browser**. `Stop SonicBrief.bat` / `--quit` stops it without the tray. Shortcuts "SonicBrief" and "Stop SonicBrief" exist on the user's Desktop and Start Menu (made by `desktop/install_shortcuts.ps1`).
- `extension/` is a Manifest V3 Chrome extension (popup, right-click menu, Alt+Shift+S, notifications). It talks to `127.0.0.1:7860` and launches the app through `sonicbrief-desktop://open`. **The user has not loaded it into Chrome yet.**
- 2026-11-08: the popup **detects the current video** through `extension/detect.js` (10 platforms, path-aware: a channel page or feed is rejected) and shows the page title with one **Transcribe + summarize** button. Model stays visible; transcript language, **summary language** (remembered; Simplified Chinese default) and style live under "Options". The summary language is sent as a **display name** because `pipeline.make_summary` writes it into the prompt.
- Douyin, Instagram and Facebook were added to the extension **and** to `backend/config.py`/`pipeline.py`. The backend half only reaches the installed app after the frozen server is rebuilt and reinstalled (see section 6 item 8).
- Known limits on this PC: the tray icon never appears; Windows notifications from it probably do not show; Edge is elevated, so opening the page shows "Edge isn't responding because an existing instance is running with elevated privileges" (see section 4). First start takes 1-3 minutes (Vite dependency rebuild).

### B. Native Tauri installer app (**built, installed and running**)
Architecture:

```
SonicBrief.exe (Tauri 2, Rust)  -> window loads http://127.0.0.1:7860/  (UI + API, same origin)
   | spawns hidden, kill-on-close Job Object
   +- server\SonicBrief-Server.exe (PyInstaller onedir: FastAPI + faster-whisper + yt-dlp + FFmpeg [+ CUDA DLLs] + built UI)
```

| Phase | What | Status |
| --- | --- | --- |
| 1 | Static UI served by the backend | **Done, verified** |
| 2 | Frozen server (CPU/GPU) | **CPU dev build done, verified. GPU variant never built locally** |
| 3 | Tauri shell + NSIS installer | **Built, installed and running.** Release compile 8m41s; installer 202.8 MiB; window shows the UI; tray icon works (user-confirmed) |
| 4 | GitHub Actions release workflow | Written, valid YAML, **never run on GitHub** |

## 3. Evidence so far

- `npm run build:desktop-ui` builds in about 3.5 s into `build/desktop-ui/`. Backend with `SONICBRIEF_UI_DIR` serves `/`; `/api/*` still return JSON; a headless-Edge screenshot showed the full UI with "Local API connected".
- `scripts/package_windows_desktop.py --variant cpu --dev-ffmpeg` built `build/desktop-app/server/SonicBrief-Server/` (718 MB, 2152 files). Run directly: health OK after 8 s, `GET /` 200, `/api/self-test/audio-decode` true, and a real upload of `D:\Projects\SonicBrief-V2\.test-media\m10-test-speech.wav` completed in 36 s with engine `local-whisper/cuda/float16` (it used the PC's system CUDA; a CPU-only machine would use CPU).
- Hint that a native exe may avoid this PC's limits: the user's other app `D:\AppData\SonicBrief\sonicbrief-windows.exe` (SonicBrief-V2, Tauri 2) registered a tray icon (`HKCU\Control Panel\NotifyIconSettings`). **Now confirmed for our app** (see below).

### Installer build (2026-11-07 22:35)
`scripts/build_desktop_app.ps1 -SkipServer` with `CARGO_BUILD_JOBS=1`:

- Rust release compile finished in **8m41s** with 3-4.7 GB free; no OOM. One warning only: unused `DeepLinkExt` import (it is used only under `#[cfg(debug_assertions)]`; fix by gating the import).
- Bundling then failed with `Access is denied (os error 5)` right after "NSIS.exe is outdated. Recreating it." — see quirk 11.
- Re-running the same command once with full access worked: Tauri downloaded NSIS 3.13 and `nsis_tauri_utils.dll`, verified their hashes, ran `makensis`, and produced
  `desktop-app/src-tauri/target/release/bundle/nsis/SonicBrief_0.1.0_x64-setup.exe`
  (212,661,592 bytes, SHA-256 `51A9400A2A5B4E877E273F81E9FCFC9219417FCBC8606853B0722F3873C5BDD5`).
- The pinned artefacts, if the download ever has to be replayed offline: `https://github.com/tauri-apps/binary-releases/releases/download/nsis-3.13/nsis-3.13.zip` (SHA-1 `DB14F8AF2EA346B786C6AE19343F61AAF5E09B8E`) and `https://github.com/tauri-apps/nsis-tauri-utils/releases/download/nsis_tauri_utils-v0.5.3/nsis_tauri_utils.dll` (SHA-1 `75197FEE3C6A814FE035788D1C34EAD39349B860`). Verified copies sit in `data/desktop/tools/`.

### Installer layout (verified from the generated `target/release/nsis/x64/installer.nsi`)
- Installs **per user**, `RequestExecutionLevel user` (no UAC), solid LZMA, into `%LOCALAPPDATA%\SonicBrief`.
- Every server file lands as `$INSTDIR\server\...`, including `server\SonicBrief-Server.exe` flat — exactly what `find_server_exe` looks for. Installed: 2152 files / 718 MB.
- Creates Start Menu shortcut, registers `HKCU\Software\Classes\sonicbrief-desktop` (`"<install>\sonicbrief-desktop.exe" "%1"`), the `Uninstall\SonicBrief` entry, and `uninstall.exe`.
- **The Desktop shortcut is a checkbox on the Finish page** (`MUI_FINISHPAGE_SHOWREADME_FUNCTION`), unticked by default. Silent installs create it. The pre-existing Desktop `SonicBrief.lnk` still points at the **Python launcher** (`pythonw.exe`), so it does not start the new app — use the Start Menu entry.
- Uninstall deletes files individually and only `RMDir "$INSTDIR"` non-recursively, so `data/`, `models/` and `logs/` survive. Its two `RmDir /r` lines target `$APPDATA\dev.sonicbrief.local` (WebView2 profile), not user data.

### Runtime (2026-11-07 23:47-23:52, user-confirmed)
- Log at `%LOCALAPPDATA%\SonicBrief\logs\server.log`: one `===== starting server =====`, uvicorn `Application startup complete`, then `GET /` plus `assets/index-*.js`, `.css`, logo and favicon all 200, then `GET /api/settings/gemini` — i.e. **the WebView2 window rendered the real UI and called the local API**. Zero errors, zero restarts.
- `/api/health` while the app ran: `ok=true`, `device=NVIDIA GPU`, `cuda_available=true`, `data_dir=%LOCALAPPDATA%\SonicBrief\data`, `backend_pid=20820`.
- **User confirmed: a native window with the full UI, and a working tray icon** (several Python attempts never produced one). The WebView2 path is not affected by the elevated-Edge problem.
- **Quit verified 2026-11-08:** tray → Quit stops the shell and the server (no listener left, only `TIME_WAIT` sockets), and the log's last request is `GET /api/jobs?limit=50` — `active_job_count()`, the "are jobs running?" confirmation. Closing the window with X only hides to the tray; the installer then cannot replace locked files, so tell the user to quit from the tray before upgrading.
- **Reinstalled build (2026-11-08 01:31)** carries the new backend (`%LOCALAPPDATA%\SonicBrief\server\backend\config.py` has the Douyin/Instagram/Facebook/FB.watch entries, plus the cookie hook), 2152 server files, and left `data/` untouched.
- **End-to-end summary proof (2026-11-08 01:33, installed app, pid 4460):** a 19 s YouTube video with the *small* model produced 4 segments on `local-whisper/cuda/float16` and a 167-character Gemini summary in Simplified Chinese with timestamps, in ~70 s and **zero warnings**. This exercised the extension's `summary_language: "Simplified Chinese"` display-name path against `make_summary`.
- **Extension works from Chrome (2026-11-08 01:50):** the user transcribed a Bilibili video (job `e35ca6a0…`, completed) by clicking Transcribe in the extension, so popup → queue → app → transcript is proven in the real browser, not just by tests.
- **Auto-refresh fix (2026-11-08 02:20):** a job started from Chrome did **not** appear in an already-open window, because `app/sonicbrief.tsx` only polled `/api/jobs` while the list already contained a pending job (`if (!hasPendingJobs) return`) — so a window opened onto an empty list never updated. It now polls every 5 s (2 s while pending, skipped when `document.visibilityState !== "visible"`). User-confirmed: a Chrome-initiated job appears in the open window on its own.

### Real-URL test on the frozen server (2026-11-07 22:42, port 7873, workspace data dir)
Two public YouTube links downloaded and transcribed with `local-whisper/cuda/float16`, **no JS-runtime warnings**:
`youtube.com/watch?v=jNQXAC9IVRw` ("Me at the zoo", 4 segments) and `...?v=kJQP7kiw5Fk` (Despacito, 7 segments).
The bundle ships `yt_dlp_ejs`, which appears to cover the nsig problem section 6 used to worry about.
Same run: `GET /` served the bundled UI (200, `id="root"`), and `/api/jobs/<id>/export/txt` returned 200 with `Content-Disposition: attachment` and a UTF-8 filename.

## 4. Environment quirks (read before running anything)
1. **Memory.** A release build needs headroom. `CARGO_BUILD_JOBS=1` compiled this app in 8m41s with 3-4.7 GB free and no OOM kill; the earlier default-jobs attempt died. Check free RAM first, ask before starting heavy jobs.
2. **Global `CARGO_TARGET_DIR`.** The user's Windows user environment sets `CARGO_TARGET_DIR=D:\Projects\SonicBrief-V2\target`, so cargo writes into that other project. Our `scripts/build_desktop_app.ps1` overrides it to `desktop-app/src-tauri/target` (gitignored). Do not change the user's variable.
3. **pip hangs** (broken pip cache). Always set `PIP_NO_CACHE_DIR=1` and `PIP_DISABLE_PIP_VERSION_CHECK=1`.
4. **Restricted sandbox — and do not trust process/port probes.**
   - `Get-CimInstance` (any class), `Get-MpThreatDetection`, `Get-MpThreat` and `tasklist` are denied.
   - `Get-NetTCPConnection` is WMI-based and silently returns nothing, so "port free" from it is **meaningless**. Use `netstat -ano` instead (it works and shows the owning pid).
   - `Get-Process` sees only a small subset (19 during testing) and **cannot see processes the agent did not start** — `GetProcessById` said "not running" for a live pid. Identify the server through `/api/health` → `backend_pid` plus `netstat`.
   - `%LOCALAPPDATA%` writes are denied. Do **not** launch installers or open Explorer from an agent shell: a spawned `explorer.exe` hands the installer a restricted token and a session `TEMP`, and NSIS then fails with *"Error writing temporary file. Make sure your temp folder is valid."* — which looks exactly like a broken installer and is not. This wasted an hour; the fix was the user double-clicking it themselves.
   - Write tests: use `[IO.File]::WriteAllText` to the real Temp to check, and project-local scratch (`data\desktop\tmp`) for tooling.
5. **Python-launched GUI is blocked on this PC:** pywebview's .NET (`Python.Runtime.dll`) refused to load; an Edge `--app` window launched by the app rendered blank; the tray icon from `.venv` python never appeared. Do not retry these approaches — the Tauri app is the supported path now.
6. **Edge is elevated** (started as administrator), so a normal-rights program cannot hand it a URL. Irrelevant to the Tauri app (WebView2 runs in-process).
7. **Protocol clash:** `sonicbrief://` belongs to the user's other app (`D:\AppData\SonicBrief\sonicbrief-windows.exe`). This project uses **`sonicbrief-desktop://`**. Never touch the other app's registry keys.
8. **Shell quoting:** Git Bash heredocs and `sed` mangled backslashes and Windows paths; write files with the Write/Edit tools. PowerShell 5.1 `Set-Content` can add a BOM and corrupt UTF-8 — use `[IO.File]::WriteAllText` with `UTF8Encoding($false)` (a BOM made `makensis` fail on a patched `.nsi`).
9. **Ports:** API 7860, Vite dev 3000, Python launcher instance lock 7861, tests used 7870-7873.
10. `vite.config.ts` has `server.watch.ignored: ["**/data/**"]` (Vite crashed with EBUSY on locked files under `data/`).
11. **Tauri's NSIS toolchain needs a write outside the workspace.** `%LOCALAPPDATA%\tauri` is denied to agent processes, so `npx tauri build` fails while bundling (`Access is denied (os error 5)` after "NSIS.exe is outdated. Recreating it."). There is **no env var to relocate that folder** (only `TAURI_BUNDLER_TOOLS_GITHUB_MIRROR`). Run the bundling command once with full access, or let the user run it. The cargo compile stays inside `desktop-app\src-tauri\target`.
12. **Defender noise.** A `dsh-purge-master.zip` in `%USERPROFILE%\Downloads` was quarantined as `Backdoor:PHP/Chopper.E!dha` at 22:35 and is unrelated to this project; do not confuse its 1116/1117 events with installer problems. The installer itself is unsigned: SmartScreen needs "More info → Run anyway".
13. **Node HTTPS works, PowerShell's does not.** `Invoke-WebRequest` fails with "underlying connection was closed" while `node -e "fetch(...)"` succeeds. Use Node (or the web tools) to download things.
14. **`node --test file.mjs` dies with `spawn EPERM`** here: the sandbox blocks piped child stdio, and the test runner spawns one child per file. Run the file directly instead (`node tests/extension-detect.test.mjs`) — `node:test` works in-process. The `npm test` script is still correct for CI and normal machines. **The same limit breaks `vite build`** (`execFile` inside Vite's `optimizeSafeRealPathSync`), so `npm run build:desktop-ui` — and therefore `build_desktop_app.ps1` without `-SkipServer` — aborts with "UI build failed (exit 1)". Vite build *does* work under a one-shot full-access command (`node node_modules/vite/bin/vite.js build --config vite.desktop.config.ts`, ~2 s). Work around the rest by reusing an existing `build/desktop-ui/` and running the packager directly: `.venv\Scripts\python.exe scripts\package_windows_desktop.py --variant cpu --dev-ffmpeg`, then `build_desktop_app.ps1 -SkipServer` for the installer.
15. **The NSIS installer can fail with "Error writing temporary file"** even when the *user* launches it (seen 2026-11-08 on the second build, via Win+R; the first build installed fine and the file was byte-identical to the build output). Nothing appears in `%TEMP%`, so the stub dies before staging. Root cause unconfirmed — suspect the launching shell's `TEMP`; `echo %TEMP%` in a fresh `cmd` is the first thing to check. **Working workaround:** quit the app, then copy `desktop-app\src-tauri\target\release\sonicbrief-desktop.exe` and the contents of `...\target\release\server\` over `%LOCALAPPDATA%\SonicBrief\` (same layout the installer writes; needs full access). Copy only — never delete `data/`, `models/`, `logs/` or `settings.env`; they live in the same folder and must survive.

## 5. Files changed or added (all uncommitted)
**Backend / UI (Phase 1-2)**
- `backend/config.py`: `ENV_FILE` (`SONICBRIEF_ENV_FILE`, default `backend/.env`) and `find_ui_dir()` (`SONICBRIEF_UI_DIR` or bundled `ui/`).
- `backend/gemini_settings.py`: reads and writes `config.ENV_FILE`.
- `backend/app.py`: mounts `StaticFiles(html=True)` at `/` **after** all routes.
- `desktop-ui/{index.html,main.tsx}`, `vite.desktop.config.ts`, `package.json` script `build:desktop-ui` (output `build/desktop-ui/`; `NEXT_PUBLIC_API_BASE` defined as `""` for same-origin).
- `sonicbrief_server.py`: frozen entry (PATH gets bundled FFmpeg/CUDA; defaults to `%LOCALAPPDATA%\SonicBrief\{data,settings.env,models,cache}`, shared with the portable MCP).
- `scripts/package_windows_desktop.py` (`--variant cpu|gpu`, `--dev-ffmpeg` for local tests, writes `NOT-FOR-REDISTRIBUTION.txt`), `scripts/collect_portable_licenses.py` (new `SONICBRIEF_PORTABLE_DIR` override).

**Tauri shell (Phase 3)**: `desktop-app/` - `package.json` (`@tauri-apps/cli`), `splash/index.html`, `src-tauri/{Cargo.toml,tauri.conf.json,build.rs,capabilities/default.json,icons/*,src/{main.rs,lib.rs}}`. `lib.rs` does: choose data home with fallback, find server exe (`SONICBRIEF_SERVER_EXE` or `resource_dir/server/SonicBrief-Server.exe`), spawn hidden under a kill-on-close Job Object with logs in `<home>/logs/server.log`, reuse a running server if `/api/health` and `/` both answer (else a clear "port in use" error), navigate the window when healthy, supervise and restart (max 3 per 10 min), poll `/api/jobs` for completion notifications, tray (Open / data folder / logs folder / Start with Windows / Quit with confirm if jobs run), hide-to-tray on close, single instance, `--background` or `sonicbrief-desktop:` args start hidden, deep-link scheme `sonicbrief-desktop`.

**CI / build**: `.github/workflows/desktop-app.yml` (matrix cpu/gpu; copies the audited pinned-LGPL-FFmpeg + source-built LGPL PyAV steps verbatim from `portable-mcp.yml`; server self-test; Tauri build; 2 GiB size guard; draft release on `desktop-v*` tags), `scripts/build_desktop_app.ps1` (local build), `.gitignore` additions (includes `/.portable-test/`, a 6.5 GB scratch folder that is not part of the project).

**Python launcher + extension (deliverable A)**: `desktop/*`, `SonicBrief.vbs`, `Stop SonicBrief.bat`, `extension/*` (new `extension/detect.js`), `setup.ps1` edits, README sections ("Desktop app", "Windows installer app (native)", "Chrome extension"), and two new test files: `tests/extension-detect.test.mjs` (62 cases) and `tests/extension-wiring.test.mjs` (popup/manifest wiring).

## 6. Remaining work, in order
**Done:** steps 1-3 (build, layout, install + run) and the real-URL test of step 6.
Steps 3 and 6 still have gaps noted below. Item 4 is moot: the native app is not blocked.

1. ~~Build the installer.~~ Done — see section 3.
2. ~~Check the resource layout.~~ Done — `$INSTDIR\server\SonicBrief-Server.exe`, flat.
3. **Finish the install checks:** notifications (a job finishing should raise one), Quit from the tray (does `SonicBrief-Server` disappear — the Job Object should kill it), and uninstall (does it remove the program but keep `data/`). Logs: `%LOCALAPPDATA%\SonicBrief\logs\server.log`.
4. ~~Fall back to the browser tab if the native app is blocked.~~ Not needed.
5. **Chrome extension end to end.** The installer registers `sonicbrief-desktop://` (done). The popup detects the page's video and the two earlier bugs are **fixed**: `openUi()` probes `/` and opens `7860` when the native app serves the UI (falling back to `3000`), and starting the app uses a throwaway background tab instead of navigating the user's tab. The user has loaded the extension and a **Bilibili job was transcribed from Chrome** (2026-11-08 01:50), so popup → job → app is proven. Still untested: the "app closed → link queued → app launched by protocol → job flushed" path, the completion notification, and the popup on Douyin / Instagram / Facebook (Instagram and Facebook may need the cookie env var).
6. **Real URL test.** Done for two YouTube links on the frozen server (section 3). Still worth trying a long video and a Bilibili link, and checking the installed app's data dir.
7. ~~WebView2 behaviours~~ **Verified 2026-11-08 in the installed app.** The TXT export (a `window.open` of `/api/jobs/<id>/export/txt`) downloads `Me at the zoo.txt` (253 bytes, timestamped transcript) into `%USERPROFILE%\Downloads`; the upload drop zone opens the native file picker and accepts a media file. The UI contains **no external links** (`target=_blank`), so **no `tauri-plugin-opener` is needed**. Caveat: the UI only exposes TXT — `/export/srt` and `/export/json` exist in the API but have no button, so adding them would be a UI change (and note that rebuilding the UI is blocked in the sandbox, quirk 14).
8. **GPU variant and CI.** Build `--variant gpu` (needs `pip install "nvidia-cublas-cu12>=12.4,<13" "nvidia-cudnn-cu12>=9,<10"`; neither is installed in `.venv`, so this is a large download), check `cuda_available` on a machine without system CUDA, and check the installer is under 2 GiB. Then, **with the user's approval**, push and run `desktop-app.yml` via `workflow_dispatch`; expect to debug (it has never run). The audited steps reference `scripts/check_btbn_static_licenses.py --directory`, `check_portable_gpl_codecs.py <dir>` and `third_party/ffmpeg/LICENSE.txt`; note the dev build has no `third_party/ffmpeg/LICENSE.txt` or `third_party/python/INVENTORY.json` because `--dev-ffmpeg` skips them, but release builds write both (`collect_portable_licenses.py` writes `INVENTORY.json` into `third_party/python/`), so the CI gates should pass.
9. **Git.** The user has **not** approved a commit. When they do: commit on this branch (or create `feat/windows-desktop-app` from it), following the attribution instructions in your own session. Do not push or open a PR without being asked.
10. ~~Decide the Python launcher's future~~ **Decided 2026-11-08: keep it as the development path; the native app is the supported one.** The Desktop `SonicBrief.lnk` was repointed to `%LOCALAPPDATA%\SonicBrief\sonicbrief-desktop.exe` (working dir + icon from the install), so double-clicking the desktop icon now opens the native app. `Stop SonicBrief.lnk` keeps pointing at `Stop SonicBrief.bat` but its description now says it stops the **Python dev launcher** (the native app quits from its tray menu). `desktop/`, `SonicBrief.vbs` and the dev shortcuts stay in the repo; the README says so explicitly. If the native app is ever uninstalled, that Desktop shortcut must be restored or removed by hand, because the installer only creates a desktop shortcut when its Finish-page checkbox is ticked.

## 7. Safety rules for this project
- Do not close the user's browser or kill processes you did not start. Verify what a process is before stopping it.
- Do not edit the user's environment variables or registry keys that belong to other apps.
- Do not distribute dev builds: `--dev-ffmpeg` output skips the licence checks (marked `NOT-FOR-REDISTRIBUTION.txt`). The currently installed build **is** a dev build.
- Do not start long or memory-heavy jobs without checking with the user first.
- Treat `AGENTS.md` as authoritative for the audio-collection workflow (unrelated to this task).
