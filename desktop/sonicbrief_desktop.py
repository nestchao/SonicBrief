"""SonicBrief desktop app: one window, a tray icon, and no terminal windows.

It starts the local FastAPI backend and the web UI server hidden in the
background, opens the UI in your normal browser, and keeps running in the
system tray. Run it with
``.venv\\Scripts\\pythonw.exe desktop\\sonicbrief_desktop.py`` or the
SonicBrief shortcut that setup creates.
"""
from __future__ import annotations

import ctypes
import json
import logging
import os
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.parse
import urllib.request
from ctypes import wintypes
from pathlib import Path

DESKTOP_DIR = Path(__file__).resolve().parent
ROOT = DESKTOP_DIR.parent
sys.path.insert(0, str(DESKTOP_DIR))

import autostart  # noqa: E402
from icons import render_logo  # noqa: E402

BACKEND_URL = "http://127.0.0.1:7860"
FRONTEND_URL = "http://127.0.0.1:3000"
INSTANCE_PORT = 7861  # Local-only port that doubles as the single-instance lock.
DATA_DIR = ROOT / "data"
LOG_DIR = DATA_DIR / "logs"
# Desktop scratch files. They stay inside the project because some setups deny
# writes elsewhere; vite.config.ts excludes data/ from Vite's file watcher.
LOCAL_STATE_DIR = DATA_DIR / "desktop"
VENV_PYTHON = ROOT / ".venv" / "Scripts" / "python.exe"
# Generous: a cold Vite dependency rebuild has been measured at ~3 minutes.
# Startup still fails fast if a server process exits.
STARTUP_TIMEOUT_SECONDS = float(os.getenv("SONICBRIEF_DESKTOP_STARTUP_TIMEOUT", "600"))
FRONTEND_START_RETRIES = 2
CREATE_NO_WINDOW = 0x08000000
ACTIVE_STATUSES = {"queued", "processing"}

LOG_DIR.mkdir(parents=True, exist_ok=True)
logging.basicConfig(
    filename=LOG_DIR / "desktop.log",
    level=logging.INFO,
    format="%(asctime)s %(levelname)s %(message)s",
)
log = logging.getLogger("sonicbrief.desktop")


# --------------------------------------------------------------------------- #
# Windows helpers (Job Object, clipboard, message box)
# --------------------------------------------------------------------------- #
kernel32 = ctypes.WinDLL("kernel32", use_last_error=True)
user32 = ctypes.WinDLL("user32", use_last_error=True)


class _IoCounters(ctypes.Structure):
    _fields_ = [(name, ctypes.c_ulonglong) for name in (
        "ReadOperationCount", "WriteOperationCount", "OtherOperationCount",
        "ReadTransferCount", "WriteTransferCount", "OtherTransferCount",
    )]


class _BasicLimitInformation(ctypes.Structure):
    _fields_ = [
        ("PerProcessUserTimeLimit", ctypes.c_int64),
        ("PerJobUserTimeLimit", ctypes.c_int64),
        ("LimitFlags", wintypes.DWORD),
        ("MinimumWorkingSetSize", ctypes.c_size_t),
        ("MaximumWorkingSetSize", ctypes.c_size_t),
        ("ActiveProcessLimit", wintypes.DWORD),
        ("Affinity", ctypes.c_size_t),
        ("PriorityClass", wintypes.DWORD),
        ("SchedulingClass", wintypes.DWORD),
    ]


class _ExtendedLimitInformation(ctypes.Structure):
    _fields_ = [
        ("BasicLimitInformation", _BasicLimitInformation),
        ("IoInfo", _IoCounters),
        ("ProcessMemoryLimit", ctypes.c_size_t),
        ("JobMemoryLimit", ctypes.c_size_t),
        ("PeakProcessMemoryUsed", ctypes.c_size_t),
        ("PeakJobMemoryUsed", ctypes.c_size_t),
    ]


