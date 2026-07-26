// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  SaleProductSearchDialog,
  filterSaleProductSearch,
  type SaleProductSearchOption,
} from "./SaleProductSearchDialog";

const products: SaleProductSearchOption[] = [
  { id: "coffee", imageId: "image-coffee", code: "CAF-001", barcode: "8410000000011", barcode2: "ALT-CAFE", name: "Café molido", salePrice: 10 },
  { id: "bread", code: "PAN-001", barcode: "8410000000028", name: "Pan integral", salePrice: 2.5 },
];

const labels = {
  title: "Buscador de productos",
  query: "Código, código de barras o nombre",
  image: "Imagen",
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

    const input = screen.getByRole("textbox", { name: labels.query });
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

  it("opens information with one touch and selects with a double touch", () => {
    vi.useFakeTimers();
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
    fireEvent.pointerDown(product, { pointerType: "touch" });
    fireEvent.click(product);
    expect(onInspect).not.toHaveBeenCalled();
    act(() => vi.advanceTimersByTime(300));
    expect(onInspect).toHaveBeenCalledWith(products[0]);
    expect(onSelect).not.toHaveBeenCalled();

    onInspect.mockClear();
    fireEvent.pointerDown(product, { pointerType: "touch" });
    fireEvent.click(product);
    fireEvent.pointerDown(product, { pointerType: "touch" });
    fireEvent.click(product);
    fireEvent.doubleClick(product);
    act(() => vi.advanceTimersByTime(300));
    expect(onInspect).not.toHaveBeenCalled();
    expect(onSelect).toHaveBeenCalledWith(products[0]);
  });

  it("opens information with a mouse double click even when the terminal uses touch mode", () => {
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
    fireEvent.pointerDown(product, { pointerType: "mouse" });
    fireEvent.click(product);
    fireEvent.pointerDown(product, { pointerType: "mouse" });
    fireEvent.click(product);
    fireEvent.doubleClick(product);

    expect(onInspect).toHaveBeenCalledOnce();
    expect(onInspect).toHaveBeenCalledWith(products[0]);
    expect(onSelect).not.toHaveBeenCalled();
  });
});
