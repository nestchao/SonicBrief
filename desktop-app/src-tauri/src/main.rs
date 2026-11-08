// Keep the release build a GUI app: no console window.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

fn main() {
    sonicbrief_desktop_lib::run()
}
