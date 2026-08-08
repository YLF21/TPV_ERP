// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SaleProductSearchDialog,
  filterSaleProductSearch,
  type SaleProductSearchOption,
} from "./SaleProductSearchDialog";

const products: SaleProductSearchOption[] = [
  { id: "coffee", imageId: "image-coffee", code: "CAF-001", barcode: "8410000000011", barcode2: "ALT-CAFE", name: "Café molido", productType: "UNIT", salePrice: 10, totalStock: "14.000" },
  { id: "bread", code: "PAN-001", barcode: "8410000000028", name: "Pan integral", productType: "WEIGHT", salePrice: 2.5, totalStock: "2.750" },
];

const labels = {
  title: "Buscador de productos",
  query: "Código, código de barras o nombre",
  image: "Imagen",
  code: "Código",
  barcode: "Código de barras",
  name: "Nombre",
  stock: "Stock",
  price: "Precio",
  result: "resultado",
  results: "resultados",
  empty: "No se encontraron productos",
  close: "Cerrar",
  add: "Añadir al ticket",
  details: "Ver información",
  navigate: "Navegar",
  selected: "Producto seleccionado",
  unnamedProduct: "Producto sin nombre",
  missingCode: "Sin código",
};

afterEach(() => {
  cleanup();
  vi.useRealTimers();
  vi.unstubAllGlobals();
});

describe("SaleProductSearchDialog", () => {
  it("filters partially by both codes, both barcodes and name", () => {
    expect(filterSaleProductSearch(products, "cafe").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProductSearch(products, "PAN-0").map((product) => product.id)).toEqual(["bread"]);
    expect(filterSaleProductSearch(products, "0000000011").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProductSearch(products, "alt-cafe").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("sorts the visible stock while keeping the secondary barcode as a search-only value", () => {
    render(
      <SaleProductSearchDialog
        initialQuery="00"
        labels={labels}
        products={products}
        onClose={vi.fn()}
        onSelect={vi.fn()}
      />,
    );

    fireEvent.click(screen.getByRole("button", { name: labels.stock }));

    expect(screen.getAllByRole("option")[0]).toHaveAccessibleName(/Pan integral/);
    expect(screen.queryByRole("button", { name: /Código de barras 2/i })).not.toBeInTheDocument();
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
    const input = within(dialog).getByRole("combobox", { name: labels.query });
    const options = within(dialog).getAllByRole("option");
    expect(input).toHaveValue("00");
    expect(input).toHaveAttribute("aria-controls", "sale-product-search-results");
    expect(input).toHaveAttribute("aria-expanded", "true");
    expect(input).toHaveAttribute("aria-activedescendant", "sale-product-search-option-coffee");
    expect(options[0]).toHaveAttribute("aria-selected", "true");
    expect(within(dialog).getByRole("button", { name: labels.stock })).toBeInTheDocument();
    expect(within(options[0]).getByText("14")).toBeInTheDocument();
    expect(within(options[1]).getByText("2,75")).toBeInTheDocument();
    expect(within(dialog).queryByText("Código de barras 2")).not.toBeInTheDocument();
    expect(screen.getByRole("status")).toHaveTextContent("2 resultados");

    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(options[1]).toHaveAttribute("aria-selected", "true");
    expect(input).toHaveAttribute("aria-activedescendant", "sale-product-search-option-bread");
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

  it("requests authenticated thumbnails only for products that have an image", async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response("image", {
      status: 200,
      headers: { "Content-Type": "image/webp" },
    }));
    vi.stubGlobal("fetch", fetchMock);
    render(
      <SaleProductSearchDialog
        initialQuery="0"
        labels={labels}
        products={products}
        token="access-token"
        onClose={vi.fn()}
        onSelect={vi.fn()}
      />,
    );

    await waitFor(() => expect(fetchMock).toHaveBeenCalledOnce());
    expect(String(fetchMock.mock.calls[0][0])).toContain("/products/coffee/image?thumbnail=true");
    expect(fetchMock.mock.calls[0][1]).toMatchObject({
      headers: { Authorization: "Bearer access-token" },
    });
    expect(screen.getByRole("dialog", { name: labels.title }).querySelectorAll(".sale-product-search-thumbnail")).toHaveLength(1);
  });

  it("opens information with Enter or double click and selects with Insert in keyboard mode", () => {
    const onInspect = vi.fn();
    const onSelect = vi.fn();
    render(
      <SaleProductSearchDialog
        initialQuery="cafe"
        interfaceMode="KEYBOARD"
        labels={labels}
        products={products}
        onClose={vi.fn()}
        onInspect={onInspect}
        onSelect={onSelect}
      />,
    );

    const input = screen.getByRole("combobox", { name: labels.query });
    const option = screen.getByRole("option");
    expect(option).toHaveAttribute("tabindex", "-1");
    expect(option).toHaveAccessibleName(/Nombre: Café molido; Código: CAF-001/);
    expect(screen.getByRole("status")).toHaveTextContent("1 resultado");
    expect(screen.getByText(labels.navigate)).toBeInTheDocument();

    fireEvent.keyDown(screen.getByRole("button", { name: labels.code }), { key: "Enter" });
    expect(onInspect).not.toHaveBeenCalled();
    expect(onSelect).not.toHaveBeenCalled();

    fireEvent.keyDown(input, { key: "Enter" });
    expect(onInspect).toHaveBeenCalledWith(products[0]);
    expect(onSelect).not.toHaveBeenCalled();

    onInspect.mockClear();
    fireEvent.doubleClick(screen.getByRole("option"));
    expect(onInspect).toHaveBeenCalledWith(products[0]);
    expect(onSelect).not.toHaveBeenCalled();

    fireEvent.keyDown(input, { key: "Insert" });
    expect(onSelect).toHaveBeenCalledWith(products[0]);
  });

  it("selects with one touch and uses explicit information and add actions", () => {
    const onInspect = vi.fn();
    const onSelect = vi.fn();
    render(
      <SaleProductSearchDialog
        initialQuery="cafe"
        interfaceMode="TOUCH"
        labels={labels}
        products={products}
        onClose={vi.fn()}
        onInspect={onInspect}
        onSelect={onSelect}
      />,
    );

    const product = screen.getByRole("option");
    expect(screen.queryByText(labels.navigate)).not.toBeInTheDocument();
    fireEvent.click(product);
    expect(product).toHaveAttribute("aria-selected", "true");
    expect(onInspect).not.toHaveBeenCalled();
    expect(onSelect).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: labels.details }));
    expect(onInspect).toHaveBeenCalledWith(products[0]);

    fireEvent.click(screen.getByRole("button", { name: labels.add }));
    expect(onSelect).toHaveBeenCalledWith(products[0]);
  });

  it("does not add or inspect a product through repeated taps in touch mode", () => {
    const onInspect = vi.fn();
    const onSelect = vi.fn();
    render(
      <SaleProductSearchDialog
        initialQuery="cafe"
        interfaceMode="TOUCH"
        labels={labels}
        products={products}
        onClose={vi.fn()}
        onInspect={onInspect}
        onSelect={onSelect}
      />,
    );

    const product = screen.getByRole("option");
    fireEvent.click(product);
    fireEvent.click(product);
    fireEvent.doubleClick(product);

    expect(onInspect).not.toHaveBeenCalled();
    expect(onSelect).not.toHaveBeenCalled();
  });
});
