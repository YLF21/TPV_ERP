// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { TerminalContext, UserSession } from "../types";
import { SalesDocumentScreen } from "./SalesDocumentScreen";

const { apiRequest } = vi.hoisted(() => ({ apiRequest: vi.fn() }));

vi.mock("../api/client", () => ({
  apiRequest,
  ApiError: class ApiError extends Error {
    constructor(message: string, readonly status: number) {
      super(message);
    }
  },
}));

const session: UserSession = {
  username: "seller",
  displayName: "Vendedor",
  permissions: ["VENTA"],
  accessToken: "token",
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01",
};

afterEach(() => {
  cleanup();
  localStorage.clear();
  apiRequest.mockReset();
});

describe("SalesDocumentScreen", () => {
  it("starts blank, quotes authoritatively and saves an independent invoice draft", async () => {
    apiRequest.mockImplementation((path: string, options?: { body?: unknown }) => {
      if (path === "/products/sale") return Promise.resolve([{
        id: "product-1",
        code: "P-001",
        barcode: "840000000001",
        name: "Producto fiscal",
        salePrice: 10,
        active: true,
        taxesIncluded: true,
        taxRegime: "IVA",
        taxPercentage: 21,
      }]);
      if (path === "/customers/sale-options") return Promise.resolve([{
        id: "customer-1",
        clientId: "C-001",
        fiscalName: "Cliente Fiscal SL",
        documentNumber: "B11111111",
      }]);
      if (path === "/warehouses") return Promise.resolve([{
        id: "warehouse-1",
        active: true,
        defaultWarehouse: true,
      }]);
      if (path === "/pos/sales-document-checkouts/quote") {
        expect(options?.body).toMatchObject({
          type: "FACTURA_VENTA",
          customerId: "customer-1",
          completionMode: "DRAFT",
          payments: [],
        });
        return Promise.resolve({ total: "10.00" });
      }
      if (path === "/pos/sales-document-checkouts") {
        expect(options?.body).toMatchObject({
          type: "FACTURA_VENTA",
          customerId: "customer-1",
          warehouseId: "warehouse-1",
          completionMode: "DRAFT",
          quotedTotal: "10.00",
          payments: [],
        });
        return Promise.resolve({ document: { id: "draft-1" }, printDocument: null });
      }
      return Promise.reject(new Error(`unexpected request ${path}`));
    });

    render(
      <SalesDocumentScreen
        locale="es"
        session={session}
        terminalContext={terminalContext}
      />,
    );

    expect(screen.getByText("Añade productos por código o abre el buscador.")).toBeInTheDocument();
    fireEvent.click(await screen.findByRole("button", { name: /seleccionar cliente/i }));
    const customerDialog = screen.getByRole("dialog", { name: /seleccionar cliente/i });
    fireEvent.click(within(customerDialog).getByRole("button", { name: /cliente fiscal sl/i }));

    const quickEntry = screen.getByLabelText(/entrada rápida/i);
    fireEvent.change(quickEntry, { target: { value: "P-001" } });
    fireEvent.submit(quickEntry.closest("form")!);

    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith(
      "/pos/sales-document-checkouts/quote",
      expect.any(Object),
    ));
    await waitFor(() => expect(
      screen.getByRole("button", { name: "Guardar borrador" }),
    ).toBeEnabled());

    fireEvent.click(screen.getByRole("button", { name: "Guardar borrador" }));

    expect(await screen.findByText("Borrador guardado: draft-1")).toBeInTheDocument();
    expect(screen.getByText("Añade productos por código o abre el buscador.")).toBeInTheDocument();
  });

  it("opens the full product finder when the quick code is unknown", async () => {
    apiRequest.mockImplementation((path: string) => {
      if (path === "/products/sale") return Promise.resolve([{
        id: "product-1",
        code: "P-001",
        name: "Café molido",
        salePrice: 10,
        active: true,
      }]);
      if (path === "/customers/sale-options") return Promise.resolve([]);
      if (path === "/warehouses") return Promise.resolve([{ id: "warehouse-1", active: true }]);
      return Promise.reject(new Error(`unexpected request ${path}`));
    });

    render(
      <SalesDocumentScreen
        locale="es"
        session={session}
        terminalContext={terminalContext}
      />,
    );

    const quickEntry = await screen.findByLabelText(/entrada rápida/i);
    fireEvent.change(quickEntry, { target: { value: "Café" } });
    fireEvent.submit(quickEntry.closest("form")!);

    expect(screen.getByRole("dialog", { name: /buscador de productos/i })).toBeInTheDocument();
  });
});
