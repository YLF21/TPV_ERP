// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, LocaleCode, UserSession } from "../types";
import { SalesReportScreen } from "./SalesReportScreen";

vi.mock("../api/client", async () => ({
  ...await vi.importActual<typeof import("../api/client")>("../api/client"),
  apiRequest: vi.fn(),
}));

const terminalContext = { storeName: "Tienda", terminalCode: "01" };
const session: UserSession = {
  username: "operator", displayName: "Operator", accessToken: "token", permissions: ["GESTION_VENTAS"],
};
const now = new Date();
const today = [now.getFullYear(), String(now.getMonth() + 1).padStart(2, "0"), String(now.getDate()).padStart(2, "0")].join("-");
type Report = "invoices" | "deliveryNotes";
type DocumentFixture = ReturnType<typeof documentFixture>;

function documentFixture(report: Report, index: number) {
  const invoice = report === "invoices";
  return {
    id: `${invoice ? "invoice" : "delivery"}-${index}`,
    tipo: invoice ? "FACTURA_VENTA" : "ALBARAN_VENTA",
    estado: "PENDIENTE", numero: `${invoice ? "FV" : "AV"}-${String(index).padStart(3, "0")}`,
    fecha: today, total: "100.00", pendiente: "100.00", clienteId: "customer-1", clienteCodigo: "C-001",
  };
}

function createReportRequest() {
  const documents = ["invoices", "deliveryNotes"].flatMap((report) =>
    [1, 2].map((index) => documentFixture(report as Report, index)));
  return vi.fn().mockImplementation(async (path: string) => {
    const url = new URL(path, "http://test.local");
    if (url.pathname === "/document-reports/invoices") {
      return { items: [1, 2].map((index) => documentFixture("invoices", index)), hasMore: false, nextCursor: null };
    }
    if (url.pathname === "/document-reports/delivery-notes") {
      return { items: [1, 2].map((index) => documentFixture("deliveryNotes", index)), hasMore: false, nextCursor: null };
    }
    const document = documents.find((item) => path.startsWith(`/documents/${item.id}/`));
    if (document && path.endsWith("/detail")) return {
      id: document.id, type: document.tipo, status: document.estado, number: document.numero,
      date: today, base: "82.64", tax: "17.36", discount: "0.00", total: document.total, lines: [],
    };
    if (document && path.endsWith("/print-copy")) return printSnapshot(document);
    if (path === "/warehouses") return [];
    if (path === "/sales/operation-security") return { operations: [] };
    return { items: [], hasMore: false, nextCursor: null };
  });
}

function printSnapshot(document: DocumentFixture) {
  return {
    documentId: document.id, documentType: document.tipo, documentNumber: document.numero,
    issueDate: today, total: document.total, lines: [], payments: [],
    renderedPdf: { contentType: "application/pdf", base64: "amFzcGVyLXBkZg==" },
  };
}

async function mountReport({ report = "invoices", app = "venta", locale = "es" }: {
  report?: Report; app?: AppKind; locale?: LocaleCode;
} = {}) {
  const request = createReportRequest();
  const printCommercialDocument = vi.fn().mockResolvedValue({ status: "PRINTED" });
  const { container } = render(<SalesReportScreen app={app} locale={locale}
    session={session} terminalContext={terminalContext}
    initialReport={`salesReport.${report}`} request={request} printCommercialDocument={printCommercialDocument}
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
  await waitFor(() => expect(container.querySelectorAll(".report-table tbody tr")).toHaveLength(2));
  const rows = () => Array.from(container.querySelectorAll<HTMLTableRowElement>(".report-table tbody tr"));
  return { request, rows, printCommercialDocument, t: createTranslator(locale) };
}

function openMenu(label = "Más acciones") {
  fireEvent.click(screen.getByRole("button", { name: label }));
  return screen.getByRole("menu");
}

beforeEach(() => {
  vi.mocked(apiRequest).mockResolvedValue([] as never);
});

afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.restoreAllMocks();
  vi.clearAllMocks();
  Reflect.deleteProperty(window, "tpvDesktop");
});

