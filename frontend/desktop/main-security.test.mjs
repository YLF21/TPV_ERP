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

  it("opens the sales document workspace in fullscreen mode", () => {
    const windowStart = source.indexOf("salesDocumentWindow = new BrowserWindow({");
    const preferencesStart = source.indexOf("webPreferences:", windowStart);

    expect(windowStart).toBeGreaterThan(-1);
    expect(preferencesStart).toBeGreaterThan(windowStart);
    expect(source.slice(windowStart, preferencesStart)).toContain("fullscreen: true");
  });
});
