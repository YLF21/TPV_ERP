// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { renderToStaticMarkup } from "react-dom/server";
import { getHardwareBridge } from "../hardware/hardware";
import { createTranslator } from "../i18n/LocalizedMessages";
import { outputConfirmedTicketsSequentially } from "../sale/ticketPrinting";
import type { AppKind, LocaleCode, UserSession } from "../types";
import { SalesReportScreen } from "./SalesReportScreen";
import { allReports } from "./salesReportAccess";

vi.mock("../api/client", async () => ({
  ...await vi.importActual<typeof import("../api/client")>("../api/client"),
  apiRequest: vi.fn(),
}));
vi.mock("../sale/ticketPrinting", async () => ({
  ...await vi.importActual<typeof import("../sale/ticketPrinting")>("../sale/ticketPrinting"),
  outputConfirmedTicketsSequentially: vi.fn(),
}));
vi.mock("../hardware/hardware", async () => ({
  ...await vi.importActual<typeof import("../hardware/hardware")>("../hardware/hardware"),
  getHardwareBridge: vi.fn(),
}));

const terminalContext = { storeName: "Tienda", terminalCode: "01" };
const permissions: UserSession["permissions"] = ["GESTION_VENTAS"];
const now = new Date();
const today = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, "0"), String(now.getDate()).padStart(2, "0")].join("-");
const tickets = [1, 2].map((index) => ({
  id: `ticket-${index}`, tipo: "TICKET", estado: "CONFIRMADO", numero: `T-00${index}`,
  fecha: today, total: "12.10", lifecycleStatus: "CONFIRMED",
}));
const snapshot = { documentId: "ticket-2", documentNumber: "T-002", total: "12.10" };

function reportRequest(rows = tickets, securityAvailable = true) {
  return vi.fn().mockImplementation(async (path: string) => {
    if (path.startsWith("/document-reports/date-options")) return { earliestDate: today, currentDate: today };
    if (path === "/sales/operation-security") {
      return {
        storeId: "store-1", version: 1,
        operations: securityAvailable ? ["CANCEL_TICKET", "CONVERT_TICKET_TO_INVOICE"].map((code) => ({
          code, category: "TICKET", shortcuts: [], permissions: ["GESTION_VENTAS"],
          defaultRequirePermission: true, defaultRequirePassword: true,
          requirePermission: true, requirePassword: true, customized: false,
        })) : [],
      };
    }
    if (path.startsWith("/document-reports/tickets")) {
      return { items: rows, hasMore: false, nextCursor: null };
    }
    if (/^\/documents\/ticket-\d\/detail$/.test(path)) {
      const index = path.includes("ticket-2") ? 2 : 1;
      return {
        id: `ticket-${index}`, type: "TICKET", status: "CONFIRMADO", number: `T-00${index}`,
        date: today, base: "10.00", tax: "2.10", discount: "0.00", total: "12.10",
        lines: [{ id: "line-2", code: "P2", name: "Artículo", quantity: "1.000", unitPrice: "10.00",
          discount: "0.00", taxPercentage: "21.00", total: "12.10" }],
      };
    }
    if (path === "/tickets/ticket-2/print-set") return { printTicket: snapshot };
    if (path === "/warehouses") return [];
    return { items: [], hasMore: false, nextCursor: null };
  });
}

