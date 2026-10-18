// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { AppKind, UserSession } from "../types";
import { StockScreen } from "./StockScreen";
import { writeStoredTableLayout } from "./tableLayoutPreferences";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn()
}));

const warehouseId = "11111111-1111-4111-8111-111111111111";
const session: UserSession = {
  username: "document-exports", displayName: "Document exports", permissions: ["ADMIN"], accessToken: "test-token"
};
const documentViews = ["stock.current", "stock.topSales", "stock.offers", "stock.memberPrice", "stock.noDiscount"] as const;
type DocumentView = typeof documentViews[number];
const apps = ["venta", "gestion"] as const;

const saveFile = vi.fn().mockResolvedValue({ ok: true });
const fetchFile = vi.fn(async () => new Response(new Uint8Array([1, 2, 3]), {
  status: 200,
  headers: { "Content-Disposition": "attachment; filename=stock.pdf" }
}));

function exportPosts() {
  return vi.mocked(apiRequest).mock.calls.filter(([path, options]) =>
    (path === "/stock/exports" || path === "/stock/exports/pdf") && options?.method === "POST");
}

function exportButton(shortcut: "F6" | "F7") {
  return document.querySelector<HTMLButtonElement>(`.stock-export-button[aria-keyshortcuts="${shortcut}"]`);
}

async function renderScreen(app: AppKind, initialView: DocumentView | "stock.promotions" | "stock.bulkEdit" = "stock.current") {
  const result = render(<StockScreen app={app} initialView={initialView} locale="es" session={session}
    terminalContext={{ storeName: "Test", terminalCode: "TEST" }} allowSafeRetirement
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
  if (initialView === "stock.bulkEdit") await screen.findByRole("button", { name: "Nuevo F5" });
  else if (initialView === "stock.topSales") await screen.findByRole("searchbox", { name: "Buscar top ventas" });
  else await screen.findByRole("searchbox", { name: "Buscar artículo" });
  return result;
}

beforeEach(() => {
  localStorage.clear();
  saveFile.mockReset().mockResolvedValue({ ok: true });
  fetchFile.mockReset().mockImplementation(async () => new Response(new Uint8Array([1, 2, 3]), {
    status: 200, headers: { "Content-Disposition": "attachment; filename=stock.pdf" }
  }));
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
  Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { reports: { saveFile } } });
  vi.stubGlobal("fetch", fetchFile);
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => {
    if (path.startsWith("/stock/page")) return {
      items: [{ product: {
        id: "p-1", code: "CAFE-1", name: "Café de prueba", active: true, productType: "UNIT",
        priceUseMode: "NORMAL", salePrice: 4.5, purchasePrice: 2
      }, stock: [{ productId: "p-1", warehouseId, quantity: 3 }] }], hasMore: false
    };
    if (path.startsWith("/stock/top-sales")) return [];
    if (path === "/document-reports/date-options?report=topSales") return {
      currentDate: "2026-10-15", earliestDate: "2026-01-01"
    };
    if (path === "/warehouses") return [{ id: warehouseId, name: "GENERAL", active: true, defaultWarehouse: true }];
    if (path === "/promotions" || path === "/families" || path === "/products" || path === "/product-bulk-edits") return [];
    if (path === "/stock/exports" || path === "/stock/exports/pdf") return {
      id: "job-1", status: "COMPLETED", processedRows: 1, fileSize: 3
    };
    return [];
  });
});

