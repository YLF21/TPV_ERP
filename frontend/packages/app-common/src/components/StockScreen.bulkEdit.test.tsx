// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { StockScreen, type StockInventoryRow } from "./StockScreen";
import type { StockBulkDraftView, StockBulkEditRowData } from "./stockBulkEdit";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});

const originalScrollIntoView = Object.getOwnPropertyDescriptor(HTMLElement.prototype, "scrollIntoView");
beforeEach(() => {
  Object.defineProperty(HTMLElement.prototype, "scrollIntoView", { configurable: true, value: vi.fn() });
  vi.stubGlobal("fetch", vi.fn(async (input: string) => {
    if (String(input).endsWith("/images")) return Response.json([]);
    if (String(input) === "/__tpv/backend-address") return Response.json({ backendLabel: "PRUEBA" });
    throw new Error(`Unexpected fetch: ${String(input)}`);
  }));
});
afterEach(() => {
  cleanup();
  localStorage.clear();
  vi.resetAllMocks();
  vi.unstubAllGlobals();
  if (originalScrollIntoView) Object.defineProperty(HTMLElement.prototype, "scrollIntoView", originalScrollIntoView);
  else delete (HTMLElement.prototype as Partial<HTMLElement>).scrollIntoView;
});

const initialProduct: StockInventoryRow = {
  productId: "product-1", version: 4, active: "common.yes",
  warehouseId: "warehouse-1", warehouseName: "GENERAL", quantity: 3, totalQuantity: 3,
  code: "CAFE-1", barcode: "8430000000001", name: "Café de prueba", description: "Tueste natural",
  purchasePrice: "2.00", salePrice: "4.50", memberPrice: "4.00", wholesalePrice: "3.50", offerPrice: "4.25",
  productType: "UNIT", discountType: "NORMAL", backendDiscountType: "NORMAL",
  familyId: "family-1", familyName: "Bebidas", subfamilyId: "subfamily-1", subfamilyName: "Café",
  taxId: "tax-1", taxName: "7%", taxesIncluded: "common.yes", offerActive: "common.no", offerFrom: "-", offerUntil: "-"
};

function rowFor(product: StockInventoryRow): StockBulkEditRowData {
  return { id: "row-1", selected: true, query: product.code, product: { ...product }, draft: {} };
}

type ApplyBody = {
  version: number;
  updates: Array<{ productId: string; expectedVersion: number; product: Record<string, unknown> }>;
  content: StockBulkEditRowData[];
};