async function mountReport({
  app = "venta", locale = "es", granted = permissions, rows = tickets, securityAvailable = true,
}: { app?: AppKind; locale?: LocaleCode; granted?: UserSession["permissions"]; rows?: typeof tickets; securityAvailable?: boolean } = {}) {
  const request = reportRequest(rows, securityAvailable);
  const { container } = render(<SalesReportScreen app={app} locale={locale}
    session={{ username: "operator", displayName: "Operator", accessToken: "token", permissions: granted }}
    terminalContext={terminalContext} initialReport="salesReport.tickets" request={request}
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
  await waitFor(() => expect(container.querySelectorAll(".report-table tbody tr")).toHaveLength(2));
  const row = container.querySelectorAll<HTMLTableRowElement>(".report-table tbody tr")[1];
  return { row, request, t: createTranslator(locale) };
}

function openMenu(label = "Más acciones") {
  fireEvent.click(screen.getByRole("button", { name: label }));
  return screen.getByRole("menu");
}

beforeEach(() => {
  vi.mocked(apiRequest).mockImplementation(async (path) => {
    if (path === "/tickets/cancellation-preview?number=T-002") {
      return {
        ticket: { id: "ticket-2", numero: "T-002", fecha: today, total: "12.10" },
        manualReferences: [], integratedCardPayments: [], cashAmount: "12.10", openCashDrawer: false,
        consumedVoucherCodes: [], generatedVoucherCodes: [],
      } as never;
    }
    if (path.startsWith("/gift-receipts/preview?")) {
      return {
        ticketId: "ticket-2", ticketNumber: "T-002", issuedAt: `${today}T10:00:00Z`,
        lines: [{ lineId: "line-2", code: "P2", name: "Artículo regalo", productType: "UNIT",
          availableQuantity: "1.000", serialNumbers: [] }],
      } as never;
    }
    return [] as never;
  });
  vi.mocked(outputConfirmedTicketsSequentially).mockResolvedValue({ status: "PRINTED" } as never);
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.clearAllMocks();
  Reflect.deleteProperty(window, "tpvDesktop");
});

describe("Ticket report document actions", () => {
  it.each([
    ["venta", "es", "Descuento miembro", "Descuento del documento"],
    ["venta", "en", "Member discount", "Document discount"],
    ["venta", "zh", "会员折扣", "单据折扣"],
    ["gestion", "es", "Descuento miembro", "Descuento del documento"],
    ["gestion", "en", "Member discount", "Document discount"],
    ["gestion", "zh", "会员折扣", "单据折扣"],
  ] as const)("labels discounts by their saved origin without changing amounts in %s / %s", async (app, locale, memberLabel, manualLabel) => {
    const { row, request } = await mountReport({ app, locale });
    const baseLine = {
      code: "DESCUENTO DOCUMENTAL", name: "DESCUENTO DOCUMENTAL", quantity: "1.000",
      unitPrice: "-0.04", discount: "0.00", taxRegime: "IVA", taxPercentage: "7.00", total: "-0.04",
    };
    const lines = [
      { ...baseLine, id: "member", documentAdjustmentType: "MEMBER_PERCENT" },
      { ...baseLine, id: "manual", documentAdjustmentType: "MANUAL_PERCENT" },
      { ...baseLine, id: "legacy" },
      { ...baseLine, id: "unknown", documentAdjustmentType: "FUTURE_TYPE", name: "Original" },
      { ...baseLine, id: "product", code: "P-1", name: "Producto", unitPrice: "1.42", total: "1.42" },
    ];
    const savedLines = JSON.stringify(lines);
    request.mockResolvedValueOnce({ id: "ticket-2", type: "TICKET", status: "CONFIRMADO", number: "T-002",
      date: today, base: "1.18", tax: "0.08", discount: "0.00", total: "1.26", lines });
    request.mockClear();

    fireEvent.doubleClick(row);
    const detail = await screen.findByRole("dialog", { name: "T-002" });
    await waitFor(() => expect(within(detail).getAllByText(memberLabel)).toHaveLength(2));
    const detailRows = detail.querySelectorAll<HTMLTableRowElement>("tbody tr");
    expect(detailRows).toHaveLength(5);
    const labels = [memberLabel, manualLabel, "DESCUENTO DOCUMENTAL", "DESCUENTO DOCUMENTAL", "P-1"];
    const names = [memberLabel, manualLabel, "DESCUENTO DOCUMENTAL", "Original", "Producto"];
    detailRows.forEach((detailRow, index) => {
      expect(detailRow.cells[0]).toHaveTextContent(labels[index]);
      expect(detailRow.cells[1]).toHaveTextContent(names[index]);
      if (index < 4) {
        expect(detailRow.cells[6].textContent?.replace(/\s/g, ""))
          .toBe(locale === "es" ? "-0,04€" : "-€0.04");
      }
    });
    expect(JSON.stringify(lines)).toBe(savedLines);
    expect(request).toHaveBeenCalledExactlyOnceWith("/documents/ticket-2/detail", { token: "token" });
  });

  it.each([
    ["venta", "es"], ["venta", "en"], ["venta", "zh"],
    ["gestion", "es"], ["gestion", "en"], ["gestion", "zh"],
  ] as const)("orders ticket actions and removes the toolbar cancellation button in %s / %s", async (app, locale) => {
    const { row, t } = await mountReport({ app, locale });
    fireEvent.click(row);
    expect(screen.queryByRole("button", { name: t("sale.ticketCancel.title") })).toBeNull();
    const menu = openMenu(t("salesReport.moreActions"));
    expect(within(menu).getAllByRole("menuitem").slice(0, 5).map((item) => item.textContent?.trim())).toEqual([
      t("salesReport.printDocumentCopy"),
      t("sale.shortcut.giftReceipt"),
      t("salesReport.exportDocumentExcel"),
      t("sale.shortcut.convertInvoice"),
      t("sale.ticketCancel.title"),
    ]);
    expect(within(menu).getByRole("menuitem", { name: t("sale.ticketCancel.title") })).toBeEnabled();
  });

  it("opens the existing cancellation confirmation for the selected ticket without cancelling it", async () => {
    const { row, t } = await mountReport();
    fireEvent.click(row);
    fireEvent.click(within(openMenu()).getByRole("menuitem", { name: t("sale.ticketCancel.title") }));
    const dialog = await screen.findByRole("dialog", { name: t("sale.documentCancel.title") });
    expect(within(dialog).getByLabelText(t("sale.documentCancel.documentCode"))).toHaveValue("T-002");
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(
      "/tickets/cancellation-preview?number=T-002", { token: "token" }));
    expect(screen.queryByRole("menu")).toBeNull();
    expect(vi.mocked(apiRequest).mock.calls.some(([path]) => path.endsWith("/cancel"))).toBe(false);
    expect(outputConfirmedTicketsSequentially).not.toHaveBeenCalled();
  });

  it("keeps cancellation and conversion disabled without operation security configuration", async () => {
    const { row, t } = await mountReport({ securityAvailable: false });
    fireEvent.click(row);
    const menu = openMenu();
    expect(within(menu).getByRole("menuitem", { name: t("sale.ticketCancel.title") })).toBeDisabled();
    expect(within(menu).getByRole("menuitem", { name: t("sale.shortcut.convertInvoice") })).toBeDisabled();
  });

  it.each([
    ["es", "Visualización"],
    ["en", "Visualization"],
    ["zh", "显示设置"],
  ] as const)("removes the redundant visualization dialog and button from every report in %s", (locale, label) => {
    for (const app of ["venta", "gestion"] as const) {
      for (const initialReport of allReports) {
        const html = renderToStaticMarkup(<SalesReportScreen app={app} locale={locale}
          session={{ username: "admin", displayName: "Admin", permissions: ["ADMIN"] }}
          terminalContext={terminalContext} initialReport={initialReport}
          onBack={vi.fn()} onLocaleChange={vi.fn()} />);
        const container = document.createElement("div");
        container.innerHTML = html;
        expect(within(container).queryByRole("button", { name: label, hidden: true })).toBeNull();
        expect(within(container).queryByRole("dialog", { name: label, hidden: true })).toBeNull();
      }
    }
  });

  it.each(["venta", "gestion"] as const)("keeps header column visibility and the selected ticket in %s", async (app) => {
    const { row } = await mountReport({ app });
    fireEvent.click(row);
    const dateHeader = document.querySelector('.report-table th[data-column-key="date"]')!;
    fireEvent.click(within(dateHeader as HTMLElement).getByRole("button", { name: "Opciones de columna" }));
    const timeOption = screen.getByRole("menuitemcheckbox", { name: "Hora" });
    expect(timeOption).toHaveAttribute("aria-checked", "true");
    fireEvent.click(timeOption);
    expect(document.querySelector('.report-table th[data-column-key="time"]')).not.toBeInTheDocument();
    expect(row).toHaveAttribute("aria-selected", "true");
    fireEvent.click(screen.getByRole("menuitemcheckbox", { name: "Hora" }));
    expect(document.querySelector('.report-table th[data-column-key="time"]')).toBeInTheDocument();
    fireEvent.keyDown(window, { key: "Escape" });
    expect(row).toHaveAttribute("aria-selected", "true");
  });

  it("does not load the obsolete visualization preferences", async () => {
    await mountReport();
    fireEvent.click(screen.getByRole("button", { name: "Factura" }));
    await waitFor(() => expect(screen.getByRole("heading", { name: "Factura" })).toBeVisible());
    expect(vi.mocked(apiRequest).mock.calls.map(([path]) => path))
      .not.toEqual(expect.arrayContaining([expect.stringContaining("/visualization-preferences")]));
  });

  it.each(["venta", "gestion"] as const)("keeps column controls in every document report in %s", async (app) => {
    const { t } = await mountReport({ app, granted: ["ADMIN"] });
    for (const reportKey of allReports.filter((key) =>
      key !== "salesReport.dailySales" && key !== "salesReport.salesDocuments"
    )) {
      fireEvent.click(screen.getByRole("button", { name: t(reportKey) }));
      await waitFor(() => expect(screen.getByRole("heading", { name: t(reportKey) })).toBeVisible());
      const header = document.querySelector<HTMLElement>('.report-table th[data-column-key]:not([data-column-key="time"]):not([data-column-key="total"])')!;
      fireEvent.click(within(header).getByRole("button", { name: "Opciones de columna" }));
      const purchase = reportKey === "salesReport.inputInvoices" || reportKey === "salesReport.inputDeliveryNotes";
      const columnKey = purchase ? "warehouse" : "time";
      const columnLabel = purchase ? "Almacén" : "Hora";
      const option = screen.getByRole("menuitemcheckbox", { name: columnLabel });
      const wasVisible = option.getAttribute("aria-checked") === "true";
      fireEvent.click(option);
      expect(document.querySelector(`.report-table th[data-column-key="${columnKey}"]`) !== null).toBe(!wasVisible);
      fireEvent.click(screen.getByRole("menuitemcheckbox", { name: columnLabel }));
      expect(document.querySelector(`.report-table th[data-column-key="${columnKey}"]`) !== null).toBe(wasVisible);
      fireEvent.keyDown(window, { key: "Escape" });
      expect(screen.queryByRole("button", { name: "Visualización", hidden: true })).toBeNull();
    }
    expect(vi.mocked(apiRequest).mock.calls.map(([path]) => path))
      .toEqual(expect.arrayContaining([expect.stringContaining("/ui/table-preferences/")]));
  });

  it("disables document actions without a selected row and keeps the report Excel button", async () => {
    await mountReport();
    const menu = openMenu();
    for (const name of ["Imprimir copia", "Imprimir ticket regalo", "Exportar Excel", "Convertir ticket a factura", "Anular ticket"]) {
      expect(within(menu).getByRole("menuitem", { name })).toBeDisabled();
    }
    const reportExcel = screen.getByRole("button", { name: "Excel" });
    expect(reportExcel).toBeEnabled();
    expect(reportExcel).toHaveAttribute("aria-keyshortcuts", "F6");
  });

  it("exports only the selected ticket without opening its detail or writing business data", async () => {
    const { row, request } = await mountReport();
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { reports: { saveFile } } });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
      new Response(new Uint8Array([1, 2, 3]), { status: 200 }));
    fireEvent.click(row);
    fireEvent.click(within(openMenu()).getByRole("menuitem", { name: "Exportar Excel" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledWith(expect.objectContaining({ defaultFileName: "T-002.xlsx" })));
    expect(fetchSpy).toHaveBeenCalledWith(expect.stringContaining("/excel/documents/ticket-2/export"), {
      headers: { Authorization: "Bearer token" },
    });
    expect(fetchSpy).toHaveBeenCalledTimes(1);
    expect(request).not.toHaveBeenCalledWith(expect.stringMatching(/\/detail$/), expect.anything());
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("menu")).not.toBeInTheDocument();
  });

  it("reports export failure and enables retry", async () => {
    const { row } = await mountReport();
    vi.spyOn(globalThis, "fetch").mockResolvedValue(new Response("Error", { status: 500 }));
    fireEvent.click(row);
    fireEvent.click(within(openMenu()).getByRole("menuitem", { name: "Exportar Excel" }));
    expect(await screen.findByRole("alert")).toHaveTextContent(createTranslator("es")("salesReport.documentExcelError"));
    expect(within(openMenu()).getByRole("menuitem", { name: "Exportar Excel" })).toBeEnabled();
  });

  it("prints the selected ticket through the existing historical print-set without opening the detail", async () => {
    const { row, request } = await mountReport();
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { hardware: {} } });
    fireEvent.click(row);
    fireEvent.click(within(openMenu()).getByRole("menuitem", { name: "Imprimir copia" }));
    await waitFor(() => expect(outputConfirmedTicketsSequentially).toHaveBeenCalledWith(
      [snapshot], terminalContext, "DEFAULT", "es"));
    expect(request).toHaveBeenCalledWith("/tickets/ticket-2/print-set", { token: "token" });
    expect(request).not.toHaveBeenCalledWith("/tickets/ticket-1/print-set", expect.anything());
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
  });

  it.each(["venta", "gestion"] as const)("opens the selected gift ticket from both locations in %s and restores the detail on Escape", async (app) => {
    const { row } = await mountReport({ app });
    fireEvent.click(row);
    fireEvent.click(within(openMenu()).getByRole("menuitem", { name: "Imprimir ticket regalo" }));
    let gift = await screen.findByRole("dialog", { name: "Imprimir ticket regalo" });
    await within(gift).findByText("Artículo regalo");
    expect(within(gift).getByLabelText("N.º de ticket")).toHaveValue("T-002");
    expect(apiRequest).toHaveBeenCalledWith("/gift-receipts/preview?ticketNumber=T-002", { token: "token" });
    expect(apiRequest).not.toHaveBeenCalledWith("/tickets/last-current-terminal", expect.anything());
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Más acciones" })).toHaveFocus();

    fireEvent.doubleClick(row);
    const detail = await screen.findByRole("dialog", { name: "T-002" });
    const giftButton = within(detail).getByRole("button", { name: "Imprimir ticket regalo" });
    await waitFor(() => expect(giftButton).toBeEnabled());
    const linesScroll = detail.querySelector(".report-document-lines-scroll")!;
    linesScroll.scrollTop = 120;
    fireEvent.click(giftButton);
    gift = await screen.findByRole("dialog", { name: "Imprimir ticket regalo" });
    await within(gift).findByText("Artículo regalo");
    expect(screen.queryByRole("dialog", { name: "T-002" })).not.toBeInTheDocument();
    expect(fireEvent.keyDown(window, { key: "F5" })).toBe(false);
    expect(fireEvent.keyDown(window, { key: "F6" })).toBe(false);
    expect(outputConfirmedTicketsSequentially).not.toHaveBeenCalled();
    expect(vi.mocked(apiRequest).mock.calls.some(([, options]) => options?.method === "POST")).toBe(false);
    fireEvent.keyDown(window, { key: "Escape" });
    expect(screen.getByRole("dialog", { name: "T-002" })).toBeVisible();
    expect(detail.querySelector(".report-document-lines-scroll")).toBe(linesScroll);
    expect(linesScroll.scrollTop).toBe(120);
    expect(screen.getByRole("button", { name: "Imprimir ticket regalo" })).toHaveFocus();
    expect(screen.getByRole("button", { name: "Exportar Excel" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Imprimir copia" })).toBeVisible();
  });

  it.each(["en", "zh"] as const)("uses translated menu and detail actions in %s", async (locale) => {
    const { row, t } = await mountReport({ locale });
    fireEvent.click(row);
    const menu = openMenu(t("salesReport.moreActions"));
    for (const key of ["salesReport.exportDocumentExcel", "salesReport.printDocumentCopy", "sale.shortcut.giftReceipt"]) {
      expect(within(menu).getByRole("menuitem", { name: t(key) })).toBeEnabled();
    }
    fireEvent.click(screen.getByRole("button", { name: t("salesReport.moreActions") }));
    fireEvent.doubleClick(row);
    const detail = await screen.findByRole("dialog", { name: "T-002" });
    await waitFor(() => expect(within(detail).getByRole("button", { name: t("sale.shortcut.giftReceipt") })).toBeEnabled());
  });

  it("creates and prints the selected gift through Jasper only after explicit article selection and confirmation", async () => {
    const { row } = await mountReport();
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    vi.mocked(getHardwareBridge).mockReturnValue({
      getHardwareConfig: vi.fn().mockResolvedValue({}), printTicket,
    } as unknown as ReturnType<typeof getHardwareBridge>);
    const previewRequest = vi.mocked(apiRequest).getMockImplementation()!;
    vi.mocked(apiRequest).mockImplementation(async (path, options) => {
      if (path === "/gift-receipts" && options?.method === "POST") return {
        id: "gift-2", code: "RG-T-002", issuedAt: `${today}T10:00:00Z`,
        sourceTicketId: "ticket-2", sourceTicketNumber: "T-002",
        lines: [{ sourceLineId: "line-2", code: "P2", name: "Artículo regalo", quantity: "1.000", serialNumbers: [] }],
      } as never;
      if (path === "/gift-receipts/RG-T-002/print-document") return {
        renderedPdf: { contentType: "application/pdf", base64: "cGRm" },
        ticketRenderedImage: { contentType: "image/png", base64: "cG5n" },
      } as never;
      return previewRequest(path, options) as never;
    });
    fireEvent.click(row);
    fireEvent.click(within(openMenu()).getByRole("menuitem", { name: "Imprimir ticket regalo" }));
    const gift = await screen.findByRole("dialog", { name: "Imprimir ticket regalo" });
    await within(gift).findByText("Artículo regalo");
    expect(printTicket).not.toHaveBeenCalled();
    expect(within(gift).getByRole("button", { name: "Generar e imprimir" })).toBeDisabled();
    fireEvent.click(within(gift).getByRole("button", { name: "Seleccionar todo el ticket" }));
    fireEvent.click(within(gift).getByRole("button", { name: "Generar e imprimir" }));
    await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
    expect(apiRequest).toHaveBeenCalledWith("/gift-receipts", {
      token: "token", method: "POST", body: {
        requestId: expect.any(String), ticketNumber: "T-002",
        lines: [{ lineId: "line-2", quantity: 1, serialNumbers: [] }],
      },
    });
    expect(printTicket).toHaveBeenCalledWith(expect.objectContaining({
      requireRenderedDocument: true, layout: "GIFT_RECEIPT", documentNumber: "RG-T-002",
      renderedPdf: { contentType: "application/pdf", base64: "cGRm" },
      documentRaster: "data:image/png;base64,cG5n",
    }), expect.anything());
  });

  it("does not expose gift generation to an accounts/read-only user", async () => {
    const { row } = await mountReport({ granted: ["GESTION_CUENTAS", "TICKETS_READ"] });
    fireEvent.click(row);
    expect(within(openMenu()).queryByRole("menuitem", { name: "Imprimir ticket regalo" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Más acciones" }));
    fireEvent.doubleClick(row);
    const detail = await screen.findByRole("dialog", { name: "T-002" });
    expect(within(detail).queryByRole("button", { name: "Imprimir ticket regalo" })).not.toBeInTheDocument();
  });

  it.each(["ANULADO", "BORRADOR"])("disables gift generation for a %s ticket", async (estado) => {
    const { row } = await mountReport({ rows: tickets.map((ticket) => ({ ...ticket, estado })) });
    fireEvent.click(row);
    expect(within(openMenu()).getByRole("menuitem", { name: "Imprimir ticket regalo" })).toBeDisabled();
  });

  it("disables gift generation for an already invoiced ticket", async () => {
    const { row } = await mountReport({ rows: tickets.map((ticket) => ({ ...ticket, invoiceNumber: "FV-001" })) });
    fireEvent.click(row);
    expect(within(openMenu()).getByRole("menuitem", { name: "Imprimir ticket regalo" })).toBeDisabled();
  });
});
