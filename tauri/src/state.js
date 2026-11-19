// Pure UI state derivation — no DOM, no Tauri — so the status logic is unit-testable (see tests/).

/** Estimated mic→virtual-mic latency: one-way network + jitter buffer + ~10 ms capture/codec. */
export function latencyEstimateMs(pingMs, bufferFrames) {
  return Math.round(pingMs / 2 + bufferFrames * 10 + 10);
}

/**
 * Derive what the status line and tray should show for the connected phone.
 *
 * @param {object} s
 * @param {boolean} s.audioOutOk    an output device is open
 * @param {boolean} s.reconnecting  a reconnect timer is pending (net thread failed to start)
 * @param {string|null} s.authState "code" | "bad" | "pending" | "denied" | "busy" | null
 * @param {boolean} s.muted         desktop mute flag (authoritative for local playback)
 * @param {string} s.session        "none" | "up" | "lost"
 * @param {number} s.lastPcm        timestamp of the last audio frame (0 = none)
 * @param {number} s.now            current timestamp
 * @returns {{key: string, cls: string, tray: string, streaming?: boolean}}
 */
export function deriveStatus(s) {
  if (!s.audioOutOk) return { key: "err_no_output", cls: "err", tray: "idle" };
  const trayIdle = s.muted ? "muted" : "idle";
  if (s.reconnecting) return { key: "st_reconnecting", cls: "", tray: trayIdle };
  switch (s.authState) {
    case "denied": return { key: "st_denied", cls: "err", tray: trayIdle };
    case "busy": return { key: "st_busy", cls: "err", tray: trayIdle };
    case "bad": return { key: "bad_code", cls: "err", tray: trayIdle };
    case "pending": return { key: "st_waiting_approve", cls: "", tray: trayIdle };
    case "code": return { key: "st_enter_code", cls: "", tray: trayIdle };
    default: break;
  }
  // An explicit loss signal outranks mute: the phone is gone, muted or not.
  if (s.session === "lost") return { key: "st_lost", cls: "err", tray: trayIdle };
  // Muted → the phone stops sending, so there is no PCM; stay "muted", never fall to "waiting".
  if (s.muted) return { key: "st_muted", cls: "ok", tray: "muted" };
  const flowing = s.session === "up" && s.lastPcm > 0 && s.now - s.lastPcm < 1500;
  if (flowing) return { key: "st_streaming", cls: "ok", tray: "active", streaming: true };
  return { key: "st_waiting_phone", cls: "", tray: "idle" };
}

/**
 * Reduce a backend "auth" event payload onto the current auth sub-state.
 * @param {string|null} current
 * @param {string} payload  "AUTH:DENIED" | "AUTH:PENDING" | "AUTH:BUSY" | "AUTH:APPROVED"
 */
export function reduceAuth(current, payload) {
  const s = String(payload);
  if (s.startsWith("AUTH:DENIED")) return "denied";
  if (s === "AUTH:PENDING") return "pending";
  if (s === "AUTH:BUSY") return "busy";
  if (s === "AUTH:APPROVED") return null;
  return current;
}

/**
 * Is a saved window position still usable — i.e. does it land on a monitor that exists right now?
 *
 * Everything here is in PHYSICAL pixels (what Tauri's onMoved reports and what PhysicalPosition
 * takes), so there is no DPI conversion to get wrong. Guards three real cases:
 *   - Windows parks a minimized window at (-32000, -32000); that must never be restored.
 *   - The monitor a window was last used on is gone (laptop undocked, projector unplugged).
 *   - Garbage in localStorage.
 * `margin` keeps enough of the title bar on screen to stay grabbable with the mouse.
 *
 * @param {{x: number, y: number}} pos       physical top-left of the window
 * @param {Array<{position: {x: number, y: number}, size: {width: number, height: number}}>} monitors
 * @param {number} [margin]
 */
export function isOnScreen(pos, monitors, margin = 80) {
  if (!pos || !Number.isFinite(pos.x) || !Number.isFinite(pos.y)) return false;
  if (!Array.isArray(monitors) || monitors.length === 0) return false;
  return monitors.some((m) => {
    if (!m || !m.position || !m.size) return false;
    const { x: mx, y: my } = m.position;
    const { width: mw, height: mh } = m.size;
    // The title bar must not sit above the monitor, and enough of the window must remain on it.
    return pos.x >= mx - margin && pos.x <= mx + mw - margin &&
           pos.y >= my && pos.y <= my + mh - margin;
  });
}

/**
 * Should the UI follow the phone to a new IP? Only while our session is dead, for the same phone id,
 * and when the address we are bound to has stopped answering discovery (a dual-homed phone answers
 * from both interfaces — that is not a move).
 */
export function shouldFollowIp({ running, session, connectedId, foundId, foundIp, targetHost, targetLastSeen, now, reconnecting }) {
  if (!running || reconnecting || session !== "lost") return false;
  if (!foundId || foundId !== connectedId || foundIp === targetHost) return false;
  return now - (targetLastSeen || 0) > 15000;
}

export const RECONNECT_MIN_MS = 1000;
export const RECONNECT_MAX_MS = 15000;
export const PHONE_NAME_MAX = 64;

/**
 * Exponential backoff for the reconnect timer: doubles each attempt, capped.
 * Pure, so the timing rule is testable without a clock.
 *
 * @param {number} current  the delay just used, in ms
 * @returns {number} the delay to use next
 */
export function nextReconnectDelay(current) {
  const base = Number.isFinite(current) && current > RECONNECT_MIN_MS ? current : RECONNECT_MIN_MS;
  return Math.min(base * 2, RECONNECT_MAX_MS);
}

/**
 * Clean a device name that arrived in an unauthenticated discovery reply, before it is rendered.
 *
 * Any host on the LAN can answer a broadcast with whatever it likes, so this string is
 * attacker-controlled: control characters would garble the row and the tray tooltip, and an
 * over-long name would push the controls out of a window the user cannot resize.
 *
 * @param {unknown} name
 * @returns {string}
 */
export function sanitizePhoneName(name) {
  return String(name == null ? "" : name)
    // Control characters, and also the bidi/format controls: an RTL override in a device name
    // reorders everything after it in the row and the tray tooltip, which is a cheap way to make a
    // hostile entry read like a friendly one.
    .replace(/[\u0000-\u001f\u007f-\u009f\u00ad\u061c\u200b-\u200f\u2028-\u202e\u2066-\u2069\ufeff]/g, " ")
    .slice(0, PHONE_NAME_MAX);
}
