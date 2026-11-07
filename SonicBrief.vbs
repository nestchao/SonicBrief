' Launches the SonicBrief desktop app without a console window.
Set fso = CreateObject("Scripting.FileSystemObject")
root = fso.GetParentFolderName(WScript.ScriptFullName)
Set shell = CreateObject("WScript.Shell")
shell.CurrentDirectory = root
shell.Run """" & root & "\.venv\Scripts\pythonw.exe"" """ & root & "\desktop\sonicbrief_desktop.py""", 0, False
