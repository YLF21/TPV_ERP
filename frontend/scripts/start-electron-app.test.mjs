import { describe, expect, it } from "vitest";
import { spawnSync } from "node:child_process";
import { mkdtempSync, realpathSync, rmdirSync, symlinkSync, unlinkSync } from "node:fs";
import { tmpdir } from "node:os";
import { dirname, join } from "node:path";
import { fileURLToPath } from "node:url";
import { resolveDesktopWindowMode, resolveViteStartup } from "./start-electron-app.mjs";

describe("CLI entrypoint", () => {
  it("runs the launcher through a linked directory instead of exiting silently", () => {
    const temporaryDirectory = mkdtempSync(join(tmpdir(), "espos-launcher-"));
    const linkedDirectory = join(temporaryDirectory, "scripts");
    try {
      symlinkSync(realpathSync(dirname(fileURLToPath(import.meta.url))), linkedDirectory,
        process.platform === "win32" ? "junction" : "dir");
      const result = spawnSync(process.execPath,
        [join(linkedDirectory, "start-electron-app.mjs"), "unknown-app"],
        { encoding: "utf8", timeout: 10000 });
      expect(result.error).toBeUndefined();
      expect(result.status).toBe(1);
      expect(result.stderr).toContain("Uso: node scripts/start-electron-app.mjs venta|gestion");
    } finally {
      unlinkSync(linkedDirectory);
      rmdirSync(temporaryDirectory);
    }
  });
});

describe("resolveViteStartup", () => {
  it("reuses an existing Vite server for Electron", () => {
    expect(resolveViteStartup(true)).toEqual({
      shouldStartVite: false,
      ownsViteProcess: false
    });
  });

  it("starts Vite when no server is running", () => {
    expect(resolveViteStartup(false)).toEqual({
      shouldStartVite: true,
      ownsViteProcess: true
    });
  });
});

describe("resolveDesktopWindowMode", () => {
  it("opens APP GESTION as a bordered maximized window", () => {
    expect(resolveDesktopWindowMode("gestion")).toBe("MAXIMIZED");
  });

  it("keeps APP VENTA in fullscreen mode", () => {
    expect(resolveDesktopWindowMode("venta")).toBe("FULLSCREEN");
  });
});