function mockBulkEditing(
  canonicalProducts: StockInventoryRow[],
  scenario: { refreshedProductOnOpen?: StockInventoryRow; applyError?: ApiError } = {}
) {
  const now = "2026-09-20T10:00:00Z";
  let currentProductVersion = scenario.refreshedProductOnOpen?.version ?? initialProduct.version!;
  let currentDraft: StockBulkDraftView = {
    id: "draft-v1", code: "EM-000001", seriesId: "series-1", versionNumber: 1,
    name: "Lista de prueba", status: "PENDING", content: [rowFor(initialProduct)], version: 2,
    createdById: "user-1", createdBy: "DEMO", createdAt: now,
    updatedById: "user-1", updatedBy: "DEMO", updatedAt: now, comments: []
  };
  const saves: Array<{ path: string; version: number; content: StockBulkEditRowData[] }> = [];
  const applies: Array<{ path: string; body: ApplyBody }> = [];
  const applyAttempts: Array<{ path: string; body: ApplyBody }> = [];
  const reads: string[] = [];
  let delayedRefreshCount = 0;
  const copy = <T,>(value: T): T => structuredClone(value);

  vi.mocked(apiRequest).mockImplementation(async (path, options) => {
    if (path === "/connectivity") return { saasConnected: false };
    if (path.startsWith("/ui/table-preferences/")) return { app: "venta", tableKey: path.split("/").at(-1), columns: [] };
    if (path.startsWith("/stock/page")) {
      // A later stock page cannot provide the canonical version used in the second apply.
      if (applies.length > 0) {
        delayedRefreshCount++;
        return new Promise(() => {});
      }
      return {
        items: [
          { product: { id: "product-1", version: 4, code: "CAFE-1", name: "Café de prueba", familyId: "family-1", subfamilyId: "subfamily-1", taxId: "tax-1", productType: "UNIT", discountType: "NORMAL", salePrice: "4.50", purchasePrice: "2.00", taxesIncluded: true, active: true }, stock: [{ productId: "product-1", warehouseId: "warehouse-1", quantity: 3 }] },
          { product: { id: "product-2", version: 8, code: "CABLE-1", name: "Cable de catálogo", familyId: "family-2", taxId: "tax-2", productType: "UNIT", discountType: "NORMAL", salePrice: "5.00", purchasePrice: "2.50", taxesIncluded: true, active: true }, stock: [{ productId: "product-2", warehouseId: "warehouse-1", quantity: 2 }] }
        ], hasMore: false
      };
    }
    if (path === "/warehouses") return [{ id: "warehouse-1", name: "GENERAL", active: true, defaultWarehouse: true }];
    if (path === "/families") return [{ id: "family-1", familyCode: "001", name: "Bebidas" }, { id: "family-2", familyCode: "002", name: "Electrónica" }];
    if (path === "/families/family-1/subfamilies") return [{ id: "subfamily-1", familyId: "family-1", name: "Café" }];
    if (path === "/families/family-2/subfamilies" || path === "/promotions" || path === "/product-bulk-edits/product-suppliers") return [];
    if (path === "/taxes/selectable") return [{ id: "tax-1", percentage: 7 }, { id: "tax-2", percentage: 3 }];
    if (path === "/product-bulk-edits") return [copy(currentDraft)];
    if (path === `/product-bulk-edits/${currentDraft.id}` && (!options?.method || options.method === "GET")) {
      reads.push(path);
      // GET can return current master data without mutating the saved draft or its version.
      if (scenario.refreshedProductOnOpen && saves.length === 0) {
        return { ...copy(currentDraft), content: [rowFor(scenario.refreshedProductOnOpen)], productSnapshotsRefreshed: true };
      }
      return copy(currentDraft);
    }
    if (path === `/product-bulk-edits/${currentDraft.id}` && options?.method === "PUT") {
      const body = options.body as { version: number; name: string; content: StockBulkEditRowData[] };
      if (body.version !== currentDraft.version) throw new ApiError("Lista obsoleta", 409, { code: "bulk_edit_stale_version" });
      saves.push({ path, version: body.version, content: copy(body.content) });
      // An applied list creates a separate pending V2, exactly as the service contract does.
      currentDraft = currentDraft.status === "APPLIED"
        ? { ...currentDraft, id: "draft-v2", code: "EM-000002", previousVersionId: currentDraft.id, versionNumber: 2, status: "PENDING", version: 0, name: body.name, content: copy(body.content) }
        : { ...currentDraft, version: currentDraft.version + 1, name: body.name, content: copy(body.content) };
      return copy(currentDraft);
    }
    if (path === `/product-bulk-edits/${currentDraft.id}/apply` && options?.method === "POST") {
      const body = options.body as ApplyBody;
      applyAttempts.push({ path, body: copy(body) });
      if (body.version !== currentDraft.version) throw new ApiError("Lista obsoleta", 409, { code: "bulk_edit_stale_version" });
      if (body.updates.some((update) => update.expectedVersion !== currentProductVersion)) {
        throw new ApiError("Producto obsoleto", 409, { code: "product_stale_version" });
      }
      if (scenario.applyError) throw scenario.applyError;
      const canonicalProduct = canonicalProducts[applies.length];
      if (!canonicalProduct) throw new Error("Unexpected extra apply");
      applies.push({ path, body: copy(body) });
      // Independent canonical response: never echo the submitted version or applied content.
      currentProductVersion = canonicalProduct.version!;
      currentDraft = { ...currentDraft, status: "APPLIED", version: currentDraft.version + 1, content: [rowFor(canonicalProduct)], appliedAt: now, appliedBy: "DEMO" };
      return copy(currentDraft);
    }
    throw new Error(`Unexpected API request: ${options?.method || "GET"} ${path}`);
  });
  return { saves, applies, applyAttempts, reads, delayedRefreshCount: () => delayedRefreshCount };
}

