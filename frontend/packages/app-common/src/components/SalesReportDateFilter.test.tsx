// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SalesReportScreen } from "./SalesReportScreen";
import type { AppKind, LocaleCode } from "../types";
import { createSalesActivityTranslator } from "../i18n/SalesActivityMessages";

vi.mock("../api/client", async () => ({
  ...await vi.importActual<typeof import("../api/client")>("../api/client"),
  apiRequest: vi.fn().mockResolvedValue(null),
}));

const currentDate = "2026-09-11";
const empty = { items: [], nextCursor: null, hasMore: false };
const row = (id: string, fecha = currentDate) => ({ id, fecha, tipo: "TICKET", estado: "CONFIRMADO", numero: id, customerCode: id, customerName: id, total: "12.00" });

function fixture() {
  return vi.fn().mockImplementation(async (path: string) => {
    if (path.startsWith("/document-reports/date-options")) return { earliestDate: "2024-02-10", currentDate };
    if (path.startsWith("/document-reports/tickets")) return { ...empty, items: [row("T-DAY", new URL(path, "http://test").searchParams.get("dateFrom") || currentDate)] };
    if (path === "/warehouses") return [];
    return empty;
  });
}

function mount(request = fixture(), report = "tickets", app: AppKind = "venta", locale: LocaleCode = "es") {
  return render(<SalesReportScreen app={app} locale={locale} initialReport={`salesReport.${report}`}
    session={{ username: "operator", displayName: "Operator", accessToken: "token", permissions: ["GESTION_VENTAS", "GESTION_ALMACEN", "GESTION_CUENTAS"] }}
    terminalContext={{ storeName: "Tienda", terminalCode: "01" }} request={request}
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
}

afterEach(() => { cleanup(); localStorage.clear(); vi.restoreAllMocks(); Reflect.deleteProperty(window, "tpvDesktop"); });

describe("report quick date integration", () => {
  it.each(["tickets", "invoices", "deliveryNotes", "warehouseOutputs", "inputInvoices", "inputDeliveryNotes", "inputWarehouse"])(
    "shows the same date dock in %s without the old top buttons", async (report) => {
      const request = fixture();
      const { container } = mount(request, report);
      await waitFor(() => expect(screen.getByRole("button", { name: "Hoy" })).toBeEnabled());
      expect(container.querySelector(".report-data > .report-date-range-filter")).not.toBeNull();
      expect(screen.queryByRole("button", { name: "Esta semana" })).not.toBeInTheDocument();
      expect(screen.queryByRole("button", { name: "Este mes" })).not.toBeInTheDocument();
      expect(request).toHaveBeenCalledWith(`/document-reports/date-options?report=${report}`, { token: "token" });
      fireEvent.change(screen.getByRole("combobox", { name: "Mes" }), { target: { value: "2026-08" } });
      const resource = ({ tickets: "document-reports/tickets", invoices: "document-reports/invoices", deliveryNotes: "document-reports/delivery-notes",
        warehouseOutputs: "warehouse-outputs", inputInvoices: "document-reports/warehouse-inputs", inputDeliveryNotes: "document-reports/warehouse-inputs", inputWarehouse: "document-reports/warehouse-inputs" } as Record<string, string>)[report];
      const type = ({ inputInvoices: "FACTURA_ENTRADA", inputDeliveryNotes: "ALBARAN_ENTRADA", inputWarehouse: "ENTRADA_ALMACEN" } as Record<string, string>)[report];
      await waitFor(() => expect(request).toHaveBeenCalledWith(`/${resource}?limit=500${type ? `&type=${type}` : ""}&dateFrom=2026-08-01&dateTo=2026-08-31`, { token: "token" }));
      const otherResources = request.mock.calls.filter(([path]) => /limit=/.test(path) && !path.startsWith(`/${resource}?`));
      expect(otherResources).toHaveLength(0);
    });

  it.each(["es", "en", "zh"] as const)("is translated in APP GESTION %s", async (locale) => {
    mount(fixture(), "tickets", "gestion", locale);
    const t = createSalesActivityTranslator(locale);
    await waitFor(() => expect(screen.getByRole("button", { name: t("today") })).toBeEnabled());
    expect(screen.getByRole("combobox", { name: t("quarter") })).toBeEnabled();
  });

  it("sends the applied date range to Excel export and makes no business write", async () => {
    const request = fixture();
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { reports: { saveFile } } });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response(new Uint8Array([1]), { status: 200 }));
    mount(request);
    await waitFor(() => expect(screen.getByRole("button", { name: "Hoy" })).toBeEnabled());
    fireEvent.change(screen.getByRole("combobox", { name: "Trimestre" }), { target: { value: "2025-Q4" } });
    await waitFor(() => expect(request).toHaveBeenCalledWith("/document-reports/tickets?limit=500&dateFrom=2025-10-01&dateTo=2025-12-31", { token: "token" }));
    fireEvent.keyDown(window, { key: "F6" });
    await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(1));
    expect(JSON.parse(fetchSpy.mock.calls[0][1]?.body as string)).toMatchObject({ reportKey: "salesReport.tickets", filters: { dateFrom: "2025-10-01", dateTo: "2025-12-31" } });
    expect(request.mock.calls.every((call) => call.length === 2 && Object.keys(call[1]).join() === "token")).toBe(true);
  });

  it.each([false, true])("isolates pagination from date changes (same range: %s)", async (sameRange) => {
    let finish: (value: unknown) => void = () => {};
    const secondPage = new Promise((resolve) => { finish = resolve; });
    const base = fixture();
    const request = vi.fn().mockImplementation(async (path: string) => {
      if (path.includes("cursor=old")) return secondPage;
      if (path.startsWith("/document-reports/tickets") && path.includes(`dateFrom=${currentDate}`)) return { items: [row("T-DAY")], nextCursor: "old", hasMore: true };
      if (path.startsWith("/document-reports/tickets") && path.includes("dateFrom=2026-08-01")) return { ...empty, items: [row("T-AUGUST", "2026-08-15")] };
      return base(path);
    });
    mount(request as ReturnType<typeof fixture>);
    await waitFor(() => expect(request.mock.calls.some(([path]) => path.includes("cursor=old"))).toBe(true));
    if (sameRange) fireEvent.click(screen.getByRole("button", { name: "Hoy" }));
    else {
      fireEvent.change(screen.getByRole("combobox", { name: "Mes" }), { target: { value: "2026-08" } });
      await screen.findByText("T-AUGUST");
    }
    await act(async () => finish({ ...empty, items: [row("T-OLD-PAGE")] }));
    if (sameRange) expect(await screen.findByText("T-OLD-PAGE")).toBeInTheDocument();
    else {
      expect(screen.queryByText("T-OLD-PAGE")).not.toBeInTheDocument();
      expect(screen.queryByText("T-DAY")).not.toBeInTheDocument();
      expect(screen.getByText("T-AUGUST")).toBeInTheDocument();
    }
  });

  it("shows an actionable metadata failure, then enables dates on retry", async () => {
    const request = fixture();
    const base = request.getMockImplementation()!;
    let fail = true;
    request.mockImplementation(async (path) => {
      if (fail && path.startsWith("/document-reports/date-options")) throw new Error("unavailable");
      return base(path);
    });
    mount(request);
    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudieron cargar los datos del informe.");
    expect(screen.getByRole("button", { name: "Hoy" })).toBeDisabled();
    fail = false;
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Hoy" })).toBeEnabled());
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();
  });

  it("initializes and clears to the store day, not the browser clock", async () => {
    const request = fixture();
    const base = request.getMockImplementation()!;
    request.mockImplementation(async (path: string) => path.startsWith("/document-reports/date-options")
      ? { earliestDate: "2020-01-01", currentDate: "2025-01-01" } : base(path));
    const { container } = mount(request);
    const storeQuery = "/document-reports/tickets?limit=500&dateFrom=2025-01-01&dateTo=2025-01-01";
    await waitFor(() => expect(request).toHaveBeenCalledWith(storeQuery, { token: "token" }));
    expect(container.querySelector(".report-command-period strong")).toHaveTextContent("Hoy");
    fireEvent.click(screen.getByRole("button", { name: "Ayer" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/document-reports/tickets?limit=500&dateFrom=2024-12-31&dateTo=2024-12-31", { token: "token" }));
    request.mockClear();
    fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
    fireEvent.click(screen.getByRole("button", { name: "Limpiar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith(storeQuery, { token: "token" }));
  });
});
