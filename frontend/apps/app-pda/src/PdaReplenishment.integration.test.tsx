// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PdaReplenishment } from "./PdaReplenishment";

const apiRequestMock = vi.hoisted(() => vi.fn());

vi.mock("@tpverp/app-common", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@tpverp/app-common")>()),
  apiRequest: apiRequestMock
}));

const translations: Record<string, string> = {
  "common.loading": "Cargando",
  "pda.replenishment.scan": "Buscar producto",
  "pda.replenishment.confirm": "Confirmar reposición",
  "pda.replenishment.source": "Origen",
  "pda.replenishment.target": "Destino",
  "pda.replenishment.quantity": "Cantidad",
  "pda.replenishment.completed": "Reposición completada",
  "pda.replenishment.destination": "Almacén a reponer",
  "pda.replenishment.manualScan": "Escaneo manual",
  "pda.replenishment.products": "productos",
  "pda.replenishment.selectAll": "Seleccionar disponibles",
  "pda.replenishment.select": "Seleccionar producto",
  "pda.replenishment.current": "Actual",
  "pda.replenishment.minimum": "Mínimo",
  "pda.replenishment.maximum": "Objetivo",
  "pda.replenishment.suggested": "Reponer",
  "pda.replenishment.from": "Desde",
  "pda.replenishment.replenishSelected": "Reponer seleccionados",
  "pda.replenishment.bulkCompleted": "Se han repuesto {count} productos.",
  "pda.replenishment.bulkReviewTitle": "Revisar reposición",
  "pda.replenishment.bulkReviewHelp": "Se aplicarán todos juntos.",
  "pda.replenishment.bulkCancel": "Volver",
  "pda.replenishment.bulkConfirm": "Confirmar todos",
  "pda.replenishment.bulkAtomicError": "No se aplicó ningún movimiento."
};

const warehouses = [
  { id: "shop", name: "Tienda", defaultWarehouse: true },
  { id: "reserve", name: "Reserva" }
];

function renderReplenishment() {
  return render(<PdaReplenishment token="token" locale="es" warehouses={warehouses} t={(key) => translations[key] ?? key} />);
}

