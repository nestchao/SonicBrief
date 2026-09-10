param(
    [Parameter(Mandatory = $true)]
    [string]$Url,

    [ValidateSet("mp3", "wav", "m4a")]
    [string]$Format = "mp3",

    [ValidateSet("brave", "chrome", "chromium", "edge", "firefox")]
    [string]$Browser = "",

    [ValidateSet("auto", "bun", "deno", "node", "quickjs")]
    [string]$JsRuntime = "auto",

    [string]$Language = "auto",

    [switch]$SkipSummary,

    [string]$FfmpegLocation = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"
$workflow = Join-Path $PSScriptRoot "collect_and_transcribe.py"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Project venv not found. Run setup.bat first."
}

$arguments = @(
    $workflow, "--url", $Url, "--audio-format", $Format,
    "--language", $Language, "--js-runtime", $JsRuntime
)
if ($Browser) {
    $arguments += @("--browser", $Browser)
}
if (-not $SkipSummary) {
    $arguments += "--summarize"
}
if ($FfmpegLocation) {
    $arguments += @("--ffmpeg-location", $FfmpegLocation)
}

& $python @arguments
exit $LASTEXITCODE
