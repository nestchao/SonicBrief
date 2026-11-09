<#
.SYNOPSIS
  Build the standalone SonicBrief Android APK (debug or signed release).

.DESCRIPTION
  Runs the unit tests, then builds APKs. A signed release is produced when all
  of ANDROID_KEYSTORE_PATH, ANDROID_KEYSTORE_PASSWORD, ANDROID_KEY_ALIAS and
  ANDROID_KEY_PASSWORD are set; otherwise it falls back to a debug build with a
  warning. SHA-256 files are written next to every APK. -Install runs
  `adb install -r` on the universal APK.

.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build_android.ps1
.EXAMPLE
  powershell -NoProfile -ExecutionPolicy Bypass -File .\scripts\build_android.ps1 -Install
#>
param(
    [switch]$Install
)

$ErrorActionPreference = "Stop"

$repoRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
Set-Location (Join-Path $repoRoot "android")

& .\gradlew.bat :app:testDebugUnitTest --console=plain
if ($LASTEXITCODE -ne 0) { throw "Unit tests failed; refusing to package." }

$hasSigning = $env:ANDROID_KEYSTORE_PATH -and $env:ANDROID_KEYSTORE_PASSWORD -and $env:ANDROID_KEY_ALIAS -and $env:ANDROID_KEY_PASSWORD
if ($hasSigning) {
    Write-Host "Signing environment detected: building signed release APKs."
    & .\gradlew.bat assembleRelease --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleRelease failed." }
    $apkDir = Join-Path $repoRoot "android\app\build\outputs\apk\release"
} else {
    Write-Warning "Signing env vars are not all set; building a debug APK instead (not for distribution)."
    & .\gradlew.bat assembleDebug --console=plain
    if ($LASTEXITCODE -ne 0) { throw "assembleDebug failed." }
    $apkDir = Join-Path $repoRoot "android\app\build\outputs\apk\debug"
}

$apks = Get-ChildItem $apkDir -Recurse -Filter *.apk
if (-not $apks) { throw "No APKs were produced in $apkDir." }
foreach ($apk in $apks) {
    $digest = (Get-FileHash $apk.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    $shaFile = "$($apk.FullName).sha256"
    "$digest  $($apk.Name)" | Set-Content -Path $shaFile -Encoding ascii -NoNewline
    $mb = [math]::Round($apk.Length / 1MB, 1)
    Write-Host ("{0}  {1} MB  sha256 {2}..." -f $apk.Name, $mb, $digest.Substring(0, 12))
}

if ($Install) {
    $universal = $apks | Where-Object { $_.Name -match 'universal' } | Select-Object -First 1
    if (-not $universal) { throw "No universal APK found for -Install." }
    Write-Host "Installing $($universal.Name) with adb (reinstall, keep data)..."
    & adb install -r $universal.FullName
    if ($LASTEXITCODE -ne 0) { throw "adb install failed; is a device connected (adb devices)?" }
}
