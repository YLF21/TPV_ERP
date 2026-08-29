// @vitest-environment jsdom
import { cleanup, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { PdaReplenishment } from "./PdaReplenishment";
import { PdaStockCount } from "./PdaStockCount";

const apiRequestMock = vi.hoisted(() => vi.fn());

vi.mock("@tpverp/app-common", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@tpverp/app-common")>()),
  apiRequest: apiRequestMock
}));

function scanWithoutFocus(value: string, start = 100) {
  [...value, "Enter"].forEach((key, index) => {
    const event = new KeyboardEvent("keydown", { key, bubbles: true, cancelable: true });
    Object.defineProperty(event, "timeStamp", { value: start + index * 10 });
    window.dispatchEvent(event);
  });
}

const warehouses = [{ id: "shop", name: "Tienda", defaultWarehouse: true }];
const t = (key: string) => ({
  "pda.count.registered": "Registrado: {product}",
  "pda.count.scanError": "Error de lectura",
  "pda.count.loadError": "Error de carga",
  "pda.replenishment.notFound": "No encontrado",
  "pda.replenishment.manualScan": "Escaneo manual",
  "pda.replenishment.destination": "Destino"
}[key] ?? key);

describe("physical scanner integration", () => {
  beforeEach(() => apiRequestMock.mockReset());
  afterEach(() => cleanup());

  it("opens a product in replenishment from a scanner burst without focus", async () => {
    apiRequestMock.mockImplementation((path?: string) => {
      if (!path) return Promise.resolve(undefined);
      if (path.startsWith("/stock/page?")) return Promise.resolve({ items: [], hasMore: false });
      if (path.startsWith("/products/sale/price-consultation")) return Promise.resolve({ productId: "p1", code: "CAFE", name: "Café" });
      if (path === "/stock?productId=p1") return Promise.resolve([{ productId: "p1", warehouseId: "shop", quantity: 3 }]);
      throw new Error(`Unexpected path: ${path}`);
    });
    render(<PdaReplenishment token="token" locale="es" warehouses={warehouses} t={t} />);
    await waitFor(() => expect(apiRequestMock).toHaveBeenCalledWith(expect.stringContaining("/stock/page?"), { token: "token" }));

    scanWithoutFocus("8412345678901");

    expect(await screen.findByText("Café")).toBeTruthy();
    await screen.findByText("Código leído: 8412345678901");
  });

  it("adds a unit to an open inventory from a scanner burst without focus", async () => {
    const initial = {
      id: "count-1", warehouseId: "shop", status: "DRAFT", createdAt: "2026-08-28T10:00:00Z", notes: null,
      lines: [{ productId: "p1", productCode: "CAFE", productName: "Café", expectedQuantity: 3, countedQuantity: 0, difference: -3 }]
    };
    apiRequestMock.mockImplementation((path?: string, options?: { body?: { countedQuantity?: number } }) => {
      if (!path) return Promise.resolve(undefined);
      if (path === "/stock-counts?status=DRAFT") return Promise.resolve([{ ...initial, lineCount: 1, totalDifference: -3 }]);
      if (path === "/stock-counts/count-1") return Promise.resolve(initial);
      if (path.startsWith("/products/sale/price-consultation")) return Promise.resolve({ productId: "p1", code: "CAFE", name: "Café" });
      if (path === "/stock-counts/count-1/lines/p1") return Promise.resolve({
        ...initial,
        lines: [{ ...initial.lines[0], countedQuantity: options?.body?.countedQuantity ?? 0, difference: -2 }]
      });
      throw new Error(`Unexpected path: ${path}`);
    });
    render(<PdaStockCount token="token" locale="es" warehouses={warehouses} t={t} />);
    await screen.findByText("Café");

    scanWithoutFocus("8412345678901");

    await waitFor(() => expect(apiRequestMock).toHaveBeenCalledWith("/stock-counts/count-1/lines/p1", {
      method: "PUT", token: "token", body: { countedQuantity: 1 }
    }));
    await screen.findByText("Código leído: 8412345678901");
  });
});
