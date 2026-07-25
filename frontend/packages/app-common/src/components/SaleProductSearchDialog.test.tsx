// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SaleProductSearchDialog,
  filterSaleProductSearch,
  type SaleProductSearchOption,
} from "./SaleProductSearchDialog";

const products: SaleProductSearchOption[] = [
  { id: "coffee", code: "CAF-001", barcode: "8410000000011", barcode2: "ALT-CAFE", name: "Café molido", salePrice: 10 },
  { id: "bread", code: "PAN-001", barcode: "8410000000028", name: "Pan integral", salePrice: 2.5 },
];

const labels = {
  title: "Buscador de productos",
  query: "Código, código de barras o nombre",
  code: "Código",
  barcode: "Código de barras",
  barcode2: "Código de barras 2",
  name: "Nombre",
  price: "Precio",
  empty: "No se encontraron productos",
  close: "Cerrar",
  unnamedProduct: "Producto sin nombre",
  missingCode: "Sin código",
};

afterEach(cleanup);

describe("SaleProductSearchDialog", () => {
  it("filters partially by both codes, both barcodes and name", () => {
    expect(filterSaleProductSearch(products, "cafe").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProductSearch(products, "PAN-0").map((product) => product.id)).toEqual(["bread"]);
    expect(filterSaleProductSearch(products, "0000000011").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProductSearch(products, "alt-cafe").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("opens with the original query and selects a result from the keyboard", () => {
    const onSelect = vi.fn();
    render(
      <SaleProductSearchDialog
        initialQuery="00"
        labels={labels}
        products={products}
        onClose={vi.fn()}
        onSelect={onSelect}
      />,
    );

    const dialog = screen.getByRole("dialog", { name: labels.title });
    const input = within(dialog).getByRole("textbox", { name: labels.query });
    const options = within(dialog).getAllByRole("option");
    expect(input).toHaveValue("00");
    expect(options[0]).toHaveAttribute("aria-selected", "true");

    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(options[1]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(input, { key: "Enter" });

    expect(onSelect).toHaveBeenCalledWith(products[1]);
  });

  it("closes with Escape and reports an empty search", () => {
    const onClose = vi.fn();
    render(
      <SaleProductSearchDialog
        initialQuery="inexistente"
        labels={labels}
        products={products}
        onClose={onClose}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.getByText(labels.empty)).toBeInTheDocument();
    fireEvent.keyDown(screen.getByRole("dialog", { name: labels.title }), { key: "Escape" });
    expect(onClose).toHaveBeenCalledOnce();
  });
});
