// @vitest-environment jsdom
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { PdaProductLookup } from "./PdaProductLookup";

const apiRequestMock = vi.hoisted(() => vi.fn());

vi.mock("@tpverp/app-common", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@tpverp/app-common")>()),
  apiRequest: apiRequestMock
}));

describe("PdaProductLookup label printing", () => {
  beforeEach(() => {
    apiRequestMock.mockReset();
    Object.defineProperty(window, "print", { configurable: true, value: vi.fn() });
  });

  it("loads the product EAN and opens printing from the PDA", async () => {
    apiRequestMock.mockImplementation((path: string) => {
      if (path.startsWith("/products/sale/price-consultation")) return Promise.resolve({
        productId: "product-1",
        code: "CAFE",
        name: "Café molido",
        salePrice: 3.5,
        activePriceType: "VENTA"
      });
      if (path === "/stock?productId=product-1") return Promise.resolve([]);
      if (path === "/products/product-1") return Promise.resolve({ barcode: "8412345678901" });
      throw new Error(`Unexpected path: ${path}`);
    });

    render(<PdaProductLookup
      token="token"
      locale="es"
      warehouses={[]}
      storeName="TIENDA PRUEBAS"
      t={(key) => ({
        "goodsCheck.productCode": "Código",
        "pda.lookup.search": "Consultar",
        "pda.lookup.printLabel": "Imprimir etiqueta",
        "pda.lookup.labelCopies": "Copias",
        "pda.lookup.noStock": "Sin stock"
      }[key] ?? key)}
    />);

    fireEvent.change(screen.getByRole("textbox", { name: "Código" }), { target: { value: "8412345678901" } });
    fireEvent.click(screen.getByRole("button", { name: "Consultar" }));

    const printButton = await screen.findByRole("button", { name: "Imprimir etiqueta" });
    expect((printButton as HTMLButtonElement).disabled).toBe(false);
    expect(screen.getByText("TIENDA PRUEBAS")).toBeTruthy();
    expect(screen.getByLabelText("8412345678901")).toBeTruthy();

    fireEvent.click(printButton);
    await waitFor(() => expect(window.print).toHaveBeenCalledOnce());
  });
});