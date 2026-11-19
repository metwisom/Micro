//! The tray icon, its menu, and the rule that the main window must always be reachable.
//!
//! The menu labels come from the webview (it owns the translations), so the menu is rebuilt from
//! the frontend rather than composed here.

use tauri::{AppHandle, Emitter, Manager};

use crate::commands::shutdown;

/// The app icon decoded to RGBA8 once (embedded at compile time).
fn app_icon_rgba() -> &'static (Vec<u8>, u32, u32) {
    static ICON: std::sync::OnceLock<(Vec<u8>, u32, u32)> = std::sync::OnceLock::new();
    ICON.get_or_init(|| {
        let bytes: &[u8] = include_bytes!("../icons/64x64.png");
        // EXPAND turns an indexed PNG into RGB(A) and widens sub-8-bit samples; it does NOT turn
        // 8-bit grayscale into RGB, so every colour type the decoder can still hand back is
        // converted explicitly below. Rebranding is a normal thing to do with a public repo, and
        // both GIMP and ImageMagick write grayscale by default for a monochrome source.
        let mut decoder = png::Decoder::new(bytes);
        decoder.set_transformations(png::Transformations::EXPAND | png::Transformations::STRIP_16);
        let mut reader = decoder.read_info().expect("tray icon png");
        let mut buf = vec![0u8; reader.output_buffer_size()];
        let info = reader.next_frame(&mut buf).expect("tray icon frame");
        let (w, h) = (info.width, info.height);
        let n = info.buffer_size();
        let px_count = (w as usize) * (h as usize);
        let expand = |src: &[u8], stride: usize, to_rgba: fn(&[u8]) -> [u8; 4]| {
            let mut o = Vec::with_capacity(px_count * 4);
            for px in src.chunks_exact(stride) {
                o.extend_from_slice(&to_rgba(px));
            }
            o
        };
        let rgba = match info.color_type {
            png::ColorType::Rgba => buf[..n].to_vec(),
            png::ColorType::Rgb => expand(&buf[..n], 3, |p| [p[0], p[1], p[2], 255]),
            png::ColorType::Grayscale => expand(&buf[..n], 1, |p| [p[0], p[0], p[0], 255]),
            png::ColorType::GrayscaleAlpha => expand(&buf[..n], 2, |p| [p[0], p[0], p[0], p[1]]),
            // Indexed is expanded away by EXPAND above, so this is unreachable in practice. Return
            // a correctly sized opaque block rather than a buffer of the wrong length: the caller
            // indexes it by pixel, and a short one would abort the app at the first tray update.
            other => {
                log::warn!("tray icon has an unsupported colour type ({other:?}); drawing it blank");
                vec![0u8; px_count * 4]
            }
        };
        // Not a debug_assert: release is the profile where a short buffer would index out of
        // bounds in `tray_image`, so the check has to exist there too.
        let rgba = if rgba.len() == px_count * 4 {
            rgba
        } else {
            log::error!("tray icon decoded to {} bytes, expected {}", rgba.len(), px_count * 4);
            vec![0u8; px_count * 4]
        };
        (rgba, w, h)
    })
}

/// Tray icon = the app icon with a colored status dot (active / muted / idle) in the corner.
fn tray_image(rgb: [u8; 3]) -> tauri::image::Image<'static> {
    let (base, w, h) = app_icon_rgba();
    let mut rgba = base.clone();
    let (w, h) = (*w, *h);
    let radius = w as f32 * 0.17;
    let cx = w as f32 - radius - 2.0;
    let cy = h as f32 - radius - 2.0;
    for y in 0..h {
        for x in 0..w {
            let dx = x as f32 - cx;
            let dy = y as f32 - cy;
            let dist = (dx * dx + dy * dy).sqrt();
            let i = ((y * w + x) * 4) as usize;
            if dist <= radius {
                rgba[i] = rgb[0];
                rgba[i + 1] = rgb[1];
                rgba[i + 2] = rgb[2];
                rgba[i + 3] = 255;
            } else if dist <= radius + 1.3 {
                // white ring for contrast against the icon
                rgba[i] = 255;
                rgba[i + 1] = 255;
                rgba[i + 2] = 255;
                rgba[i + 3] = 255;
            }
        }
    }
    tauri::image::Image::new_owned(rgba, w, h)
}

