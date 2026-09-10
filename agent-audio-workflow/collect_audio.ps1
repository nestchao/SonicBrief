param(
    [Parameter(Mandatory = $true)]
    [string]$Url,

    [ValidateSet("mp3", "wav", "m4a")]
    [string]$Format = "mp3",

    [ValidateSet("brave", "chrome", "chromium", "edge", "firefox")]
    [string]$Browser = "",

    [ValidateSet("auto", "bun", "deno", "node", "quickjs")]
    [string]$JsRuntime = "auto",

    [string]$FfmpegLocation = ""
)

$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $PSScriptRoot
$python = Join-Path $projectRoot ".venv\Scripts\python.exe"
$collector = Join-Path $PSScriptRoot "collect_audio.py"

if (-not (Test-Path -LiteralPath $python)) {
    throw "Project venv not found. Run setup.bat first."
}

$arguments = @($collector, "--url", $Url, "--audio-format", $Format, "--js-runtime", $JsRuntime)
if ($Browser) {
    $arguments += @("--browser", $Browser)
}
if ($FfmpegLocation) {
    $arguments += @("--ffmpeg-location", $FfmpegLocation)
}

& $python @arguments
exit $LASTEXITCODE
