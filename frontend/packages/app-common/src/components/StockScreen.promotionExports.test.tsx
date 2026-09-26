// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { LocaleCode, UserSession } from "../types";
import type { PromotionView } from "./PromotionForm";
import { StockScreen } from "./StockScreen";
import { writeStoredTableLayout } from "./tableLayoutPreferences";

vi.mock("../api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn()
}));

const warehouseId = "11111111-1111-4111-8111-111111111111";
const session: UserSession = {
  username: "promotion-exports", displayName: "Promotion exports", permissions: ["ADMIN"], accessToken: "test-token"
};
const promotions: PromotionView[] = [
  {
    id: "current", name: "Promoción vigente", type: "PURCHASE_THRESHOLD_DISCOUNT", status: "ACTIVE",
    startDate: "2026-01-01", endDate: null, scope: "PRODUCT_LIST", customerSegment: "ALL",
    memberCategoryId: null, minimumAmount: 10, minimumQuantity: null, buyQuantity: null,
    payQuantity: null, buyXPayYMode: null, discountAmount: null, discountPercent: 10,
    maximumDiscount: null, packPrice: null, used: false,
    targets: [{ type: "PRODUCT", targetId: "p-1" }]
  },
  {
    id: "expired", name: "Promoción caducada", type: "PURCHASE_THRESHOLD_DISCOUNT", status: "INACTIVE",
    startDate: "2025-01-01", endDate: "2025-01-31", scope: "PRODUCT_LIST", customerSegment: "ALL",
    memberCategoryId: null, minimumAmount: 5, minimumQuantity: null, buyQuantity: null,
    payQuantity: null, buyXPayYMode: null, discountAmount: null, discountPercent: 15,
    maximumDiscount: null, packPrice: null, used: true,
    targets: [{ type: "PRODUCT", targetId: "p-2" }]
  }
];

const saveFile = vi.fn().mockResolvedValue({ ok: true });
const fetchFile = vi.fn(async () => new Response(new Uint8Array([1, 2, 3]), {
  status: 200,
  headers: { "Content-Disposition": "attachment; filename=export.xlsx; filename*=UTF-8''Promoci%C3%B3n-%E4%BC%98%E6%83%A0.xlsx" }
}));

function exportBodies() {
  return vi.mocked(apiRequest).mock.calls
    .filter(([path, options]) => path === "/stock/exports" && options?.method === "POST")
    .map(([, options]) => options!.body as Record<string, unknown>);
}

function renderScreen(locale: LocaleCode = "es") {
  return render(<StockScreen app="venta" initialView="stock.promotions" locale={locale}
    session={session} terminalContext={{ storeName: "Test", terminalCode: "TEST" }}
    onBack={vi.fn()} onLocaleChange={vi.fn()} />);
}

beforeEach(() => {
  localStorage.clear();
  saveFile.mockClear();
  fetchFile.mockClear();
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
  Object.defineProperty(window, "tpvDesktop", { configurable: true, value: { reports: { saveFile } } });
  vi.stubGlobal("fetch", fetchFile);
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => {
    if (path.startsWith("/stock/page")) return {
      items: [
        { product: { id: "p-1", code: "T1", name: "Té actual", active: true, productType: "UNIT", salePrice: 3 },
          stock: [{ productId: "p-1", warehouseId, quantity: 4 }] },
        { product: { id: "p-2", code: "C2", name: "Café antiguo", active: true, productType: "UNIT", salePrice: 5 },
          stock: [{ productId: "p-2", warehouseId, quantity: 4 }] }
      ], hasMore: false
    };
    if (path === "/warehouses") return [{ id: warehouseId, name: "GENERAL", active: true, defaultWarehouse: true }];
    if (path === "/promotions") return promotions;
    if (path === "/families") return [];
    if (path === "/stock/exports") return { id: "job-1", status: "QUEUED", processedRows: 0, fileSize: 0 };
    if (path === "/stock/exports/job-1") return { id: "job-1", status: "COMPLETED", processedRows: 1, fileSize: 3 };
    return [];
  });
});

