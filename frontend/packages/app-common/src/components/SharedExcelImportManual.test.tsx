// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SharedExcelImportDialog } from "./SharedExcelImportDialog";

vi.mock("./ProductCreateDialog", async (importOriginal) => {
  const actual = await importOriginal<typeof import("./ProductCreateDialog")>();
  return { ...actual, ProductCreateDialog: (props: {
    initialForm?: { code?: string; name?: string }; onCreated: (value: { id: string; code: string; name: string }) => void; onClose: () => void;
  }) => <div role="dialog" aria-label="Alta manual">
    <input aria-label="Código del alta" defaultValue={props.initialForm?.code} />
    <span>{props.initialForm?.name}</span>
    <button onClick={() => {
      const code = (document.querySelector('[aria-label="Código del alta"]') as HTMLInputElement).value;
      props.onCreated({ id: "created-id", code, name: "Nombre corregido en formulario" });
      props.onClose();
    }}>Guardar alta</button>
    <button onClick={props.onClose}>Cancelar alta</button>
  </div> };
});

afterEach(() => { cleanup(); vi.unstubAllGlobals(); localStorage.clear(); });

describe("Excel manual creation session", () => {
  it.each(["STOCK", "WAREHOUSE_INPUT"] as const)("keeps original Excel and row bindings after editing a code and cancelling the next product in %s", async (context) => {
    const configs: Array<{ resolvedProducts?: Record<string, string> }> = [];
    const json = (value: unknown) => ({ ok: true, status: 200, headers: { get: () => null }, text: async () => JSON.stringify(value) });
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/read")) return json({
        fileName: "test.xlsx", sha256: "a".repeat(64), sheetName: "Hoja",
        rows: [["Código", "Nombre"], ["OLD-A", "Nombre Excel A"], ["OLD-B", "Nombre Excel B"]].map(row => row.map(value => ({ value }))),
        formulas: [], nonEmptyRows: 3, columns: 2, nonEmptyCells: 6
      });
      if (url.endsWith("/preview")) {
        const config = JSON.parse(await ((init!.body as FormData).get("config") as Blob).text());
        configs.push(config);
        return json({ fileName: "test.xlsx", sha256: "a".repeat(64), sheetName: "Hoja", previewFingerprint: "b".repeat(64),
          detectedRows: 2, existingRows: config.resolvedProducts?.["2"] ? 1 : 0, missingRows: config.resolvedProducts?.["2"] ? 1 : 2, errors: [],
          rows: [2, 3].map(row => ({
            rowNumber: row, rowNumbers: [row],
            classification: config.resolvedProducts?.[row] ? "EXISTING" : "MISSING",
            existence: config.resolvedProducts?.[row] ? "EXISTING" : "MISSING",
            excelData: { code: row === 2 ? "OLD-A" : "OLD-B", name: row === 2 ? "Nombre Excel A" : "Nombre Excel B" },
            databaseData: config.resolvedProducts?.[row] ? { id: config.resolvedProducts[row], code: "EDITED", name: "Nombre corregido en formulario" } : null,
            version: 1, changes: {}, purchasePriceChanged: false, errors: []
          }))
        });
      }
      return json([]);
    });
    vi.stubGlobal("fetch", fetchMock);
    const onImport = vi.fn();
    const { container } = render(<SharedExcelImportDialog open token="token" locale="es" context={context} products={[]} onClose={vi.fn()} onImportAccepted={onImport} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(["xls"], "test.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    fireEvent.click(screen.getByLabelText("Añadir automáticamente los productos no existentes"));
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Añadir productos" })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: "Añadir productos" }));
    expect(screen.getByLabelText("Código del alta")).toHaveValue("OLD-A");
    fireEvent.change(screen.getByLabelText("Código del alta"), { target: { value: "EDITED" } });
    fireEvent.click(screen.getByRole("button", { name: "Guardar alta" }));
    await waitFor(() => expect(screen.getByLabelText("Código del alta")).toHaveValue("OLD-B"));
    expect(configs.at(-1)?.resolvedProducts).toEqual({ "2": "created-id" });
    fireEvent.click(screen.getByRole("button", { name: "Cancelar alta" }));
    expect(screen.queryByRole("dialog", { name: "Alta manual" })).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Productos importables (1)" })).toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Productos no importables (1)" })).toBeInTheDocument();
    expect(container.querySelector(".shared-excel-preview")?.textContent).toContain("OLD-A");
    expect(container.querySelector(".shared-excel-preview")?.textContent).not.toContain("EDITED");
    expect(onImport).not.toHaveBeenCalled();
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith("/apply"))).toBe(false);
  });
});
