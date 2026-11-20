// @vitest-environment jsdom
import { afterEach, beforeAll, beforeEach, describe, expect, it, vi } from "vitest";
import "@testing-library/jest-dom/vitest";
import { act, fireEvent, screen, waitFor } from "@testing-library/react";
import readXlsxFile from "read-excel-file/browser";
import { zipSync, strToU8 } from "fflate";
import { previewExport } from "./previewExport";
import { escapeHtml, parseCsv, readWorkbookPreview, workbookPrintHtml, workbookRowCells } from "./workbookPreview";

const pdfMock = vi.hoisted(() => ({ getPage: vi.fn(), destroy: vi.fn(), numPages: 3 }));
vi.mock("pdfjs-dist", () => ({
  GlobalWorkerOptions: { workerSrc: "" },
  getDocument: vi.fn(() => ({ promise: Promise.resolve({ numPages: pdfMock.numPages, getPage: pdfMock.getPage }), destroy: pdfMock.destroy }))
}));
vi.mock("pdfjs-dist/build/pdf.worker.min.mjs?url", () => ({ default: "pdf-worker.js" }));

vi.mock("read-excel-file/browser", () => ({
  default: vi.fn().mockResolvedValue([
    { sheet: "First", data: Array.from({ length: 52 }, (_, index) => [`Row ${index + 1}`, index]) },
    { sheet: "Second", data: [["Other sheet", "<script>alert(1)</script>"]] }
  ])
}));

// Load the lazy dialog before timing interaction tests; its first transform is
// substantially slower on cold CI workers than subsequent preview openings.
beforeAll(async () => { await import("./ExportPreviewDialog"); });

beforeEach(() => {
  pdfMock.numPages = 3;
  pdfMock.getPage.mockReset();
  pdfMock.destroy.mockReset();
  HTMLDialogElement.prototype.showModal = function () { this.setAttribute("open", ""); };
  HTMLDialogElement.prototype.close = function () { this.removeAttribute("open"); };
});
afterEach(() => { vi.restoreAllMocks(); delete window.tpvDesktop; document.body.innerHTML = ""; });

