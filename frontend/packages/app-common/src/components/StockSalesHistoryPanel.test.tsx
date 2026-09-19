// @vitest-environment jsdom
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import type { ComponentProps } from "react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { SaasSalesHistoryItem, SaasSalesHistoryResponse } from "../api/stockSalesHistory";
import { tableLayoutStorageKey, writeStoredTableLayout } from "./tableLayoutPreferences";
import {
  defaultStockSalesHistoryRange, effectiveStockSalesHistoryTotals, filterStockSalesHistoryRows,
  stockSalesDocumentLabel, stockSalesHistoryPath, StockSalesHistoryPanel, type StockSalesHistoryRow,
} from "./StockSalesHistoryPanel";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});
const apiRequestMock = vi.mocked(apiRequest);
const row: StockSalesHistoryRow = {
  documentId: "document-1", documentType: "TICKET", documentNumber: "T-0001", status: "CONFIRMADO",
  occurredAt: "2026-07-10T12:30:00Z", customerName: "Cliente Uno", quantity: 2,
  unitPrice: 4.5, discountPercent: 10, lineTotal: 8.1, userName: "ADMIN", storeName: "Principal", warehouseName: "GENERAL",
};
const item: SaasSalesHistoryItem = {
  ...row, quantity: "2", unitPrice: "4.50", discountPercent: "10.00", lineTotal: "8.10",
  storeId: "store-1", storeCode: "S01", storeName: "Principal", installationId: "installation-1", linePosition: 1,
  productCode: "CAFE-1", productName: "Cafe molido", businessDate: "2026-07-10", currency: "EUR", countsAsSale: true,
};
const response = (overrides: Partial<SaasSalesHistoryResponse> = {}): SaasSalesHistoryResponse => ({
  companyId: "company-1", productCode: "CAFE-1", coverage: "RECEIVED_IN_SAAS", items: [item],
  stores: [{ id: "store-1", code: "S01", name: "Principal" }, { id: "store-2", code: "S02", name: "Norte" }],
  totals: [{ currency: "EUR", quantitySold: "15.125", quantityReturned: "1.250", netQuantity: "13.875", netAmount: "123.45" }],
  comparison: [], nextCursor: null, hasMore: false, incompleteDocuments: 0, ...overrides,
});
function panel(extra: Partial<ComponentProps<typeof StockSalesHistoryPanel>> = {}) {
  return render(<StockSalesHistoryPanel productId="product-1" productCode="CAFE-1" productName="Cafe molido"
    locale="es" token="token" onClose={vi.fn()} {...extra} />);
}
function lastHistoryQuery() {
  const path = historyRequests().at(-1)![0];
  return new URL(path, "http://local.test").searchParams;
}
function historyRequests() {
  return apiRequestMock.mock.calls.filter(([path]) => path.includes("/sales-history/saas"));
}
function scrollHistory(container: HTMLElement, scrollTop = 690) {
  const scroll = container.querySelector<HTMLElement>(".stock-history-table-scroll")!;
  Object.defineProperties(scroll, {
    clientHeight: { configurable: true, value: 300 },
    scrollHeight: { configurable: true, value: 1000 },
  });
  scroll.scrollTop = scrollTop;
  fireEvent.scroll(scroll);
  return scroll;
}
function deferredResponse() {
  let resolve!: (value: SaasSalesHistoryResponse) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<SaasSalesHistoryResponse>((onResolve, onReject) => {
    resolve = onResolve;
    reject = onReject;
  });
  return { promise, resolve, reject };
}
function installExportMock(pdf = false) {
  const saveFile = vi.fn().mockResolvedValue({ ok: true });
  window.tpvDesktop = { closeApplication: vi.fn(), reports: {
    saveFile, exportPdf: vi.fn(), exportTablePdf: vi.fn(), print: vi.fn(),
  } };
  const fetchMock = vi.fn().mockResolvedValue({ ok: true,
    arrayBuffer: async () => new Uint8Array([1, 2, 3]).buffer,
    json: async () => ({ renderedPdf: { base64: "JVBERi0=" }, fileName: pdf ? "historial.pdf" : undefined }),
  });
  vi.stubGlobal("fetch", fetchMock);
  return { saveFile, fetchMock };
}

