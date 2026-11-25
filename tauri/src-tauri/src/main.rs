// Prevents an extra console window on Windows in release.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

#[cfg(target_os = "linux")]
#[link(name = "glib-2.0")]
extern "C" {
    fn g_set_prgname(name: *const std::ffi::c_char);
}

fn main() {
    // Wayland matches the app ID to the bundled Micro-Mic.desktop and its icon.
    #[cfg(target_os = "linux")]
    unsafe { g_set_prgname(c"Micro-Mic".as_ptr()) };
    micro_client_lib::run()
}