afterEach(() => {
  cleanup(); localStorage.clear(); vi.unstubAllGlobals();
  delete (window as { tpvDesktop?: unknown }).tpvDesktop;
  delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

describe("StockScreen promotion Excel exports", () => {
  it.each(["es", "en", "zh"] as const)("exports the selected expired promotion with its product table layout in %s", async locale => {
    const t = createTranslator(locale);
    writeStoredTableLayout("venta", session.username, "stock.promotions.products", [
      { key: "stock", width: 94, visible: true },
      { key: "name", width: 218, visible: true },
      { key: "price", width: 102, visible: true },
      { key: "code", width: 120, visible: true }
    ]);
    renderScreen(locale);
    fireEvent.click(await screen.findByRole("button", { name: /Promoción caducada/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: new RegExp(t("stock.exportExcel.selectedPromotion")) })).toHaveProperty("disabled", false));
    fireEvent.click(screen.getByRole("button", { name: t("stock.filter.inventoryTitle") }));
    const dialog = screen.getByRole("dialog", { name: t("stock.filter.inventoryTitle") });
    for (const [label, option] of [[t("stock.column.type"), t("product.type.unit")], [t("stock.column.status"), t("stock.status.low")]] as const) {
      const field = within(dialog).getByText(label, { exact: true }).closest(".filter-field")! as HTMLElement;
      fireEvent.click(within(field).getByRole("button"));
      fireEvent.click(within(field).getByRole("button", { name: option }));
    }
    fireEvent.click(within(dialog).getByRole("button", { name: t("salesReport.filter.apply") }));
    fireEvent.change(screen.getByRole("searchbox", { name: t("stock.search.article") }), { target: { value: "Café" } });
    const detail = screen.getByRole("region", { name: "Promoción caducada" });
    await within(detail).findByText("Café antiguo");
    fireEvent.click(within(detail).getByRole("button", { name: `${t("party.sortBy")} ${t("promotion.detail.price")}` }));
    fireEvent.click(screen.getByRole("button", { name: t("stock.exportExcel.selectedPromotion") }));
    await waitFor(() => expect(exportBodies()).toHaveLength(1));
    const body = exportBodies()[0];
    expect(body).toMatchObject({
      view: "promotions", promotionScope: "SELECTED", promotionId: "expired", language: locale,
      search: "", productType: null, stockStatus: null, priceUseMode: null,
      familyId: null, taxId: null, offerActive: null, supplierId: null,
      warehouseId, sortBy: "salePrice", sortDirection: "asc"
    });
    expect(body.columns).toEqual([
      { key: "stock", label: t("stock.column.stock") },
      { key: "name", label: t("stock.column.name") },
      { key: "salePrice", label: t("promotion.detail.price") },
      { key: "code", label: t("stock.column.code") },
      { key: "family", label: t("stock.column.family") },
      { key: "subfamily", label: t("stock.column.subfamily") }
    ]);
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    expect(vi.mocked(apiRequest).mock.calls).toContainEqual(["/stock/exports/job-1", { token: "test-token" }]);
    expect(saveFile).toHaveBeenCalledWith(expect.objectContaining({
      defaultFileName: "Promoción-优惠.xlsx", bytes: new Uint8Array([1, 2, 3])
    }));
    expect(fetchFile).toHaveBeenCalledWith(expect.stringContaining("/stock/exports/job-1/file"),
      expect.objectContaining({ headers: { Authorization: "Bearer test-token" } }));
  });

  it("exports all active promotions without an ID and includes promotion metadata; F6 keeps selected scope", async () => {
    const t = createTranslator("es");
    renderScreen();
    fireEvent.click(await screen.findByRole("button", { name: /Promoción caducada/ }));
    const activeButton = screen.getByRole("button", { name: t("stock.exportExcel.activePromotions") });
    await waitFor(() => expect(activeButton).toHaveProperty("disabled", false));
    fireEvent.click(activeButton);
    await waitFor(() => expect(exportBodies()).toHaveLength(1));
    expect(exportBodies()[0]).toMatchObject({
      view: "promotions", promotionScope: "ACTIVE", promotionId: null, search: "", warehouseId,
      productType: null, stockStatus: null, priceUseMode: null, language: "es"
    });
    expect((exportBodies()[0].columns as { key: string; label: string }[]).slice(-3)).toEqual([
      { key: "promotion", label: t("stock.column.promotion") },
      { key: "promotionType", label: t("stock.column.promotionType") },
      { key: "promotionValidity", label: t("stock.column.promotionValidity") }
    ]);
    await waitFor(() => expect(saveFile).toHaveBeenCalledOnce());
    fireEvent.keyDown(document.body, { key: "F6" });
    await waitFor(() => expect(exportBodies()).toHaveLength(2));
    expect(exportBodies()[1]).toMatchObject({ promotionScope: "SELECTED", promotionId: "expired" });
  });
});
