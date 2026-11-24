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
  it("opts into warehouse filter chips without selecting or changing the search contract", () => {
    const onSelect = vi.fn();
    const onQueryChange = vi.fn();
    const { container, rerender } = render(<SaleProductSearchDialog initialQuery="cafe" labels={labels} products={products}
      interfaceMode="KEYBOARD" onSelect={onSelect} onClose={vi.fn()} onQueryChange={onQueryChange} />);
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    rerender(<SaleProductSearchDialog initialQuery="cafe" labels={labels} products={products} tableTheme="erp-blue-classic"
      interfaceMode="KEYBOARD" onSelect={onSelect} onClose={vi.fn()} onQueryChange={onQueryChange} />);
    expect(container.querySelector(".warehouse-picker-classic")).toBeInTheDocument();
    expect(screen.getByRole("option")).toHaveAccessibleName(/Café molido/);
    fireEvent.click(screen.getByRole("button", { name: `Quitar filtro ${labels.query}` }));
    expect(screen.getByRole("combobox", { name: labels.query })).toHaveValue("");
    expect(onQueryChange).toHaveBeenLastCalledWith("");
    expect(screen.queryByRole("option")).not.toBeInTheDocument();
    expect(onSelect).not.toHaveBeenCalled();
    fireEvent.change(screen.getByRole("combobox", { name: labels.query }), { target: { value: "PAN" } });
    fireEvent.keyDown(screen.getByRole("combobox", { name: labels.query }), { key: "Insert" });
    expect(onSelect).toHaveBeenCalledWith(products[1]);
  });
  it("provides the alpha keyboard only for touch mode and preserves scanning and query notifications", () => {
    const onQueryChange = vi.fn();
    const props = { initialQuery: "OLD", labels, products, onQueryChange, onClose: vi.fn(), onSelect: vi.fn() };
    const { rerender } = render(<SaleProductSearchDialog {...props} interfaceMode="KEYBOARD" />);
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
    rerender(<SaleProductSearchDialog {...props} interfaceMode="TOUCH" />);
    const input = screen.getByRole<HTMLInputElement>("combobox", { name: labels.query });
    expect(input).toHaveAttribute("inputmode", "none");
    input.focus();
    input.select();
    fireEvent.click(screen.getByRole("button", { name: "P" }));
    fireEvent.click(screen.getByRole("button", { name: "A" }));
    fireEvent.click(screen.getByRole("button", { name: "N" }));
    expect(input).toHaveValue("PAN");
    expect(input).toHaveFocus();
    expect(onQueryChange).toHaveBeenLastCalledWith("PAN");
    expect(screen.getByRole("option")).toHaveAccessibleName(/Pan integral/);
    fireEvent.change(input, { target: { value: "8410000000011" } });
    expect(screen.getByRole("option")).toHaveAccessibleName(/Café molido/);
  });

  it("filters partially by both codes, both barcodes and name", () => {
    expect(filterSaleProductSearch(products, "cafe").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProductSearch(products, "PAN-0").map((product) => product.id)).toEqual(["bread"]);
    expect(filterSaleProductSearch(products, "0000000011").map((product) => product.id)).toEqual(["coffee"]);
    expect(filterSaleProductSearch(products, "alt-cafe").map((product) => product.id)).toEqual(["coffee"]);
  });

  it("sorts each data column in both directions and leaves the image header passive", () => {
    render(
      <SaleProductSearchDialog
        initialQuery="00"
        labels={labels}
        products={products}
        onClose={vi.fn()}
        onSelect={vi.fn()}
      />,
    );

    expect(screen.queryByRole("button", { name: labels.image })).not.toBeInTheDocument();
    for (const [label, firstName, secondName, firstDirection] of [
      [labels.code, "Pan integral", "Café molido", "desc"],
      [labels.barcode, "Café molido", "Pan integral", "asc"],
      [labels.name, "Café molido", "Pan integral", "asc"],
      [labels.stock, "Pan integral", "Café molido", "asc"],
      [labels.price, "Pan integral", "Café molido", "asc"],
    ]) {
      const header = screen.getByRole("button", { name: label });
      fireEvent.click(header);
      expect(header).toHaveAttribute("data-sort-direction", firstDirection);
      expect(screen.getAllByRole("option")[0]).toHaveTextContent(firstName);
      fireEvent.click(header);
      expect(screen.getAllByRole("option")[0]).toHaveTextContent(secondName);
    }
    expect(screen.queryByRole("button", { name: /Código de barras 2/i })).not.toBeInTheDocument();
  });

  it("orders all matches before limiting the visible results", () => {
    const catalogue = Array.from({ length: 105 }, (_, index) => ({
      id: String(index), code: String(index), name: "Artículo", salePrice: index,
    }));
    const results = filterSaleProductSearch(catalogue, "artículo", 100, "es", { column: "price", direction: "desc" });
    expect(results).toHaveLength(100);
    expect(results[0].id).toBe("104");
    expect(results[99].id).toBe("5");
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

  it.each(["KEYBOARD", "TOUCH"] as const)("opens product creation from the button or F5 in %s mode", (interfaceMode) => {
    const onCreateProduct = vi.fn();
    render(
      <SaleProductSearchDialog
        initialQuery="cafe"
        interfaceMode={interfaceMode}
        labels={labels}
        products={products}
        createProductLabel="Añadir producto"
        onCreateProduct={onCreateProduct}
        onClose={vi.fn()}
        onSelect={vi.fn()}
      />,
    );

    const createButton = screen.getByRole("button", { name: /Añadir producto/ });
    expect(createButton).toHaveAttribute("aria-keyshortcuts", "F5");
    expect(createButton.closest(".sale-product-search-field")).not.toBeNull();
    expect(createButton.closest(".sale-product-search-keyboard-actions")).toBeNull();
    fireEvent.click(createButton);
    fireEvent.keyDown(screen.getByRole("combobox", { name: labels.query }), { key: "F5" });

    expect(onCreateProduct).toHaveBeenCalledTimes(2);
  });

  it("keeps the search open on F5 when the operator cannot create products", () => {
    const onClose = vi.fn();
    const onSelect = vi.fn();
    render(<SaleProductSearchDialog initialQuery="cafe" labels={labels} products={products}
      interfaceMode="TOUCH" createProductLabel="Añadir producto" onClose={onClose} onSelect={onSelect} />);
    expect(screen.queryByRole("button", { name: "Añadir producto" })).not.toBeInTheDocument();
    expect(fireEvent.keyDown(screen.getByRole("combobox"), { key: "F5" })).toBe(false);
    expect(onClose).not.toHaveBeenCalled();
    expect(onSelect).not.toHaveBeenCalled();
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
