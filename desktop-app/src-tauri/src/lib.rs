//! SonicBrief desktop shell.
//!
//! Starts the bundled `SonicBrief-Server.exe` (FastAPI + Whisper + the web UI) hidden,
//! shows `http://127.0.0.1:7860/` in a native WebView2 window, and keeps running in
//! the tray. The server is tied to this process with a kill-on-close Job Object, so
//! quitting or crashing the shell never leaves it running.

use std::{
    collections::HashMap,
    ffi::c_void,
    fs::{self, OpenOptions},
    io::Write,
    net::{SocketAddr, TcpStream},
    os::windows::{io::AsRawHandle, process::CommandExt},
    path::{Path, PathBuf},
    process::{Child, Command, Stdio},
    sync::{
        atomic::{AtomicBool, Ordering},
        Mutex,
    },
    thread,
    time::{Duration, Instant},
};

use serde_json::Value;
use tauri::{
    menu::{CheckMenuItem, Menu, MenuItem, PredefinedMenuItem},
    tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent},
    AppHandle, Manager, RunEvent, WindowEvent,
};
use tauri_plugin_autostart::ManagerExt as AutostartExt;
use tauri_plugin_deep_link::DeepLinkExt;
use tauri_plugin_dialog::{DialogExt, MessageDialogButtons};
use tauri_plugin_notification::NotificationExt;
use windows::{
    core::PCWSTR,
    Win32::{
        Foundation::HANDLE,
        System::JobObjects::{
            AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
            SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
            JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
        },
    },
};

const PORT: u16 = 7860;
const UI_URL: &str = "http://127.0.0.1:7860/";
const CREATE_NO_WINDOW: u32 = 0x0800_0000;
const STARTUP_TIMEOUT: Duration = Duration::from_secs(180);

#[derive(Default)]
struct Shared {
    child: Mutex<Option<Child>>,
    /// Job object handle (as an integer so the state stays `Send`). Never closed on
    /// purpose: closing it is what kills the server, and Windows does that at exit.
    job: Mutex<Option<usize>>,
    server_exe: Mutex<Option<PathBuf>>,
    home: Mutex<PathBuf>,
    quitting: AtomicBool,
}

// ----------------------------------------------------------------------------
// Folders and the bundled server
// ----------------------------------------------------------------------------

/// Per-user data folder. Shared with the portable MCP build (`%LOCALAPPDATA%\SonicBrief`);
/// falls back to a folder beside the app when that location cannot be written.
fn choose_home(app: &AppHandle) -> PathBuf {
    if let Ok(dir) = app.path().local_data_dir() {
        let preferred = dir.join("SonicBrief");
        if fs::create_dir_all(&preferred).is_ok() {
            return preferred;
        }
    }
    let fallback = std::env::current_exe()
        .ok()
        .and_then(|exe| exe.parent().map(|dir| dir.join("SonicBrief-data")))
        .unwrap_or_else(|| PathBuf::from("SonicBrief-data"));
    let _ = fs::create_dir_all(&fallback);
    fallback
}

fn find_server_exe(app: &AppHandle) -> Option<PathBuf> {
    if let Ok(custom) = std::env::var("SONICBRIEF_SERVER_EXE") {
        return Some(PathBuf::from(custom));
    }
    let mut candidates: Vec<PathBuf> = Vec::new();
    if let Ok(resources) = app.path().resource_dir() {
        candidates.push(resources.join("server").join("SonicBrief-Server.exe"));
    }
    if let Some(dir) = std::env::current_exe().ok().and_then(|e| e.parent().map(Path::to_path_buf)) {
        candidates.push(dir.join("server").join("SonicBrief-Server.exe"));
    }
    candidates.into_iter().find(|path| path.is_file())
}

fn logs_dir(home: &Path) -> PathBuf {
    home.join("logs")
}

// ----------------------------------------------------------------------------
// HTTP helpers (blocking; always called from background threads)
// ----------------------------------------------------------------------------

fn http_get(path: &str, timeout_secs: u64) -> Option<String> {
    let agent = ureq::AgentBuilder::new()
        .timeout(Duration::from_secs(timeout_secs))
        .build();
    agent
        .get(&format!("http://127.0.0.1:{PORT}{path}"))
        .call()
        .ok()?
        .into_string()
        .ok()
}

fn port_open() -> bool {
    let addr = SocketAddr::from(([127, 0, 0, 1], PORT));
    TcpStream::connect_timeout(&addr, Duration::from_millis(300)).is_ok()
}

