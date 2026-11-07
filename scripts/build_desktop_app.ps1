<#
.SYNOPSIS
  Local build of the SonicBrief Windows desktop installer (for testing on this PC).

.DESCRIPTION
  1. Builds the static web UI           (npm run build:desktop-ui)
  2. Builds the PyInstaller server      (scripts/package_windows_desktop.py)
  3. Builds the Tauri NSIS installer    (desktop-app, npx tauri build)

  By default the server uses the FFmpeg on PATH and skips the licence checks
  (-DevFfmpeg), so the result is NOT for redistribution. Release installers come
  from the "Windows Desktop App" GitHub workflow, which uses the audited LGPL FFmpeg.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build_desktop_app.ps1
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build_desktop_app.ps1 -SkipServer
#>
param(
    [ValidateSet("cpu", "gpu")][string]$Variant = "cpu",
    [bool]$DevFfmpeg = $true,
    [switch]$SkipServer
)
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
Set-Location $root

# This PC's pip cache can hang forever; a private temp folder avoids EPERM in TEMP.
$env:PIP_NO_CACHE_DIR = "1"
$env:PIP_DISABLE_PIP_VERSION_CHECK = "1"
$env:PYTHONUTF8 = "1"
$tmp = Join-Path $root "data\desktop\tmp"
New-Item -ItemType Directory -Force $tmp | Out-Null
$env:TEMP = $tmp
$env:TMP = $tmp

# Build Rust output inside this project, even when a global CARGO_TARGET_DIR points
# at another project's folder, and limit parallel rustc jobs to keep memory use low.
$env:CARGO_TARGET_DIR = Join-Path $root "desktop-app\src-tauri\target"
if (-not $env:CARGO_BUILD_JOBS) { $env:CARGO_BUILD_JOBS = "2" }

$python = Join-Path $root ".venv\Scripts\python.exe"
if (-not (Test-Path $python)) { throw "Run setup.bat first (.venv is missing)." }

function Step($text) { Write-Host "`n=== $text" -ForegroundColor Cyan }
function Check($what) { if ($LASTEXITCODE -ne 0) { throw "$what failed (exit $LASTEXITCODE)." } }

if (-not $SkipServer) {
    Step "Building the web UI"
    npm run build:desktop-ui
    Check "UI build"

    Step "Building the PyInstaller server ($Variant)"
    $serverArgs = @("scripts\package_windows_desktop.py", "--variant", $Variant)
    if ($DevFfmpeg) { $serverArgs += "--dev-ffmpeg" }
    & $python @serverArgs
    Check "Server build"
}

Step "Building the Tauri installer"
Push-Location (Join-Path $root "desktop-app")
try {
    if (-not (Test-Path "node_modules\@tauri-apps\cli")) { npm install; Check "npm install" }
    npx tauri build
    Check "Tauri build"
} finally {
    Pop-Location
}

$installer = Get-ChildItem (Join-Path $env:CARGO_TARGET_DIR "release\bundle\nsis") -Filter "*-setup.exe" -ErrorAction SilentlyContinue |
    Sort-Object LastWriteTime -Descending | Select-Object -First 1
if (-not $installer) { throw "The installer was not produced." }
Write-Host ("`nInstaller: {0} ({1:N0} MB)" -f $installer.FullName, ($installer.Length / 1MB)) -ForegroundColor Green
if ($DevFfmpeg) { Write-Host "Local test build (dev FFmpeg): do not distribute." -ForegroundColor Yellow }
