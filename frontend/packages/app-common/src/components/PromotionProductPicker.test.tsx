// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import {
  filterPromotionProducts,
  PromotionProductPicker,
  type PromotionCatalogProduct
} from "./PromotionProductPicker";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
vi.mock("./PromotionProductExcelImportButton", () => ({
  PromotionProductExcelImportButton: ({ onAddProducts }: { onAddProducts: (ids: string[]) => void }) => (
    <button type="button" onClick={() => onAddProducts(["p3"])}>
      Excel
    </button>
  )
}));

const products: PromotionCatalogProduct[] = [
  { id: "p1", code: "001", barcode: "8410001", name: "Café", familyId: "f1", subfamilyId: "s1" },
  { id: "p2", code: "002", barcode: "8410002", name: "Té", familyId: "f1", subfamilyId: "s2" },
  { id: "p3", code: "003", name: "Leche", familyId: "f2" }
];

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

function setupApi(stockRows = [
  { productId: "p1", warehouseId: "w1", quantity: 4 },
  { productId: "p2", warehouseId: "w1", quantity: 0 }
], otherWarehouseStockRows = [{ productId: "p2", warehouseId: "w2", quantity: 8 }]) {
  vi.mocked(apiRequest).mockImplementation(async (path) => {
    if (path === "/families")
      return [
        { id: "f1", name: "Bebidas" },
        { id: "f2", name: "Lácteos" }
      ] as never;
    if (path === "/warehouses")
      return [
        { id: "w1", name: "General", active: true, defaultWarehouse: true },
        { id: "w2", name: "Otro", active: true },
        { id: "w3", name: "Inactivo", active: false }
      ] as never;
    if (path === "/families/f1/subfamilies")
      return [
        { id: "s1", name: "Café" },
        { id: "s2", name: "Té" }
      ] as never;
    if (path === "/stock?warehouseId=w1")
      return stockRows as never;
    if (path === "/stock?warehouseId=w2") return otherWarehouseStockRows as never;
    return [] as never;
  });
}

function chooseFilter(label: string, option: string) {
  fireEvent.click(screen.getByRole("button", { name: label }));
  fireEvent.click(screen.getByRole("option", { name: option }));
}

