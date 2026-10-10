const fs = require("node:fs");
const path = require("node:path");
const { createHash } = require("node:crypto");

const MODES = new Set(["FULLSCREEN", "WINDOWED"]);
const UUID = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;
const SCOPE = /^[0-9a-f]{64}$/;

function displayTerminalScope(identity, backendScope) {
  if (!identity || typeof identity.terminalId !== "string" || !UUID.test(identity.terminalId)) return undefined;
  let installation;
  if (identity.installationId != null) {
    if (typeof identity.installationId !== "string" || !UUID.test(identity.installationId)) return undefined;
    installation = `installation:${identity.installationId.toLowerCase()}`;
  } else {
    try {
      const backend = new URL(backendScope);
      if (!["http:", "https:"].includes(backend.protocol) || backend.username || backend.password
          || backend.search || backend.hash) return undefined;
      const pathname = backend.pathname.replace(/\/+$/, "");
      installation = `backend:${backend.origin}${pathname}`;
    } catch { return undefined; }
  }
  return createHash("sha256").update(`display:v1:${installation}:terminal:${identity.terminalId.toLowerCase()}`).digest("hex");
}

function displayConfigPath(userDataPath, terminalScope) {
  if (typeof terminalScope !== "string" || !SCOPE.test(terminalScope)) throw new Error("DISPLAY_TERMINAL_UNAVAILABLE");
  return path.join(userDataPath, "display-config", `${terminalScope}.json`);
}

function readDisplayMode(userDataPath, fallbackMode, terminalScope, fileSystem = fs) {
  if (typeof terminalScope !== "string" || !SCOPE.test(terminalScope)) return fallbackMode;
  try {
    const saved = JSON.parse(fileSystem.readFileSync(displayConfigPath(userDataPath, terminalScope), "utf8"));
    if (saved?.scope === terminalScope && MODES.has(saved?.mode)) return saved.mode;
  } catch {
    // A missing or damaged preference must not prevent the application from starting.
  }
  return fallbackMode;
}

function writeDisplayMode(userDataPath, mode, terminalScope, fileSystem = fs) {
  const target = displayConfigPath(userDataPath, terminalScope);
  const temporary = `${target}.${process.pid}.${Date.now()}.tmp`;
  try {
    fileSystem.mkdirSync(path.dirname(target), { recursive: true });
    fileSystem.writeFileSync(temporary, JSON.stringify({ scope: terminalScope, mode }), { encoding: "utf8", flag: "wx" });
    fileSystem.renameSync(temporary, target);
  } finally {
    try { fileSystem.rmSync(temporary, { force: true }); } catch { /* keep the original error */ }
  }
}

function windowedBounds(workArea, previous) {
  const width = Math.min(Math.max(1, previous?.width || 1280), workArea.width);
  const height = Math.min(Math.max(1, previous?.height || 800), workArea.height);
  const x = previous?.x == null ? workArea.x + Math.floor((workArea.width - width) / 2)
    : Math.max(workArea.x, Math.min(previous.x, workArea.x + workArea.width - width));
  const y = previous?.y == null ? workArea.y + Math.floor((workArea.height - height) / 2)
    : Math.max(workArea.y, Math.min(previous.y, workArea.y + workArea.height - height));
  return { x, y, width, height };
}

function createWindowDisplay({ window, screen, userDataPath, fallbackMode, terminalScope, fileSystem = fs, platform = process.platform }) {
  let normalBounds = windowedBounds(screen.getPrimaryDisplay().workArea);
  let transitioning = false;
  const selectedMode = readDisplayMode(userDataPath, fallbackMode, terminalScope, fileSystem);
  const currentMode = () => window.isFullScreen() ? "FULLSCREEN" : "WINDOWED";
  const workArea = () => screen.getDisplayMatching(window.getBounds()).workArea;

  // Electron's macOS full-screen transition completes on a later event.
  function changeFullScreen(value) {
    if (window.isFullScreen() === value) return Promise.resolve();
    if (platform !== "darwin") {
      window.setFullScreen(value);
      if (window.isFullScreen() !== value) throw new Error("La ventana no cambió de modo");
      return Promise.resolve();
    }
    return new Promise((resolve, reject) => {
      const event = value ? "enter-full-screen" : "leave-full-screen";
      const timer = setTimeout(() => {
        window.removeListener(event, done);
        reject(new Error("Tiempo agotado al cambiar el modo de ventana"));
      }, 5000);
      function done() { clearTimeout(timer); resolve(); }
      window.once(event, done);
      try { window.setFullScreen(value); }
      catch (error) { clearTimeout(timer); window.removeListener(event, done); reject(error); }
    });
  }

  function load() {
    if (typeof terminalScope !== "string" || !SCOPE.test(terminalScope)) return { ok: false, code: "DISPLAY_TERMINAL_UNAVAILABLE", message: "La identidad de terminal no está disponible" };
    if (window.isDestroyed()) return { ok: false, code: "DISPLAY_WINDOW_UNAVAILABLE", message: "La ventana no está disponible" };
    return { ok: true, mode: currentMode() };
  }

  async function setMode(mode) {
    if (!MODES.has(mode)) return { ok: false, code: "DISPLAY_MODE_INVALID", message: "Modo de ventana no válido" };
    if (typeof terminalScope !== "string" || !SCOPE.test(terminalScope)) return load();
    if (window.isDestroyed()) return load();
    if (transitioning) return { ok: false, code: "DISPLAY_BUSY", message: "Hay otro cambio de modo en curso" };
    transitioning = true;
    const before = currentMode();
    const oldBounds = { ...normalBounds };
    const wasMaximized = window.isMaximized();
    const previousWindowBounds = !wasMaximized && before === "WINDOWED" ? window.getBounds() : undefined;
    try {
      if (mode === "FULLSCREEN" && before !== mode) {
        if (!wasMaximized) normalBounds = window.getBounds();
        await changeFullScreen(true);
      } else if (mode === "WINDOWED" && before !== mode) {
        await changeFullScreen(false);
        window.setBounds(windowedBounds(workArea(), normalBounds));
        window.maximize();
      }
      writeDisplayMode(userDataPath, mode, terminalScope, fileSystem);
      return { ok: true, mode: currentMode() };
    } catch {
      try {
        if (window.isFullScreen() !== (before === "FULLSCREEN")) await changeFullScreen(before === "FULLSCREEN");
        if (wasMaximized) window.maximize();
        else if (!window.isFullScreen()) window.setBounds(previousWindowBounds ?? oldBounds);
        normalBounds = oldBounds;
      } catch { /* Return the actual state if rollback itself fails. */ }
      return { ok: false, code: "DISPLAY_CHANGE_FAILED", message: "No se pudo cambiar o guardar el modo de ventana" };
    } finally {
      transitioning = false;
    }
  }

  return { selectedMode, initialBounds: normalBounds, load, setMode };
}

module.exports = { createWindowDisplay, displayConfigPath, displayTerminalScope, readDisplayMode, windowedBounds };