kernel32.CreateJobObjectW.argtypes = [ctypes.c_void_p, wintypes.LPCWSTR]
kernel32.CreateJobObjectW.restype = wintypes.HANDLE
kernel32.SetInformationJobObject.argtypes = [wintypes.HANDLE, ctypes.c_int, ctypes.c_void_p, wintypes.DWORD]
kernel32.SetInformationJobObject.restype = wintypes.BOOL
kernel32.AssignProcessToJobObject.argtypes = [wintypes.HANDLE, wintypes.HANDLE]
kernel32.AssignProcessToJobObject.restype = wintypes.BOOL
kernel32.CloseHandle.argtypes = [wintypes.HANDLE]
kernel32.GlobalLock.argtypes = [ctypes.c_void_p]
kernel32.GlobalLock.restype = ctypes.c_void_p
kernel32.GlobalUnlock.argtypes = [ctypes.c_void_p]
user32.GetClipboardData.argtypes = [wintypes.UINT]
user32.GetClipboardData.restype = ctypes.c_void_p
user32.OpenClipboard.argtypes = [wintypes.HWND]
user32.MessageBoxW.argtypes = [wintypes.HWND, wintypes.LPCWSTR, wintypes.LPCWSTR, wintypes.UINT]


class ChildProcessJob:
    """Every child (and grandchild) dies when this app exits, even on a crash."""

    def __init__(self) -> None:
        self.handle = kernel32.CreateJobObjectW(None, None)
        info = _ExtendedLimitInformation()
        info.BasicLimitInformation.LimitFlags = 0x2000  # JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        if not self.handle or not kernel32.SetInformationJobObject(
            self.handle, 9, ctypes.byref(info), ctypes.sizeof(info)  # JobObjectExtendedLimitInformation
        ):
            log.warning("Could not create kill-on-close job object (error %s)", ctypes.get_last_error())

    def assign(self, process: subprocess.Popen) -> None:
        if self.handle and not kernel32.AssignProcessToJobObject(self.handle, int(process._handle)):  # type: ignore[attr-defined]
            log.warning("Could not assign pid %s to job object (error %s)", process.pid, ctypes.get_last_error())

    def close(self) -> None:
        if self.handle:
            kernel32.CloseHandle(self.handle)
            self.handle = None


def read_clipboard_text() -> str:
    if not user32.OpenClipboard(None):
        return ""
    try:
        handle = user32.GetClipboardData(13)  # CF_UNICODETEXT
        if not handle:
            return ""
        pointer = kernel32.GlobalLock(handle)
        if not pointer:
            return ""
        try:
            return ctypes.wstring_at(pointer)
        finally:
            kernel32.GlobalUnlock(handle)
    finally:
        user32.CloseClipboard()


def ask_yes_no(title: str, message: str) -> bool:
    MB_YESNO, MB_ICONQUESTION, MB_TOPMOST, IDYES = 0x4, 0x20, 0x40000, 6
    return user32.MessageBoxW(None, message, title, MB_YESNO | MB_ICONQUESTION | MB_TOPMOST) == IDYES


def show_error(title: str, message: str) -> None:
    user32.MessageBoxW(None, message, title, 0x10 | 0x40000)  # MB_ICONERROR | MB_TOPMOST


# --------------------------------------------------------------------------- #
# HTTP helpers
# --------------------------------------------------------------------------- #
def http_json(url: str, data: dict[str, str] | None = None, timeout: float = 5.0) -> object:
    body = urllib.parse.urlencode(data).encode() if data is not None else None
    request = urllib.request.Request(url, data=body, method="POST" if body is not None else "GET")
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return json.loads(response.read().decode("utf-8"))


def backend_healthy() -> dict | None:
    try:
        health = http_json(f"{BACKEND_URL}/api/health", timeout=2)
        return health if isinstance(health, dict) and health.get("ok") else None
    except (OSError, ValueError):
        return None


def frontend_up() -> bool:
    # Vite only listens once it is ready, so an accepted connection is enough.
    # (An HTTP request can take many seconds while the first page compiles.)
    try:
        with socket.create_connection(("127.0.0.1", 3000), timeout=2):
            return True
    except OSError:
        return False