#[tauri::command]
pub fn set_tray_icon(app: AppHandle, state: String) {
    let rgb = match state.as_str() {
        "active" => [0x3e, 0xcf, 0x8e],
        "muted" => [0xc4, 0x2b, 0x1c],
        _ => [0x9a, 0xa0, 0xa6],
    };
    if let Some(tray) = app.tray_by_id("main") {
        let _ = tray.set_icon(Some(tray_image(rgb)));
    }
}

#[tauri::command]
pub fn update_tray(
    app: AppHandle,
    show: String,
    quit: String,
    disconnect: Option<String>,
    status: Option<String>,
) -> tauri::Result<()> {
    rebuild_tray_menu(&app, &show, &quit, disconnect.as_deref(), status.as_deref())
}

fn rebuild_tray_menu(
    app: &AppHandle,
    show: &str,
    quit: &str,
    disconnect: Option<&str>,
    status: Option<&str>,
) -> tauri::Result<()> {
    use tauri::menu::{MenuBuilder, MenuItemBuilder};

    let mut b = MenuBuilder::new(app);
    if let Some(s) = status {
        let item = MenuItemBuilder::with_id("status", s).enabled(false).build(app)?;
        b = b.item(&item).separator();
    }
    let show_i = MenuItemBuilder::with_id("show", show).build(app)?;
    b = b.item(&show_i);
    if let Some(d) = disconnect {
        let di = MenuItemBuilder::with_id("disconnect", d).build(app)?;
        b = b.item(&di);
    }
    let quit_i = MenuItemBuilder::with_id("quit", quit).build(app)?;
    b = b.separator().item(&quit_i);

    let menu = b.build()?;
    if let Some(tray) = app.tray_by_id("main") {
        tray.set_menu(Some(menu))?;
    }
    Ok(())
}

/// Show the window and guarantee it is reachable. A window can end up outside every monitor without
/// the webview ever getting a say — the screen it lived on was unplugged, or the OS parked it while
/// minimized — and then "Show" would helpfully raise something nobody can see. Re-centre in that case.
pub(crate) fn show_window(w: &tauri::WebviewWindow) {
    let on_screen = (|| -> tauri::Result<bool> {
        let pos = w.outer_position()?;
        let size = w.outer_size()?;
        Ok(w.available_monitors()?.iter().any(|m| {
            let (mp, ms) = (m.position(), m.size());
            // Require the title bar to be on the monitor and enough width to grab it by.
            let margin = (size.width as i32 / 2).min(80);
            pos.x >= mp.x - margin
                && pos.x <= mp.x + ms.width as i32 - margin
                && pos.y >= mp.y
                && pos.y <= mp.y + ms.height as i32 - 40
        }))
    })()
    .unwrap_or(true); // can't tell (no monitor info) → leave the window where it is

    if !on_screen {
        log::warn!("window was off-screen — re-centering it");
        let _ = w.center();
    }
    let _ = w.unminimize();
    let _ = w.show();
    let _ = w.set_focus();
}

pub(crate) fn build_tray(app: &AppHandle) -> tauri::Result<()> {
    use tauri::menu::{MenuBuilder, MenuItemBuilder};
    use tauri::tray::TrayIconBuilder;

    let show = MenuItemBuilder::with_id("show", "Show").build(app)?;
    let quit = MenuItemBuilder::with_id("quit", "Quit").build(app)?;
    let menu = MenuBuilder::new(app).items(&[&show, &quit]).build()?;

    let mut builder =
        TrayIconBuilder::with_id("main").menu(&menu).on_menu_event(|app, event| match event.id().as_ref() {
            "show" => {
                if let Some(w) = app.get_webview_window("main") {
                    show_window(&w);
                }
            }
            "disconnect" => {
                let _ = app.emit("tray-action", "disconnect");
            }
            "quit" => {
                // Orderly: sealed BYE to the phone, streams closed, Linux virtual mic unloaded —
                // then exit. The HELO counter needs nothing here; it is reserved on disk before use.
                shutdown(app);
                app.exit(0);
            }
            _ => {}
        });
    if let Some(icon) = app.default_window_icon().cloned() {
        builder = builder.icon(icon);
    }
    builder.build(app)?;
    Ok(())
}