afterEach(() => {
  cleanup(); localStorage.clear(); vi.unstubAllGlobals();
  delete (window as { tpvDesktop?: unknown }).tpvDesktop;
  delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

describe("StockScreen document exports", () => {
  it.each(apps.flatMap(app => documentViews.map(view => [app, view] as const)))(
    "%s %s offers F6 Excel and F7 PDF using the same scoped body", async (app, view) => {
      await renderScreen(app, view);
      await waitFor(() => expect(exportButton("F7")).not.toBeNull());
      expect(exportButton("F6")).not.toBeNull();
      expect(document.querySelector(".stock-document-screen")).not.toBeNull();
      fireEvent.click(exportButton("F7")!);
      await waitFor(() => expect(exportPosts()).toHaveLength(1));
      expect(exportPosts()[0][0]).toBe("/stock/exports/pdf");
      const pdfBody = exportPosts()[0][1]!.body;
      await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
      expect(saveFile).toHaveBeenCalledWith(expect.objectContaining({
        defaultFileName: "stock.pdf", filters: [{ name: "PDF", extensions: ["pdf"] }],
        bytes: new Uint8Array([1, 2, 3])
      }));
      expect(fetchFile).toHaveBeenCalledWith(expect.stringContaining("/stock/exports/job-1/file"),
        expect.objectContaining({ headers: { Authorization: "Bearer test-token" } }));
      await waitFor(() => expect(exportButton("F6")).toHaveProperty("disabled", false));
      fireEvent.keyDown(window, { key: "F6" });
      await waitFor(() => expect(exportPosts()).toHaveLength(2));
      expect(exportPosts()[1][0]).toBe("/stock/exports");
      expect(exportPosts()[1][1]!.body).toEqual(pdfBody);
      expect(pdfBody).toMatchObject({ language: "es" });
      expect(pdfBody).not.toHaveProperty("promotionId");
    }
  );

  it("exports only visible columns in their displayed order with active search, sort and stock filters", async () => {
    writeStoredTableLayout("venta", session.username, "stock.current", [
      { key: "salePrice", width: 90, visible: true },
      { key: "name", width: 220, visible: true },
      { key: "code", width: 100, visible: false }
    ]);
    await renderScreen("venta");
    fireEvent.change(screen.getByRole("searchbox", { name: "Buscar artículo" }), { target: { value: "Café" } });
    fireEvent.click(screen.getByRole("button", { name: "Filtrar stock" }));
    const filter = screen.getByRole("dialog", { name: "Filtrar stock" });
    const t = createTranslator("es");
    const typeField = within(filter).getByText(t("stock.column.type"), { exact: true }).closest(".filter-field")! as HTMLElement;
    fireEvent.click(within(typeField).getByRole("button"));
    fireEvent.click(within(typeField).getByRole("button", { name: t("product.type.unit") }));
    fireEvent.click(within(filter).getByRole("button", { name: t("salesReport.filter.apply") }));
    fireEvent.click(screen.getByRole("button", { name: t("stock.column.name") }));
    fireEvent.click(exportButton("F7")!);
    await waitFor(() => expect(exportPosts()).toHaveLength(1));
    const body = exportPosts()[0][1]!.body as Record<string, unknown>;
    expect(body).toMatchObject({ view: "", search: "Café", productType: "UNIT",
      warehouseId, sortBy: "name", sortDirection: "asc" });
    expect((body.columns as { key: string }[]).map(column => column.key).slice(0, 2)).toEqual(["salePrice", "name"]);
    expect((body.columns as { key: string }[]).some(column => column.key === "code")).toBe(false);
  });

  it("sends selected top-sales dates and sort with the PDF request", async () => {
    await renderScreen("venta", "stock.topSales");
    fireEvent.click(exportButton("F7")!);
    await waitFor(() => expect(exportPosts()).toHaveLength(1));
    const body = exportPosts()[0][1]!.body as Record<string, unknown>;
    expect(body).toMatchObject({ view: "top_sales", sortBy: "ranking", sortDirection: "asc" });
    expect(body.dateFrom).toMatch(/^\d{4}-\d{2}-\d{2}$/);
    expect(body.dateTo).toMatch(/^\d{4}-\d{2}-\d{2}$/);
  });

  it("keeps F7 inside product detail for editing and F9 available in management", async () => {
    const { container } = await renderScreen("gestion", "stock.current");
    expect(document.querySelector('button.safe-retirement-open[aria-keyshortcuts="F9"]')).not.toBeNull();
    fireEvent.doubleClick(container.querySelector("article.stock-row")!);
    const detail = await screen.findByRole("dialog", { name: "Información del producto" });
    fireEvent.keyDown(detail, { key: "F7" });
    expect(exportPosts()).toHaveLength(0);
  });

  it("does not launch PDF with F7 while a filter modal is open", async () => {
    await renderScreen("venta");
    fireEvent.click(screen.getByRole("button", { name: "Filtrar stock" }));
    const filter = screen.getByRole("dialog", { name: "Filtrar stock" });
    fireEvent.keyDown(filter, { key: "F7" });
    expect(exportPosts()).toHaveLength(0);
  });

  it.each(["stock.promotions", "stock.bulkEdit"] as const)("does not offer PDF in %s", async view => {
    await renderScreen("venta", view);
    expect(exportButton("F7")).toBeNull();
    fireEvent.keyDown(window, { key: "F7" });
    expect(exportPosts()).toHaveLength(0);
  });

  it("does not offer PDF in APP PDA", async () => {
    await renderScreen("pda");
    expect(exportButton("F7")).toBeNull();
    fireEvent.keyDown(window, { key: "F7" });
    expect(exportPosts()).toHaveLength(0);
  });

  it("uses a PDF fallback name and never reports success after a canceled save", async () => {
    fetchFile.mockImplementationOnce(async () => new Response(new Uint8Array([1, 2, 3]), { status: 200 }));
    saveFile.mockResolvedValueOnce({ ok: true, canceled: true });
    await renderScreen("venta");
    fireEvent.click(exportButton("F7")!);
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    expect((saveFile.mock.calls[0][0] as { defaultFileName: string }).defaultFileName).toMatch(/\.pdf$/);
    await waitFor(() => expect(document.querySelector(".stock-export-notice.success")).toBeNull());
  });

  it("reports a failed PDF job without saving a file", async () => {
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (path === "/stock/exports/pdf") return { id: "job-1", status: "FAILED", error: "PDF fallido" };
      if (path.startsWith("/stock/page")) return { items: [], hasMore: false };
      if (path === "/warehouses") return [{ id: warehouseId, name: "GENERAL", active: true, defaultWarehouse: true }];
      return [];
    });
    await renderScreen("venta");
    fireEvent.click(exportButton("F7")!);
    expect((await screen.findByRole("alert")).textContent).toContain("PDF fallido");
    expect(saveFile).not.toHaveBeenCalled();
  });
});