describe("PromotionProductPicker", () => {
  it("filters by code, barcode, name, family and warehouse availability", async () => {
    setupApi();
    render(
      <PromotionProductPicker
        locale="es"
        token="token"
        products={products}
        selectedProductIds={[]}
        initialWarehouseId="w2"
        onApply={vi.fn()}
        onClose={vi.fn()}
      />
    );
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/stock?warehouseId=w2", { token: "token" }));
    await waitFor(() => expect(screen.getByText("8", { selector: "td" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Almacén" }));
    expect(screen.queryByRole("option", { name: "Inactivo" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: "Otro" }));
    chooseFilter("Disponibilidad", "Con stock");
    expect(screen.getByText("Té", { selector: "td" })).toBeInTheDocument();
    expect(screen.queryByText("Café", { selector: "td" })).not.toBeInTheDocument();
    chooseFilter("Disponibilidad", "Todos");
    fireEvent.change(screen.getByLabelText("Código, nombre o código de barras"), { target: { value: "8410001" } });
    expect(screen.getByText("Café", { selector: "td" })).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Código, nombre o código de barras"), { target: { value: "" } });
    chooseFilter("Familia", "Bebidas");
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/families/f1/subfamilies", { token: "token" }));
    chooseFilter("Subfamilia", "Té");
    expect(screen.getByText("Té", { selector: "td" })).toBeInTheDocument();
    expect(screen.queryByText("Leche", { selector: "td" })).not.toBeInTheDocument();
  });

  it("keeps selections across filters, adds Excel IDs, and only applies on confirmation", async () => {
    setupApi();
    const apply = vi.fn();
    render(
      <PromotionProductPicker
        locale="es"
        products={products}
        selectedProductIds={["saved-missing"]}
        onApply={apply}
        onClose={vi.fn()}
      />
    );
    await waitFor(() => expect(screen.getByRole("button", { name: "Almacén" })).toHaveTextContent("General"));
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 001" }));
    fireEvent.change(screen.getByLabelText("Código, nombre o código de barras"), { target: { value: "Té" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 002" }));
    fireEvent.click(screen.getByRole("button", { name: "Excel" }));
    expect(apply).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Cancelar" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(apply).toHaveBeenCalledWith(["saved-missing", "p1", "p2", "p3"]);
  });

  it("allows reviewing and removing previously selected inactive or missing products", () => {
    setupApi();
    const apply = vi.fn();
    render(
      <PromotionProductPicker
        locale="es"
        products={[...products, { id: "inactive", code: "999", name: "Retirado", active: false }]}
        selectedProductIds={["inactive", "missing"]}
        onApply={apply}
        onClose={vi.fn()}
      />
    );
    expect(screen.queryByText("Retirado", { selector: "td" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Solo seleccionados" }));
    expect(screen.getByText("Retirado", { selector: "td" })).toBeInTheDocument();
    expect(screen.getByText("missing", { selector: "td" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 999" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(apply).toHaveBeenCalledWith(["missing"]);
  });

  it("loads the first 50 products and appends more rows near the bottom", () => {
    setupApi();
    const many = Array.from({ length: 120 }, (_, index) => ({
      id: `row-${index}`,
      code: String(index).padStart(3, "0"),
      name: `Product ${index}`
    }));
    render(
      <PromotionProductPicker
        locale="es"
        products={many}
        selectedProductIds={[]}
        onApply={vi.fn()}
        onClose={vi.fn()}
      />
    );
    const scroller = document.querySelector(".promotion-product-picker-table-scroll")!;
    const rows = () => screen.getAllByRole("row").slice(1);
    expect(rows()).toHaveLength(50);
    expect(screen.queryByRole("button", { name: "Siguiente" })).not.toBeInTheDocument();

    scroller.scrollTop = 800;
    Object.defineProperties(scroller, {
      scrollHeight: { configurable: true, value: 1000 },
      clientHeight: { configurable: true, value: 150 }
    });
    fireEvent.scroll(scroller);
    expect(rows()).toHaveLength(100);
    expect(screen.getByText("Product 99", { selector: "td" })).toBeInTheDocument();
  });

  it("sorts the entire result before rendering a batch, keeping the selection and header focus", async () => {
    const catalog = Array.from({ length: 60 }, (_, i) => ({
      id: String(i + 1), code: String(i + 1), name: `Producto ${60 - i}`,
      barcode: i === 59 ? null : `BC-${i + 1}`, barcode2: "BC-0"
    }));
    setupApi([
      { productId: "58", warehouseId: "w1", quantity: -4 },
      { productId: "59", warehouseId: "w1", quantity: 12 },
      { productId: "60", warehouseId: "w1", quantity: 100 }
    ]);
    render(<PromotionProductPicker locale="es" products={catalog} selectedProductIds={["60"]}
      onApply={vi.fn()} onClose={vi.fn()} />);
    await waitFor(() => expect(screen.getByText("0", { selector: "tbody tr:first-child td:last-child" })).toBeInTheDocument());

    const codes = () => Array.from(screen.getByRole("table").querySelectorAll("tbody tr td:nth-child(2)"), cell => cell.textContent);
    const sortBy = (label: string) => {
      const button = screen.getByRole("button", { name: `Ordenar por ${label}` });
      button.focus();
      fireEvent.click(button);
      expect(button).toHaveFocus();
      return button.closest("th");
    };

    expect(codes()).toHaveLength(50);
    expect(codes().slice(0, 2)).toEqual(["1", "2"]);
    expect(sortBy("Código")).toHaveAttribute("aria-sort", "descending");
    expect(codes().slice(0, 2)).toEqual(["60", "59"]);
    expect(screen.getByRole("checkbox", { name: "Seleccionar 60" })).toBeChecked();
    expect(sortBy("Código de barras")).toHaveAttribute("aria-sort", "ascending");
    expect(codes().slice(0, 2)).toEqual(["60", "1"]);
    sortBy("Producto");
    expect(codes().slice(0, 2)).toEqual(["60", "59"]);
    sortBy("Stock disponible");
    expect(codes().slice(0, 2)).toEqual(["58", "1"]);
    expect(sortBy("Stock disponible")).toHaveAttribute("aria-sort", "descending");
    expect(codes().slice(0, 3)).toEqual(["60", "59", "1"]);
  });

  it("moves focus to the next product when ArrowDown is pressed at the loaded limit", () => {
    setupApi();
    const many = Array.from({ length: 80 }, (_, index) => ({
      id: `row-${index}`,
      code: String(index).padStart(3, "0"),
      name: `Product ${index}`
    }));
    render(
      <PromotionProductPicker
        locale="es"
        products={many}
        selectedProductIds={[]}
        onApply={vi.fn()}
        onClose={vi.fn()}
      />
    );
    const lastLoaded = screen.getByText("Product 49", { selector: "td" }).closest("tr")!;
    lastLoaded.focus();
    fireEvent.keyDown(lastLoaded, { key: "ArrowDown" });
    const next = screen.getByText("Product 50", { selector: "td" }).closest("tr")!;
    expect(next).toHaveFocus();
  });

  it("preserves selection and search focus while filtering resets the loaded list", () => {
    setupApi();
    const catalog = Array.from({ length: 120 }, (_, index) => ({
      id: `row-${index}`,
      code: String(index).padStart(3, "0"),
      name: `Producto ${String(index).padStart(3, "0")}`
    }));
    const apply = vi.fn();
    render(
      <PromotionProductPicker
        locale="es"
        products={catalog}
        selectedProductIds={[]}
        onApply={apply}
        onClose={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 000" }));
    const scroller = document.querySelector(".promotion-product-picker-table-scroll")!;
    scroller.scrollTop = 800;
    Object.defineProperties(scroller, {
      scrollHeight: { configurable: true, value: 1000 },
      clientHeight: { configurable: true, value: 150 }
    });
    fireEvent.scroll(scroller);
    expect(screen.getAllByRole("row").slice(1)).toHaveLength(100);
    const search = screen.getByRole("searchbox");
    fireEvent.change(search, { target: { value: "Producto" } });
    expect(search).toHaveFocus();
    expect(scroller.scrollTop).toBe(0);
    expect(screen.getAllByRole("row").slice(1)).toHaveLength(50);
    fireEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(apply).toHaveBeenCalledWith(["row-0"]);
  });

  it("selects all filtered results, including products not rendered yet", () => {
    setupApi();
    const many = Array.from({ length: 120 }, (_, index) => ({
      id: `row-${index}`,
      code: String(index).padStart(3, "0"),
      name: `Product ${index}`
    }));
    const apply = vi.fn();
    render(
      <PromotionProductPicker
        locale="es"
        products={many}
        selectedProductIds={[]}
        onApply={apply}
        onClose={vi.fn()}
      />
    );
    expect(screen.getAllByRole("row").slice(1)).toHaveLength(50);
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar los 120 resultados del filtro" }));
    expect(screen.getByRole("checkbox", { name: "Quitar los 120 resultados del filtro" })).toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(apply).toHaveBeenCalledWith(many.map((product) => product.id));
  });

  it("shows the filtered result count and total selection, and removes all selected results from the header", () => {
    setupApi();
    const apply = vi.fn();
    render(<PromotionProductPicker locale="es" products={products} selectedProductIds={["p2", "p3"]}
      onApply={apply} onClose={vi.fn()} />);

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Café" } });
    expect(screen.getByText("1 resultado · 2 seleccionados en total")).toBeInTheDocument();
    const headerCheckbox = screen.getByRole("table").querySelector("thead input[type=checkbox]")!;
    fireEvent.click(headerCheckbox);
    expect(screen.getByText("1 resultado · 3 seleccionados en total")).toBeInTheDocument();
    expect(headerCheckbox).toBeChecked();
    fireEvent.click(headerCheckbox);
    expect(screen.getByText("1 resultado · 2 seleccionados en total")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Deshacer" }));
    expect(headerCheckbox).toBeChecked();
    expect(screen.getByText("1 resultado · 3 seleccionados en total")).toBeInTheDocument();
    fireEvent.click(headerCheckbox);
    fireEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(apply).toHaveBeenCalledWith(["p2", "p3"]);
  });

  it("removes hidden selections and restores them without discarding later manual or Excel additions", () => {
    setupApi();
    const apply = vi.fn();
    const catalog = [...products, { id: "p4", code: "004", name: "Agua" },
      { id: "inactive", code: "999", name: "Retirado", active: false }];
    render(<PromotionProductPicker locale="es" products={catalog}
      selectedProductIds={["p1", "p2", "inactive", "missing"]} onApply={apply} onClose={vi.fn()} />);

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Café" } });
    expect(screen.getByText("1 resultado · 4 seleccionados en total")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Quitar los 4 seleccionados" }));
    expect(screen.getByRole("button", { name: "Quitar seleccionados (0)" })).toBeDisabled();
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "" } });
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 004" }));
    fireEvent.click(screen.getByRole("button", { name: "Excel" }));
    fireEvent.click(screen.getByRole("button", { name: "Deshacer" }));
    fireEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(apply).toHaveBeenCalledOnce();
    expect(apply.mock.calls[0][0]).toHaveLength(6);
    expect(apply.mock.calls[0][0]).toEqual(expect.arrayContaining(["p1", "p2", "inactive", "missing", "p4", "p3"]));
  });

  it("uses the singular removal label and disables removal when nothing is selected", () => {
    setupApi();
    render(<PromotionProductPicker locale="es" products={products} selectedProductIds={[]}
      onApply={vi.fn()} onClose={vi.fn()} />);
    expect(screen.getByRole("button", { name: "Quitar seleccionados (0)" })).toBeDisabled();
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 001" }));
    fireEvent.click(screen.getByRole("button", { name: "Quitar 1 seleccionado" }));
    expect(screen.getByRole("checkbox", { name: "Seleccionar 001" })).not.toBeChecked();
    fireEvent.click(screen.getByRole("button", { name: "Deshacer" }));
    expect(screen.getByRole("checkbox", { name: "Seleccionar 001" })).toBeChecked();
  });

  it("restores filters, sorting and deep scroll after reviewing every selected product", async () => {
    const catalog = Array.from({ length: 120 }, (_, index) => ({
      id: `row-${index}`, code: String(index).padStart(3, "0"), name: `Producto ${index}`,
      familyId: "f1", subfamilyId: "s1"
    }));
    const otherStock = catalog.map(({ id }) => ({ productId: id, warehouseId: "w2", quantity: 1 }));
    setupApi([], otherStock);
    render(<PromotionProductPicker locale="es" products={catalog} selectedProductIds={["missing"]}
      onApply={vi.fn()} onClose={vi.fn()} />);

    await waitFor(() => expect(screen.getByRole("button", { name: "Almacén" })).toHaveTextContent("General"));
    chooseFilter("Almacén", "Otro");
    await waitFor(() => expect(screen.getByText("1", { selector: "tbody tr:first-child td:last-child" })).toBeInTheDocument());
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Producto" } });
    chooseFilter("Familia", "Bebidas");
    await waitFor(() => expect(apiRequest).toHaveBeenCalledWith("/families/f1/subfamilies", { token: undefined }));
    chooseFilter("Subfamilia", "Café");
    chooseFilter("Disponibilidad", "Con stock");
    fireEvent.click(screen.getByRole("button", { name: "Ordenar por Código" }));
    expect(screen.getByRole("button", { name: "Ordenar por Código" }).closest("th")).toHaveAttribute("aria-sort", "descending");
    const scroller = document.querySelector(".promotion-product-picker-table-scroll")! as HTMLDivElement;
    Object.defineProperties(scroller, {
      scrollHeight: { configurable: true, value: 1000 },
      clientHeight: { configurable: true, value: 150 }
    });
    scroller.scrollTop = 800;
    fireEvent.scroll(scroller);
    expect(screen.getAllByRole("row").slice(1)).toHaveLength(100);
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 030" }));

    fireEvent.click(screen.getByRole("checkbox", { name: "Solo seleccionados" }));
    expect(screen.getByText("missing", { selector: "td" })).toBeInTheDocument();
    expect(screen.getByText("Producto 30", { selector: "td" })).toBeInTheDocument();
    chooseFilter("Almacén", "General");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "030" } });
    await waitFor(() => expect(screen.getByText("0", { selector: "tbody tr:first-child td:last-child" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("checkbox", { name: "Solo seleccionados" }));

    expect(screen.getByRole("searchbox")).toHaveValue("Producto");
    expect(screen.getByRole("button", { name: "Familia" })).toHaveTextContent("Bebidas");
    expect(screen.getByRole("button", { name: "Subfamilia" })).toHaveTextContent("Café");
    expect(screen.getByRole("button", { name: "Disponibilidad" })).toHaveTextContent("Con stock");
    expect(screen.getByRole("button", { name: "Almacén" })).toHaveTextContent("Otro");
    expect(screen.getByRole("button", { name: "Ordenar por Código" }).closest("th")).toHaveAttribute("aria-sort", "descending");
    await waitFor(() => expect(screen.getAllByRole("row").slice(1)).toHaveLength(100));
    expect(scroller.scrollTop).toBe(800);
  });

  it("dismisses the open filter before closing the product picker", async () => {
    setupApi();
    const onClose = vi.fn();
    const onApply = vi.fn();
    render(<PromotionProductPicker locale="es" products={products} selectedProductIds={[]} onApply={onApply} onClose={onClose} />);
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 001" }));
    fireEvent.click(screen.getByRole("button", { name: "Disponibilidad" }));
    fireEvent.keyDown(screen.getByRole("option", { name: "Con stock" }), { key: "Escape" });
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(screen.getByRole("alertdialog", { name: "¿Descartar los cambios en la selección?" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Seguir editando" })).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Descartar y salir" }));
    expect(onClose).toHaveBeenCalledOnce();
    expect(onApply).not.toHaveBeenCalled();
  });

  it("supports arrow and space keys and warns on Escape after changing selection", async () => {
    setupApi();
    const close = vi.fn();
    render(
      <PromotionProductPicker
        locale="es"
        products={products}
        selectedProductIds={[]}
        onApply={vi.fn()}
        onClose={close}
      />
    );
    const first = screen.getByText("Café", { selector: "td" }).closest("tr")!;
    first.focus();
    fireEvent.keyDown(first, { key: "ArrowDown" });
    const second = screen.getByText("Té", { selector: "td" }).closest("tr")!;
    expect(second).toHaveFocus();
    fireEvent.keyDown(second, { key: " " });
    expect(screen.getByRole("checkbox", { name: "Seleccionar 002" })).toBeChecked();
    fireEvent.keyDown(second, { key: "Escape" });
    expect(screen.getByRole("button", { name: "Seguir editando" })).toHaveFocus();
    expect(close).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Descartar y salir" }));
    expect(close).toHaveBeenCalledTimes(1);
  });

  it("keeps a substituted product and restores focus after continuing or dismissing the warning with Escape", () => {
    setupApi();
    const apply = vi.fn();
    const close = vi.fn();
    render(<PromotionProductPicker locale="es" products={products} selectedProductIds={["p1"]}
      onApply={apply} onClose={close} />);

    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 001" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 002" }));
    const closeButton = screen.getByRole("button", { name: "Cerrar" });
    closeButton.focus();
    fireEvent.click(closeButton);
    fireEvent.click(screen.getByRole("button", { name: "Seguir editando" }));
    expect(closeButton).toHaveFocus();
    expect(screen.queryByRole("button", { name: "Descartar y salir" })).not.toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Seleccionar 002" })).toBeChecked();
    expect(close).not.toHaveBeenCalled();

    const search = screen.getByRole("searchbox");
    search.focus();
    fireEvent.keyDown(search, { key: "Escape" });
    const continueButton = screen.getByRole("button", { name: "Seguir editando" });
    expect(continueButton).toHaveFocus();
    fireEvent.keyDown(continueButton, { key: "Escape" });
    expect(search).toHaveFocus();
    expect(screen.queryByRole("button", { name: "Descartar y salir" })).not.toBeInTheDocument();
    expect(screen.getByRole("checkbox", { name: "Seleccionar 002" })).toBeChecked();
    expect(close).not.toHaveBeenCalled();

    fireEvent.click(screen.getByRole("button", { name: "Confirmar" }));
    expect(apply).toHaveBeenCalledWith(["p2"]);
    expect(screen.queryByRole("button", { name: "Descartar y salir" })).not.toBeInTheDocument();
  });

  it("closes directly when the original selection is restored in a different order", () => {
    setupApi();
    const apply = vi.fn();
    const close = vi.fn();
    render(<PromotionProductPicker locale="es" products={products} selectedProductIds={["p1", "p2"]}
      onApply={apply} onClose={close} />);

    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 001" }));
    fireEvent.click(screen.getByRole("checkbox", { name: "Seleccionar 001" }));
    fireEvent.click(screen.getByRole("button", { name: "Cerrar" }));
    expect(close).toHaveBeenCalledOnce();
    expect(apply).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Descartar y salir" })).not.toBeInTheDocument();
  });

  it("closes directly after changing only filters", () => {
    setupApi();
    const apply = vi.fn();
    const close = vi.fn();
    render(<PromotionProductPicker locale="es" products={products} selectedProductIds={["p1"]}
      onApply={apply} onClose={close} />);

    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Té" } });
    fireEvent.keyDown(screen.getByRole("searchbox"), { key: "Escape" });
    expect(close).toHaveBeenCalledOnce();
    expect(apply).not.toHaveBeenCalled();
    expect(screen.queryByRole("button", { name: "Descartar y salir" })).not.toBeInTheDocument();
  });

  it("does not interpret unknown stock as zero", () => {
    expect(filterPromotionProducts(products, "", "", "", "unavailable", null, "es")).toEqual([]);
    expect(
      filterPromotionProducts(products, "", "", "", "unavailable", new Map([["p1", 4]]), "es").map(
        (item) => item.id
      )
    ).toEqual(["p2", "p3"]);
    expect(
      filterPromotionProducts(products, "", "", "", "unavailable", new Map([["p1", null]]), "es").map(
        (item) => item.id
      )
    ).toEqual(["p2", "p3"]);
  });
});