describe("PdaReplenishment", () => {
  beforeEach(() => apiRequestMock.mockReset());
  afterEach(() => cleanup());

  it("scans a product and records a warehouse transfer", async () => {
    apiRequestMock.mockImplementation((path?: string) => {
      if (!path) return Promise.resolve(undefined);
      if (path.startsWith("/stock/page?")) return Promise.resolve({ items: [], hasMore: false, nextCursor: null });
      if (path.startsWith("/products/sale/price-consultation")) return Promise.resolve({ productId: "product-1", code: "CAFE", name: "Café" });
      if (path === "/stock?productId=product-1") return Promise.resolve([
        { productId: "product-1", warehouseId: "reserve", quantity: 10 },
        { productId: "product-1", warehouseId: "shop", quantity: 1 }
      ]);
      if (path === "/stock/transfers") return Promise.resolve({ sourceWarehouseId: "reserve", targetWarehouseId: "shop", sourceQuantity: 8, targetQuantity: 3 });
      throw new Error(`Unexpected path: ${path}`);
    });

    renderReplenishment();
    fireEvent.change(screen.getByRole("textbox", { name: "Escaneo manual" }), { target: { value: "8412345678901" } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar producto" }));

    await screen.findByText("Café");
    fireEvent.change(screen.getByRole("spinbutton", { name: "Cantidad" }), { target: { value: "2" } });
    fireEvent.click(screen.getByRole("button", { name: "Confirmar reposición" }));

    await waitFor(() => expect(apiRequestMock).toHaveBeenCalledWith("/stock/transfers", {
      token: "token",
      body: { productId: "product-1", sourceWarehouseId: "reserve", targetWarehouseId: "shop", quantity: 2 }
    }));
    expect((await screen.findByRole("status")).textContent).toContain("Reposición completada");
  });

  it("selects and replenishes several suggested products", async () => {
    apiRequestMock.mockImplementation((path?: string, options?: { body?: Record<string, unknown> }) => {
      if (!path) return Promise.resolve(undefined);
      if (path.startsWith("/stock/page?")) return Promise.resolve({
        hasMore: false,
        nextCursor: null,
        items: [
          { product: { id: "p1", code: "CAFE", name: "Café", stockMin: 4, stockMax: 10 }, stock: [{ productId: "p1", warehouseId: "shop", quantity: 1 }, { productId: "p1", warehouseId: "reserve", quantity: 20 }] },
          { product: { id: "p2", code: "TE", name: "Té", stockMin: 3, stockMax: 7 }, stock: [{ productId: "p2", warehouseId: "shop", quantity: 0 }, { productId: "p2", warehouseId: "reserve", quantity: 8 }] }
        ]
      });
      if (path === "/stock/transfers/batch") {
        const transfers = options?.body?.transfers as Array<Record<string, unknown>>;
        return Promise.resolve({
          batchId: "batch-1",
          transfers: transfers.map((item) => ({
            productId: item.productId,
            sourceWarehouseId: "reserve",
            targetWarehouseId: "shop",
            sourceQuantity: item.productId === "p1" ? 13 : 1,
            targetQuantity: item.productId === "p1" ? 8 : 7
          }))
        });
      }
      throw new Error(`Unexpected path: ${path}`);
    });

    renderReplenishment();
    await screen.findByText("Café");
    await screen.findByText("Té");
    fireEvent.click(screen.getByRole("button", { name: "Seleccionar disponibles" }));
    fireEvent.click(screen.getByRole("button", { name: "Reponer seleccionados (2)" }));

    const dialog = await screen.findByRole("dialog", { name: "Revisar reposición" });
    expect(dialog.textContent).toContain("CAFE");
    expect(dialog.textContent).toContain("Té");
    fireEvent.click(screen.getByRole("button", { name: "Confirmar todos" }));

    await waitFor(() => {
      const batches = apiRequestMock.mock.calls.filter(([path]) => path === "/stock/transfers/batch");
      expect(batches).toHaveLength(1);
      expect(batches[0]?.[1]?.body?.transfers).toEqual([
        { productId: "p2", sourceWarehouseId: "reserve", targetWarehouseId: "shop", quantity: 7 },
        { productId: "p1", sourceWarehouseId: "reserve", targetWarehouseId: "shop", quantity: 9 }
      ]);
    });
    expect((await screen.findByRole("status")).textContent).toContain("Se han repuesto 2 productos.");
  });

  it("keeps the review open and reports that no movement was applied when the batch fails", async () => {
    apiRequestMock.mockImplementation((path?: string) => {
      if (!path) return Promise.resolve(undefined);
      if (path?.startsWith("/stock/page?")) return Promise.resolve({ hasMore: false, nextCursor: null, items: [
        { product: { id: "p1", code: "CAFE", name: "Café", stockMin: 4, stockMax: 10 }, stock: [{ productId: "p1", warehouseId: "shop", quantity: 1 }, { productId: "p1", warehouseId: "reserve", quantity: 20 }] }
      ] });
      if (path === "/stock/transfers/batch") return Promise.reject(new Error("stock_changed"));
      throw new Error(`Unexpected path: ${path}`);
    });

    renderReplenishment();
    await screen.findByText("Café");
    fireEvent.click(screen.getByRole("button", { name: "Seleccionar disponibles" }));
    fireEvent.click(screen.getByRole("button", { name: "Reponer seleccionados (1)" }));
    fireEvent.click(await screen.findByRole("button", { name: "Confirmar todos" }));

    expect((await screen.findByRole("alert")).textContent).toContain("No se aplicó ningún movimiento.");
    expect(screen.getByRole("dialog", { name: "Revisar reposición" })).not.toBeNull();
  });
});