fn api_healthy() -> bool {
    http_get("/api/health", 3)
        .and_then(|body| serde_json::from_str::<Value>(&body).ok())
        .and_then(|value| value.get("ok").and_then(Value::as_bool))
        .unwrap_or(false)
}

fn ui_served() -> bool {
    http_get("/", 3).is_some()
}

fn active_job_count() -> usize {
    http_get("/api/jobs?limit=50", 3)
        .and_then(|body| serde_json::from_str::<Value>(&body).ok())
        .and_then(|jobs| {
            jobs.as_array().map(|list| {
                list.iter()
                    .filter(|job| matches!(job.get("status").and_then(Value::as_str), Some("queued" | "processing")))
                    .count()
            })
        })
        .unwrap_or(0)
}

// ----------------------------------------------------------------------------
// Server process (hidden, tied to this process by a Job Object)
// ----------------------------------------------------------------------------

fn kill_on_close_job() -> Option<usize> {
    unsafe {
        let job = CreateJobObjectW(None, PCWSTR::null()).ok()?;
        let mut info = JOBOBJECT_EXTENDED_LIMIT_INFORMATION::default();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        SetInformationJobObject(
            job,
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const c_void,
            std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32,
        )
        .ok()?;
        Some(job.0 as usize)
    }
}

fn spawn_server(shared: &Shared) -> Result<(), String> {
    let exe = shared
        .server_exe
        .lock()
        .map_err(|_| "state poisoned")?
        .clone()
        .ok_or("The bundled SonicBrief server was not found. Reinstall SonicBrief.")?;
    let home = shared.home.lock().map_err(|_| "state poisoned")?.clone();

    let logs = logs_dir(&home);
    fs::create_dir_all(&logs).map_err(|e| format!("Cannot create {}: {e}", logs.display()))?;
    let mut log = OpenOptions::new()
        .create(true)
        .append(true)
        .open(logs.join("server.log"))
        .map_err(|e| format!("Cannot open the server log: {e}"))?;
    let _ = writeln!(log, "\n===== starting server =====");
    let log_err = log.try_clone().map_err(|e| e.to_string())?;

    let child = Command::new(&exe)
        .current_dir(exe.parent().unwrap_or_else(|| Path::new(".")))
        .env("SONICBRIEF_DATA_DIR", home.join("data"))
        .env("SONICBRIEF_ENV_FILE", home.join("settings.env"))
        .env("HF_HOME", home.join("models"))
        .env("XDG_CACHE_HOME", home.join("cache"))
        // Unbuffered stdout/stderr: a hung worker used to take its log lines with it,
        // which made "stuck at 25%" impossible to diagnose after the fact.
        .env("PYTHONUNBUFFERED", "1")
        .stdin(Stdio::null())
        .stdout(log)
        .stderr(log_err)
        .creation_flags(CREATE_NO_WINDOW)
        .spawn()
        .map_err(|e| format!("Could not start {}: {e}", exe.display()))?;

    let mut job = shared.job.lock().map_err(|_| "state poisoned")?;
    if job.is_none() {
        *job = kill_on_close_job();
    }
    if let Some(handle) = *job {
        unsafe {
            let _ = AssignProcessToJobObject(HANDLE(handle as *mut c_void), HANDLE(child.as_raw_handle()));
        }
    }
    drop(job);

    *shared.child.lock().map_err(|_| "state poisoned")? = Some(child);
    Ok(())
}

fn child_exited(shared: &Shared) -> Option<String> {
    let mut guard = shared.child.lock().ok()?;
    let child = guard.as_mut()?;
    match child.try_wait() {
        Ok(Some(status)) => {
            *guard = None;
            Some(status.to_string())
        }
        _ => None,
    }
}

fn stop_server(shared: &Shared) {
    if let Ok(mut guard) = shared.child.lock() {
        if let Some(mut child) = guard.take() {
            let _ = child.kill();
            let _ = child.wait();
        }
    }
}

