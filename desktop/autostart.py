"""Per-user Windows integration: start-with-Windows and the sonicbrief-desktop:// link.

Everything lives under HKEY_CURRENT_USER, so no administrator rights are needed.
The sonicbrief-desktop:// protocol lets the Chrome extension launch the desktop app.
"""
from __future__ import annotations

import winreg
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
PYTHONW = ROOT / ".venv" / "Scripts" / "pythonw.exe"
SCRIPT = ROOT / "desktop" / "sonicbrief_desktop.py"
ICON = ROOT / "desktop" / "sonicbrief.ico"

RUN_KEY = r"Software\Microsoft\Windows\CurrentVersion\Run"
# Distinct from "sonicbrief://", which the packaged SonicBrief Windows app may own.
PROTOCOL = "sonicbrief-desktop"
PROTOCOL_KEY = rf"Software\Classes\{PROTOCOL}"
VALUE_NAME = "SonicBrief"


def launch_command(*extra: str) -> str:
    return " ".join(f'"{part}"' for part in (PYTHONW, SCRIPT, *extra))


def is_enabled() -> bool:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
            value, _ = winreg.QueryValueEx(key, VALUE_NAME)
    except OSError:
        return False
    return value == launch_command("--background")


def enable() -> None:
    # --background: start quietly in the tray at sign-in, without opening a window.
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, RUN_KEY) as key:
        winreg.SetValueEx(key, VALUE_NAME, 0, winreg.REG_SZ, launch_command("--background"))


def disable() -> None:
    try:
        with winreg.OpenKey(winreg.HKEY_CURRENT_USER, RUN_KEY, 0, winreg.KEY_SET_VALUE) as key:
            winreg.DeleteValue(key, VALUE_NAME)
    except FileNotFoundError:
        pass


def register_protocol() -> None:
    """Register sonicbrief-desktop:// so links (e.g. from the extension) open this app."""
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, PROTOCOL_KEY) as key:
        winreg.SetValueEx(key, "", 0, winreg.REG_SZ, "URL:SonicBrief Desktop")
        winreg.SetValueEx(key, "URL Protocol", 0, winreg.REG_SZ, "")
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, PROTOCOL_KEY + r"\DefaultIcon") as key:
        winreg.SetValueEx(key, "", 0, winreg.REG_SZ, str(ICON))
    with winreg.CreateKey(winreg.HKEY_CURRENT_USER, PROTOCOL_KEY + r"\shell\open\command") as key:
        winreg.SetValueEx(key, "", 0, winreg.REG_SZ, launch_command("%1"))