async function openWorkspace() {
  render(<StockScreen app="venta" locale="es" initialView="stock.bulkEdit"
    session={{ username: "demo", displayName: "DEMO", permissions: ["ADMIN"], accessToken: "test-token" }}
    terminalContext={{ storeName: "Prueba", terminalCode: "DEMO" }} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
  fireEvent.doubleClick((await screen.findByText("Lista de prueba")).closest("tr")!);
  await screen.findByRole("button", { name: "Aplicar cambios" });
  await waitFor(() => expect(screen.getAllByRole("button", { name: "Impuesto" }).length).toBeGreaterThan(0));
}

async function selectTax(label: string) {
  fireEvent.click(screen.getAllByRole("button", { name: "Impuesto" })[0]);
  const dialog = await screen.findByRole("dialog", { name: "Impuesto %" });
  fireEvent.click(within(dialog).getByRole("option", { name: label }));
  fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Impuesto %" })).toBeNull());
}

async function selectFamily(label: string) {
  fireEvent.click(screen.getAllByRole("button", { name: "Familia" })[0]);
  const dialog = await screen.findByRole("dialog", { name: "Familia" });
  fireEvent.click(within(dialog).getByRole("button", { name: label }));
  fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar" }));
  await waitFor(() => expect(screen.queryByRole("dialog", { name: "Familia" })).toBeNull());
}

async function confirmApplyChanges() {
  fireEvent.click(screen.getByRole("button", { name: "Aplicar cambios" }));
  const dialog = await screen.findByRole("dialog", { name: "Aplicar cambios" });
  fireEvent.click(within(dialog).getByRole("button", { name: "Aplicar cambios" }));
}

async function applyChanges() {
  await confirmApplyChanges();
  await waitFor(() => expect(screen.getByText("Cambios aplicados", { exact: true })).toBeTruthy());
}

describe("Stock bulk editing save and apply", () => {
  it("applies a tax and family change together using the product version and clearing the old subfamily", async () => {
    const canonical = { ...initialProduct, version: 5, taxId: "tax-2", taxName: "3%", familyId: "family-2", familyName: "Electrónica", subfamilyId: "-", subfamilyName: "-" };
    const backend = mockBulkEditing([canonical]);
    await openWorkspace();
    await selectTax("3%");
    await selectFamily("Electrónica");
    await applyChanges();

    expect(backend.saves).toHaveLength(1);
    expect(backend.applies).toHaveLength(1);
    expect(backend.applies[0].body.version).toBe(3);
    expect(backend.applies[0].body.updates).toEqual([expect.objectContaining({
      productId: "product-1", expectedVersion: 4,
      product: expect.objectContaining({ taxId: "tax-2", familyId: "family-2", subfamilyId: null })
    })]);
  });

  it.each([false, true])("retains the canonical version and tax for a second family apply in V2 (reopen: %s)", async (reopen) => {
    const afterTax = { ...initialProduct, version: 5, taxId: "tax-2", taxName: "3%" };
    const afterFamily = { ...afterTax, version: 6, familyId: "family-2", familyName: "Electrónica", subfamilyId: "-", subfamilyName: "-" };
    const backend = mockBulkEditing([afterTax, afterFamily]);
    await openWorkspace();
    await selectTax("3%");
    await applyChanges();
    await waitFor(() => expect(backend.delayedRefreshCount()).toBeGreaterThan(0));
    if (reopen) {
      fireEvent.keyDown(document.body, { key: "Escape" });
      const listName = await screen.findByText("Lista de prueba");
      fireEvent.doubleClick(listName.closest("tr")!);
      await screen.findByRole("button", { name: "Aplicar cambios" });
    }
    await selectFamily("Electrónica");
    await applyChanges();

    expect(backend.applies).toHaveLength(2);
    expect(backend.applies[0].body.updates[0].expectedVersion).toBe(4);
    expect(backend.applies[1].path).toBe("/product-bulk-edits/draft-v2/apply");
    expect(backend.applies[1].body.version).toBe(reopen ? 1 : 0);
    expect(backend.applies[1].body.updates).toEqual([expect.objectContaining({
      productId: "product-1", expectedVersion: 5,
      product: expect.objectContaining({ taxId: "tax-2", familyId: "family-2", subfamilyId: null })
    })]);
    expect(backend.reads).toEqual(reopen
      ? ["/product-bulk-edits/draft-v1", "/product-bulk-edits/draft-v1", "/product-bulk-edits/draft-v2"]
      : ["/product-bulk-edits/draft-v1"]);
  });

  it("opens an old saved list from GET and preserves the current price and version when changing tax and family", async () => {
    const refreshed = { ...initialProduct, version: 5, salePrice: "5.25" };
    const applied = { ...refreshed, version: 6, taxId: "tax-2", taxName: "3%", familyId: "family-2", familyName: "Electrónica", subfamilyId: "-", subfamilyName: "-" };
    const backend = mockBulkEditing([applied], { refreshedProductOnOpen: refreshed });
    await openWorkspace();

    expect(backend.reads).toEqual(["/product-bulk-edits/draft-v1"]);
    expect((screen.getAllByRole("textbox", { name: "Precio venta" })[0] as HTMLInputElement).value).toBe("5.25");
    await selectTax("3%");
    await selectFamily("Electrónica");
    await applyChanges();

    expect(backend.saves).toHaveLength(1);
    expect(backend.saves[0].content[0].product).toEqual(expect.objectContaining({ version: 5, salePrice: "5.25" }));
    expect(backend.applies[0].body.updates).toEqual([expect.objectContaining({
      productId: "product-1", expectedVersion: 5,
      product: expect.objectContaining({ salePrice: "5.25", taxId: "tax-2", familyId: "family-2", subfamilyId: null })
    })]);
  });

  it("persists a refreshed master snapshot when saving before any further edit", async () => {
    const refreshed = { ...initialProduct, version: 5, salePrice: "5.25" };
    const backend = mockBulkEditing([], { refreshedProductOnOpen: refreshed });
    await openWorkspace();
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(backend.saves).toHaveLength(1));
    expect(backend.saves[0].content[0].product).toEqual(expect.objectContaining({ version: 5, salePrice: "5.25" }));
    expect(backend.applies).toHaveLength(0);
  });

  it.each([
    { code: "BULK_EDIT_PRODUCT_VERSION_CONFLICT", message: /Hay productos modificados/, reload: false },
    { code: "STATE_CONFLICT", message: /conflicto con el estado actual de los datos/, reload: false },
    { code: "BULK_EDIT_LIST_VERSION_CONFLICT", message: /La versión guardada de la lista ha cambiado/, reload: true }
  ])("preserves pending edits and identifies $code after an apply conflict", async ({ code, message, reload }) => {
    const backend = mockBulkEditing([], { applyError: new ApiError("Conflicto de prueba", 409, { code }) });
    await openWorkspace();
    await selectTax("3%");
    await selectFamily("Electrónica");
    await confirmApplyChanges();

    await screen.findByText(message);
    expect(screen.queryByRole("dialog", { name: "Aplicar cambios" })).toBeNull();
    expect(backend.applyAttempts).toHaveLength(1);
    expect(backend.applies).toHaveLength(0);
    const tax = screen.getAllByRole("button", { name: "Impuesto" })[0];
    const family = screen.getAllByRole("button", { name: "Familia" })[0];
    expect(tax.textContent).toContain("3%");
    expect(family.textContent).toContain("Electrónica");
    expect(tax.closest(".bulk-choice-cell")?.classList.contains("changed")).toBe(true);
    expect(family.closest(".bulk-choice-cell")?.classList.contains("changed")).toBe(true);
    expect(Boolean(screen.queryByRole("button", { name: "Recargar" }))).toBe(reload);
    expect(Boolean(screen.queryByText(/La versión guardada de la lista ha cambiado/))).toBe(reload);
    expect(screen.queryByText(/otra sesión/)).toBeNull();
  });

  it("cancels a conflicting list reload without losing local edits and replaces them only after confirmation", async () => {
    const backend = mockBulkEditing([], {
      applyError: new ApiError("Lista obsoleta", 409, { code: "BULK_EDIT_LIST_VERSION_CONFLICT" })
    });
    await openWorkspace();
    await selectTax("3%");
    await confirmApplyChanges();
    await screen.findByText(/La versión guardada de la lista ha cambiado/);

    const salePrice = () => screen.getAllByRole("textbox", { name: "Precio venta" })[0] as HTMLInputElement;
    fireEvent.change(salePrice(), { target: { value: "9.75" } });
    fireEvent.click(screen.getByRole("button", { name: "Recargar" }));
    const cancelledDialog = await screen.findByRole("dialog", { name: "Recargar" });
    expect(within(cancelledDialog).getByText(/se reemplazarán los cambios locales sin guardar/)).toBeTruthy();
    expect(backend.reads).toEqual(["/product-bulk-edits/draft-v1"]);
    fireEvent.click(within(cancelledDialog).getByRole("button", { name: "Cancelar" }));
    expect(screen.queryByRole("dialog", { name: "Recargar" })).toBeNull();
    expect(salePrice().value).toBe("9.75");
    expect(backend.reads).toHaveLength(1);

    fireEvent.click(screen.getByRole("button", { name: "Recargar" }));
    const confirmedDialog = await screen.findByRole("dialog", { name: "Recargar" });
    fireEvent.click(within(confirmedDialog).getByRole("button", { name: "Confirmar" }));
    await screen.findByText("Versión actual recargada");
    expect(screen.queryByRole("dialog", { name: "Recargar" })).toBeNull();
    expect(backend.reads).toEqual(["/product-bulk-edits/draft-v1", "/product-bulk-edits/draft-v1"]);
    expect(salePrice().value).toBe("4.50");
    expect(screen.getAllByRole("button", { name: "Impuesto" })[0].textContent).toContain("3%");
    expect(backend.saves).toHaveLength(1);
    expect(backend.applyAttempts).toHaveLength(1);
  });
});
