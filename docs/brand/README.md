# Brand artwork

`sonicbrief-icon-1024.png` is the source artwork for every icon the app uses:
the window, taskbar, tray, Start Menu shortcut, installer, the 512 px logo shown
in the sidebar, and the Chrome extension's icons.

Regenerate everything after changing it:

```powershell
.venv\Scripts\python.exe scripts\make_icons.py --source "docs\brand\sonicbrief-icon-1024.png"
```

That writes:

| Output | Used by |
| --- | --- |
| `desktop-app/src-tauri/icons/icon.png` (1024) | master copy |
| `desktop-app/src-tauri/icons/32x32.png`, `128x128.png`, `128x128@2x.png` | window, taskbar, tray |
| `desktop-app/src-tauri/icons/icon.ico` (16–256, 7 sizes) | exe, Start Menu, installer (`installerIcon`) |
| `public/sonicbrief-logo.png` (512) | sidebar in the app UI |

Without `--source` the script redraws the mark from measured proportions instead,
so the pipeline still works if the artwork is unavailable.

The script trims the transparent border, adds a 4% margin for taskbar legibility
(`MARGIN` in `scripts/make_icons.py`), and downsamples with LANCZOS so small
sizes stay crisp.

After regenerating, rebuild the UI before the server — Vite is what copies
`public/` into the bundle:

```powershell
npm run build:desktop-ui
powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build_desktop_app.ps1
```

The sidebar image URL carries `?v=2`; bump it when the artwork changes so
WebView2 cannot serve a cached copy.