describe("StockSalesHistoryPanel", () => {
  beforeEach(() => { apiRequestMock.mockReset(); apiRequestMock.mockResolvedValue(response()); localStorage.clear(); });
  afterEach(() => { cleanup(); localStorage.clear(); Reflect.deleteProperty(window, "tpvDesktop"); vi.unstubAllGlobals(); });

  it("keeps the legacy exported helpers compatible without using them for SaaS totals", () => {
    expect(stockSalesHistoryPath("product/1", "2026-07-01", "2026-07-10"))
      .toBe("/stock/products/product%2F1/sales-history?from=2026-07-01&to=2026-07-10");
    expect(defaultStockSalesHistoryRange(new Date(2026, 6, 10))).toEqual({ from: "2026-06-11", to: "2026-07-10" });
    expect(filterStockSalesHistoryRows([row, { ...row, status: "ANULADO" }], "CONFIRMADO")).toEqual([row]);
    expect(stockSalesDocumentLabel(row)).toBe("TICKET T-0001");
    const totals = effectiveStockSalesHistoryTotals([row, { ...row, status: "ANULADO", quantity: 10, lineTotal: 100 },
      { ...row, quantity: -1, lineTotal: -4.5 }]);
    expect(totals.quantity).toBe(1);
    expect(totals.amount).toBeCloseTo(3.6);
  });

  it("renders the existing 11-column layout and accessible date, status and store filters", () => {
    const html = renderToStaticMarkup(<StockSalesHistoryPanel productId="product-1" productName="Cafe molido"
      locale="es" token="token" onClose={vi.fn()} />);
    expect(html).toContain('type="date"');
    expect(html).toContain('aria-label="Estado"');
    expect(html).toContain('aria-label="Tienda"');
    expect(html).not.toContain("<select");
    expect(html).toContain("Documento");
    expect(html).toContain("Precio unitario");
    expect(html).not.toContain("Almacén");
    expect(html).not.toContain("Solo datos recibidos en SaaS");
    const withoutHeading = renderToStaticMarkup(<StockSalesHistoryPanel productId="product-1" productName="Cafe molido"
      showProductHeading={false} locale="es" token="token" onClose={vi.fn()} />);
    expect(withoutHeading).not.toContain("Cafe molido");
  });

  it("only queries the authenticated SaaS proxy and shows server totals for all pages by currency", async () => {
    apiRequestMock.mockResolvedValue(response({ totals: [
      ...response().totals,
      { currency: "USD", quantitySold: "2", quantityReturned: "0", netQuantity: "2", netAmount: "9007199254740993.2500" },
    ] }));
    const { container } = panel({ accessToken: "sale-access-token" });
    await screen.findByText("TICKET T-0001");
    expect(apiRequestMock).toHaveBeenCalledWith(expect.stringContaining("/stock/products/product-1/sales-history/saas?"),
      expect.objectContaining({ token: "sale-access-token", signal: expect.any(AbortSignal) }));
    expect(lastHistoryQuery().get("size")).toBe("200");
    expect(lastHistoryQuery().get("sortDirection")).toBe("desc");
    const footer = container.querySelector(".stock-history-totals")!;
    expect(footer.textContent).toContain("13,875");
    expect(footer.textContent).toContain("123,45");
    expect(footer.textContent).toContain("9.007.199.254.740.993,25");
    expect(footer.textContent).toContain("USD");
    expect(footer.querySelectorAll("div")).toHaveLength(2);
    expect(apiRequestMock.mock.calls.filter(([path]) => path.includes("/sales-history"))
      .every(([path]) => path.includes("/sales-history/saas?"))).toBe(true);
  });

  it("sends date, status, store and column sort to the server and resets the cursor", async () => {
    apiRequestMock.mockImplementation(async (path) => path.includes("cursor=")
      ? response({ items: [{ ...item, documentId: "document-2", documentNumber: "SECOND" }] })
      : response({ hasMore: true, nextCursor: "next+/=" }));
    const { container } = panel();
    await screen.findByText("TICKET T-0001");
    scrollHistory(container);
    await waitFor(() => expect(lastHistoryQuery().get("cursor")).toBe("next+/="));
    await screen.findByText("TICKET SECOND");
    fireEvent.click(screen.getByRole("button", { name: "Estado" }));
    fireEvent.click(screen.getByRole("option", { name: "ANULADO" }));
    await waitFor(() => expect(lastHistoryQuery().get("status")).toBe("ANULADO"));
    expect(lastHistoryQuery().has("cursor")).toBe(false);
    await screen.findByText("TICKET T-0001");
    fireEvent.click(screen.getByRole("button", { name: "Tienda" }));
    fireEvent.click(screen.getByRole("option", { name: "S02 · Norte" }));
    await waitFor(() => expect(lastHistoryQuery().get("storeIds")).toBe("store-2"));
    const dates = container.querySelectorAll<HTMLInputElement>('input[type="date"]');
    fireEvent.change(dates[0], { target: { value: "2026-07-31" } });
    fireEvent.change(dates[1], { target: { value: "2026-07-01" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar filtro" }));
    await waitFor(() => expect(lastHistoryQuery().get("from")).toBe("2026-07-01"));
    expect(lastHistoryQuery().get("to")).toBe("2026-07-31");
    fireEvent.click(container.querySelector('th[data-column-key="quantity"] .table-layout-sort-button')!);
    await waitFor(() => expect(lastHistoryQuery().get("sortBy")).toBe("quantity"));
    expect(lastHistoryQuery().get("sortDirection")).toBe("asc");
    fireEvent.click(container.querySelector('th[data-column-key="quantity"] .table-layout-sort-button')!);
    await waitFor(() => expect(lastHistoryQuery().get("sortDirection")).toBe("desc"));
  });

  it("appends near the end without duplicate requests or lines, automatic loops or recalculated totals", async () => {
    const secondPage = deferredResponse();
    const second = { ...item, documentId: "document-2", documentNumber: "SECOND" };
    apiRequestMock.mockResolvedValueOnce(response({ nextCursor: "next", hasMore: true }))
      .mockImplementationOnce(() => secondPage.promise)
      .mockResolvedValueOnce(response({ items: [{ ...item, documentNumber: "THIRD", linePosition: 2 }] }));
    const { container } = panel();
    await screen.findByText("TICKET T-0001");
    const totals = container.querySelector(".stock-history-totals")!.textContent;
    scrollHistory(container, 100);
    expect(historyRequests()).toHaveLength(1);
    scrollHistory(container);
    scrollHistory(container);
    scrollHistory(container);
    expect(historyRequests()).toHaveLength(2);
    expect(lastHistoryQuery().get("cursor")).toBe("next");
    expect(screen.getByText("TICKET T-0001")).toBeTruthy();
    await act(async () => secondPage.resolve(response({
      items: [item, second, second, { ...item, storeId: "store-2", storeName: "Norte" }],
      nextCursor: "third", hasMore: true,
    })));
    await screen.findByText("TICKET SECOND");
    expect(container.querySelectorAll("tbody tr")).toHaveLength(3);
    expect(screen.getAllByText("TICKET T-0001")).toHaveLength(2);
    expect(historyRequests()).toHaveLength(2);
    expect(screen.getByText("3 líneas cargadas")).toBeTruthy();
    expect(container.querySelector(".stock-history-totals")!.textContent).toBe(totals);
    scrollHistory(container);
    await screen.findByText("TICKET THIRD");
    expect(lastHistoryQuery().get("cursor")).toBe("third");
    expect(container.querySelectorAll("tbody tr")).toHaveLength(4);
    expect(container.querySelector(".stock-history-totals")!.textContent).toBe(totals);
    scrollHistory(container);
    expect(historyRequests()).toHaveLength(3);
    expect(screen.queryByRole("button", { name: "Anterior" })).toBeNull();
    expect(screen.queryByRole("button", { name: "Siguiente" })).toBeNull();
  });

  it("resets loaded rows and cursor when filters change and ignores a stale additional page", async () => {
    const stalePage = deferredResponse();
    apiRequestMock.mockResolvedValueOnce(response({ nextCursor: "old-next", hasMore: true }))
      .mockImplementationOnce(() => stalePage.promise)
      .mockResolvedValueOnce(response({ items: [{ ...item, documentNumber: "FILTERED", status: "ANULADO" }],
        nextCursor: "filtered-next", hasMore: true }))
      .mockResolvedValueOnce(response({ items: [{ ...item, documentId: "filtered-2", documentNumber: "FILTERED-2", status: "ANULADO" }] }));
    const { container } = panel();
    await screen.findByText("TICKET T-0001");
    scrollHistory(container);
    expect(lastHistoryQuery().get("cursor")).toBe("old-next");
    fireEvent.click(screen.getByRole("button", { name: "Estado" }));
    fireEvent.click(screen.getByRole("option", { name: "ANULADO" }));
    await screen.findByText("TICKET FILTERED");
    expect(lastHistoryQuery().get("status")).toBe("ANULADO");
    expect(lastHistoryQuery().has("cursor")).toBe(false);
    expect(screen.queryByText("TICKET T-0001")).toBeNull();
    await act(async () => stalePage.resolve(response({ items: [{ ...item, documentNumber: "STALE" }] })));
    expect(screen.queryByText("TICKET STALE")).toBeNull();
    expect(container.querySelectorAll("tbody tr")).toHaveLength(1);
    expect(screen.getByText("1 líneas cargadas")).toBeTruthy();
    scrollHistory(container);
    await screen.findByText("TICKET FILTERED-2");
    expect(lastHistoryQuery().get("cursor")).toBe("filtered-next");
    expect(lastHistoryQuery().get("status")).toBe("ANULADO");
    expect(container.querySelectorAll("tbody tr")).toHaveLength(2);
  });

  it("keeps loaded lines after an additional-page failure and retries the same cursor", async () => {
    const failedPage = deferredResponse();
    apiRequestMock.mockResolvedValueOnce(response({ nextCursor: "retry+/=", hasMore: true }))
      .mockImplementationOnce(() => failedPage.promise)
      .mockResolvedValueOnce(response({ items: [{ ...item, documentId: "document-2", documentNumber: "RECOVERED" }] }));
    const { container } = panel();
    await screen.findByText("TICKET T-0001");
    const totals = container.querySelector(".stock-history-totals")!.textContent;
    scrollHistory(container);
    const failedPath = historyRequests().at(-1)![0];
    await act(async () => failedPage.reject(new Error("upstream_unreachable")));
    await screen.findByRole("alert");
    expect(screen.getByText("TICKET T-0001")).toBeTruthy();
    expect(container.querySelectorAll("tbody tr")).toHaveLength(1);
    expect(container.querySelector(".stock-history-totals")!.textContent).toBe(totals);
    scrollHistory(container);
    expect(historyRequests()).toHaveLength(2);
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    await screen.findByText("TICKET RECOVERED");
    expect(historyRequests()).toHaveLength(3);
    expect(historyRequests().at(-1)![0]).toBe(failedPath);
    expect(lastHistoryQuery().get("cursor")).toBe("retry+/=");
    expect(screen.getByText("TICKET T-0001")).toBeTruthy();
    expect(container.querySelectorAll("tbody tr")).toHaveLength(2);
    expect(screen.queryByRole("alert")).toBeNull();
  });

  it("sorts complete store aggregates with exact decimals and opens the selected store's detail", async () => {
    const comparison = [
      { ...response().totals[0], storeId: "store-1", storeCode: "S01", storeName: "Principal", netQuantity: "9007199254740992.001" },
      { ...response().totals[0], storeId: "store-2", storeCode: "S02", storeName: "Norte", netQuantity: "9007199254740992.002" },
    ];
    apiRequestMock.mockResolvedValue(response({ comparison, nextCursor: "detail-next", hasMore: true }));
    const { container } = panel();
    await screen.findByText("TICKET T-0001");
    fireEvent.click(screen.getByRole("button", { name: "Comparación por tienda" }));
    expect(container.querySelector("tbody tr:first-child")?.textContent).toContain("Norte");
    const requestCount = apiRequestMock.mock.calls.length;
    scrollHistory(container);
    scrollHistory(container);
    expect(apiRequestMock.mock.calls).toHaveLength(requestCount);
    fireEvent.click(container.querySelector('th[data-column-key="netQuantity"] .table-layout-sort-button')!);
    expect(container.querySelector("tbody tr:first-child")?.textContent).toContain("Principal");
    expect(apiRequestMock.mock.calls).toHaveLength(requestCount);
    fireEvent.click(screen.getByRole("button", { name: "S02 · Norte" }));
    expect(document.activeElement).toBe(screen.getByRole("button", { name: "Aplicar filtro" }));
    await waitFor(() => expect(lastHistoryQuery().get("storeIds")).toBe("store-2"));
    expect(screen.getByRole("button", { name: "Detalle" }).getAttribute("aria-pressed")).toBe("true");
    await screen.findByText("TICKET T-0001");
  });

  it("retains distinct lines for the same document in different stores and never opens remote IDs locally", async () => {
    apiRequestMock.mockResolvedValue(response({ items: [item, { ...item, storeId: "store-2", storeName: "Norte" },
      { ...item, linePosition: 2, quantity: "3" }] }));
    const onOpenDocument = vi.fn();
    const { container } = panel({ onOpenDocument });
    await waitFor(() => expect(container.querySelectorAll("tbody tr")).toHaveLength(3));
    fireEvent.doubleClick(container.querySelector("tbody tr")!);
    expect(screen.getByText(/Documento de Principal: TICKET T-0001/)).toBeTruthy();
    fireEvent.keyDown(container.querySelectorAll("tbody tr")[1], { key: "Enter" });
    expect(onOpenDocument).not.toHaveBeenCalled();
  });

  it("retries SaaS failures without a local fallback, zero totals or blocking close", async () => {
    apiRequestMock.mockRejectedValueOnce(new Error("upstream_unreachable")).mockResolvedValue(response());
    const onClose = vi.fn();
    const { container } = panel({ onClose });
    await screen.findByRole("alert");
    expect(container.querySelector(".stock-history-totals")?.textContent).toContain("—");
    expect(screen.queryByText("SIN DATOS")).toBeNull();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    await screen.findByText("TICKET T-0001");
    expect(apiRequestMock.mock.calls.every(([path]) => path.includes("/sales-history/saas?"))).toBe(true);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("discloses incomplete received data and shows a concise empty state", async () => {
    apiRequestMock.mockResolvedValue(response({ items: [], totals: [], incompleteDocuments: 2 }));
    const { container } = panel();
    await screen.findByText(/2 documentos recibidos están incompletos/);
    expect(screen.getByText("SIN DATOS")).toBeTruthy();
    expect(container.querySelector(".stock-history-totals")?.textContent).toBe("Sin totales recibidos para estos filtros");
  });

  it("ignores a stale response after server filters change", async () => {
    let resolveFirst!: (value: SaasSalesHistoryResponse) => void;
    apiRequestMock.mockImplementationOnce(() => new Promise((resolve) => { resolveFirst = resolve; }))
      .mockResolvedValue(response({ items: [{ ...item, documentNumber: "CURRENT" }] }));
    panel();
    fireEvent.click(screen.getByRole("button", { name: "Estado" }));
    fireEvent.click(screen.getByRole("option", { name: "ANULADO" }));
    await screen.findByText("TICKET CURRENT");
    resolveFirst(response());
    await waitFor(() => expect(screen.queryByText("TICKET T-0001")).toBeNull());
  });

  it("closes a selector with Escape before the history dialog", async () => {
    const onClose = vi.fn();
    panel({ onClose });
    await screen.findByText("TICKET T-0001");
    const store = screen.getByRole("button", { name: "Tienda" });
    fireEvent.click(store);
    fireEvent.keyDown(screen.getByRole("option", { name: "Todas las tiendas" }), { key: "Escape" });
    expect(screen.queryByRole("listbox")).toBeNull();
    expect(document.activeElement).toBe(store);
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(store, { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("contains full names and retains precise negative decimal strings", async () => {
    const customerName = "Cliente con nombre fiscal muy largo para la columna del historial de ventas";
    apiRequestMock.mockResolvedValue(response({ items: [{ ...item, customerName, quantity: "-2.125", unitPrice: "-4.505", lineTotal: "-8.1000" }] }));
    const { container } = panel();
    await screen.findByText(customerName);
    const cell = (key: string) => container.querySelector<HTMLTableCellElement>(`tbody td[data-column-key="${key}"]`)!;
    expect(cell("customer").firstElementChild?.getAttribute("title")).toBe(customerName);
    expect(cell("quantity").textContent).toBe("-2,125");
    expect(cell("unitPrice").textContent).toBe("-4,505");
    expect(cell("total").textContent).toContain("-8,10");
    for (const key of ["quantity", "unitPrice", "total"]) expect(cell(key).classList.contains("stock-history-cell-negative")).toBe(true);
  });

  it.each(["xlsx", "pdf"] as const)("exports the full applied SaaS view as %s without the page cursor", async (extension) => {
    const { saveFile, fetchMock } = installExportMock(extension === "pdf");
    apiRequestMock.mockResolvedValueOnce(response({ nextCursor: "next", hasMore: true }))
      .mockResolvedValueOnce(response({ items: [{ ...item, documentId: "document-2", documentNumber: "SECOND" }] }));
    const { container } = panel();
    await screen.findByText("TICKET T-0001");
    scrollHistory(container);
    await screen.findByText("TICKET SECOND");
    expect(screen.getByText("TICKET T-0001")).toBeTruthy();
    fireEvent.click(screen.getByRole("button", { name: "Exportar" }));
    fireEvent.click(screen.getByRole("menuitem", { name: extension === "xlsx" ? "Exportar a Excel" : "Exportar a PDF" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    expect(fetchMock.mock.calls[0][0]).toContain(`/sales-history/saas/${extension === "xlsx" ? "export" : "render"}`);
    const payload = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(payload).toMatchObject({ status: null, storeIds: [], view: "detail", locale: "es", sortBy: "occurredAt", sortDirection: "desc" });
    expect(payload).not.toHaveProperty("cursor");
    expect(payload.columns).toHaveLength(10);
    expect(payload.columns.map((column: { key: string }) => column.key)).not.toContain("warehouse");
    expect(payload.columns[0]).toEqual({ key: "occurredAt", label: "Fecha y hora" });
    expect(Object.keys(payload.labels)).toEqual(["title", "product", "code", "period", "status", "allStatuses", "totalQuantity", "totalAmount"]);
  });

  it("exports the comparison columns and ranking for every received store", async () => {
    const { saveFile, fetchMock } = installExportMock();
    apiRequestMock.mockResolvedValue(response({ comparison: [{ ...response().totals[0], storeId: "store-1", storeCode: "S01", storeName: "Principal" }] }));
    panel();
    await screen.findByText("TICKET T-0001");
    fireEvent.click(screen.getByRole("button", { name: "Comparación por tienda" }));
    fireEvent.click(screen.getByRole("button", { name: "Exportar" }));
    fireEvent.click(screen.getByRole("menuitem", { name: "Exportar a Excel" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    const payload = JSON.parse(fetchMock.mock.calls[0][1].body);
    expect(payload.view).toBe("comparison");
    expect(payload.comparisonSortBy).toBe("netQuantity");
    expect(payload.comparisonSortDirection).toBe("desc");
    expect(payload.columns.map((column: { key: string }) => column.key)).toEqual(["store", "quantitySold", "quantityReturned", "netQuantity", "netAmount"]);
  });

  it.each(["en", "zh"] as const)("localizes the central history controls and errors in %s", async (locale) => {
    apiRequestMock.mockRejectedValue(new Error("offline"));
    const { container } = panel({ locale });
    await screen.findByRole("alert");
    expect(container.textContent).not.toContain("stock.history.");
    expect(container.textContent).toContain(locale === "en" ? "Store comparison" : "门店对比");
  });

  it.each([
    { locale: "es", quantity: "Cantidad total", amount: "Importe total", loaded: "1 líneas cargadas", updated: "Actualizado:" },
    { locale: "en", quantity: "Total quantity", amount: "Total amount", loaded: "1 lines loaded", updated: "Updated:" },
    { locale: "zh", quantity: "总数量", amount: "总金额", loaded: "已加载 1 条明细", updated: "更新时间：" },
  ] as const)("localizes total and loaded-history labels in $locale", async ({ locale, quantity, amount, loaded, updated }) => {
    apiRequestMock.mockResolvedValue(response({ receivedAt: "2026-07-10T12:40:00Z" }));
    const { container } = panel({ locale });
    await screen.findByText("TICKET T-0001");
    const totals = container.querySelector(".stock-history-totals")!.textContent;
    expect(totals).toContain(quantity);
    expect(totals).toContain(amount);
    expect(screen.getByText(loaded)).toBeTruthy();
    expect(container.querySelector(".stock-history-pagination")!.textContent).toContain(updated);
  });

  it("keeps the persisted interactive order while ignoring the retired warehouse column", async () => {
    writeStoredTableLayout("venta", "ana", "stock.productSalesHistory", [
      { key: "warehouse", width: 160, visible: true },
      { key: "document", width: 180, visible: true },
      { key: "status", width: 130, visible: true },
      { key: "occurredAt", width: 160, visible: true },
      { key: "customer", width: 200, visible: true },
      { key: "quantity", width: 110, visible: true },
      { key: "unitPrice", width: 130, visible: true },
      { key: "discount", width: 110, visible: true },
      { key: "total", width: 130, visible: true },
      { key: "user", width: 150, visible: true },
      { key: "store", width: 160, visible: true }
    ], localStorage);
    apiRequestMock.mockResolvedValueOnce(response({ items: [item] }));

    const { container } = render(
      <StockSalesHistoryPanel
        productId="product-1"
        productName="Cafe molido"
        locale="es"
        app="venta"
        username="ana"
        token="token"
        onClose={vi.fn()}
      />
    );

    await waitFor(() => expect(container.querySelectorAll("tbody tr").length).toBe(1));

    const headerKeys = () => Array.from(container.querySelectorAll<HTMLElement>("thead [data-column-key]"))
      .map((header) => header.dataset.columnKey);
    const rowValues = () => Array.from(container.querySelectorAll("tbody tr:first-child td"))
      .map((cell) => cell.textContent?.trim());
    const assertColumnPresentation = () => {
      for (const key of headerKeys()) {
        const header = container.querySelector(`thead [data-column-key="${key}"]`)!;
        const cell = container.querySelector(`tbody td[data-column-key="${key}"]`)!;
        const numeric = ["quantity", "unitPrice", "discount", "total"].includes(key ?? "");
        const className = numeric ? "stock-history-cell-numeric" : "stock-history-cell-text";
        expect(header.classList.contains(className)).toBe(true);
        expect(cell.classList.contains(className)).toBe(true);
      }
      const width = Array.from(container.querySelectorAll<HTMLTableColElement>("colgroup col"))
        .reduce((total, column) => total + Number.parseFloat(column.style.width), 0);
      const table = container.querySelector("table")!;
      expect(table.style.minWidth).toBe(`${width}px`);
      expect(table.style.width).toBe("100%");
    };

    expect(headerKeys()).not.toContain("warehouse");
    expect(screen.queryByRole("columnheader", { name: /Almacén/ })).toBeNull();
    expect(headerKeys().slice(0, 2)).toEqual(["document", "status"]);
    expect(rowValues().slice(0, 2)).toEqual(["TICKET T-0001", "CONFIRMADO"]);
    expect(Array.from(container.querySelectorAll("colgroup col")).map((col) => (col as HTMLElement).style.width).slice(0, 2))
      .toEqual(["180px", "130px"]);
    expect(Array.from(container.querySelectorAll<HTMLElement>("thead [data-column-key]")).every((header) => header.draggable))
      .toBe(true);
    assertColumnPresentation();

    const values = new Map<string, string>();
    const dataTransfer = {
      effectAllowed: "move",
      dropEffect: "move",
      setData: (type: string, value: string) => values.set(type, value),
      getData: (type: string) => values.get(type) ?? ""
    };
    fireEvent.dragStart(container.querySelector('[data-column-key="user"]') as HTMLElement, { dataTransfer });
    fireEvent.dragOver(container.querySelector('[data-column-key="document"]') as HTMLElement, { dataTransfer });
    fireEvent.drop(container.querySelector('[data-column-key="document"]') as HTMLElement, { dataTransfer });
    expect(headerKeys()[0]).toBe("user");
    expect(rowValues()[0]).toBe("ADMIN");

    fireEvent.keyDown(container.querySelector('[data-column-key="user"]') as HTMLElement, {
      key: "ArrowRight",
      ctrlKey: true
    });
    expect(headerKeys().slice(0, 2)).toEqual(["document", "user"]);
    expect(rowValues().slice(0, 2)).toEqual(["TICKET T-0001", "ADMIN"]);

    const userHeader = container.querySelector('[data-column-key="user"]') as HTMLElement;
    fireEvent.keyDown(userHeader.querySelector(".table-layout-column-resizer") as HTMLButtonElement, { key: "ArrowRight" });
    const stored = JSON.parse(localStorage.getItem(
      tableLayoutStorageKey("venta", "ana", "stock.productSalesHistory")
    ) ?? "{}") as { columns: Array<{ key: string; width: number }> };
    expect(stored.columns.map((column) => column.key)).not.toContain("warehouse");
    expect(stored.columns.map((column) => column.key).slice(0, 2)).toEqual(["document", "user"]);
    expect(stored.columns.find((column) => column.key === "user")?.width).toBe(158);
    assertColumnPresentation();
  });

});
