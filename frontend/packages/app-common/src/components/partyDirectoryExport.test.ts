// @vitest-environment jsdom
import { readFile, writeFile } from "node:fs/promises";
import { resolve } from "node:path";
import { strFromU8, unzipSync } from "fflate";
import { afterEach, describe, expect, it, vi } from "vitest";
import { previewExport } from "../export/previewExport";
import { previewPreparedPdf } from "../export/preparedPdfExport";
import { savePartyDirectoryExport, type PartyDirectoryExportReport } from "./partyDirectoryExport";

vi.mock("../export/previewExport", () => ({ previewExport: vi.fn() }));
vi.mock("../export/preparedPdfExport", () => ({ previewPreparedPdf: vi.fn() }));

const previewExportMock = vi.mocked(previewExport);
const previewPreparedPdfMock = vi.mocked(previewPreparedPdf);

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
  vi.resetAllMocks();
  document.body.innerHTML = "";
});

describe("savePartyDirectoryExport", () => {
  it("writes optional QA workbooks from the same report fixtures", async () => {
    if (process.env.WRITE_PARTY_DIRECTORY_FIXTURES !== "1") return;
    const fixtureDirectory = resolve(process.cwd(), "apps/app-gestion/output/third-party-design");
    previewExportMock.mockResolvedValue({ downloaded: false, printed: false, canceled: true });
    for (const name of ["clientes", "proveedores", "socios-vacio"]) {
      const fixture = JSON.parse(await readFile(resolve(fixtureDirectory, `${name}-report.json`), "utf8")) as PartyDirectoryExportReport;
      await savePartyDirectoryExport("xlsx", fixture, `${name}.xlsx`);
      const blob = previewExportMock.mock.calls.at(-1)![0].blob;
      const bytes = await new Promise<Uint8Array>((done, fail) => {
        const reader = new FileReader();
        reader.onload = () => done(new Uint8Array(reader.result as ArrayBuffer));
        reader.onerror = () => fail(reader.error);
        reader.readAsArrayBuffer(blob);
      });
      await writeFile(resolve(fixtureDirectory, `${name}.xlsx`), bytes);
    }
  });
  it("writes an Excel workbook with literal strings, Unicode and frozen headers", async () => {
    previewExportMock.mockResolvedValue({ downloaded: false, printed: false, canceled: true });

    await savePartyDirectoryExport("xlsx", report, "clientes.xlsx");

    expect(previewExportMock).toHaveBeenCalledOnce();
    const request = previewExportMock.mock.calls[0][0];
    expect(request.fileName).toBe("clientes.xlsx");
    expect(request.blob.type).toBe("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
    const bytes = new Uint8Array(await new Promise<ArrayBuffer>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as ArrayBuffer);
      reader.onerror = () => reject(reader.error);
      reader.readAsArrayBuffer(request.blob);
    }));
    const files = unzipSync(bytes);
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
    expect(xml.querySelector('c[r="A4"]')?.getAttribute("s")).toBe("5");
    expect(xml.querySelector('c[r="B2"]')?.textContent).toBe("<script>alert(1)</script>");
    expect(xml.querySelector('c[r="B8"]')?.textContent).toBe("2");
    expect(xml.querySelector("autoFilter")?.getAttribute("ref")).toBe("A4:B6");
    expect(xml.querySelector("pageSetup")?.getAttribute("orientation")).toBe("portrait");
    expect(xml.querySelector('c[r="B6"]')?.getAttribute("s")).toBe("3");
    expect(xml.querySelector('c[r="A8"]')?.getAttribute("s")).toBe("4");
    const styles = strFromU8(files["xl/styles.xml"]);
    expect(styles).toContain('rgb="FF0A2F56"');
    expect(styles).toContain('wrapText="1"');
    expect(styles).toContain('val="14"');
    expect(styles).toContain('val="18"');
    expect(request.formattedWorkbook).toBe(true);
  });

  it("includes actual company and store metadata and paginates wide sheets", async () => {
    previewExportMock.mockResolvedValue({ downloaded: false, printed: false, canceled: true });
    await savePartyDirectoryExport("xlsx", {
      ...report, companyName: "Empresa & Hijos", storeName: "Tienda <Centro>",
      printedAt: "15/11/2026, 11:30",
      columns: Array.from({ length: 7 }, (_, index) => ({ key: String(index), label: `Columna ${index}` }))
    }, "clientes.xlsx");
    const blob = previewExportMock.mock.calls[0][0].blob;
    const buffer = await blob.arrayBuffer();
    const files = unzipSync(new Uint8Array(buffer));
    const sheet = new DOMParser().parseFromString(strFromU8(files["xl/worksheets/sheet1.xml"]), "application/xml");
    expect(sheet.querySelector("parsererror")).toBeNull();
    expect(sheet.querySelector('c[r="B2"]')?.textContent).toBe("Empresa & Hijos");
    expect(sheet.querySelector('c[r="B3"]')?.textContent).toBe("Tienda <Centro>");
    expect(sheet.querySelector('c[r="A4"]')?.textContent).toBe("Fecha de impresión");
    expect(sheet.querySelector('c[r="B4"]')?.textContent).toBe("15/11/2026, 11:30");
    expect(sheet.querySelector("pane")?.getAttribute("ySplit")).toBe("7");
    expect(sheet.querySelector("pageSetup")?.getAttribute("orientation")).toBe("landscape");
    expect(sheet.querySelector("pageSetup")?.getAttribute("fitToHeight")).toBe("0");
    expect(sheet.querySelector("autoFilter")?.getAttribute("ref")).toBe("A7:G9");
    expect(Array.from(sheet.querySelectorAll("mergeCell")).map((cell) => cell.getAttribute("ref")))
      .toEqual(expect.arrayContaining(["A1:G1", "B2:G2", "B3:G3", "B4:G4", "B5:G5", "A11:F11"]));
    expect(sheet.querySelector('c[r="G11"]')?.textContent).toBe("2");
    expect(sheet.querySelector('c[r="A11"]')?.getAttribute("s")).toBe("4");
    expect(sheet.querySelector('c[r="G1"]')?.getAttribute("s")).toBe("1");
    expect(sheet.querySelector('c[r="A2"]')?.getAttribute("s")).toBe("6");
    expect(sheet.querySelector('c[r="B2"]')?.getAttribute("s")).toBe("7");
    expect(strFromU8(files["xl/workbook.xml"])).toContain("'Clientes &amp; proveedores'!$7:$7");
  });

  it("sizes wrapped multiline data rows while retaining the complete literal value", async () => {
    previewExportMock.mockResolvedValue({ downloaded: false, printed: false, canceled: true });
    const long = `${"Texto largo ".repeat(20)}\n中文保留`;
    await savePartyDirectoryExport("xlsx", { ...report, rows: [["C-001", long]] }, "clientes.xlsx");
    const bytes = new Uint8Array(await new Promise<ArrayBuffer>((resolve, reject) => {
      const reader = new FileReader();
      reader.onload = () => resolve(reader.result as ArrayBuffer);
      reader.onerror = () => reject(reader.error);
      reader.readAsArrayBuffer(previewExportMock.mock.calls[0][0].blob);
    }));
    const sheet = new DOMParser().parseFromString(strFromU8(unzipSync(bytes)["xl/worksheets/sheet1.xml"]), "application/xml");
    expect(Number(sheet.querySelector('row[r="5"]')?.getAttribute("ht"))).toBeGreaterThan(45);
    expect(sheet.querySelector('c[r="B5"]')?.textContent).toBe(long);
    expect(sheet.querySelector('c[r="B5"]')?.getAttribute("t")).toBe("inlineStr");
  });

  it("previews the desktop PDF with the report and propagates preview failure", async () => {
    previewPreparedPdfMock.mockResolvedValueOnce({ downloaded: false, printed: false, canceled: true })
      .mockRejectedValueOnce(new Error("Disk full"));
    vi.stubGlobal("tpvDesktop", { reports: {} });

    await expect(savePartyDirectoryExport("pdf", report, "clientes.pdf")).resolves.toBeUndefined();
    expect(previewPreparedPdfMock).toHaveBeenCalledWith("table", { ...report, subject: report.title, reportStyle: "partyDirectory", locale: "es" }, "clientes.pdf", "es");
    await expect(savePartyDirectoryExport("pdf", report, "clientes.pdf")).rejects.toThrow("Disk full");
  });

  it("handles a canceled or failed Excel preview", async () => {
    previewExportMock.mockResolvedValueOnce({ downloaded: false, printed: false, canceled: true })
      .mockRejectedValueOnce(new Error("Disk full"));

    await expect(savePartyDirectoryExport("xlsx", report, "clientes.xlsx")).resolves.toBeUndefined();
    await expect(savePartyDirectoryExport("xlsx", report, "clientes.xlsx")).rejects.toThrow("Disk full");
  });

  it("previews escaped browser HTML and prints only when download is selected", async () => {
    vi.stubGlobal("tpvDesktop", undefined);
    previewExportMock.mockResolvedValue({ downloaded: false, printed: false, canceled: true });
    const print = vi.fn();
    const printed = document.implementation.createHTMLDocument();
    vi.spyOn(HTMLIFrameElement.prototype, "contentDocument", "get").mockReturnValue(printed);
    vi.spyOn(HTMLIFrameElement.prototype, "contentWindow", "get").mockReturnValue({ focus: vi.fn(), print } as unknown as Window);

    await savePartyDirectoryExport("pdf", report, "clientes.pdf");

    expect(previewExportMock).toHaveBeenCalledOnce();
    const request = previewExportMock.mock.calls[0][0];
    expect(request.fileName).toBe("clientes.pdf");
    expect(request.blob.type).toBe("text/html");
    expect(request.html).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(request.html).toContain("#0A2F56");
    expect(request.html).toContain("counter(pages)");
    expect(request.html).not.toContain("<script>");
    expect(print).not.toHaveBeenCalled();
    await request.onDownload?.();
    expect(print).toHaveBeenCalledOnce();
    expect(printed.querySelector("h1")?.textContent).toBe(report.title);
    expect(printed.querySelector("table tbody td")?.textContent).toBe("00123");
    expect(printed.querySelector("script")).toBeNull();
    expect(printed.querySelector("section.filters")?.textContent).toContain("<script>alert(1)</script>");
    expect(document.body.querySelector("table")).toBeNull();
  });
});
