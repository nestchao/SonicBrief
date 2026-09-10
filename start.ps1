$ErrorActionPreference = "Stop"
$projectRoot = Split-Path -Parent $MyInvocation.MyCommand.Path
Set-Location $projectRoot

if (-not (Test-Path ".venv\Scripts\python.exe")) {
    throw "Run setup.bat before starting SonicBrief."
}

$backendCommand = "Set-Location '$projectRoot\backend'; & '$projectRoot\.venv\Scripts\python.exe' app.py"
Start-Process powershell -ArgumentList "-NoExit", "-NoProfile", "-Command", $backendCommand

Start-Job -ScriptBlock {
    Start-Sleep -Seconds 3
    Start-Process "http://127.0.0.1:3000"
} | Out-Null

npm run dev -- --host 127.0.0.1 --port 3000 --strictPort