# --------------------------------------------------------------------------- #
# Service manager
# --------------------------------------------------------------------------- #
class Services:
    def __init__(self) -> None:
        self.job = ChildProcessJob()
        self.processes: list[subprocess.Popen] = []
        self._log_files: list = []
        self.frontend: subprocess.Popen | None = None
        self.backend: subprocess.Popen | None = None
        self._spawn_frontend = None

    def supervise(self, should_stop, on_event) -> None:
        """After startup, restart the frontend if it crashes (max 3 times per 10 min)."""
        restarts: list[float] = []
        while not should_stop():
            time.sleep(3)
            if should_stop():
                return
            if self.backend is not None and self.backend.poll() is not None:
                log.error("Backend exited unexpectedly (code %s)", self.backend.returncode)
                on_event("SonicBrief engine stopped", "Quit and reopen SonicBrief. Details are in the logs folder.")
                self.backend = None
            if self.frontend is not None and self._spawn_frontend is not None and self.frontend.poll() is not None:
                now = time.monotonic()
                restarts = [t for t in restarts if now - t < 600]
                if len(restarts) >= 3:
                    log.error("Frontend keeps crashing; not restarting again")
                    on_event("SonicBrief interface stopped", "It crashed repeatedly. Details are in the logs folder.")
                    self.frontend = None
                    continue
                restarts.append(now)
                log.warning("Frontend exited (code %s); restarting", self.frontend.returncode)
                self.frontend = self._spawn_frontend()

    def _spawn(self, name: str, args: list[str], cwd: Path, extra_env: dict[str, str] | None = None) -> subprocess.Popen:
        log_file = open(LOG_DIR / f"{name}.log", "a", encoding="utf-8", buffering=1)
        log_file.write(f"\n===== {time.strftime('%Y-%m-%d %H:%M:%S')} starting {name} =====\n")
        env = {**os.environ, "PYTHONUNBUFFERED": "1", "PYTHONIOENCODING": "utf-8", "FORCE_COLOR": "0", **(extra_env or {})}
        process = subprocess.Popen(
            args, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=log_file,
            stderr=subprocess.STDOUT, creationflags=CREATE_NO_WINDOW,
        )
        self.job.assign(process)
        self.processes.append(process)
        self._log_files.append(log_file)
        log.info("Started %s (pid %s): %s", name, process.pid, args)
        return process

    def start(self, on_status) -> None:
        """Start (or reuse) the backend and frontend, then wait until both answer."""
        backend = frontend = None
        if backend_healthy():
            log.info("Reusing backend already running at %s", BACKEND_URL)
        else:
            if not VENV_PYTHON.exists():
                raise RuntimeError("The Python environment is missing. Run setup.bat first.")
            on_status("Starting the transcription engine…")
            backend = self._spawn("backend", [str(VENV_PYTHON), "app.py"], ROOT / "backend")

        if frontend_up():
            log.info("Reusing web UI already running at %s", FRONTEND_URL)
        else:
            node = shutil.which("node")
            vite = ROOT / "node_modules" / "vite" / "bin" / "vite.js"
            if not node:
                raise RuntimeError("Node.js was not found in PATH. Install Node.js 22+ and run setup.bat.")
            if not vite.exists():
                raise RuntimeError("Frontend packages are missing. Run setup.bat first.")
            frontend_tmp = LOCAL_STATE_DIR / "tmp"
            frontend_tmp.mkdir(parents=True, exist_ok=True)
            on_status("Starting the interface…")

            def spawn_frontend() -> subprocess.Popen:
                return self._spawn(
                    "frontend", [node, str(vite), "--host", "127.0.0.1", "--port", "3000", "--strictPort"], ROOT,
                    # Private temp dir for Vite/Miniflare scratch files.
                    extra_env={"TEMP": str(frontend_tmp), "TMP": str(frontend_tmp)},
                )

            frontend = spawn_frontend()

        # Vite's dependency re-optimisation occasionally crashes the Cloudflare
        # worker runner on a cold start ("internal error"); a restart succeeds.
        frontend_retries = FRONTEND_START_RETRIES
        deadline = time.monotonic() + STARTUP_TIMEOUT_SECONDS
        while True:
            if frontend is not None and frontend.poll() is not None and frontend_retries > 0:
                frontend_retries -= 1
                log.warning("Frontend exited during startup (code %s); retrying", frontend.returncode)
                on_status("Retrying the interface…")
                time.sleep(1)
                frontend = spawn_frontend()
                deadline = max(deadline, time.monotonic() + 90)
            for name, process in (("backend", backend), ("frontend", frontend)):
                if process is not None and process.poll() is not None:
                    raise RuntimeError(f"The {name} stopped while starting (exit code {process.returncode}). See {LOG_DIR / (name + '.log')}")
            backend_ok, frontend_ok = backend_healthy() is not None, frontend_up()
            if backend_ok and frontend_ok:
                self.frontend, self.backend = frontend, backend
                self._spawn_frontend = spawn_frontend if frontend is not None else None
                return
            if time.monotonic() > deadline:
                waiting = [n for n, ok in (("backend", backend_ok), ("frontend", frontend_ok)) if not ok]
                raise RuntimeError(f"Timed out waiting for the {' and '.join(waiting)}. See logs in {LOG_DIR}")
            on_status("Loading Whisper and the interface…" if not backend_ok else "Preparing the interface (first start can take a few minutes)…")
            time.sleep(0.5)

    def stop(self) -> None:
        for process in self.processes:
            if process.poll() is None:
                process.terminate()
        for process in self.processes:
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
        self.job.close()  # Kills any grandchildren left behind (e.g. Vite workers).
        for log_file in self._log_files:
            log_file.close()


