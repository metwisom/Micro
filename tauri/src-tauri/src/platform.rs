//! The parts that differ per operating system, kept behind one uniform surface so the rest of the
//! backend never has to carry a `#[cfg]`: the machine name, the desktop accent colour, and the
//! Linux-only virtual microphone.

/// The machine name shown on the phone. `gethostname` works in GUI sessions on every OS (the
/// HOSTNAME shell variable is not exported there). '|' is the wire field delimiter → never in a name.
pub(crate) fn hostname() -> String {
    let h = gethostname::gethostname().to_string_lossy().trim().replace('|', " ");
    if h.is_empty() {
        "PC".to_string()
    } else {
        h
    }
}

#[cfg(windows)]
pub(crate) fn read_accent() -> Option<String> {
    use winreg::enums::HKEY_CURRENT_USER;
    use winreg::RegKey;
    let dwm = RegKey::predef(HKEY_CURRENT_USER).open_subkey("Software\\Microsoft\\Windows\\DWM").ok()?;
    let c: u32 = dwm.get_value("ColorizationColor").ok()?;
    Some(format!("#{:06x}", c & 0x00FF_FFFF))
}
#[cfg(not(windows))]
pub(crate) fn read_accent() -> Option<String> {
    None
}

// --- Linux: auto-create a virtual microphone (null-sink + remapped source) ----
// On Windows/macOS this is a no-op (use VB-Cable / BlackHole instead).
#[cfg(target_os = "linux")]
pub(crate) fn setup_virtual_mic() -> Vec<String> {
    use std::process::Command;
    // Reuse if a "Micro" sink already exists (e.g. left over from a crash).
    let exists = Command::new("pactl")
        .args(["list", "short", "sinks"])
        .output()
        .map(|o| String::from_utf8_lossy(&o.stdout).contains("Micro"))
        .unwrap_or(false);
    if exists {
        return Vec::new();
    }
    let load = |args: &[&str]| -> Option<String> {
        let o = Command::new("pactl").args(args).output().ok()?;
        if o.status.success() {
            Some(String::from_utf8_lossy(&o.stdout).trim().to_string())
        } else {
            None
        }
    };
    let mut ids = Vec::new();
    match load(&[
        "load-module",
        "module-null-sink",
        "sink_name=Micro",
        "sink_properties=device.description=Micro",
    ]) {
        Some(id) => ids.push(id),
        None => return ids,
    }
    if let Some(id) = load(&[
        "load-module",
        "module-remap-source",
        "master=Micro.monitor",
        "source_name=MicroMic",
        "source_properties=device.description=Micro",
    ]) {
        ids.push(id);
    }
    ids
}

#[cfg(target_os = "linux")]
pub(crate) fn teardown_virtual_mic(ids: &[String]) {
    use std::process::Command;
    for id in ids.iter().rev() {
        let _ = Command::new("pactl").args(["unload-module", id]).output();
    }
}

#[cfg(not(target_os = "linux"))]
pub(crate) fn setup_virtual_mic() -> Vec<String> {
    Vec::new()
}
#[cfg(not(target_os = "linux"))]
pub(crate) fn teardown_virtual_mic(_ids: &[String]) {}