/// Start the server (or reuse a running one) and wait until it answers.
fn ensure_server(app: &AppHandle) -> Result<(), String> {
    let shared = app.state::<Shared>();
    if port_open() {
        return if api_healthy() && ui_served() {
            Ok(()) // A SonicBrief server with the UI is already running; use it.
        } else {
            Err(format!(
                "Port {PORT} is already used by another program (for example the SonicBrief developer \
                 server from start.bat). Close it and open SonicBrief again."
            ))
        };
    }

    set_status(app, "Starting the transcription engine…");
    spawn_server(&shared)?;
    let started = Instant::now();
    loop {
        if api_healthy() {
            return Ok(());
        }
        if let Some(status) = child_exited(&shared) {
            let log = logs_dir(&shared.home.lock().map(|h| h.clone()).unwrap_or_default()).join("server.log");
            return Err(format!("The SonicBrief server stopped while starting ({status}).\nDetails: {}", log.display()));
        }
        if started.elapsed() > STARTUP_TIMEOUT {
            return Err("The SonicBrief server did not respond in time. Try again.".into());
        }
        if started.elapsed() > Duration::from_secs(8) {
            set_status(app, "Loading the Whisper runtime (the first start can take a minute)…");
        }
        thread::sleep(Duration::from_millis(400));
    }
}

// ----------------------------------------------------------------------------
// Window
// ----------------------------------------------------------------------------

fn splash_eval(app: &AppHandle, js: String) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.eval(js);
    }
}

fn set_status(app: &AppHandle, text: &str) {
    let text = serde_json::to_string(text).unwrap_or_default();
    splash_eval(app, format!("window.__status && window.__status({text})"));
}

fn set_error(app: &AppHandle, text: &str) {
    let text = serde_json::to_string(text).unwrap_or_default();
    splash_eval(app, format!("window.__error && window.__error({text})"));
}