describe("Commercial report document actions", () => {
  it.each([
    ["invoices", "venta", "es", "Pagar pendiente"], ["deliveryNotes", "venta", "es", "Pagar pendiente"],
    ["invoices", "gestion", "es", "Pagar pendiente"], ["deliveryNotes", "gestion", "es", "Pagar pendiente"],
    ["invoices", "gestion", "en", "Pay outstanding balance"], ["deliveryNotes", "gestion", "en", "Pay outstanding balance"],
    ["invoices", "gestion", "zh", "支付欠款"], ["deliveryNotes", "gestion", "zh", "支付欠款"],
  ] as const)("orders copy and Excel without pending actions in %s / %s / %s", async (report, app, locale, removedPayLabel) => {
    const { rows, t } = await mountReport({ report, app, locale });
    fireEvent.click(rows()[1]);
    const menu = openMenu(t("salesReport.moreActions"));
    const actions = within(menu).getAllByRole("menuitem");
    expect(actions.slice(0, 2).map((item) => item.textContent?.trim())).toEqual([
      t("salesReport.printDocumentCopy"), t("salesReport.exportDocumentExcel"),
    ]);
    expect(within(menu).queryByRole("menuitem", { name: t("salesReport.quick.pending") })).toBeNull();
    expect(within(menu).queryByRole("menuitem", { name: removedPayLabel })).toBeNull();
    expect(within(menu).getByRole("menuitem", { name: t("salesReport.printDocumentCopy") })).toBeEnabled();
    expect(within(menu).getByRole("menuitem", { name: t("salesReport.exportDocumentExcel") })).toBeEnabled();
    expect(within(menu).getByRole("menuitem", { name: t("salesReport.openDocument") })).toBeEnabled();
    if (app === "gestion") {
      expect(within(menu).getByRole("menuitem", { name: t("salesReport.activity.open") })).toBeDisabled();
    }
  });

  it.each(["invoices", "deliveryNotes"] as const)("disables document actions without a selection in %s", async (report) => {
    await mountReport({ report });
    const menu = openMenu();
    for (const name of ["Imprimir copia", "Exportar Excel"]) {
      expect(within(menu).getByRole("menuitem", { name })).toBeDisabled();
    }
    expect(within(menu).queryByRole("menuitem", { name: "Pagar pendiente" })).toBeNull();
    expect(within(menu).queryByRole("menuitem", { name: "Pendientes" })).toBeNull();
    expect(screen.getByRole("button", { name: "Excel" })).toBeEnabled();
    expect(screen.getByRole("button", { name: "Excel" })).toHaveAttribute("aria-keyshortcuts", "F6");
  });

  it.each(["invoices", "deliveryNotes"] as const)("exports the selected %s document through the individual endpoint", async (report) => {
    const { rows, request } = await mountReport({ report });
    const selected = documentFixture(report, 2);
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { reports: { saveFile } } });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockImplementation(async () =>
      new Response(new Uint8Array([1, 2, 3]), { status: 200 }));
    fireEvent.click(rows()[1]);
    fireEvent.click(within(openMenu()).getByRole("menuitem", { name: "Exportar Excel" }));
    await waitFor(() => expect(saveFile).toHaveBeenCalledWith(
      expect.objectContaining({ defaultFileName: `${selected.numero}.xlsx` })));
    expect(fetchSpy).toHaveBeenCalledExactlyOnceWith(expect.stringContaining(`/excel/documents/${selected.id}/export`), {
      headers: { Authorization: "Bearer token" },
    });
    expect(request).not.toHaveBeenCalledWith(expect.stringMatching(/\/detail$/), expect.anything());
    expect(screen.queryByRole("dialog")).toBeNull();
    expect(screen.queryByRole("menu")).toBeNull();
  });

  it.each(["invoices", "deliveryNotes"] as const)("prints the selected %s copy through the existing Jasper document flow", async (report) => {
    const { rows, request, printCommercialDocument } = await mountReport({ report });
    const selected = documentFixture(report, 2);
    Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { hardware: {} } });
    fireEvent.click(rows()[1]);
    fireEvent.click(within(openMenu()).getByRole("menuitem", { name: "Imprimir copia" }));
    await waitFor(() => expect(printCommercialDocument).toHaveBeenCalledExactlyOnceWith(
      { ...printSnapshot(selected), kind: "COMMERCIAL_DOCUMENT" }, terminalContext, undefined, "es"));
    expect(request).toHaveBeenCalledWith(`/documents/${selected.id}/detail`, { token: "token" });
    expect(request).toHaveBeenCalledWith(`/documents/${selected.id}/print-copy`, { token: "token" });
    expect(request).not.toHaveBeenCalledWith(`/documents/${documentFixture(report, 1).id}/print-copy`, expect.anything());
    expect(screen.queryByRole("dialog")).toBeNull();
  });
});