# --------------------------------------------------------------------------- #
# App UI
# --------------------------------------------------------------------------- #
# SonicBrief opens as a tab in the person's normal browser. A separate browser
# window launched from this process can be blocked on some setups (it stays
# blank), while handing the address to the already-running browser works.


class DesktopApp:
    def __init__(self, open_window: bool) -> None:
        self.open_window_when_ready = open_window
        self.services = Services()
        self.tray = None
        self.quitting = False
        self.ready = False
        self.told_about_tray = False
        self.job_statuses: dict[str, str] | None = None

    # ----- window -----
    def show_window(self) -> None:
        if not self.ready:
            self.notify("SonicBrief is still starting", "It opens in your browser as soon as it's ready.")
            self.open_window_when_ready = True
            return
        log.info("Opening %s in the default browser", FRONTEND_URL)
        try:
            os.startfile(FRONTEND_URL)  # noqa: S606 - the default browser.
        except OSError as exc:
            log.exception("Could not open the browser")
            self.notify("Could not open your browser", f"Open {FRONTEND_URL} yourself. ({exc})")
            return
        if not self.told_about_tray:
            self.told_about_tray = True
            self.notify("SonicBrief is running", "It keeps working in the system tray. Right-click the icon to quit.")

    def boot(self) -> None:
        def on_status(text: str) -> None:
            if self.tray is not None:
                self.tray.title = f"SonicBrief: {text}"

        try:
            self.services.start(on_status)
        except Exception as exc:  # noqa: BLE001 - shown to the user.
            log.exception("Startup failed")
            if self.tray is not None:
                self.tray.title = "SonicBrief: could not start"
            show_error("SonicBrief could not start", f"{exc}\n\nUse the tray icon's 'Open logs folder' for details.")
            return
        self.ready = True
        if self.tray is not None:
            self.tray.title = "SonicBrief"
        threading.Thread(target=self.watch_jobs, daemon=True).start()
        threading.Thread(target=self.services.supervise, args=(lambda: self.quitting, self.notify), daemon=True).start()
        if self.open_window_when_ready:
            self.show_window()

    # ----- tray -----
    def notify(self, title: str, message: str) -> None:
        try:
            self.tray.notify(message, title)
        except Exception:  # noqa: BLE001 - notifications are best-effort.
            log.exception("Notification failed")

    def transcribe_clipboard(self, *_args) -> None:
        url = read_clipboard_text().strip()
        if not url.lower().startswith(("http://", "https://")):
            self.notify("No link on the clipboard", "Copy a YouTube, Bilibili or other supported video link first.")
            return
        health = backend_healthy()
        if health is None:
            self.notify("SonicBrief is not ready", "Wait for SonicBrief to finish starting, then try again.")
            return
        use_gemini = "true" if health.get("gemini_configured") else "false"
        try:
            job = http_json(f"{BACKEND_URL}/api/jobs/url", {
                "url": url, "model_name": "turbo", "language": "auto",
                "generate_summary": use_gemini, "allow_gemini_fallback": use_gemini,
                "summary_language": "zh-CN", "summary_style": "standard",
            }, timeout=15)
        except urllib.error.HTTPError as exc:
            try:
                detail = json.loads(exc.read().decode()).get("detail", str(exc))
            except (ValueError, AttributeError):
                detail = str(exc)
            self.notify("Could not add that link", str(detail))
            return
        except OSError as exc:
            self.notify("Could not reach SonicBrief", str(exc))
            return
        self.notify("Transcription queued", url)
        if isinstance(job, dict) and self.job_statuses is not None:
            self.job_statuses[str(job.get("id"))] = str(job.get("status"))

    def toggle_autostart(self, *_args) -> None:
        try:
            if autostart.is_enabled():
                autostart.disable()
            else:
                autostart.enable()
        except OSError as exc:
            log.exception("Could not change start-with-Windows setting")
            self.notify("Could not change startup setting", str(exc))

    def active_job_count(self) -> int:
        try:
            jobs = http_json(f"{BACKEND_URL}/api/jobs?limit=50", timeout=3)
        except (OSError, ValueError):
            return 0
        return sum(1 for job in jobs if isinstance(job, dict) and job.get("status") in ACTIVE_STATUSES) if isinstance(jobs, list) else 0

    def quit(self, *_args) -> None:
        running = self.active_job_count() if self.services.processes else 0
        if running and not ask_yes_no(
            "Quit SonicBrief?",
            f"{running} transcription job(s) are still running and will be stopped. Quit anyway?",
        ):
            return
        self.quitting = True
        if self.tray is not None:
            self.tray.stop()

    def build_tray(self):
        import pystray
        from pystray._util import win32 as pystray_win32

        # pystray calls this only so the icon survives an Explorer restart;
        # restricted sessions deny it, which must not stop the tray icon.
        original_filter = pystray_win32.ChangeWindowMessageFilterEx

        def tolerant_filter(*args):
            try:
                return original_filter(*args)
            except OSError:
                log.warning("ChangeWindowMessageFilterEx denied; continuing without it")
                return 0

        pystray_win32.ChangeWindowMessageFilterEx = tolerant_filter

        menu = pystray.Menu(
            pystray.MenuItem("Open SonicBrief", lambda *_: self.show_window(), default=True),
            pystray.MenuItem("Transcribe link from clipboard", self.transcribe_clipboard),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Open data folder", lambda *_: os.startfile(DATA_DIR)),  # noqa: S606
            pystray.MenuItem("Open logs folder", lambda *_: os.startfile(LOG_DIR)),  # noqa: S606
            pystray.MenuItem("Start with Windows", self.toggle_autostart, checked=lambda _item: autostart.is_enabled()),
            pystray.Menu.SEPARATOR,
            pystray.MenuItem("Quit SonicBrief", self.quit),
        )
        return pystray.Icon("SonicBrief", render_logo(64), "SonicBrief: starting…", menu)

    # ----- job notifications -----
    def watch_jobs(self) -> None:
        while not self.quitting:
            try:
                jobs = http_json(f"{BACKEND_URL}/api/jobs?limit=20", timeout=5)
            except (OSError, ValueError):
                jobs = None
            if isinstance(jobs, list):
                current = {str(j.get("id")): j for j in jobs if isinstance(j, dict)}
                if self.job_statuses is None:
                    self.job_statuses = {job_id: str(j.get("status")) for job_id, j in current.items()}
                else:
                    for job_id, job in current.items():
                        status, previous = str(job.get("status")), self.job_statuses.get(job_id)
                        if status != previous and status in {"completed", "failed"} and (previous is None or previous in ACTIVE_STATUSES):
                            title = str(job.get("title") or "Media")
                            if status == "completed":
                                self.notify("Transcript ready", title)
                            else:
                                self.notify("Transcription failed", f"{title}\n{job.get('error') or ''}".strip())
                        self.job_statuses[job_id] = status
            time.sleep(4)

    # ----- single instance -----
    def listen_for_other_instances(self, server: socket.socket) -> None:
        while True:
            try:
                connection, _ = server.accept()
            except OSError:
                return
            with connection:
                message = connection.recv(64)
            if message.startswith(b"show"):
                log.info("Another launch asked to open SonicBrief")
                self.show_window()
            elif message.startswith(b"quit"):
                # Works even when the tray icon is not visible (see Stop SonicBrief.bat).
                log.info("Another launch asked SonicBrief to quit")
                threading.Thread(target=self.quit, daemon=True).start()

    def run(self, server: socket.socket) -> None:
        try:
            autostart.register_protocol()
        except OSError:
            # Optional: only needed so the Chrome extension can launch the app.
            log.warning("Could not register the %s:// link", autostart.PROTOCOL, exc_info=True)
        threading.Thread(target=self.listen_for_other_instances, args=(server,), daemon=True).start()
        self.tray = self.build_tray()

        def on_tray_ready(icon) -> None:
            icon.visible = True
            threading.Thread(target=self.boot, daemon=True).start()

        try:
            self.tray.run(setup=on_tray_ready)  # Blocks until Quit.
        finally:
            self.quitting = True
            self.services.stop()
            server.close()
            log.info("SonicBrief desktop exited")


