// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { readProductExcelImport } from "../api/productExcelImports";
import { PromotionProductExcelImportButton } from "./PromotionProductExcelImportButton";

vi.mock("../api/productExcelImports", async (importOriginal) => ({
  ...(await importOriginal<typeof import("../api/productExcelImports")>()),
  readProductExcelImport: vi.fn()
}));

afterEach(() => {
  cleanup();
  vi.clearAllMocks();
});

describe("PromotionProductExcelImportButton", () => {
  it("previews matches and adds only existing, unique, unselected product IDs", async () => {
    vi.mocked(readProductExcelImport).mockResolvedValue({
      fileName: "products.xlsx",
      sha256: "sha",
      sheetName: "Sheet1",
      formulas: [],
      nonEmptyRows: 5,
      columns: 2,
      nonEmptyCells: 8,
      rows: [
        ["Code", "Barcode"],
        ["0001", null],
        ["0001", null],
        ["missing", null],
        [null, "002"]
      ].map((row) => row.map((value) => ({ value })))
    });
    const onAddProducts = vi.fn();
    render(
      <PromotionProductExcelImportButton
        locale="es"
        token="token"
        products={[
          { id: "a", code: "0001", name: "Uno" },
          { id: "b", barcode: "002", name: "Dos" }
        ]}
        selectedProductIds={["b"]}
        onAddProducts={onAddProducts}
      />
    );
    fireEvent.click(screen.getByRole("button", { name: "Importar Excel" }));
    const dialog = screen.getByRole("dialog", { name: "Importar productos desde Excel" });
    fireEvent.change(within(dialog).getByLabelText("Abrir archivo"), {
      target: { files: [new File(["xls"], "products.xlsx")] }
    });
    await waitFor(() =>
      expect(
        within(dialog).getByText("1 para añadir · 1 con incidencias · 2 ya seleccionados o repetidos")
      ).toBeInTheDocument()
    );
    expect(readProductExcelImport).toHaveBeenCalledWith(expect.any(File), "token", expect.any(AbortSignal));
    fireEvent.click(within(dialog).getByRole("button", { name: "Añadir a la selección (1)" }));
    expect(onAddProducts).toHaveBeenCalledExactlyOnceWith(["a"]);
    expect(screen.queryByRole("dialog", { name: "Importar productos desde Excel" })).not.toBeInTheDocument();
  });

  it("stops the loading state when a pending read is replaced by an invalid file", async () => {
    vi.mocked(readProductExcelImport).mockImplementation(() => new Promise(() => undefined));
    render(
      <PromotionProductExcelImportButton
        locale="es"
        products={[]}
        selectedProductIds={[]}
        onAddProducts={vi.fn()}
      />
    );
    fireEvent.click(screen.getByRole("button", { name: "Importar Excel" }));
    const input = screen.getByLabelText("Abrir archivo");
    fireEvent.change(input, { target: { files: [new File(["xls"], "products.xlsx")] } });
    expect(screen.getByText("Leyendo Excel…")).toBeInTheDocument();
    fireEvent.change(input, { target: { files: [new File(["bad"], "products.txt")] } });
    await waitFor(() => expect(screen.queryByText("Leyendo Excel…")).not.toBeInTheDocument());
    expect(screen.getByRole("alert")).toHaveTextContent("Selecciona un archivo .xls o .xlsx.");
  });

  it("paginates visible cells while adding IDs from every row", async () => {
    const products = Array.from({ length: 201 }, (_, index) => ({ id: `p${index}`, code: `C${index}` }));
    vi.mocked(readProductExcelImport).mockResolvedValue({
      fileName: "many.xlsx",
      sha256: "sha",
      sheetName: "Sheet1",
      formulas: [],
      nonEmptyRows: 202,
      columns: 1,
      nonEmptyCells: 202,
      rows: [[{ value: "Code" }], ...products.map((product) => [{ value: product.code }])]
    });
    const onAddProducts = vi.fn();
    render(
      <PromotionProductExcelImportButton
        locale="es"
        products={products}
        selectedProductIds={[]}
        onAddProducts={onAddProducts}
      />
    );
    fireEvent.click(screen.getByRole("button", { name: "Importar Excel" }));
    fireEvent.change(screen.getByLabelText("Abrir archivo"), {
      target: { files: [new File(["xls"], "many.xlsx")] }
    });
    await waitFor(() => expect(screen.getByRole("button", { name: "Añadir a la selección (201)" })).toBeEnabled());
    const raw = screen.getByRole("table");
    expect(within(raw).getAllByRole("row")).toHaveLength(101);
    fireEvent.click(screen.getByRole("button", { name: "Vista previa (201)" }));
    expect(document.querySelectorAll(".promotion-excel-results tbody tr")).toHaveLength(100);
    fireEvent.click(screen.getByRole("button", { name: "Añadir a la selección (201)" }));
    expect(onAddProducts).toHaveBeenCalledWith(products.map((product) => product.id));
  });
});