fn show_main_window(app: &AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn started_in_background(args: &[String]) -> bool {
    args.iter()
        .any(|arg| arg == "--background" || arg.to_lowercase().starts_with("sonicbrief-desktop:"))
}

fn notify(app: &AppHandle, title: &str, body: &str) {
    let _ = app.notification().builder().title(title).body(body).show();
}

// ----------------------------------------------------------------------------
// Background work
// ----------------------------------------------------------------------------

fn boot(app: AppHandle, background: bool) {
    // The configured window is created asynchronously; give it a moment.
    thread::sleep(Duration::from_millis(400));
    if !background {
        show_main_window(&app);
    }

    match ensure_server(&app) {
        Ok(()) => {
            splash_eval(&app, format!("window.location.replace({})", serde_json::to_string(UI_URL).unwrap_or_default()));
            watch(app);
        }
        Err(message) => {
            set_error(&app, &message);
            show_main_window(&app); // Never fail silently in the background.
        }
    }
}

/// Restart a crashed server and announce finished jobs (polling the local API).
fn watch(app: AppHandle) {
    let mut statuses: Option<HashMap<String, String>> = None;
    let mut restarts: Vec<Instant> = Vec::new();
    loop {
        thread::sleep(Duration::from_secs(4));
        let shared = app.state::<Shared>();
        if shared.quitting.load(Ordering::SeqCst) {
            return;
        }

        if child_exited(&shared).is_some() && !shared.quitting.load(Ordering::SeqCst) {
            restarts.retain(|t| t.elapsed() < Duration::from_secs(600));
            if restarts.len() >= 3 || spawn_server(&shared).is_err() {
                notify(&app, "SonicBrief stopped", "The transcription engine keeps crashing. Quit and reopen SonicBrief.");
                return;
            }
            restarts.push(Instant::now());
            continue;
        }

        let Some(body) = http_get("/api/jobs?limit=20", 5) else { continue };
        let Ok(Value::Array(jobs)) = serde_json::from_str::<Value>(&body) else { continue };
        let current: HashMap<String, (String, String, String)> = jobs
            .iter()
            .filter_map(|job| {
                Some((
                    job.get("id")?.as_str()?.to_string(),
                    (
                        job.get("status")?.as_str()?.to_string(),
                        job.get("title").and_then(Value::as_str).unwrap_or("Media").to_string(),
                        job.get("error").and_then(Value::as_str).unwrap_or("").to_string(),
                    ),
                ))
            })
            .collect();
        match statuses.as_mut() {
            None => statuses = Some(current.iter().map(|(id, (s, _, _))| (id.clone(), s.clone())).collect()),
            Some(known) => {
                for (id, (status, title, error)) in &current {
                    let previous = known.get(id).map(String::as_str);
                    let was_active = matches!(previous, None | Some("queued" | "processing"));
                    if previous != Some(status.as_str()) && was_active {
                        match status.as_str() {
                            "completed" => notify(&app, "Transcript ready", title),
                            "failed" => notify(&app, "Transcription failed", format!("{title}\n{error}").trim()),
                            _ => {}
                        }
                    }
                    known.insert(id.clone(), status.clone());
                }
            }
        }
    }
}

fn shutdown(app: &AppHandle) {
    let shared = app.state::<Shared>();
    shared.quitting.store(true, Ordering::SeqCst);
    stop_server(&shared);
}

fn quit_app(app: AppHandle) {
    let running = active_job_count();
    if running > 0 {
        let confirmed = app
            .dialog()
            .message(format!("{running} transcription job(s) are still running and will be stopped. Quit anyway?"))
            .title("Quit SonicBrief?")
            .buttons(MessageDialogButtons::YesNo)
            .blocking_show();
        if !confirmed {
            return;
        }
    }
    shutdown(&app);
    app.exit(0);
}

fn open_folder(path: &Path) {
    let _ = fs::create_dir_all(path);
    let _ = Command::new("explorer").arg(path).spawn();
}

// ----------------------------------------------------------------------------
// App
// ----------------------------------------------------------------------------

pub fn run() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let background = started_in_background(&args);

    let app = tauri::Builder::default()
        .plugin(tauri_plugin_single_instance::init(|app, argv, _cwd| {
            // Launching SonicBrief again brings it forward, unless it was the
            // browser extension or Windows startup asking us to run quietly.
            if !started_in_background(&argv) {
                show_main_window(app);
            }
        }))
        .plugin(tauri_plugin_deep_link::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_notification::init())
        .plugin(tauri_plugin_autostart::init(
            tauri_plugin_autostart::MacosLauncher::LaunchAgent,
            Some(vec!["--background"]),
        ))
        .manage(Shared::default())
        .setup(move |app| {
            #[cfg(debug_assertions)]
            let _ = app.deep_link().register_all();

            let handle = app.handle().clone();
            let shared = app.state::<Shared>();
            *shared.home.lock().unwrap() = choose_home(&handle);
            *shared.server_exe.lock().unwrap() = find_server_exe(&handle);

            let open_item = MenuItem::with_id(app, "open", "Open SonicBrief", true, None::<&str>)?;
            let data_item = MenuItem::with_id(app, "data", "Open data folder", true, None::<&str>)?;
            let logs_item = MenuItem::with_id(app, "logs", "Open logs folder", true, None::<&str>)?;
            let autostart_enabled = handle.autolaunch().is_enabled().unwrap_or(false);
            let autostart_item =
                CheckMenuItem::with_id(app, "autostart", "Start with Windows", true, autostart_enabled, None::<&str>)?;
            let quit_item = MenuItem::with_id(app, "quit", "Quit SonicBrief", true, None::<&str>)?;
            let separator = PredefinedMenuItem::separator(app)?;
            let separator2 = PredefinedMenuItem::separator(app)?;
            let menu = Menu::with_items(
                app,
                &[&open_item, &data_item, &logs_item, &separator, &autostart_item, &separator2, &quit_item],
            )?;

            let autostart_toggle = autostart_item.clone();
            let mut tray = TrayIconBuilder::with_id("sonicbrief")
                .tooltip("SonicBrief")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "open" => show_main_window(app),
                    "data" => {
                        let home = app.state::<Shared>().home.lock().map(|h| h.clone()).unwrap_or_default();
                        open_folder(&home.join("data"));
                    }
                    "logs" => {
                        let home = app.state::<Shared>().home.lock().map(|h| h.clone()).unwrap_or_default();
                        open_folder(&logs_dir(&home));
                    }
                    "autostart" => {
                        let manager = app.autolaunch();
                        let was_enabled = manager.is_enabled().unwrap_or(false);
                        let result = if was_enabled { manager.disable() } else { manager.enable() };
                        if let Err(error) = result {
                            let _ = autostart_toggle.set_checked(was_enabled);
                            notify(app, "Could not change the startup setting", &error.to_string());
                        }
                    }
                    "quit" => {
                        let app = app.clone();
                        thread::spawn(move || quit_app(app)); // The confirm dialog must not block the UI thread.
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        show_main_window(tray.app_handle());
                    }
                });
            if let Some(icon) = app.default_window_icon() {
                tray = tray.icon(icon.clone());
            }
            tray.build(app)?;

            thread::spawn(move || boot(handle, background));
            Ok(())
        })
        .on_window_event(|window, event| {
            if window.label() == "main" {
                if let WindowEvent::CloseRequested { api, .. } = event {
                    let _ = window.hide(); // Keep running in the tray.
                    api.prevent_close();
                }
            }
        })
        .build(tauri::generate_context!())
        .expect("error while building SonicBrief");

    app.run(|handle, event| {
        if let RunEvent::Exit = event {
            shutdown(handle);
        }
    });
}
