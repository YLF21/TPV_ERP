import fs from "node:fs";
import os from "node:os";
import path from "node:path";
import vm from "node:vm";
import { createRequire } from "node:module";
import { afterEach, describe, expect, it } from "vitest";

const require = createRequire(import.meta.url);
const { createWindowDisplay, displayConfigPath, displayTerminalScope, readDisplayMode, windowedBounds } = require("./window-display.cjs");
const { createPrivilegedIpcRegistrar } = require("./electron-security.cjs");
const temporaryDirectories = [];
const uuid = (n) => `00000000-0000-4000-8000-${String(n).padStart(12, "0")}`;
const identity = (terminal = 1, installation = 10, binding = 20) => ({
  terminalId: uuid(terminal), installationId: uuid(installation), bindingId: uuid(binding), terminalCredential: "secret",
});
const scope = (value = identity(), backend = "https://shop.example:8443") => displayTerminalScope(value, backend);
const userData = () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), "tpv-display-"));
  temporaryDirectories.push(directory);
  return directory;
};
afterEach(() => temporaryDirectories.splice(0).forEach((directory) => fs.rmSync(directory, { recursive: true, force: true })));

function fakeWindow(initialFullScreen = true) {
  return {
    fullScreen: initialFullScreen,
    maximized: false,
    bounds: { x: 70, y: 50, width: 1280, height: 800 },
    isDestroyed() { return false; },
    isFullScreen() { return this.fullScreen; },
    setFullScreen(value) { this.fullScreen = value; },
    isMaximized() { return this.maximized; },
    maximize() { this.maximized = true; },
    unmaximize() { this.maximized = false; },
    getBounds() { return { ...this.bounds }; },
    setBounds(bounds) { this.bounds = { ...bounds }; },
  };
}
const screen = {
  getPrimaryDisplay: () => ({ workArea: { x: 0, y: 0, width: 1600, height: 900 } }),
  getDisplayMatching: () => ({ workArea: { x: 0, y: 0, width: 1600, height: 900 } }),
};

