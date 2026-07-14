import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { SharedExcelImportDialog } from "./SharedExcelImportDialog";

describe("SharedExcelImportDialog", () => {
  let storage: Map<string, string>;

  beforeEach(() => {
    storage = new Map();
    vi.stubGlobal("localStorage", {
      getItem: (key: string) => storage.get(key) ?? null,
      setItem: (key: string, value: string) => storage.set(key, value),
      removeItem: (key: string) => storage.delete(key),
      clear: () => storage.clear()
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it("shows the import summary in the viewer before an Excel file is loaded", () => {
    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toContain("Resumen de importacion");
    expect(html).toContain("Pulsa Abrir Excel");
    expect(html).toContain("Impuestos incluidos usa 1 para verdadero y 0 para falso");
    expect(html).toContain("Usar precio define la tarifa activa");
    expect(html).toContain("Prohibido descuento usa 1");
    expect(html).toContain("Oferta activa usa 1");
  });

  it("renders fullscreen preview, mapping controls and bottom sections", () => {
    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        sheet={[
          ["Codigo", "Barcode", "Nombre", "Compra", "Venta", "Cantidad"],
          ["A001", "", "Agua", "1.00", "2.00", "3"],
          ["NOPE", "", "Nuevo", "", "", "1"]
        ]}
        products={[{ id: "product-1", code: "A001", barcode: "843" }]}
        currentPurchasePrice={() => "1.00"}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toContain('class="filter-dialog shared-excel-dialog"');
    expect(html).toContain("Abrir Excel");
    expect(html).toContain("Abrir edicion");
    expect(html).toContain("Regreso [Esc]");
    expect(html).toContain("Limpiar");
    expect(html).toContain("Aplicar");
    expect(html).toContain("Generar documento resumen");
    expect(html).toContain("Codigo");
    expect(html).toContain("Codigo de barras");
    expect(html).toContain("Producto empieza en fila");
    expect(html).toContain("Precio socio");
    expect(html).not.toContain('value="A"');
    expect(html).not.toContain('value="F"');
    expect(html).toContain("Productos no importables (0)");
    expect(html).toContain("Productos importables (0)");
    expect(html).toContain("Errores (0)");
    expect(html).toContain("Configuracion archivo");
    expect(html).toContain("NOPE");
  });

  it("loads the saved terminal template into mapping fields", () => {
    storage.set("tpv.sharedExcelImport.v1.terminal-1", JSON.stringify({
      mapping: {
        code: "A",
        name: "B",
        purchasePrice: "C",
        salePrice: "D"
      },
      quantityColumn: "",
      startRow: 2,
      updateFields: {}
    }));

    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        terminalContext={{ terminalCode: "01", terminalId: "terminal-1" }}
        sheet={[
          ["Codigo", "Nombre", "Compra", "Venta"],
          ["A001", "Agua", "1.20", "2.00"]
        ]}
        products={[{ id: "product-1", code: "A001", barcode: "843" }]}
        currentPurchasePrice={() => "1.00"}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toContain('value="A"');
    expect(html).toContain('value="C"');
    expect(html).toContain("1 filas detectadas");
    expect(html).toContain("0 no existentes");
  });

  it("keeps product identity limited to code and barcode", () => {
    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        sheet={[
          ["Codigo", "Barcode", "Nombre", "Compra", "Venta"],
          ["", "", "Nombre igual", "1", "2"]
        ]}
        products={[{ id: "product-1", code: "A001", barcode: "843" }]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toContain("Errores (0)");
    expect(html).not.toContain("product-1");
  });
});