def acquire_instance_lock() -> socket.socket | None:
    server = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    server.setsockopt(socket.SOL_SOCKET, socket.SO_EXCLUSIVEADDRUSE, 1)
    try:
        server.bind(("127.0.0.1", INSTANCE_PORT))
    except OSError:
        server.close()
        return None
    server.listen(4)
    return server


def signal_existing_instance(message: bytes) -> bool:
    try:
        with socket.create_connection(("127.0.0.1", INSTANCE_PORT), timeout=2) as connection:
            connection.sendall(message)
        return True
    except OSError:
        return False


def request_quit() -> int:
    """``--quit``: ask the running app to exit and wait until it has."""
    if not signal_existing_instance(b"quit"):
        print("SonicBrief is not running.")
        return 0
    print("Stopping SonicBrief (this can take a few seconds)...")
    deadline = time.monotonic() + 45  # Long enough to answer the "jobs still running" prompt.
    while time.monotonic() < deadline:
        time.sleep(1)
        try:
            socket.create_connection(("127.0.0.1", INSTANCE_PORT), timeout=1).close()
        except OSError:
            print("SonicBrief has stopped.")
            return 0
    print("SonicBrief is still running (was the quit prompt answered 'No'?).")
    return 1


def main() -> int:
    # Start-with-Windows and extension launches (sonicbrief-desktop://) start quietly.
    args = sys.argv[1:]
    if "--quit" in args:
        return request_quit()
    background = "--background" in args or any(a.lower().startswith(autostart.PROTOCOL + ":") for a in args)
    server = acquire_instance_lock()
    if server is None:
        if signal_existing_instance(b"ping" if background else b"show"):
            return 0
        show_error("SonicBrief", f"Port {INSTANCE_PORT} is in use by another program, so SonicBrief cannot start.")
        return 1
    try:
        DesktopApp(open_window=not background).run(server)
    except Exception as exc:  # noqa: BLE001 - last-resort report for pythonw.
        log.exception("Fatal error")
        show_error("SonicBrief", f"SonicBrief stopped unexpectedly:\n{exc}\n\nSee {LOG_DIR / 'desktop.log'}")
        return 1
    return 0


if __name__ == "__main__":
    sys.exit(main())
