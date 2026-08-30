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

  it("uses ProgramData configuration for packaged backend selection", () => {
    expect(source).toContain("app.isPackaged === true");
    expect(source).toContain("productionBackendConfigPath()");
    expect(source).toContain("envValue: isPackaged ? undefined : process.env.TPV_DESKTOP_BACKEND_URL");
  });

  it("denies popups and navigation outside the configured application origin", () => {
    expect(navigation).toContain('window.webContents.on("will-navigate"');
    expect(navigation).toContain("new URL(targetUrl).origin === trustedAppOrigin");
    expect(navigation).toContain('setWindowOpenHandler(() => ({ action: "deny" }))');
    expect(source).toContain("restrictNavigation(mainWindow, trustedAppOrigin)");
    expect(source).toContain("restrictNavigation(salesDocumentWindow, trustedAppOrigin)");
  });

  it("keeps secondary IPC access limited to the channels they need", () => {
    expect(source).toContain('registerIpc("tpv:hardware:get-config", () => readHardwareConfig(), mainAndSalesWindows)');
    expect(source).toContain('registerIpc("tpv:hardware:print-ticket", (_event, ticket, config) => printTicket(ticket, config), mainAndSalesDocument)');
    expect(source).toContain('exportTicketPdf(ticket, defaultFileName), mainAndSalesDocument');
    expect(source).toContain('exportA4DocumentPdf(document, defaultFileName), mainAndSalesDocument');
    expect(source).toContain('registerIpc("tpv:hardware:print-a4-document", (_event, document, config) => printA4Document(document, config), mainAndSalesDocument)');
    expect(source).toContain('registerIpc("tpv:hardware:print-product-label", (_event, request, config) => printProductLabel(request, config), mainAndSalesUtility)');
    expect(source).toContain('exportProductLabelPdf(request, defaultFileName), mainAndSalesUtility');
    const saveStart = source.indexOf('registerIpc("tpv:hardware:save-config"');
    const saveEnd = source.indexOf('registerIpc("tpv:hardware:print-ticket"');
    const saveConfig = source.slice(saveStart, saveEnd);
    const drawerStart = source.indexOf('registerIpc("tpv:hardware:open-cash-drawer"');
    const drawerEnd = source.indexOf('registerIpc("tpv:hardware:test-scanner-input"');
    const drawer = source.slice(drawerStart, drawerEnd);
    expect(saveConfig).not.toContain("mainAndSales");
    expect(drawer).not.toContain("mainAndSales");
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
