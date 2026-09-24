// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { UserSession } from "../../../packages/app-common/src/types";
import { apiRequest } from "../../../packages/app-common/src/api/client";
import { StockCountDocumentWindow } from "./StockCountDocumentWindow";
import * as api from "./warehouseOperationsApi";

vi.mock("../../../packages/app-common/src/api/client", async (importOriginal) => ({
  ...await importOriginal<typeof import("../../../packages/app-common/src/api/client")>(),
  apiRequest: vi.fn()
}));
vi.mock("./warehouseOperationsApi", async (importOriginal) => ({
  ...await importOriginal<typeof import("./warehouseOperationsApi")>(),
  createStockCount: vi.fn(), saveStockCountDraft: vi.fn(), confirmStockCount: vi.fn(), loadStockCount: vi.fn(), exportStockCount: vi.fn()
}));

const session: UserSession = { username: "admin", displayName: "Admin", accessToken: "token", permissions: ["GESTION_ALMACEN"] };
const warehouses: api.WarehouseOption[] = [{ id: "general", name: "GENERAL", active: true }];
const products: api.ProductOption[] = [
  { id: "coffee", code: "CAF", barcode: "8401", name: "Café", productType: "UNIT", active: true },
  { id: "tea", code: "TEA", barcode: "8402", name: "Té", productType: "UNIT", active: true }
];
const draft: api.StockCountDetail = {
  id: "inventory-1", number: "INV-2026-000001", version: 2, documentDate: "2026-09-24", storeId: "store",
  warehouseId: "general", status: "DRAFT", notes: "Recuento", createdBy: "admin", createdAt: "2026-09-24T10:00:00Z",
  lines: [
    { productId: "coffee", productCode: "CAF", productBarcode: "8401", productName: "Café", expectedQuantity: 10, countedQuantity: null, difference: null },
    { productId: "tea", productCode: "TEA", productBarcode: "8402", productName: "Té", expectedQuantity: 5, countedQuantity: 0, difference: -5 }
  ]
};
const t = (key: string) => key;

function mount(initial: api.StockCountDetail | null = draft) {
  const onClose = vi.fn(); const onSaved = vi.fn();
  render(<StockCountDocumentWindow initial={initial} warehouses={warehouses} session={session} locale="es" t={t} onClose={onClose} onSaved={onSaved} />);
  return { onClose, onSaved };
}

