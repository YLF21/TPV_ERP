const { lineDisplayScrollLength } = require("./customer-line-display.cjs");

function selectCustomerDisplay(displays, primaryId, selectedId, operatorId = primaryId) {
  const configured = String(selectedId || "");
  const target = configured
    ? displays.find((display) => String(display.id) === configured)
    : displays.find((display) => display.id !== primaryId && display.id !== operatorId);
  if (!target || target.id === primaryId || target.id === operatorId) {
    throw new Error("No hay una pantalla secundaria disponible para el cliente");
  }
  return target;
}

function createCustomerDisplayController({ BrowserWindow, screen, renderHtml, sendLineDisplay, esposLogo, icon,
  getOperatorDisplayId = () => screen.getPrimaryDisplay().id, windowed = false }) {
  let window;
  let activeConfig;
  let activeMode;
  let targetId;
  let observingDisplays = false;
  let nameScrollTimer;
  let nameScrollGeneration = 0;
  let queue = Promise.resolve();
  const serialize = (operation) => {
    const result = queue.then(operation);
    queue = result.catch(() => {});
    return result;
  };
  const mode = (config) => config?.customerDisplayMode === "LINE_DISPLAY" ? "LINE_DISPLAY" : "DIGITAL_SCREEN";

  function stopNameScroll() {
    nameScrollGeneration += 1;
    clearTimeout(nameScrollTimer);
    nameScrollTimer = undefined;
  }

  function scheduleNameScroll(config, state, lastOffset, generation, offset = 1, delay = 1500) {
    nameScrollTimer = setTimeout(() => {
      nameScrollTimer = undefined;
      void serialize(async () => {
        if (generation !== nameScrollGeneration || activeMode !== "LINE_DISPLAY") return;
        await sendLineDisplay(config, state, { nameOffset: offset, firstLineOnly: true });
        if (generation !== nameScrollGeneration || activeMode !== "LINE_DISPLAY") return;
        const nextOffset = offset >= lastOffset ? 0 : offset + 1;
        scheduleNameScroll(config, state, lastOffset, generation, nextOffset,
          offset === 0 || offset === lastOffset ? 1500 : 500);
      }).catch(() => {
        if (generation === nameScrollGeneration) stopNameScroll();
      });
    }, delay);
    nameScrollTimer.unref?.();
  }

  function destination(config) {
    const displays = screen.getAllDisplays();
    const primaryId = screen.getPrimaryDisplay().id;
    const operatorId = getOperatorDisplayId();
    if (!windowed) return selectCustomerDisplay(displays, primaryId, config.customerDisplayScreenId, operatorId);
    const selectedId = String(config.customerDisplayScreenId || "");
    const target = selectedId ? displays.find(display => String(display.id) === selectedId)
      : displays.find(display => display.id !== primaryId && display.id !== operatorId)
        ?? screen.getPrimaryDisplay();
    if (!target) throw new Error("La pantalla seleccionada no está disponible");
    return target;
  }

  function initialWindowBounds(target) {
    const area = target.workArea ?? target.bounds;
    const width = Math.min(1100, Math.max(320, area.width - 80));
    const height = Math.min(700, Math.max(240, area.height - 80));
    return { x: area.x + Math.round((area.width - width) / 2),
      y: area.y + Math.round((area.height - height) / 2), width, height };
  }

  function checkDisplays() {
    if (!window || window.isDestroyed() || activeMode !== "DIGITAL_SCREEN") return;
    if (windowed) return; // A movable preview must keep the size and position chosen by the operator.
    try {
      const target = selectCustomerDisplay(screen.getAllDisplays(), screen.getPrimaryDisplay().id,
        String(targetId), getOperatorDisplayId());
      window.setBounds(target.bounds);
    } catch {
      // Close synchronously: Windows can otherwise move the client view onto the cashier screen.
      window.destroy();
      window = undefined;
      activeConfig = undefined;
      activeMode = undefined;
      targetId = undefined;
    }
  }
  function start() {
    if (observingDisplays) return;
    if (typeof screen.on === "function") {
      screen.on("display-removed", checkDisplays);
      screen.on("display-metrics-changed", checkDisplays);
    }
    observingDisplays = true;
  }

  async function closeActive() {
    stopNameScroll();
    const previousConfig = activeConfig;
    const previousMode = activeMode;
    if (window && !window.isDestroyed()) window.destroy();
    window = undefined;
    activeConfig = undefined;
    activeMode = undefined;
    targetId = undefined;
    if (previousMode === "LINE_DISPLAY" && previousConfig) {
      await sendLineDisplay(previousConfig, { line1: "", line2: "", phase: "IDLE" });
    }
  }

  async function show(config, state) {
    stopNameScroll();
    const nextMode = mode(config);
    if (activeMode && activeMode !== nextMode) {
      try { await closeActive(); } catch { /* A disconnected old device must not block the new one. */ }
    }
    activeConfig = { ...config };
    activeMode = nextMode;
    if (nextMode === "LINE_DISPLAY") {
      const generation = nameScrollGeneration;
      await sendLineDisplay(config, state);
      const lastOffset = lineDisplayScrollLength(state, config);
      if (lastOffset > 0 && generation === nameScrollGeneration) {
        scheduleNameScroll(config, state, lastOffset, generation);
      }
      return { ok: true };
    }
    let target;
    try {
      target = destination(config);
    } catch (error) {
      await closeActive();
      throw error;
    }
    if (window && !window.isDestroyed()) {
      if (!windowed && targetId !== target.id) window.setBounds(target.bounds);
      targetId = target.id;
      await window.webContents.executeJavaScript(
        `window.customerDisplayUpdate(${JSON.stringify(state ?? {}).replaceAll("\u2028", "\\u2028").replaceAll("\u2029", "\\u2029")})`);
      return { ok: true };
    }
    window = new BrowserWindow({
      icon, ...(windowed ? initialWindowBounds(target) : target.bounds),
      title: "esPOS · Visor de cliente", show: false, frame: windowed, fullscreen: !windowed,
      resizable: windowed, ...(windowed ? { minWidth: 320, minHeight: 240 } : {}),
      autoHideMenuBar: true, skipTaskbar: !windowed, focusable: windowed, backgroundColor: "#ffffff",
      webPreferences: { contextIsolation: true, nodeIntegration: false, sandbox: true },
    });
    const created = window;
    targetId = target.id;
    created.webContents.setWindowOpenHandler(() => ({ action: "deny" }));
    created.webContents.on("will-navigate", (event) => event.preventDefault());
    if (windowed) created.on("page-title-updated", (event) => event.preventDefault());
    created.on("closed", () => { if (window === created) window = undefined; });
    try {
      await created.loadURL(`data:text/html;charset=utf-8,${encodeURIComponent(renderHtml(state, { esposLogo }))}`);
      if (!created.isDestroyed()) {
        if (windowed) created.show();
        else created.showInactive();
      }
    } catch (error) {
      await closeActive();
      throw error;
    }
    return { ok: true };
  }

  return {
    start,
    open: (config, state) => serialize(() => show(config, state)),
    update: (config, state) => serialize(() => {
      const effective = activeConfig ?? config;
      if (!activeConfig && !config?.customerDisplayEnabled) return { ok: true };
      return show(effective, state);
    }),
    close: () => {
      stopNameScroll();
      return serialize(async () => { await closeActive(); return { ok: true }; });
    },
    configurationChanged: (config) => {
      stopNameScroll();
      return serialize(async () => {
        if (windowed && config.customerDisplayEnabled && mode(config) === "DIGITAL_SCREEN"
          && activeMode === "DIGITAL_SCREEN" && window && !window.isDestroyed()) {
          return show(config, { line1: config.customerDisplayIdleLine1, line2: config.customerDisplayIdleLine2, phase: "IDLE" });
        }
        try { await closeActive(); } catch { /* A disconnected device must not prevent disabling it. */ }
        if (config.customerDisplayEnabled) {
          return show(config, { line1: config.customerDisplayIdleLine1, line2: config.customerDisplayIdleLine2, phase: "IDLE" });
        }
        return { ok: true };
      });
    },
    checkDisplays,
  };
}

module.exports = { selectCustomerDisplay, createCustomerDisplayController };
