@echo off
rem Stops SonicBrief (the tray icon is not required).
"%~dp0.venv\Scripts\python.exe" "%~dp0desktop\sonicbrief_desktop.py" --quit
timeout /t 3 >nul
