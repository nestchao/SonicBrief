$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

if (-not (Get-Command py -ErrorAction SilentlyContinue)) {
    throw "Python launcher 'py' was not found. Install Python 3.11 first."
}

if (-not (Test-Path ".venv\Scripts\python.exe")) {
    py -3.11 -m venv .venv
}

& ".venv\Scripts\python.exe" -m pip install --upgrade pip
& ".venv\Scripts\python.exe" -m pip install -r "backend\requirements.txt"
npm ci

if (-not (Test-Path "backend\.env")) {
    Copy-Item "backend\.env.example" "backend\.env"
}

Write-Host ""
Write-Host "SonicBrief setup is complete." -ForegroundColor Green
Write-Host "Start SonicBrief with start.bat, then open Gemini Settings to add your Google AI Studio API key and choose a Gemini model."
Write-Host "For speaker identification, also install backend\requirements-diarization.txt and add HF_TOKEN."
Write-Host "AI-agent integration is available through sonicbrief_mcp.py; it uses the same local backend and job queue."
