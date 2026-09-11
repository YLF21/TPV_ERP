// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SalesReportScreen, formatReportDisplayValue } from "./SalesReportScreen";
import { createTranslator } from "../i18n/LocalizedMessages";
import { printWarehouseA4Document } from "../warehouse/warehouseDocumentPrinting";

vi.mock("../api/client", async () => ({
  ...await vi.importActual<typeof import("../api/client")>("../api/client"),
  apiRequest: vi.fn().mockResolvedValue(null)
}));
vi.mock("../warehouse/warehouseDocumentPrinting", async () => ({
  ...await vi.importActual<typeof import("../warehouse/warehouseDocumentPrinting")>("../warehouse/warehouseDocumentPrinting"),
  printWarehouseA4Document: vi.fn().mockResolvedValue({ ok: true })
}));

const today = "2026-09-11";
const empty = { items: [], hasMore: false, nextCursor: null };
const invoice = {
  document: { id: "input-invoice", documentType: "FACTURA_ENTRADA", number: "FE-001", date: today,
    status: "CONFIRMADA", supplierId: "supplier-uuid", warehouseId: "warehouse-uuid",
    subtotal: "12.26", globalDiscount: "10", total: "11.03",
    lines: [{ productId: "product-uuid", productCode: "P-1", productName: "Artículo", quantity: "6",
      purchaseUnitPrice: "2.15", discount: "5", purchaseTotal: "12.26" }] },
  supplierCode: "PR-001", supplierName: "Proveedor de prueba", warehouseName: "GENERAL"
};
const note = { ...invoice, document: { ...invoice.document, id: "input-note", documentType: "ALBARAN_ENTRADA", number: "AE-001" } };
function fixture() {
  return vi.fn().mockImplementation(async (path: string) => {
    if (path.startsWith("/document-reports/date-options")) return { earliestDate: "2025-01-01", currentDate: today };
    if (path.endsWith("/print-document")) return { renderedPdf: { contentType: "application/pdf", base64: "amFzcGVy" } };
    if (path.startsWith("/document-reports/warehouse-inputs?")) {
      return { ...empty, items: [path.includes("type=FACTURA_ENTRADA") ? invoice : note] };
    }
    return empty;
  });
}
function mount(request = fixture(), report = "inputInvoices") {
  return render(<SalesReportScreen app="venta" locale="es" request={request} initialReport={`salesReport.${report}`}
    session={{ username: "accounts", displayName: "Cuentas", accessToken: "token", permissions: ["GESTION_CUENTAS"] }}
    terminalContext={{ storeName: "Tienda", terminalCode: "01" }} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
}
async function openInvoice() {
  fireEvent.doubleClick((await screen.findByText("FE-001")).closest("tr")!);
  const dialog = await screen.findByRole("dialog");
  await within(dialog).findByText("P-1");
  return dialog;
}
afterEach(() => {
  cleanup(); localStorage.clear(); vi.restoreAllMocks(); vi.clearAllMocks();
  Reflect.deleteProperty(window, "tpvDesktop");
});

describe("current warehouse purchase reports", () => {
  it.each([["inputInvoices", "FACTURA_ENTRADA", "FE-001"], ["inputDeliveryNotes", "ALBARAN_ENTRADA", "AE-001"]])(
    "reads %s without operational warehouse permissions or legacy queries", async (report, type, number) => {
      const request = fixture();
      const { container } = mount(request, report);
      expect(await screen.findByText(number)).toBeInTheDocument();
      expect(screen.getByText("Proveedor de prueba")).toBeInTheDocument();
      expect(screen.getByText("Confirmada")).toBeInTheDocument();
      expect(container.querySelector(".report-table tbody")).toHaveTextContent("11,03 €");
      expect(request).toHaveBeenCalledWith(expect.stringContaining(`type=${type}`), { token: "token" });
      expect(request.mock.calls.some(([path]) => /^\/(warehouse-inputs|warehouses|document-reports\/(invoices|delivery-notes))(\?|$)/.test(path))).toBe(false);
      for (const label of ["Pendiente", "Vencimiento", "Impuesto", "Terminal", "Usuario"]) {
        expect(within(container.querySelector(".report-table thead") as HTMLElement).queryByText(label)).toBeNull();
      }
      expect(screen.queryByText("supplier-uuid")).toBeNull();
    });

  it("opens real number and line discounts, without a fabricated tax or commercial detail call", async () => {
    const request = fixture(); mount(request);
    const dialog = await openInvoice();
    expect(within(dialog).getByRole("heading", { name: "FE-001" })).toBeVisible();
    expect(within(dialog).getByText("P-1")).toBeVisible();
    expect(within(dialog).getByText("5 %")).toBeVisible();
    expect(within(dialog).queryByText("Impuesto")).toBeNull();
    expect(request.mock.calls.some(([path]) => path.startsWith("/documents/"))).toBe(false);
  });

  it("exports a selected input through its scoped individual endpoint", async () => {
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { reports: { saveFile } } });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(new Uint8Array([1]), { status: 200 }));
    mount(); const dialog = await openInvoice();
    fireEvent.click(within(dialog).getByRole("button", { name: "Exportar Excel" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalled());
    expect(fetchSpy.mock.calls[0][0]).toMatch(/\/excel\/warehouse-inputs\/input-invoice\/export$/);
    expect(saveFile.mock.calls[0][0]).toMatchObject({ defaultFileName: "FE-001.xlsx" });
  });

  it.each([true, false])("requires Jasper for input printing (available: %s)", async (available) => {
    const request = fixture();
    const base = request.getMockImplementation()!;
    if (!available) request.mockImplementation(async (path) => path.endsWith("/print-document") ? {} : base(path));
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { hardware: {} } });
    mount(request); const dialog = await openInvoice();
    fireEvent.click(within(dialog).getByRole("button", { name: "Imprimir copia" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/document-reports/warehouse-inputs/input-invoice/print-document", { token: "token" }));
    if (available) await waitFor(() => expect(printWarehouseA4Document).toHaveBeenCalledWith(expect.objectContaining({
      requireRenderedDocument: true, renderedPdf: { contentType: "application/pdf", base64: "amFzcGVy" }
    })));
    else {
      await waitFor(() => expect(within(dialog).getByRole("button", { name: "Imprimir copia" })).toBeEnabled());
      expect(printWarehouseA4Document).not.toHaveBeenCalled();
    }
  });

  it("does not reuse a purchase cursor or append a stale page in another input type", async () => {
    let finish: (result: unknown) => void = () => {};
    const oldPage = new Promise((resolve) => { finish = resolve; });
    const request = fixture(); const base = request.getMockImplementation()!;
    request.mockImplementation(async (path) => {
      if (path.includes("cursor=invoice-page")) return oldPage;
      if (path.includes("type=FACTURA_ENTRADA")) return { items: [invoice], hasMore: true, nextCursor: "invoice-page" };
      return base(path);
    });
    mount(request);
    await waitFor(() => expect(request.mock.calls.some(([path]) => path.includes("cursor=invoice-page"))).toBe(true));
    fireEvent.click(screen.getByRole("button", { name: createTranslator("es")("salesReport.inputDeliveryNotes") }));
    expect(await screen.findByText("AE-001")).toBeVisible();
    await act(async () => finish({ ...empty, items: [{ ...invoice, document: { ...invoice.document, number: "OLD-PAGE" } }] }));
    expect(screen.queryByText("OLD-PAGE")).toBeNull();
    expect(screen.queryByText("FE-001")).toBeNull();
    expect(request.mock.calls.some(([path]) => path.includes("type=ALBARAN_ENTRADA") && path.includes("cursor=invoice-page"))).toBe(false);
  });

  it("formats document discounts as percentages, not money", () => {
    expect(formatReportDisplayValue("globalDiscount", "10.5", "es")).toBe("10,5 %");
    expect(formatReportDisplayValue("subtotal", "12.26", "es")).toBe("12,26\u00a0€");
  });

  it("does not match invisible line payloads when searching the report", async () => {
    mount(); await screen.findByText("FE-001");
    const search = screen.getByPlaceholderText(createTranslator("es")("salesReport.searchPlaceholder"));
    fireEvent.change(search, { target: { value: "product-uuid" } });
    expect(screen.queryByText("FE-001")).toBeNull();
    fireEvent.change(search, { target: { value: "Proveedor de prueba" } });
    expect(screen.getByText("FE-001")).toBeVisible();
  });
});