describe("StockCountDocumentWindow", () => {
  afterEach(cleanup);
  beforeEach(() => {
    vi.clearAllMocks();
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/stock-counts/resources") return { products, families: [], warehouses } as never;
      if (path.startsWith("/stock-counts/balances?")) return [
        { productId: "coffee", warehouseId: "general", quantity: 10 },
        { productId: "tea", warehouseId: "general", quantity: 5 }
      ] as never;
      throw new Error(`Unexpected request: ${path}`);
    });
    vi.mocked(api.saveStockCountDraft).mockImplementation(async (_id, input) => ({
      ...draft, version: input.expectedVersion + 1,
      lines: input.lines.map((line) => ({ ...draft.lines.find((item) => item.productId === line.productId)!, ...line }))
    }));
  });

  it("keeps a blank count pending and an explicit zero counted when saving a draft", async () => {
    const { onSaved } = mount();
    const coffee = screen.getByRole("textbox", { name: "Cantidad contada CAF" });
    const tea = screen.getByRole("textbox", { name: "Cantidad contada TEA" });
    expect(coffee).toHaveValue("");
    expect(coffee).toHaveAttribute("placeholder", "Pendiente");
    expect(tea).toHaveValue("0");
    expect(screen.getByText("1 / 2")).toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("button", { name: "Guardar borrador (F9)" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Guardar borrador (F9)" }));
    await waitFor(() => expect(api.saveStockCountDraft).toHaveBeenCalledWith("inventory-1", {
      expectedVersion: 2, documentDate: "2026-09-24", notes: "Recuento",
      lines: [
        { productId: "coffee", countedQuantity: null, expectedQuantity: 10 },
        { productId: "tea", countedQuantity: 0, expectedQuantity: 5 }
      ]
    }, "token"));
    expect(api.confirmStockCount).not.toHaveBeenCalled();
    expect(onSaved).toHaveBeenCalledOnce();
    expect(coffee).toHaveValue("");
    fireEvent.click(screen.getByRole("button", { name: "Revisar diferencias" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Cuenta los artículos pendientes");
    expect(api.confirmStockCount).not.toHaveBeenCalled();
  });

  it("requires every count, saves a reviewed draft, then confirms only from the review dialog", async () => {
    const counted = { ...draft, lines: [{ ...draft.lines[0], countedQuantity: 9, difference: -1 }] };
    vi.mocked(api.saveStockCountDraft).mockResolvedValue({ ...counted, version: 3 });
    vi.mocked(api.confirmStockCount).mockResolvedValue({ ...counted, version: 4, status: "CONFIRMED" });
    mount(counted);
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/stock-counts/balances?warehouseId=general", { token: "token" }));
    fireEvent.click(screen.getByRole("button", { name: "Revisar diferencias" }));
    const review = await screen.findByRole("alertdialog", { name: "Confirmar inventario" });
    expect(api.saveStockCountDraft).toHaveBeenCalledWith("inventory-1", expect.objectContaining({ expectedVersion: 2 }), "token");
    expect(api.confirmStockCount).not.toHaveBeenCalled();
    fireEvent.click(within(review).getByRole("button", { name: "Volver a revisar" }));
    expect(screen.queryByRole("alertdialog", { name: "Confirmar inventario" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Revisar diferencias" }));
    fireEvent.click(within(await screen.findByRole("alertdialog", { name: "Confirmar inventario" })).getByRole("button", { name: "Confirmar inventario" }));
    await waitFor(() => expect(api.confirmStockCount).toHaveBeenCalledWith("inventory-1", "token", counted.lines, 3));
    expect(await screen.findByText("Este documento está cerrado y no se puede editar.")).toBeInTheDocument();
  });

  it("clears affected counts when reference stock changes and blocks confirmation", async () => {
    const counted = { ...draft, lines: [{ ...draft.lines[0], countedQuantity: 9, difference: -1 }] };
    let stockCalls = 0;
    vi.mocked(apiRequest).mockImplementation(async (path) => {
      if (path === "/stock-counts/resources") return { products, families: [], warehouses } as never;
      if (path.startsWith("/stock-counts/balances?")) return [{ productId: "coffee", warehouseId: "general", quantity: ++stockCalls === 1 ? 10 : 12 }] as never;
      throw new Error(`Unexpected request: ${path}`);
    });
    mount(counted);
    await waitFor(() => expect(stockCalls).toBe(1));
    fireEvent.click(screen.getByRole("button", { name: "Revisar diferencias" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("El stock de algunos artículos ha cambiado");
    expect(screen.getByRole("textbox", { name: "Cantidad contada CAF" })).toHaveValue("");
    expect(screen.getByText("0 / 1")).toBeInTheDocument();
    expect(api.saveStockCountDraft).not.toHaveBeenCalled();
    expect(api.confirmStockCount).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Revisar diferencias" }));
    expect(await screen.findByRole("alert")).toHaveTextContent("Cuenta los artículos pendientes");
    expect(api.confirmStockCount).not.toHaveBeenCalled();
  });

  it("makes confirmed documents read-only and asks before discarding draft edits", async () => {
    const confirmed = { ...draft, status: "CONFIRMED" as const };
    const { onClose } = mount(confirmed);
    expect(screen.getByRole("button", { name: "Guardar borrador (F9)" })).toBeDisabled();
    expect(screen.getByRole("textbox", { name: "Cantidad contada CAF" })).toBeDisabled();
    expect(screen.queryByRole("combobox", { name: "Buscar producto" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Salir (Esc)" }));
    expect(onClose).toHaveBeenCalledOnce();
    cleanup();

    const draftWindow = mount();
    fireEvent.change(screen.getByRole("textbox", { name: "Notas" }), { target: { value: "Nuevo recuento" } });
    fireEvent.click(screen.getByRole("button", { name: "Salir (Esc)" }));
    const prompt = screen.getByRole("alertdialog", { name: "Cambios sin guardar" });
    expect(draftWindow.onClose).not.toHaveBeenCalled();
    fireEvent.click(within(prompt).getByRole("button", { name: "Seguir editando" }));
    expect(screen.queryByRole("alertdialog", { name: "Cambios sin guardar" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Salir (Esc)" }));
    fireEvent.click(within(screen.getByRole("alertdialog", { name: "Cambios sin guardar" })).getByRole("button", { name: "Descartar y salir" }));
    expect(draftWindow.onClose).toHaveBeenCalledOnce();
  });
});