describe("desktop display preference", () => {
  it("isolates terminals and installations while retaining a mode after relinking the same terminal", async () => {
    const directory = userData();
    const firstScope = scope(identity(1, 10, 20));
    const otherTerminal = scope(identity(2, 10, 20));
    const otherInstallation = scope(identity(1, 11, 20));
    const rebound = scope(identity(1, 10, 21));
    expect(firstScope).toMatch(/^[0-9a-f]{64}$/);
    expect(rebound).toBe(firstScope);
    expect(otherTerminal).not.toBe(firstScope);
    expect(otherInstallation).not.toBe(firstScope);
    const controller = createWindowDisplay({ window: fakeWindow(), screen, userDataPath: directory,
      fallbackMode: "FULLSCREEN", terminalScope: firstScope });
    expect(await controller.setMode("WINDOWED")).toEqual({ ok: true, mode: "WINDOWED" });
    expect(readDisplayMode(directory, "FULLSCREEN", rebound)).toBe("WINDOWED");
    expect(readDisplayMode(directory, "FULLSCREEN", otherTerminal)).toBe("FULLSCREEN");
    expect(readDisplayMode(directory, "FULLSCREEN", otherInstallation)).toBe("FULLSCREEN");
    expect(fs.existsSync(path.join(directory, "display-config.json"))).toBe(false);
    const saved = JSON.parse(fs.readFileSync(displayConfigPath(directory, firstScope), "utf8"));
    expect(saved).toEqual({ scope: firstScope, mode: "WINDOWED" });
    fs.writeFileSync(displayConfigPath(directory, firstScope), JSON.stringify({ scope: otherTerminal, mode: "FULLSCREEN" }));
    expect(readDisplayMode(directory, "FULLSCREEN", firstScope)).toBe("FULLSCREEN");
  });

  it("scopes a legacy identity to a canonical backend URL and terminal UUID", () => {
    const legacy = { terminalId: uuid(1), terminalCredential: "secret" };
    expect(scope(legacy, "HTTPS://SHOP.EXAMPLE:443/")).toBe(scope(legacy, "https://shop.example"));
    expect(scope(legacy, "https://other.example")).not.toBe(scope(legacy, "https://shop.example"));
    expect(scope({ terminalId: uuid(2) }, "https://shop.example")).not.toBe(scope(legacy, "https://shop.example"));
    expect(scope(legacy, "https://name:password@shop.example")).toBeUndefined();
  });

  it("disables loading and saving without a valid trusted terminal identity", async () => {
    const directory = userData();
    expect(scope(null)).toBeUndefined();
    expect(scope({ terminalId: "terminal-1", installationId: uuid(10) })).toBeUndefined();
    expect(scope({ terminalId: uuid(1), installationId: "installation" })).toBeUndefined();
    const controller = createWindowDisplay({ window: fakeWindow(), screen, userDataPath: directory,
      fallbackMode: "FULLSCREEN", terminalScope: undefined });
    expect(controller.selectedMode).toBe("FULLSCREEN");
    expect(controller.load()).toMatchObject({ ok: false, code: "DISPLAY_TERMINAL_UNAVAILABLE" });
    expect(await controller.setMode("WINDOWED")).toMatchObject({ ok: false, code: "DISPLAY_TERMINAL_UNAVAILABLE" });
    expect(fs.existsSync(path.join(directory, "display-config"))).toBe(false);
  });

  it("keeps Venta fullscreen and Gestión maximized by default; saved Venta preference wins at restart", async () => {
    const directory = userData();
    const terminalScope = scope();
    fs.writeFileSync(path.join(directory, "display-config.json"), JSON.stringify({ mode: "WINDOWED" }));
    expect(readDisplayMode(directory, "FULLSCREEN", terminalScope)).toBe("FULLSCREEN");
    expect(readDisplayMode(directory, "MAXIMIZED", terminalScope)).toBe("MAXIMIZED");
    const first = createWindowDisplay({ window: fakeWindow(), screen, userDataPath: directory, fallbackMode: "FULLSCREEN", terminalScope });
    expect(await first.setMode("WINDOWED")).toEqual({ ok: true, mode: "WINDOWED" });
    expect(readDisplayMode(directory, "FULLSCREEN", terminalScope)).toBe("WINDOWED");
    const restarted = createWindowDisplay({ window: fakeWindow(false), screen, userDataPath: directory, fallbackMode: "FULLSCREEN", terminalScope });
    expect(restarted.selectedMode).toBe("WINDOWED");
    expect(restarted.load()).toEqual({ ok: true, mode: "WINDOWED" });
  });

  it("transitions both ways, preserves a resized normal window, and clamps it to the work area", async () => {
    const window = fakeWindow();
    const controller = createWindowDisplay({ window, screen, userDataPath: userData(), fallbackMode: "FULLSCREEN", terminalScope: scope() });
    expect(await controller.setMode("WINDOWED")).toEqual({ ok: true, mode: "WINDOWED" });
    expect(window.bounds).toEqual({ x: 160, y: 50, width: 1280, height: 800 });
    expect(window.isMaximized()).toBe(true);
    window.unmaximize();
    window.bounds = { x: 1200, y: 600, width: 1100, height: 760 };
    expect(await controller.setMode("FULLSCREEN")).toEqual({ ok: true, mode: "FULLSCREEN" });
    expect(await controller.setMode("WINDOWED")).toEqual({ ok: true, mode: "WINDOWED" });
    expect(window.bounds).toEqual({ x: 500, y: 140, width: 1100, height: 760 });
    expect(window.isMaximized()).toBe(true);
    expect(windowedBounds({ x: 10, y: 20, width: 900, height: 600 })).toEqual({ x: 10, y: 20, width: 900, height: 600 });
  });

  it("rejects invalid modes without altering the window or preference", async () => {
    const directory = userData();
    const window = fakeWindow();
    const controller = createWindowDisplay({ window, screen, userDataPath: directory, fallbackMode: "FULLSCREEN", terminalScope: scope() });
    expect(await controller.setMode("MAXIMIZED")).toMatchObject({ ok: false, code: "DISPLAY_MODE_INVALID" });
    expect(window.isFullScreen()).toBe(true);
    expect(fs.existsSync(displayConfigPath(directory, scope()))).toBe(false);
  });

  it("rolls back a transition when preference writing fails", async () => {
    const window = fakeWindow();
    const fileSystem = { readFileSync: () => { throw new Error("missing"); }, mkdirSync: () => {}, writeFileSync: () => { throw new Error("disk full"); }, rmSync: () => {} };
    const controller = createWindowDisplay({ window, screen, userDataPath: userData(), fallbackMode: "FULLSCREEN", terminalScope: scope(), fileSystem });
    expect(await controller.setMode("WINDOWED")).toMatchObject({ ok: false, code: "DISPLAY_CHANGE_FAILED" });
    expect(controller.load()).toEqual({ ok: true, mode: "FULLSCREEN" });
  });

  it("restores the actual resized bounds when saving fullscreen fails", async () => {
    const window = fakeWindow(false);
    const actualBounds = { x: 225, y: 105, width: 925, height: 675 };
    window.bounds = { ...actualBounds };
    window.setFullScreen = (value) => {
      window.fullScreen = value;
      window.bounds = value ? { x: 0, y: 0, width: 1600, height: 900 } : { x: 0, y: 0, width: 1280, height: 800 };
    };
    const fileSystem = { readFileSync: () => { throw new Error("missing"); }, mkdirSync: () => {}, writeFileSync: () => { throw new Error("disk full"); }, rmSync: () => {} };
    const controller = createWindowDisplay({ window, screen, userDataPath: userData(), fallbackMode: "WINDOWED", terminalScope: scope(), fileSystem });
    expect(await controller.setMode("FULLSCREEN")).toMatchObject({ ok: false, code: "DISPLAY_CHANGE_FAILED" });
    expect(window.bounds).toEqual(actualBounds);
    expect(controller.load()).toEqual({ ok: true, mode: "WINDOWED" });
  });

  it("rejects simultaneous changes while preserving invalid-mode validation", async () => {
    const window = fakeWindow(false);
    let finishTransition;
    window.once = (_event, callback) => { finishTransition = callback; };
    window.removeListener = () => {};
    const controller = createWindowDisplay({ window, screen, userDataPath: userData(), fallbackMode: "WINDOWED", terminalScope: scope(), platform: "darwin" });
    const first = controller.setMode("FULLSCREEN");
    expect(await controller.setMode("INVALID")).toMatchObject({ ok: false, code: "DISPLAY_MODE_INVALID" });
    expect(await controller.setMode("WINDOWED")).toMatchObject({ ok: false, code: "DISPLAY_BUSY" });
    finishTransition();
    expect(await first).toEqual({ ok: true, mode: "FULLSCREEN" });
    expect(await controller.setMode("FULLSCREEN")).toEqual({ ok: true, mode: "FULLSCREEN" });
  });

  it("starts a saved windowed Venta maximized after ready-to-show", () => {
    const directory = userData();
    const terminalScope = scope();
    fs.mkdirSync(path.dirname(displayConfigPath(directory, terminalScope)), { recursive: true });
    fs.writeFileSync(displayConfigPath(directory, terminalScope), JSON.stringify({ scope: terminalScope, mode: "WINDOWED" }));
    const source = fs.readFileSync(new URL("./main.cjs", import.meta.url), "utf8");
    const start = source.indexOf("function createWindow() {");
    const end = source.indexOf("\nfunction createSalesDocumentWindow(", start);
    let options;
    let readyToShow;
    let maximized = false;
    let shown = false;
    const context = vm.createContext({
      desktopAppConfig: { key: "venta" }, mainWindowMode: "MAXIMIZED", desktopAppIcon: "icon.ico",
      appName: "Venta", appUrl: "http://localhost:5173/", trustedAppOrigin: "http://localhost:5173",
      linkingStorage: { read: () => ({ identity: identity() }) }, runtimeBackendUrl: "https://shop.example:8443",
      process: { env: {} }, displayTerminalScope,
      app: { getPath: () => directory }, Menu: { setApplicationMenu: () => {} },
      screen, readDisplayMode, windowedBounds, createWindowDisplay,
      path, __dirname: "desktop", restrictNavigation: () => {},
      BrowserWindow: class {
        constructor(input) { options = input; }
        isDestroyed() { return false; }
        isFullScreen() { return options.fullscreen; }
        maximize() { maximized = true; }
        show() { shown = true; }
        getBounds() { return { x: options.x, y: options.y, width: options.width, height: options.height }; }
        loadURL() {}
        once(event, callback) { if (event === "ready-to-show") readyToShow = callback; }
      },
    });
    vm.runInContext(source.slice(start, end), context);
    context.createWindow();
    expect(options).toMatchObject({ fullscreen: false, show: false, frame: true, width: 1280, height: 800 });
    expect(maximized).toBe(false);
    readyToShow();
    expect(maximized).toBe(true);
    expect(shown).toBe(true);
  });

  it("keeps Gestión maximized with its original window options despite a saved Venta preference", () => {
    const directory = userData();
    fs.writeFileSync(path.join(directory, "display-config.json"), JSON.stringify({ mode: "WINDOWED" }));
    const source = fs.readFileSync(new URL("./main.cjs", import.meta.url), "utf8");
    const start = source.indexOf("function createWindow() {");
    const end = source.indexOf("\nfunction createSalesDocumentWindow(", start);
    let options;
    let readyToShow;
    let maximized = false;
    let shown = false;
    const context = vm.createContext({
      desktopAppConfig: { key: "gestion" }, mainWindowMode: "MAXIMIZED", desktopAppIcon: "icon.ico",
      appName: "Gestión", appUrl: "http://localhost:5173/", trustedAppOrigin: "http://localhost:5173",
      displayTerminalScope, linkingStorage: undefined, runtimeBackendUrl: undefined, process: { env: {} },
      app: { getPath: () => directory }, Menu: { setApplicationMenu: () => {} },
      screen, readDisplayMode, windowedBounds, createWindowDisplay,
      path, __dirname: "desktop", restrictNavigation: () => {},
      BrowserWindow: class {
        constructor(input) { options = input; }
        isDestroyed() { return false; }
        maximize() { maximized = true; }
        show() { shown = true; }
        loadURL() {}
        once(event, callback) { if (event === "ready-to-show") readyToShow = callback; }
      },
    });
    vm.runInContext(source.slice(start, end), context);
    context.createWindow();
    expect(options).toMatchObject({ fullscreen: false, show: false, frame: true });
    for (const key of ["width", "height", "x", "y"]) expect(options).not.toHaveProperty(key);
    expect(maximized).toBe(false);
    readyToShow();
    expect(maximized).toBe(true);
    expect(shown).toBe(true);
  });

  it("does not save a mode when the native transition fails", async () => {
    const directory = userData();
    const window = fakeWindow();
    window.setFullScreen = () => { throw new Error("native transition failed"); };
    const controller = createWindowDisplay({ window, screen, userDataPath: directory, fallbackMode: "FULLSCREEN", terminalScope: scope() });
    expect(await controller.setMode("WINDOWED")).toMatchObject({ ok: false, code: "DISPLAY_CHANGE_FAILED" });
    expect(controller.load()).toEqual({ ok: true, mode: "FULLSCREEN" });
    expect(fs.existsSync(displayConfigPath(directory, scope()))).toBe(false);
  });

  it("authorizes display IPC only for the primary window and its trusted main frame", async () => {
    const mainSource = fs.readFileSync(new URL("./main.cjs", import.meta.url), "utf8");
    expect(mainSource).toContain('registerIpc("tpv:display:load", () => mainWindowDisplay?.load()');
    expect(mainSource).toContain('registerIpc("tpv:display:set-mode", (_event, mode) => mainWindowDisplay?.setMode(mode)');
    const handlers = new Map();
    const primary = { webContents: { mainFrame: { url: "http://localhost:5173/" } } };
    const secondary = { webContents: { mainFrame: { url: "http://localhost:5173/" } } };
    const register = createPrivilegedIpcRegistrar({
      ipcMain: { handle: (name, handler) => handlers.set(name, handler) },
      getTrustedOrigin: () => "http://localhost:5173",
    });
    register("tpv:display:load", () => primary, () => ({ ok: true, mode: "FULLSCREEN" }));
    expect(await handlers.get("tpv:display:load")({ sender: primary.webContents, senderFrame: primary.webContents.mainFrame }))
      .toEqual({ ok: true, mode: "FULLSCREEN" });
    expect(await handlers.get("tpv:display:load")({ sender: secondary.webContents, senderFrame: secondary.webContents.mainFrame }))
      .toMatchObject({ ok: false, code: "IPC_UNAUTHORIZED" });
  });
});
