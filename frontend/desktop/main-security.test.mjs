import fs from "node:fs";
import { describe, expect, it } from "vitest";

describe("desktop navigation security", () => {
  const source = fs.readFileSync(new URL("./main.cjs", import.meta.url), "utf8");
  const navigation = fs.readFileSync(new URL("./navigation-security.cjs", import.meta.url), "utf8");

  it("keeps renderer processes isolated and sandboxed", () => {
    expect(source).toContain("contextIsolation: true");
    expect(source).toContain("nodeIntegration: false");
    expect(source).toContain("sandbox: true");
  });

  it("denies popups and navigation outside the configured application origin", () => {
    expect(navigation).toContain('window.webContents.on("will-navigate"');
    expect(navigation).toContain("new URL(targetUrl).origin === trustedAppOrigin");
    expect(navigation).toContain('setWindowOpenHandler(() => ({ action: "deny" }))');
    expect(source).toContain("restrictNavigation(mainWindow, trustedAppOrigin)");
    expect(source).toContain("restrictNavigation(salesDocumentWindow, trustedAppOrigin)");
  });

  it("opens the sales document workspace maximized with its native frame", () => {
    const windowStart = source.indexOf("salesDocumentWindow = new BrowserWindow({");
    const preferencesStart = source.indexOf("webPreferences:", windowStart);
    const windowEnd = source.indexOf("return { ok: true, focused: false };", preferencesStart);

    expect(windowStart).toBeGreaterThan(-1);
    expect(preferencesStart).toBeGreaterThan(windowStart);
    expect(windowEnd).toBeGreaterThan(preferencesStart);
    expect(source.slice(windowStart, preferencesStart)).toContain("fullscreen: false");
    expect(source.slice(windowStart, preferencesStart)).toContain("frame: true");
    expect(source.slice(windowStart, preferencesStart)).toContain("center: true");
    expect(source.slice(preferencesStart, windowEnd)).toContain("salesDocumentWindow.maximize()");
  });

  it("streams RAW printer payloads through stdin instead of the Windows command line", () => {
    expect(source).toContain('[Console]::In.ReadToEnd()');
    expect(source).toContain('child.stdin.end(buffer.toString("base64"))');
    expect(source).not.toContain('Array.from(buffer).join(",")');
  });

  it("replaces an existing encrypted terminal identity on Windows", () => {
    expect(source).toContain("fs.copyFileSync(temporary, target)");
    expect(source).toContain("fs.rmSync(temporary, { force: true })");
    expect(source).not.toContain("fs.renameSync(temporary, target)");
  });
});