describe("export preview", () => {
  it.each(["xlsx", "pdf", "html"])("zooms only the %s preview with Ctrl+wheel and keeps ordinary scrolling", async (kind) => {
    pdfMock.getPage.mockResolvedValue({
      getViewport: ({ scale }: { scale: number }) => ({ width: 600 * scale, height: 840 * scale }),
      render: () => ({ promise: Promise.resolve(), cancel: vi.fn() })
    });
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({} as CanvasRenderingContext2D);
    const outcome = previewExport({ blob: new Blob(["preview"]), fileName: `report.${kind}`,
      html: kind === "html" ? "<p>Document preview</p>" : undefined });
    await screen.findByRole("button", { name: "Ampliar" }, { timeout: 5000 });
    const dialog = screen.getByRole("dialog");
    const target = kind === "html" ? dialog.querySelector("iframe")!.contentDocument! : dialog.querySelector("main")!;
    if (kind === "pdf") await waitFor(() =>
      expect(Number.parseFloat(dialog.querySelector("canvas")?.style.width ?? "")).toBeGreaterThan(0));
    await act(async () => {});
    const zoomText = () => Number(screen.getByText(/\d+%/).textContent?.replace("%", ""));
    const before = zoomText();
    const ordinary = new WheelEvent("wheel", { bubbles: true, cancelable: true, deltaY: -100 });
    fireEvent(target, ordinary);
    expect(ordinary.defaultPrevented).toBe(false);
    expect(zoomText()).toBe(before);
    const enlarge = new WheelEvent("wheel", { bubbles: true, cancelable: true, ctrlKey: true, deltaY: -100 });
    fireEvent(target, enlarge);
    expect(enlarge.defaultPrevented).toBe(true);
    expect(zoomText()).toBeGreaterThan(before);
    const enlarged = zoomText();
    fireEvent.wheel(target, { ctrlKey: true, deltaY: 100 });
    expect(zoomText()).toBeLessThan(enlarged);
    for (let index = 0; index < 30; index++) fireEvent.wheel(target, { ctrlKey: true, deltaY: -100 });
    expect(zoomText()).toBe(250);
    for (let index = 0; index < 30; index++) fireEvent.wheel(target, { ctrlKey: true, deltaY: 100 });
    expect(zoomText()).toBe(25);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    await outcome;
    const closed = new WheelEvent("wheel", { bubbles: true, cancelable: true, ctrlKey: true, deltaY: -100 });
    fireEvent(target, closed);
    expect(closed.defaultPrevented).toBe(false);
  });

  it("opens without downloading and resolves cancellation on Escape while restoring focus", async () => {
    const saveFile = vi.fn();
    window.tpvDesktop = { reports: { saveFile } } as unknown as Window["tpvDesktop"];
    const opener = document.createElement("button"); document.body.append(opener); opener.focus();
    const outcome = previewExport({ blob: new Blob(["a,b\n1,2"]), fileName: "test.csv" });
    await screen.findByRole("dialog", {}, { timeout: 5000 });
    expect(saveFile).not.toHaveBeenCalled();
    // The dialog is rendered before its effect installs the keyboard listener.
    await waitFor(() => expect(screen.getByRole("button", { name: "Cerrar" })).toHaveFocus());
    fireEvent.keyDown(window, { key: "Escape" });
    expect(await outcome).toEqual({ downloaded: false, printed: false, canceled: true });
    expect(document.activeElement).toBe(opener);
  });

  it("saves the original bytes and keeps the preview open after desktop cancellation", async () => {
    const saveFile = vi.fn().mockResolvedValueOnce({ ok: true, canceled: true }).mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile } } as unknown as Window["tpvDesktop"];
    const original = new Uint8Array([0, 255, 4, 10]);
    const outcome = previewExport({ blob: new Blob([original]), fileName: "report.csv" });
    await screen.findByRole("dialog", {}, { timeout: 5000 });
    fireEvent.click(await screen.findByRole("button", { name: "Descargar" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(1));
    expect(screen.getByRole("dialog")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Descargar" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(2));
    expect([...saveFile.mock.calls[1][0].bytes]).toEqual([...original]);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(await outcome).toEqual({ downloaded: true, printed: false, canceled: false });
  });

  it("shows all workbook rows beyond 50 without page navigation and prints every sheet", async () => {
    const printExport = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile: vi.fn(), printExport } } as unknown as Window["tpvDesktop"];
    const outcome = previewExport({ blob: new Blob(["workbook"]), fileName: "report.xlsx", locale: "en" });
    expect(await screen.findByText("Row 1", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(printExport).not.toHaveBeenCalled();
    expect(screen.getByText("Row 52")).toBeInTheDocument();
    expect(screen.queryByRole("button", { name: "Next page" })).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Sheet"), { target: { value: "1" } });
    expect(screen.getByText("Other sheet")).toBeInTheDocument();
    const initialZoom = Number(screen.getByText(/\d+%/).textContent?.replace("%", ""));
    fireEvent.click(screen.getByRole("button", { name: "Zoom in" }));
    expect(Number(screen.getByText(/\d+%/).textContent?.replace("%", ""))).toBeGreaterThan(initialZoom);
    fireEvent.click(screen.getByRole("button", { name: "Print" }));
    await waitFor(() => expect(printExport).toHaveBeenCalledOnce());
    const html = printExport.mock.calls[0][0].html as string;
    expect(html).toContain("Row 52");
    expect(html).toContain("Other sheet");
    expect(html).toContain("&lt;script&gt;");
    expect(html).not.toContain("<script>");
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(await outcome).toEqual({ downloaded: false, printed: true, canceled: false });
  });

  it("loads more workbook rows on scroll and resets the visible rows when switching sheets", async () => {
    const printExport = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile: vi.fn(), printExport } } as unknown as Window["tpvDesktop"];
    vi.mocked(readXlsxFile).mockResolvedValueOnce([
      { sheet: "First", data: Array.from({ length: 450 }, (_, index) => [`Row ${index + 1}`]) },
      { sheet: "Second", data: [["Other sheet"]] }
    ]);
    const outcome = previewExport({ blob: new Blob(["workbook"]), fileName: "report.xlsx", locale: "en" });
    expect(await screen.findByText("Row 200", {}, { timeout: 5000 })).toBeInTheDocument();
    expect(screen.queryByText("Row 201")).not.toBeInTheDocument();
    const stage = screen.getByText("Row 200").closest("main")!;
    Object.defineProperties(stage, { clientHeight: { configurable: true, value: 600 }, scrollHeight: { configurable: true, value: 5000 } });
    stage.scrollTop = 4500;
    fireEvent.scroll(stage);
    expect(await screen.findByText("Row 400")).toBeInTheDocument();
    expect(screen.queryByText("Row 401")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Sheet"), { target: { value: "1" } });
    expect(screen.getByText("Other sheet")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Sheet"), { target: { value: "0" } });
    expect(screen.getByText("Row 200")).toBeInTheDocument();
    expect(screen.queryByText("Row 201")).not.toBeInTheDocument();
    expect(stage.scrollTop).toBe(0);
    fireEvent.click(screen.getByRole("button", { name: "Print" }));
    await waitFor(() => expect(printExport).toHaveBeenCalledOnce());
    expect(printExport.mock.calls[0][0].html).toContain("Row 450");
    fireEvent.click(screen.getByRole("button", { name: "Close" }));
    expect(await outcome).toEqual({ downloaded: false, printed: true, canceled: false });
  });

  it("renders styled daily XLSX in the dialog and prints its formatted cells", async () => {
    vi.mocked(readXlsxFile).mockResolvedValueOnce([{ sheet: "Diario", data: [["Daily title"], ["Section"], ["<concept>", null, null, null, 2, 12345.5], [new Date(Date.UTC(2026, 10, 14))]] }]);
    const printExport = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile: vi.fn(), printExport } } as unknown as Window["tpvDesktop"];
    const outcome = previewExport({ blob: styledWorkbook(), fileName: "daily.xlsx", formattedWorkbook: true });
    const title = await screen.findByText("Daily title", {}, { timeout: 5000 });
    expect(title.closest("td")).toHaveAttribute("colspan", "6");
    expect(title.closest("td")).toHaveStyle({ backgroundColor: "#0A2F56" });
    expect(screen.getByText("12.345,50 €")).toBeInTheDocument();
    expect(screen.getByText("14/11/2026")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Imprimir" }));
    await waitFor(() => expect(printExport).toHaveBeenCalledOnce());
    const html = printExport.mock.calls[0][0].html as string;
    expect(html).toContain("@page{size:A4 portrait");
    expect(html).toContain("&lt;concept&gt;");
    expect(html).toContain("14/11/2026");
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(await outcome).toEqual({ downloaded: false, printed: true, canceled: false });
  });

  it("offers the original download when preview data cannot be parsed", async () => {
    vi.mocked(readXlsxFile).mockRejectedValueOnce(new Error("invalid workbook"));
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile } } as unknown as Window["tpvDesktop"];
    const blob = new Blob(["bad"]);
    const outcome = previewExport({ blob, fileName: "bad.xlsx", locale: "es" });
    await screen.findByRole("dialog", {}, { timeout: 5000 });
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo mostrar");
    fireEvent.click(screen.getByRole("button", { name: "Descargar" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    expect([...saveFile.mock.calls[0][0].bytes]).toEqual([...new Uint8Array(await blob.arrayBuffer())]);
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(await outcome).toEqual({ downloaded: true, printed: false, canceled: false });
  });

  it("renders PDF pages and changes zoom without saving or printing", async () => {
    pdfMock.getPage.mockResolvedValue({
      getViewport: ({ scale }: { scale: number }) => ({ width: 600 * scale, height: 840 * scale }),
      render: () => ({ promise: Promise.resolve(), cancel: vi.fn() })
    });
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({} as CanvasRenderingContext2D);
    const saveFile = vi.fn(); const printExport = vi.fn();
    window.tpvDesktop = { reports: { saveFile, printExport } } as unknown as Window["tpvDesktop"];
    const outcome = previewExport({ blob: new Blob(["%PDF"]), fileName: "report.pdf" });
    await screen.findByRole("button", { name: "Última página" }, { timeout: 5000 });
    await waitFor(() => expect(pdfMock.getPage).toHaveBeenCalledWith(1));
    fireEvent.click(screen.getByRole("button", { name: "Última página" }));
    await waitFor(() => expect(pdfMock.getPage).toHaveBeenCalledWith(3));
    const initialZoom = Number(screen.getByText(/\d+%/).textContent?.replace("%", ""));
    fireEvent.click(screen.getByRole("button", { name: "Ampliar" }));
    expect(Number(screen.getByText(/\d+%/).textContent?.replace("%", ""))).toBeGreaterThan(initialZoom);
    expect(saveFile).not.toHaveBeenCalled(); expect(printExport).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(await outcome).toEqual({ downloaded: false, printed: false, canceled: true });
    expect(pdfMock.destroy).toHaveBeenCalled();
  });

  it("prints every PDF page as paged HTML without sending PDF bytes", async () => {
    pdfMock.numPages = 2;
    pdfMock.getPage.mockImplementation(async () => ({
      getViewport: ({ scale }: { scale: number }) => ({ width: 600 * scale, height: 840 * scale }),
      render: () => ({ promise: Promise.resolve(), cancel: vi.fn() }), cleanup: vi.fn()
    }));
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({} as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, "toDataURL").mockReturnValue("data:image/png;base64,ZmFrZQ==");
    const printExport = vi.fn().mockResolvedValue({ ok: true });
    window.tpvDesktop = { reports: { saveFile: vi.fn(), printExport } } as unknown as Window["tpvDesktop"];
    const outcome = previewExport({ blob: new Blob(["%PDF"]), fileName: "A&B.pdf" });
    await screen.findByRole("button", { name: "Última página" }, { timeout: 5000 });
    fireEvent.click(screen.getByRole("button", { name: "Imprimir" }));
    await waitFor(() => expect(printExport).toHaveBeenCalledOnce());
    const payload = printExport.mock.calls[0][0];
    expect(Object.keys(payload)).toEqual(["html"]);
    expect(payload.html).toContain("page-1");
    expect(payload.html).toContain("page-2");
    expect(payload.html).not.toContain("page-3");
    expect(payload.html).toContain("@page pdf-page-2{size:600pt 840pt;margin:0}");
    expect(payload.html).toContain("A&amp;B.pdf");
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(await outcome).toEqual({ downloaded: false, printed: true, canceled: false });
  });

  it("does not send a prepared PDF to print after the preview closes", async () => {
    pdfMock.numPages = 2;
    let finishSecond!: (value: unknown) => void;
    pdfMock.getPage.mockImplementation((pageNumber: number) => pageNumber === 1 ? Promise.resolve({
      getViewport: ({ scale }: { scale: number }) => ({ width: 600 * scale, height: 840 * scale }),
      render: () => ({ promise: Promise.resolve(), cancel: vi.fn() }), cleanup: vi.fn()
    }) : new Promise((resolve) => { finishSecond = resolve; }));
    vi.spyOn(HTMLCanvasElement.prototype, "getContext").mockReturnValue({} as CanvasRenderingContext2D);
    vi.spyOn(HTMLCanvasElement.prototype, "toDataURL").mockReturnValue("data:image/png;base64,ZmFrZQ==");
    const printExport = vi.fn();
    window.tpvDesktop = { reports: { saveFile: vi.fn(), printExport } } as unknown as Window["tpvDesktop"];
    const outcome = previewExport({ blob: new Blob(["%PDF"]), fileName: "report.pdf" });
    await screen.findByRole("button", { name: "Última página" }, { timeout: 5000 });
    fireEvent.click(screen.getByRole("button", { name: "Imprimir" }));
    await waitFor(() => expect(pdfMock.getPage).toHaveBeenCalledWith(2));
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(await outcome).toEqual({ downloaded: false, printed: false, canceled: true });
    finishSecond({ getViewport: () => ({ width: 600, height: 840 }), render: () => ({ promise: Promise.resolve(), cancel: vi.fn() }), cleanup: vi.fn() });
    await Promise.resolve();
    expect(printExport).not.toHaveBeenCalled();
  });
});

describe("workbook preview data", () => {
  it("parses quoted CSV fields and escapes print content", () => {
    expect(parseCsv('a;b\r\n"1;2";"a""b"')).toEqual([["a", "b"], ["1;2", 'a"b']]);
    expect(escapeHtml('<img src="x">')).toBe("&lt;img src=&quot;x&quot;&gt;");
    expect(workbookPrintHtml([{ name: "<Sheet>", rows: [["<x>"]] }], "Report")).toContain("&lt;Sheet&gt;");
  });

  it("keeps the ordinary workbook preview unchanged unless formatting is requested", async () => {
    const blob = styledWorkbook();
    vi.mocked(readXlsxFile).mockResolvedValueOnce([{ sheet: "Diario", data: [["Title"], ["Section"], ["<concept>", null, null, null, 2, 12345.5]] }]);
    const sheets = await readWorkbookPreview(blob, "daily.xlsx");
    expect(sheets[0].format).toBeUndefined();
    expect(workbookRowCells(sheets[0], sheets[0].rows[2], 2)).toHaveLength(6);
    expect(workbookPrintHtml(sheets, "Daily")).not.toContain("colspan");
  });

  it("shows workbook colors, horizontal merges, widths and numeric euro format only when requested", async () => {
    vi.mocked(readXlsxFile).mockResolvedValueOnce([{ sheet: "Diario", data: [["<Title>"], ["Section"], ["<concept>", null, null, null, 2, 12345.5], [new Date("2026-11-14T00:00:00Z")]] }]);
    const sheets = await readWorkbookPreview(styledWorkbook(), "daily.xlsx", true);
    expect(sheets[0].rows[2][5]).toBe(12345.5);
    expect(sheets[0].format?.columnWidths[0]).toBeGreaterThan(100);
    expect(sheets[0].format?.rowHeights[0]).toBeGreaterThan(30);
    const title = workbookRowCells(sheets[0], sheets[0].rows[0], 0);
    expect(title[0]).toMatchObject({ value: "<Title>", colSpan: 6, style: { bold: true, color: "#FFFFFF", background: "#0A2F56", align: "center" } });
    const row = workbookRowCells(sheets[0], sheets[0].rows[2], 2);
    expect(row.map((cell) => cell.value)).toEqual(["<concept>", "2", "12.345,50 €"]);
    expect(row[0].colSpan).toBe(4);
    expect(workbookRowCells(sheets[0], sheets[0].rows[3], 3)[0].value).toBe("14/11/2026");
    const html = workbookPrintHtml(sheets, "<Daily>");
    expect(html).toContain('colspan="6"');
    expect(html).toContain('colspan="4"');
    expect(html).toContain("background-color:#0A2F56");
    expect(html).toContain("background-color:#117C83");
    expect(html).toContain("12.345,50 €");
    expect(html).toContain("A4 portrait");
    expect(html).toContain("print-color-adjust:exact");
    expect(html).toContain("&lt;concept&gt;");
    expect(html).not.toContain("<concept>");
    expect(html).not.toContain("<Title>");
  });

  it("uses the workbook print orientation for formatted sheets", async () => {
    vi.mocked(readXlsxFile).mockResolvedValueOnce([{ sheet: "Diario", data: [["Title"]] }]);
    const sheets = await readWorkbookPreview(styledWorkbook("landscape"), "documents.xlsx", true);
    expect(sheets[0].format?.orientation).toBe("landscape");
    expect(workbookPrintHtml(sheets, "Documents")).toContain("@page{size:A4 landscape;");
  });

  it.each([[8, "A3"], [66, "A2"], [999, "A4"]])("preserves supported workbook paper size %s for printing", async (paperSize, expected) => {
    vi.mocked(readXlsxFile).mockResolvedValueOnce([{ sheet: "Diario", data: [["Title"]] }]);
    const sheets = await readWorkbookPreview(styledWorkbook("landscape", Number(paperSize)), "Stock.xlsx", true);
    expect(workbookPrintHtml(sheets, "Stock")).toContain(`@page{size:${expected} landscape;`);
  });

  it("preserves three-decimal stock quantities and unit prices in preview and print", async () => {
    const data = [[1.234, 12.375], [2, -0.125]];
    vi.mocked(readXlsxFile).mockResolvedValueOnce([{ sheet: "Stock", data }]);
    const files = {
      "xl/workbook.xml": '<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Stock" r:id="rId1"/></sheets></workbook>',
      "xl/_rels/workbook.xml.rels": '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>',
      "xl/styles.xml": '<styleSheet><numFmts><numFmt numFmtId="164" formatCode="#,##0.00# &quot;€&quot;"/><numFmt numFmtId="165" formatCode="#,##0.###"/></numFmts><cellXfs><xf numFmtId="164"/><xf numFmtId="165"/></cellXfs></styleSheet>',
      "xl/worksheets/sheet1.xml": '<worksheet><sheetData><row r="1"><c r="A1" s="0"/><c r="B1" s="1"/></row><row r="2"><c r="A2" s="0"/><c r="B2" s="1"/></row></sheetData></worksheet>'
    };
    const blob = new Blob([zipSync(Object.fromEntries(Object.entries(files).map(([path, content]) => [path, strToU8(content)])))]);
    const sheets = await readWorkbookPreview(blob, "Stock.xlsx", true);
    expect(sheets[0].rows).toEqual(data);
    expect(sheets[0].rows.map((row, index) => workbookRowCells(sheets[0], row, index).map(cell => cell.value)))
      .toEqual([["1,234 €", "12,375"], ["2,00 €", "-0,125"]]);
    const html = workbookPrintHtml(sheets, "Stock");
    for (const value of ["1,234 €", "12,375", "2,00 €", "-0,125"]) expect(html).toContain(`>${value}</td>`);
  });

  it("prints every stock column group on its A4 orientation with repeating column headings", async () => {
    vi.mocked(readXlsxFile).mockResolvedValueOnce([
      { sheet: "Stock 1", data: [["Empresa Canaria"], ["Código", "Nombre", "Stock"], ["P-1", "Café", 1.125]] },
      { sheet: "Stock 2", data: [["Empresa Canaria"], ["Código", "Nombre", "Precio"], ["P-1", "Café", 1.675]] }
    ]);
    const files = {
      "xl/workbook.xml": '<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Stock 1" r:id="rId1"/><sheet name="Stock 2" r:id="rId2"/></sheets><definedNames><definedName name="_xlnm.Print_Titles" localSheetId="0">\'Stock 1\'!$2:$2</definedName><definedName name="_xlnm.Print_Titles" localSheetId="1">\'Stock 2\'!$2:$2</definedName></definedNames></workbook>',
      "xl/_rels/workbook.xml.rels": '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/><Relationship Id="rId2" Target="worksheets/sheet2.xml"/></Relationships>',
      "xl/styles.xml": '<styleSheet><cellXfs><xf/></cellXfs></styleSheet>',
      "xl/worksheets/sheet1.xml": '<worksheet><pageSetup orientation="landscape" paperSize="9"/></worksheet>',
      "xl/worksheets/sheet2.xml": '<worksheet><pageSetup orientation="portrait" paperSize="9"/></worksheet>'
    };
    const blob = new Blob([zipSync(Object.fromEntries(Object.entries(files).map(([path, content]) => [path, strToU8(content)])))]);
    const sheets = await readWorkbookPreview(blob, "Stock.xlsx", true);
    const html = workbookPrintHtml(sheets, "Stock");
    const document = new DOMParser().parseFromString(html, "text/html");
    const sections = Array.from(document.querySelectorAll("section"));
    expect(sections).toHaveLength(2);
    expect(Array.from(document.querySelectorAll("thead")).map(header => header.textContent))
      .toEqual(["CódigoNombreStock", "CódigoNombrePrecio"]);
    for (const section of sections) {
      expect(section.textContent).toContain("P-1Café");
      expect(section.querySelector("thead")?.textContent).not.toContain("Empresa Canaria");
    }
    expect(html).toContain("@page workbook-sheet-0{size:A4 landscape;");
    expect(html).toContain("@page workbook-sheet-1{size:A4 portrait;");
    expect(html).toContain('style="page:workbook-sheet-0"');
    expect(html).toContain('style="page:workbook-sheet-1"');
  });

  it("shows purchase discounts as percentages in preview and print without changing quantities or stored values", async () => {
    const data = [[0], [0.1], [0.1], [0.105], [0.1025], [1], [3.75]];
    vi.mocked(readXlsxFile).mockResolvedValueOnce([{ sheet: "Compras", data }]);
    const styles = [0, 0, 1, 2, 2, 0, 3];
    const files = {
      "xl/workbook.xml": '<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Compras" r:id="rId1"/></sheets></workbook>',
      "xl/_rels/workbook.xml.rels": '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>',
      "xl/styles.xml": '<styleSheet><numFmts><numFmt numFmtId="164" formatCode="0.##%"/></numFmts><cellXfs><xf numFmtId="9"/><xf numFmtId="10"/><xf numFmtId="164"/><xf numFmtId="0"/></cellXfs></styleSheet>',
      "xl/worksheets/sheet1.xml": `<worksheet><sheetData>${styles.map((style, index) => `<row r="${index + 1}"><c r="A${index + 1}" s="${style}"/></row>`).join("")}</sheetData></worksheet>`
    };
    const blob = new Blob([zipSync(Object.fromEntries(Object.entries(files).map(([path, content]) => [path, strToU8(content)])))]);
    const sheets = await readWorkbookPreview(blob, "Facturas_de_compra.xlsx", true);
    expect(sheets[0].rows).toEqual(data);
    const expected = ["0%", "10%", "10,00%", "10,5%", "10,25%", "100%", "3.75"];
    expect(sheets[0].rows.map((row, index) => workbookRowCells(sheets[0], row, index)[0].value)).toEqual(expected);
    const html = workbookPrintHtml(sheets, "Compras");
    for (const value of expected) expect(html).toContain(`>${value}</td>`);
  });
});

function styledWorkbook(orientation?: "portrait" | "landscape", paperSize = 9): Blob {
  const files = {
    "xl/workbook.xml": '<workbook xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets><sheet name="Diario" r:id="rId1"/></sheets></workbook>',
    "xl/_rels/workbook.xml.rels": '<Relationships><Relationship Id="rId1" Target="worksheets/sheet1.xml"/></Relationships>',
    "xl/styles.xml": '<styleSheet><numFmts count="2"><numFmt numFmtId="164" formatCode="#,##0.00 €"/><numFmt numFmtId="165" formatCode="dd/mm/yyyy"/></numFmts><fonts count="2"><font><sz val="11"/></font><font><b/><sz val="18"/><color rgb="FFFFFFFF"/></font></fonts><fills count="4"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="solid"><fgColor rgb="FF0A2F56"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FF117C83"/></patternFill></fill><fill><patternFill patternType="solid"><fgColor rgb="FFEAF1F7"/></patternFill></fill></fills><cellXfs count="5"><xf fontId="1" fillId="1"><alignment horizontal="center"/></xf><xf fontId="1" fillId="2"/><xf fontId="0" fillId="3"/><xf fontId="0" fillId="0" numFmtId="164"><alignment horizontal="right"/></xf><xf fontId="0" fillId="0" numFmtId="165"/></cellXfs></styleSheet>',
    "xl/worksheets/sheet1.xml": '<worksheet><cols><col min="1" max="1" width="22"/><col min="6" max="6" width="16"/></cols><sheetData><row r="1" ht="27"><c r="A1" s="0"/></row><row r="2"><c r="A2" s="1"/></row><row r="3"><c r="A3" s="2"/><c r="F3" s="3"/></row><row r="4"><c r="A4" s="4"/></row></sheetData><mergeCells><mergeCell ref="A1:F1"/><mergeCell ref="A2:F2"/><mergeCell ref="A3:D3"/></mergeCells>' + (orientation ? `<pageSetup orientation="${orientation}" paperSize="${paperSize}"/>` : "") + '</worksheet>'
  };
  return new Blob([zipSync(Object.fromEntries(Object.entries(files).map(([path, content]) => [path, strToU8(content)])))]);
}
