# Creates Desktop and Start Menu shortcuts for the SonicBrief desktop app.
$ErrorActionPreference = "Stop"
$root = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
$pythonw = Join-Path $root ".venv\Scripts\pythonw.exe"
$script = Join-Path $root "desktop\sonicbrief_desktop.py"
$icon = Join-Path $root "desktop\sonicbrief.ico"
if (-not (Test-Path $pythonw)) { throw "Run setup.bat first: $pythonw was not found." }

$shell = New-Object -ComObject WScript.Shell
$targets = @(
    [Environment]::GetFolderPath("Desktop"),
    (Join-Path ([Environment]::GetFolderPath("Programs")) "")
)
foreach ($folder in $targets) {
    $shortcut = $shell.CreateShortcut((Join-Path $folder "SonicBrief.lnk"))
    $shortcut.TargetPath = $pythonw
    $shortcut.Arguments = "`"$script`""
    $shortcut.WorkingDirectory = $root
    $shortcut.IconLocation = $icon
    $shortcut.Description = "SonicBrief local transcription"
    $shortcut.Save()
    Write-Host "Created $(Join-Path $folder 'SonicBrief.lnk')"

    # Stop shortcut: works even when Windows does not show the tray icon.
    $stop = $shell.CreateShortcut((Join-Path $folder "Stop SonicBrief.lnk"))
    $stop.TargetPath = Join-Path $root "Stop SonicBrief.bat"
    $stop.WorkingDirectory = $root
    $stop.IconLocation = "$env:SystemRoot\System32\shell32.dll,131"
    $stop.Description = "Stop SonicBrief and its background services"
    $stop.Save()
    Write-Host "Created $(Join-Path $folder 'Stop SonicBrief.lnk')"
}
