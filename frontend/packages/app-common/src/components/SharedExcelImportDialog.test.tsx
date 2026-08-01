import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  detectExcelHeaderMapping,
  normalizeExcelDecimalCells,
  normalizeExcelDecimalValue,
  sharedExcelImportKeyAction,
  SharedExcelImportDialog,
  updateExcelSheetCell
} from "./SharedExcelImportDialog";

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

    expect(html).toContain("Resumen de importación");
    expect(html).toContain("Pulsa Abrir Excel");
    expect(html).toContain("Impuestos incluidos utiliza 1 para verdadero y 0 para falso");
    expect(html).toContain("Usar precio define la tarifa activa");
    expect(html).toContain("Prohibido descuento utiliza 1");
    expect(html).toContain("Oferta activa utiliza 1");
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
    expect(html).toContain("Editar tabla");
    expect(html).toContain("Volver [Esc]");
    expect(html).toContain("Limpiar fichero");
    expect(html).toContain("Limpiar configuración");
    expect(html).toContain("Aplicar");
    expect(html).toContain("Generar documento resumen");
    expect(html).toContain("Código");
    expect(html).toContain("Código de barras");
    expect(html).toContain("Los productos empiezan en la fila");
    expect(html).toContain("Precio de socio");
    expect(html).not.toContain('value="A"');
    expect(html).not.toContain('value="F"');
    expect(html).toContain("Productos no importables (0)");
    expect(html).toContain("Productos importables (0)");
    expect(html).toContain("Errores (0)");
    expect(html).toContain("Configuración del archivo");
    expect(html).toContain("NOPE");
  });

  it("renders every imported row in the Excel preview", () => {
    const sheet = [
      ["Codigo", "Nombre"],
      ...Array.from({ length: 20 }, (_, index) => [
        `COD-${String(index + 1).padStart(2, "0")}`,
        `Producto ${index + 1}`
      ])
    ];

    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        sheet={sheet}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toContain("COD-01");
    expect(html).toContain("COD-20");
    expect(html).toContain("<th>21</th>");
  });

  it("detects column mappings and update checks from the first row", () => {
    expect(detectExcelHeaderMapping([
      ["Código", "EAN", "Descripción", "Cantidad", "Precio", "Descuento", "Precio de venta", "Precio de socio"],
      ["A001", "843000000001", "Producto", 2, 4.1, 10, 10.25, 8.2]
    ])).toEqual({
      mapping: {
        code: "A",
        barcode: "B",
        description: "C",
        purchasePrice: "E",
        purchaseDiscountPercent: "F",
        salePrice: "G",
        memberPrice: "H"
      },
      quantityColumn: "D",
      updateFields: {
        description: true,
        purchasePrice: true,
        purchaseDiscountPercent: true,
        salePrice: true,
        memberPrice: true
      }
    });
  });

  it("updates a cell in memory without modifying the imported source array", () => {
    const original = [
      ["Código", "Precio de venta"],
      ["A001", 10.25]
    ];

    const updated = updateExcelSheetCell(original, 1, 1, "11.50");

    expect(updated[1][1]).toBe("11.50");
    expect(original[1][1]).toBe(10.25);
  });

  it("preserves formulas and recalculates them when a referenced cell changes", () => {
    const original = [
      ["Precio de compra", "Precio de venta"],
      [4.1, { kind: "formula", formula: "A2*2.5", value: 10.25 }]
    ];

    const updated = updateExcelSheetCell(original, 1, 0, "5");

    expect(updated[1][1]).toEqual(expect.objectContaining({
      kind: "formula",
      formula: "A2*2.5",
      value: 12.5
    }));
  });

  it("shows only the calculated value and hides formula indicators", () => {
    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        sheet={[
          ["Precio de compra", "Precio de venta"],
          [4.1, { kind: "formula", formula: "A2*2.5", value: 10.25 }]
        ]}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toContain(">10.25<");
    expect(html).not.toContain(">fx<");
    expect(html).not.toContain("A2*2.5");
  });

  it("rounds long decimal values to two decimals without changing integers or identifiers", () => {
    expect(normalizeExcelDecimalValue(22.799999999999997)).toBe("22.80");
    expect(normalizeExcelDecimalValue("11.200000000000001")).toBe("11.20");
    expect(normalizeExcelDecimalValue("7,125")).toBe("7,13");
    expect(normalizeExcelDecimalValue("8435606744034")).toBe("8435606744034");
    expect(normalizeExcelDecimalValue("10.25")).toBe("10.25");

    expect(normalizeExcelDecimalCells([
      ["Código", "Precio"],
      ["A001", 22.799999999999997]
    ])).toEqual([
      ["Código", "Precio"],
      ["A001", "22.80"]
    ]);
  });

  it("maps Escape to closing the Excel import and ignores other keys", () => {
    expect(sharedExcelImportKeyAction("Escape")).toBe("close");
    expect(sharedExcelImportKeyAction("Esc")).toBeNull();
    expect(sharedExcelImportKeyAction("Enter")).toBeNull();
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

  it("loads the saved import options as the terminal defaults", () => {
    storage.set("tpv.sharedExcelImport.v1.terminal-options", JSON.stringify({
      mapping: {},
      quantityColumn: "",
      startRow: 2,
      updateFields: {},
      options: {
        autoAddMissing: false,
        generateSummaryDocument: true,
        showOnlyImported: true,
        skipZeroPriceUpdate: false,
        updateSupplier: true,
        priceSource: "memberPrice"
      }
    }));

    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        terminalContext={{ terminalCode: "01", terminalId: "terminal-options" }}
        sheet={[["Código"], ["A001"]]}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toMatch(/type="checkbox"\/> Añadir automáticamente/);
    expect(html).toMatch(/type="checkbox" checked=""\/> Generar documento resumen/);
    expect(html).toMatch(/type="checkbox" checked=""\/> Mostrar solo importados/);
    expect(html).toMatch(/type="checkbox"\/> No actualizar cuando el precio nuevo sea 0/);
    expect(html).toMatch(/type="checkbox" checked=""\/> Actualizar el proveedor del producto/);
    expect(html).toContain('<option value="memberPrice" selected="">Precio de socio</option>');
  });

  it("keeps column mappings empty until an Excel file is loaded", () => {
    storage.set("tpv.sharedExcelImport.v1.terminal-empty", JSON.stringify({
      mapping: {
        code: "A",
        description: "C",
        purchasePrice: "E"
      },
      quantityColumn: "D",
      startRow: 2,
      updateFields: {
        description: true,
        purchasePrice: true
      },
      options: {
        autoAddMissing: true,
        generateSummaryDocument: true,
        showOnlyImported: false,
        skipZeroPriceUpdate: true,
        updateSupplier: false,
        priceSource: "purchasePrice"
      }
    }));

    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        terminalContext={{ terminalCode: "01", terminalId: "terminal-empty" }}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).not.toContain('value="A"');
    expect(html).not.toContain('value="C"');
    expect(html).not.toContain('value="E"');
    expect(html).toMatch(/type="checkbox" checked=""\/> Generar documento resumen/);
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

  it.each([
    ["en", "Import summary", "Open Excel"],
    ["zh", "导入说明", "打开 Excel"]
  ] as const)("renders the complete import entry point in %s", (locale, heading, action) => {
    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale={locale}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toContain(heading);
    expect(html).toContain(action);
    expect(html).not.toContain("Resumen de importación");
  });
});
