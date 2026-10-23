// @vitest-environment jsdom
import { strFromU8, unzipSync } from "fflate";
import { afterEach, describe, expect, it, vi } from "vitest";
import { savePartyDirectoryExport, type PartyDirectoryExportReport } from "./partyDirectoryExport";

const report: PartyDirectoryExportReport = {
  title: "Clientes & proveedores",
  columns: [{ key: "code", label: "Código" }, { key: "name", label: "Razón social" }],
  rows: [["00123", "Árbol <Servicios>"], ["=2+2", "Málaga & 中文"]],
  filters: [{ label: "Búsqueda", value: "<script>alert(1)</script>" }],
  totals: [{ label: "Total", value: "2" }]
};

afterEach(() => {
  vi.unstubAllGlobals();
  vi.restoreAllMocks();
  document.body.innerHTML = "";
});

describe("savePartyDirectoryExport", () => {
  it("writes an Excel workbook with literal strings, Unicode and frozen headers", async () => {
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    vi.stubGlobal("tpvDesktop", { reports: { saveFile } });

    await savePartyDirectoryExport("xlsx", report, "clientes.xlsx");

    expect(saveFile).toHaveBeenCalledOnce();
    const request = saveFile.mock.calls[0][0];
    expect(request.defaultFileName).toBe("clientes.xlsx");
    expect(request.filters).toEqual([{ name: "Excel", extensions: ["xlsx"] }]);
    const files = unzipSync(request.bytes);
    expect(Object.keys(files)).toEqual(expect.arrayContaining([
      "[Content_Types].xml", "xl/workbook.xml", "xl/styles.xml", "xl/worksheets/sheet1.xml"
    ]));
    const sheet = strFromU8(files["xl/worksheets/sheet1.xml"]);
    const workbook = new DOMParser().parseFromString(strFromU8(files["xl/workbook.xml"]), "application/xml");
    expect(workbook.querySelector("sheet")?.getAttribute("name")).toBe(report.title);
    const xml = new DOMParser().parseFromString(sheet, "application/xml");
    expect(xml.querySelector("parsererror")).toBeNull();
    expect(xml.querySelector('c[r="A5"]')?.textContent).toBe("00123");
    expect(xml.querySelector('c[r="B5"]')?.textContent).toBe("Árbol <Servicios>");
    expect(xml.querySelector('c[r="A6"]')?.textContent).toBe("=2+2");
    expect(xml.querySelector('c[r="B6"]')?.textContent).toBe("Málaga & 中文");
    expect(xml.querySelector('c[r="A6"]')?.getAttribute("t")).toBe("inlineStr");
    expect(sheet).not.toContain("<f>");
    expect(xml.querySelector("pane")?.getAttribute("ySplit")).toBe("4");
    expect(xml.querySelector('c[r="A4"]')?.getAttribute("s")).toBe("1");
    expect(xml.querySelector('c[r="B2"]')?.textContent).toBe("<script>alert(1)</script>");
    expect(xml.querySelector('c[r="B8"]')?.textContent).toBe("2");
  });

  it("passes only the report to desktop PDF and handles failure or cancellation", async () => {
    const exportTablePdf = vi.fn().mockResolvedValueOnce({ ok: true, canceled: true })
      .mockResolvedValueOnce({ ok: false, code: "SAVE_FAILED", message: "Disk full" });
    vi.stubGlobal("tpvDesktop", { reports: { exportTablePdf } });

    await expect(savePartyDirectoryExport("pdf", report, "clientes.pdf")).resolves.toBeUndefined();
    expect(exportTablePdf).toHaveBeenCalledWith({ ...report, subject: report.title }, "clientes.pdf");
    await expect(savePartyDirectoryExport("pdf", report, "clientes.pdf")).rejects.toThrow("Disk full");
  });

  it("handles a canceled or failed desktop Excel save", async () => {
    const saveFile = vi.fn().mockResolvedValueOnce({ ok: true, canceled: true })
      .mockResolvedValueOnce({ ok: false, code: "SAVE_FAILED", message: "Disk full" });
    vi.stubGlobal("tpvDesktop", { reports: { saveFile } });

    await expect(savePartyDirectoryExport("xlsx", report, "clientes.xlsx")).resolves.toBeUndefined();
    await expect(savePartyDirectoryExport("xlsx", report, "clientes.xlsx")).rejects.toThrow("Disk full");
  });

  it("prints an isolated, escaped report in a browser", async () => {
    vi.stubGlobal("tpvDesktop", undefined);
    const print = vi.fn();
    const printed = document.implementation.createHTMLDocument();
    vi.spyOn(HTMLIFrameElement.prototype, "contentDocument", "get").mockReturnValue(printed);
    vi.spyOn(HTMLIFrameElement.prototype, "contentWindow", "get").mockReturnValue({ focus: vi.fn(), print } as unknown as Window);

    await savePartyDirectoryExport("pdf", report, "clientes.pdf");

    expect(print).toHaveBeenCalledOnce();
    expect(printed.querySelector("h1")?.textContent).toBe(report.title);
    expect(printed.querySelector("table tbody td")?.textContent).toBe("00123");
    expect(printed.querySelector("script")).toBeNull();
    expect(printed.querySelector("section")?.textContent).toContain("<script>alert(1)</script>");
    expect(document.body.querySelector("table")).toBeNull();
  });
});
