// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  detectExcelHeaderMapping,
  excelReviewColumns,
  excelReviewChangedColumns,
  populatedExcelReviewColumns,
  combinedFamilyUpdateFields,
  normalizeExcelDecimalCells,
  normalizeExcelDecimalValue,
  applyPreviewOptions,
  changedExcelCells,
  previewRowToClassifiedRow,
  sanitizeExcelColumnLetter,
  sharedExcelImportKeyAction,
  SharedExcelImportDialog,
  PRODUCT_EXCEL_IMPORT_ERROR_CODES,
  localizedImportErrorCatalog,
  updateExcelSheetCell
} from "./SharedExcelImportDialog";

describe("SharedExcelImportDialog", () => {
  it.each(["STOCK", "WAREHOUSE_INPUT"] as const)("keeps the exact three attribute groups in %s", (context) => {
    const { container } = render(<SharedExcelImportDialog open locale="es" context={context} products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    const groups = [1, 2, 3].map((column) => Array.from(container.querySelectorAll<HTMLElement>(".shared-excel-mapping-row"))
      .filter((row) => row.style.gridColumn === String(column)).map((row) => row.getAttribute("aria-label")));
    expect(groups).toEqual([
      ["Código", "Código de barras", "Nombre", "Descripción", ...(context === "STOCK" ? [] : ["Cantidad"]), "Precio de compra", "Descuento de compra", "Familia / Subfamilia", "Código de barras 2"],
      ["Precio de venta", "Precio de miembro", "Precio mayorista", "Precio de oferta", "Descuento de oferta %", "Oferta desde", "Oferta hasta", "Oferta activa"],
      ["Tipo de producto", "Usar precio", "Prohibido descuento", "Impuestos", "Impuestos incluidos", "Cantidad por paquete", "Stock mínimo", "Stock máximo"]
    ]);
  });

  it("uses the requested fresh defaults and resets them without checking update permissions", () => {
    render(<SharedExcelImportDialog open locale="es" context="STOCK" products={[]}
      sheet={[["Código", "Tipo", "Impuestos", "Impuestos incluidos", "Prohibido descuento"], ["A1", "3", "21", "0", "1"]]}
      taxOptions={[{ id: "other", label: "21%" }, { id: "system", label: "7%", defaultTax: true }]}
      onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    const expected = [["Tipo de producto", "1"], ["Prohibido descuento", "0"], ["Impuestos", "7%"], ["Impuestos incluidos", "1"]];
    for (const [label, value] of expected) {
      expect(screen.getByRole("combobox", { name: label + " Columna o valor" })).toHaveValue(value);
      expect(screen.getByLabelText("Actualizar " + label)).not.toBeChecked();
    }
    fireEvent.change(screen.getByRole("combobox", { name: "Impuestos Columna o valor" }), { target: { value: "AA" } });
    fireEvent.click(screen.getByRole("button", { name: "Limpiar configuración" }));
    for (const [label, value] of expected) expect(screen.getByRole("combobox", { name: label + " Columna o valor" })).toHaveValue(value);
    expect(screen.getByRole("combobox", { name: "Oferta activa Columna o valor" })).toHaveValue("");
  });

  it("preserves saved column assignments and normalizes an old fixed product type", () => {
    storage.set("tpv.sharedExcelImport.v1.terminal-default", JSON.stringify({ mapping: { productType: "B", discountType: "D", taxId: "AA", taxesIncluded: "E", offerActive: "F" }, updateFields: { productType: false, offerActive: false } }));
    const props = { locale: "es" as const, context: "STOCK" as const, products: [], onClose: vi.fn(), onImportAccepted: vi.fn(), taxOptions: [{ id: "system", label: "7%", defaultTax: true }] };
    const { rerender } = render(<SharedExcelImportDialog {...props} open sheet={[["Código", "Oferta activa", "Tipo", "Prohibido descuento", "Impuestos", "Impuestos incluidos"], ["A1"]]} />);
    for (const [label, value] of [["Tipo de producto", "B"], ["Prohibido descuento", "D"], ["Impuestos", "AA"], ["Impuestos incluidos", "E"], ["Oferta activa", "F"]])
      expect(screen.getByRole("combobox", { name: label + " Columna o valor" })).toHaveValue(value);
    expect(screen.getByRole("checkbox", { name: "Actualizar Tipo de producto" })).not.toBeChecked();
    expect(screen.getByRole("checkbox", { name: "Actualizar Oferta activa" })).not.toBeChecked();
    storage.set("tpv.sharedExcelImport.v1.terminal-default", JSON.stringify({ options: { valueSources: { productType: { source: "global", value: "WEIGHT" } } } }));
    rerender(<SharedExcelImportDialog {...props} open={false} />);
    rerender(<SharedExcelImportDialog {...props} open />);
    expect(screen.getByRole("combobox", { name: "Tipo de producto Columna o valor" })).toHaveValue("2");
  });

  it.each([false, true])("loads the store default asynchronously without replacing a manual choice (%s)", async (manual) => {
    let complete!: (value: ReturnType<typeof jsonResponse>) => void;
    vi.stubGlobal("fetch", vi.fn(() => new Promise((resolve) => { complete = resolve; })));
    render(<SharedExcelImportDialog open locale="es" token="token" context="WAREHOUSE_INPUT" products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    const input = screen.getByRole("combobox", { name: "Impuestos Columna o valor" });
    if (manual) fireEvent.change(input, { target: { value: "B" } });
    await act(async () => {
      complete(jsonResponse([{ id: "other", percentage: 21, defaultTax: false }, { id: "default", percentage: 7, defaultTax: true, active: true }]));
    });
    if (!manual) await waitFor(() => expect(input).toHaveValue("7%"));
    else {
      fireEvent.click(screen.getByRole("button", { name: "Impuestos" }));
      await screen.findByRole("option", { name: "7%" });
      expect(input).toHaveValue("B");
    }
  });

  it("never substitutes the first tax when the system has no active default", () => {
    render(<SharedExcelImportDialog open locale="es" products={[]}
      taxOptions={[{ id: "inactive", label: "4%", defaultTax: true, active: false }, { id: "other", label: "21%" }]}
      onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    expect(screen.getByRole("combobox", { name: "Impuestos Columna o valor" })).toHaveValue("");
  });

  it.each(["back", "escape"])("asks before closing through %s and preserves the mapping when cancelled", async (source) => {
    const onClose = vi.fn();
    const { container } = render(<SharedExcelImportDialog open locale="es" sheet={[["Código"], ["A1"]]}
      products={[]} onClose={onClose} onImportAccepted={vi.fn()} />);
    const column = screen.getByLabelText("Código Columna Excel");
    fireEvent.change(column, { target: { value: "AA" } });
    const trigger = source === "back" ? screen.getByRole("button", { name: "Volver [Esc]" }) : column;
    trigger.focus();
    const requestClose = () => source === "back" ? fireEvent.click(trigger) : fireEvent.keyDown(trigger, { key: "Escape" });
    requestClose();
    const confirmation = screen.getByRole("alertdialog", { name: "¿Cerrar la importación de Excel?" });
    expect(confirmation).toHaveClass("app-venta-home-confirm-dialog");
    expect(container.querySelector(".shared-excel-overlay")).toHaveAttribute("inert");
    expect(container.querySelector(".shared-excel-overlay")).toHaveAttribute("aria-hidden", "true");
    expect(screen.queryByRole("dialog", { name: "Importar Excel" })).not.toBeInTheDocument();
    expect(within(confirmation).getByRole("button", { name: "Cancelar" })).toHaveFocus();
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(within(confirmation).getByRole("button", { name: "Cancelar" }));
    await waitFor(() => expect(trigger).toHaveFocus());
    expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("AA");
    expect(container.querySelector(".shared-excel-overlay")).not.toHaveAttribute("inert");
    requestClose();
    const closeButton = within(screen.getByRole("alertdialog")).getByRole("button", { name: "Cerrar importador" });
    fireEvent.click(closeButton);
    fireEvent.click(closeButton);
    expect(onClose).toHaveBeenCalledOnce();
  });

  it("Escape cancels only the close confirmation, ignores held Escape and traps focus inside it", async () => {
    const onClose = vi.fn();
    render(<SharedExcelImportDialog open locale="es" products={[]} onClose={onClose} onImportAccepted={vi.fn()} />);
    const back = screen.getByRole("button", { name: "Volver [Esc]" });
    back.focus();
    fireEvent.keyDown(back, { key: "Escape" });
    const confirmation = screen.getByRole("alertdialog");
    const cancel = within(confirmation).getByRole("button", { name: "Cancelar" });
    const confirm = within(confirmation).getByRole("button", { name: "Cerrar importador" });
    fireEvent.keyDown(cancel, { key: "Escape", repeat: true });
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    fireEvent.keyDown(cancel, { key: "Tab", shiftKey: true });
    expect(confirm).toHaveFocus();
    fireEvent.keyDown(confirm, { key: "Tab" });
    expect(cancel).toHaveFocus();
    fireEvent.keyDown(cancel, { key: "Escape" });
    await waitFor(() => expect(back).toHaveFocus());
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(onClose).not.toHaveBeenCalled();
  });

  it.each([
    ["es", "¿Cerrar la importación de Excel?", "Cerrar importador", "se conservarán"],
    ["en", "Close the Excel import?", "Close importer", "will be kept"],
    ["zh", "关闭 Excel 导入窗口？", "关闭导入窗口", "予以保留"]
  ] as const)("localizes close confirmation in %s and clears it when reopened", (locale, title, action, persisted) => {
    const props = { locale, products: [], onClose: vi.fn(), onImportAccepted: vi.fn() };
    const { rerender } = render(<SharedExcelImportDialog {...props} open />);
    fireEvent.keyDown(window, { key: "Escape" });
    const confirmation = screen.getByRole("alertdialog", { name: title });
    expect(confirmation).toHaveTextContent(persisted);
    expect(within(confirmation).getByRole("button", { name: action })).toBeInTheDocument();
    rerender(<SharedExcelImportDialog {...props} open={false} />);
    rerender(<SharedExcelImportDialog {...props} open />);
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
  });

  it.each([
    ["purchasePrice", "1,230", "1.23", false],
    ["productType", "1", "UNIT", false],
    ["productType", "2", "WEIGHT", false],
    ["productType", "3", "UNIT", true],
    ["salePrice", "1.234", "1.23", true],
    ["salePrice", "99999999999999999.001", "99999999999999999.002", true],
    ["purchasePrice", "001,230", "1.23", false],
    ["purchasePrice", "-0.000", "0", false],
    ["memberPrice", "0", "2.208", true],
    ["offerPrice", "", "0", true],
    ["offerPrice", "invalid", "0", true],
    ["purchaseDiscountPercent", "20,00", "20.0", false],
    ["taxId", "21%", "21.00%", false],
    ["offerFrom", "08-09-26", "2026-09-08", false],
    ["offerUntil", "29-02-25", "2025-02-28", true],
    ["priceUseMode", "MEMBER_PRICE", "2", false],
    ["barcode2", "001234", "1234", true],
    ["familyId", "001002", "001003", true],
    ["name", "Producto NUEVO", "Producto actual", true],
    ["description", "", null, false]
  ])("compares %s without rounding prices or changing the displayed values", (field, excel, current, different) => {
    const columns = excelReviewColumns("summary", "STOCK", false, "es", key => key);
    const row = { excelData: { [field]: excel }, databaseData: { [field]: current } };
    expect(excelReviewChangedColumns(row, columns)).toEqual(different ? ["current." + field, "excel." + field] : []);
    expect(row.excelData[field]).toBe(excel);
  });

  it("does not mark missing products, unassigned fields, document quantity or other tabs as differences", () => {
    const columns = excelReviewColumns("summary", "WAREHOUSE_INPUT", false, "es", key => key);
    expect(excelReviewChangedColumns({ excelData: { name: "Nuevo" }, databaseData: null }, columns)).toEqual([]);
    expect(excelReviewChangedColumns({ excelData: { quantity: "5" }, databaseData: { name: "BD" } }, columns)).toEqual([]);
    const otherColumns = excelReviewColumns("priceChanged", "STOCK", false, "es", key => key);
    expect(excelReviewChangedColumns({ excelData: { name: "Nuevo" }, databaseData: { name: "BD" } }, otherColumns)).toEqual([]);
    expect(columns.find(column => column.key === "excel.quantity")?.source).toBeUndefined();
    expect(excelReviewColumns("summary", "STOCK", false, "es", key => key).some(column => column.key.endsWith(".quantity"))).toBe(false);
  });

  it.each(["es", "en", "zh"] as const)("shows non-updatable identity and quantity once with plain headings in %s", (locale) => {
    const labels = locale === "es" ? ["Código", "Código de barras", "Cantidad"]
      : locale === "en" ? ["Code", "Barcode", "Quantity"] : ["编码", "条码", "数量"];
    for (const panel of ["summary", "missing", "accepted", "priceChanged", "errors"] as const) {
      for (const onlyNew of [false, true]) {
        const columns = excelReviewColumns(panel, "WAREHOUSE_INPUT", onlyNew, locale, key => key);
        for (const [index, field] of ["code", "barcode", "quantity"].entries()) {
          expect(columns.filter(column => column.key.endsWith("." + field))).toEqual([
            { key: "excel." + field, label: labels[index] }
          ]);
        }
        const changed = excelReviewChangedColumns({ excelData: { code: "EXCEL", barcode: "001", quantity: "6" },
          databaseData: { code: "BD", barcode: "1", quantity: "999" } }, columns);
        expect(changed).toEqual([]);
        expect(columns.find(column => column.key === "excel.name")?.label).toContain("sharedExcel.column.excelData");
      }
    }
    expect(excelReviewColumns("summary", "STOCK", false, locale, key => key).some(column => column.key.endsWith(".quantity"))).toBe(false);
  });

  it("highlights all differences in summary and importable rows even with no planned writes, including the Excel-only view", async () => {
    const fixture = previewExistingFixture();
    fixture.rows[0].databaseData.name = "BD_ONLY_SENTINEL";
    const fetchMock = vi.fn(async (url: string) => jsonResponse(url.endsWith("/read") ? readApiFixture() : fixture));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(<SharedExcelImportDialog open locale="es" token="token" context="STOCK"
      products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(["xls"], "review.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    const updateName = screen.getByLabelText("Actualizar Nombre") as HTMLInputElement;
    if (updateName.checked) fireEvent.click(updateName);
    fireEvent.click(screen.getByLabelText("Generar documento resumen"));
    fireEvent.click(screen.getByLabelText("Mostrar solo valores nuevos (Excel)"));
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Aplicado:"));
    fireEvent.click(screen.getByRole("button", { name: "Documento resumen (1)" }));
    const review = () => container.querySelector(".shared-excel-review")!;
    expect(review().querySelector('td[data-column-key="excel.name"]')).toHaveClass("shared-excel-review-cell--changed");
    expect(review().querySelector('[data-column-key="current.name"]')).toBeNull();
    expect(review().outerHTML).not.toContain("BD_ONLY_SENTINEL");
    fireEvent.click(screen.getByRole("button", { name: "Revisar fila 2" }));
    expect(screen.getByRole("region", { name: "Revisar fila 2" }).outerHTML).not.toContain("BD_ONLY_SENTINEL");
    fireEvent.click(screen.getByRole("button", { name: "Productos importables (1)" }));
    expect(review().querySelector('th[data-column-key="current.name"]')).toHaveClass("shared-excel-review-cell--current");
    expect(review().querySelector('th[data-column-key="excel.name"]')).toHaveClass("shared-excel-review-cell--excel");
    expect(review().querySelector('td[data-column-key="current.name"]')).toHaveClass("shared-excel-review-cell--changed");
    expect(review().querySelector('td[data-column-key="excel.name"]')).toHaveClass("shared-excel-review-cell--changed");
    expect(review().querySelector('td[data-column-key="excel.code"]')).not.toHaveClass("shared-excel-review-cell--changed");
    expect(screen.getAllByRole("img", { name: "Valor distinto" })).toHaveLength(2);
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith("/apply"))).toBe(false);
  });

  it.each([
    ["Usar precio", "priceUseMode", "2 - Precio de miembro", "2", "MEMBER_PRICE"],
    ["Prohibido descuento", "discountType", "1 - Sí", "1", "1"],
    ["Impuestos incluidos", "taxesIncluded", "1 - Sí", "1", "1"],
    ["Impuestos", "taxId", "IVA 21%", "21%", "tax-21"],
    ["Tipo de producto", "productType", "2 - Peso", "2", "2"],
    ["Oferta activa", "offerActive", "1 - Sí", "1", "1"]
  ])("uses a single editable assignment for %s and clears the previous source", async (label, field, choice, short, fixed) => {
    const onClose = vi.fn();
    const requests: Array<Record<string, any>> = [];
    vi.stubGlobal("fetch", vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/read")) return jsonResponse(readApiFixture());
      if (url.endsWith("/preview")) requests.push(JSON.parse(await ((init!.body as FormData).get("config") as Blob).text()));
      return jsonResponse(previewExistingFixture());
    }));
    const { container } = render(<SharedExcelImportDialog open locale="es" token="token" context="STOCK" products={[]}
      taxOptions={[{ id: "tax-21", label: "IVA 21%" }]} onClose={onClose} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(["xls"], "test.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    const input = screen.getByRole("combobox", { name: label + " Columna o valor" });
    fireEvent.change(input, { target: { value: "aa" } });
    expect(input).toHaveValue("AA");
    fireEvent.keyDown(input, { key: "ArrowDown" });
    const option = screen.getByRole("option", { name: choice });
    fireEvent.keyDown(option, { key: "Enter" });
    expect(input).toHaveValue(short);
    expect(input).toHaveFocus();
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(requests).toHaveLength(1));
    expect(requests[0].mapping[field]).toBeUndefined();
    expect(requests[0].options.valueSources[field]).toEqual({ source: "global", value: fixed });
    await waitFor(() => expect(screen.getByRole("status")).not.toHaveTextContent("Validando"));
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    const edited = screen.getByRole("combobox", { name: label + " Columna o valor" });
    fireEvent.change(edited, { target: { value: "b" } });
    expect(edited).toHaveValue("B");
    fireEvent.click(screen.getByRole("button", { name: label }));
    fireEvent.keyDown(screen.getAllByRole("option")[0], { key: "Escape" });
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
    fireEvent.change(edited, { target: { value: "" } });
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(requests).toHaveLength(2));
    expect(requests[1].mapping[field]).toBeUndefined();
    expect(requests[1].options.valueSources[field]).toEqual({ source: "excel", value: "" });
  });

  it("transfers the family pair together, but leaves an empty or unchecked assignment unchanged", () => {
    expect(combinedFamilyUpdateFields({ familyId: true }, "001")).toEqual({ familyId: true, subfamilyId: true });
    expect(combinedFamilyUpdateFields({ familyId: true }, "001002")).toEqual({ familyId: true, subfamilyId: true });
    expect(combinedFamilyUpdateFields({ familyId: true, subfamilyId: true }, " ")).toEqual({ familyId: false, subfamilyId: false });
    expect(combinedFamilyUpdateFields({ familyId: false, purchasePrice: true }, "001")).toEqual({ familyId: false, subfamilyId: false, purchasePrice: true });
  });
  it.each(["Familia", "Subfamilia", "Familia / Subfamilia", "Family / Subfamily", "类别 / 子类别"])(
    "maps %s to one combined family control with both update flags", (header) => {
      const detected = detectExcelHeaderMapping([["Código", header], ["A1", "001002"]]);
      expect(detected.mapping).toMatchObject({ code: "A", familyId: "B" });
      expect(detected.mapping.subfamilyId).toBeUndefined();
      expect(detected.updateFields).toMatchObject({ familyId: true, subfamilyId: true });
    }
  );

  it("renders one family assignment and toggles its update checkbox", () => {
    render(<SharedExcelImportDialog open locale="es" products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    expect(screen.getByLabelText("Familia / Subfamilia Columna Excel")).toBeInTheDocument();
    expect(screen.queryByLabelText("Subfamilia Columna Excel")).not.toBeInTheDocument();
    expect(screen.getByText("3 dígitos: familia (quita subfamilia); 6: familia y subfamilia")).toBeInTheDocument();
    const checkbox = screen.getByLabelText("Actualizar Familia / Subfamilia");
    fireEvent.click(checkbox);
    expect(checkbox).toBeChecked();
    fireEvent.click(checkbox);
    expect(checkbox).not.toBeChecked();
  });

  it("places barcode 2 at the end of the first group and discards retired mappings", () => {
    storage.set("tpv.sharedExcelImport.v1.terminal-ref", JSON.stringify({ mapping: { code: "A", supplierReference: "C", comments: "D" }, updateFields: { supplierReference: true, comments: true } }));
    const { container } = render(<SharedExcelImportDialog open locale="es" context="STOCK"
      terminalContext={{ terminalCode: "01", terminalId: "terminal-ref" }} products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    expect(screen.queryByLabelText("Referencia proveedor Columna Excel")).not.toBeInTheDocument();
    const groups = container.querySelectorAll(".shared-excel-mapping-row");
    expect(groups.item(7)).toHaveTextContent("Código de barras 2");
    expect(screen.queryByLabelText("Comentarios Columna Excel")).not.toBeInTheDocument();
    const columns = excelReviewColumns("summary", "STOCK", false, "es", (key) => key);
    expect(columns.slice(-3, -1).map((col) => col.key)).toEqual(["current.barcode2", "excel.barcode2"]);
    expect(columns.some((col) => col.key.includes("supplierReference"))).toBe(false);
    expect(columns.some((col) => col.key.includes("comments"))).toBe(false);
  });

  it.each(["summary", "accepted", "priceChanged", "errors"] as const)("hides only empty %s attribute pairs, never zeros, false or a populated current value", (panel) => {
    const row = previewRowToClassifiedRow({ ...previewExistingFixture().rows[0], classification: "EXISTING",
      excelData: { code: "A", name: " ", salePrice: 0, taxesIncluded: false, description: "" },
      databaseData: { name: "Current", description: null } }, [], {}, "", []);
    const columns = excelReviewColumns(panel, "STOCK", false, "es", (key) => key);
    const visible = populatedExcelReviewColumns(columns, [row]).map((col) => col.key);
    expect(visible).toContain("excel.salePrice");
    expect(visible).toContain("excel.taxesIncluded");
    expect(visible).toContain("current.name");
    expect(visible).toContain("excel.name");
    expect(visible).not.toContain("excel.description");
    expect(visible).not.toContain("current.description");
    expect(columns.map((col) => col.key)).toContain("excel.description");
  });

  it("requires reassignment of legacy separate family columns instead of silently clearing subfamilies", () => {
    storage.set("tpv.sharedExcelImport.v1.terminal-family", JSON.stringify({
      mapping: { code: "A", familyId: "B", subfamilyId: "C" },
      updateFields: { familyId: true, subfamilyId: true }
    }));
    render(<SharedExcelImportDialog open locale="es" terminalContext={{ terminalCode: "01", terminalId: "terminal-family" }}
      sheet={[["Código", "Familia", "Subfamilia"], ["A1", "001", "001002"]]}
      products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    expect(screen.getByLabelText("Familia / Subfamilia Columna Excel")).toHaveValue("");
    expect(screen.getByLabelText("Actualizar Familia / Subfamilia")).not.toBeChecked();
    expect(screen.getByText("La configuración guardada separaba Familia y Subfamilia. Vuelve a asignar la columna Excel del campo unificado.")).toBeInTheDocument();
  });

  it.each([
    ["summary", "Documento resumen", "SUMMARY"], ["missing", "Productos no importables", "MISSING"],
    ["accepted", "Productos importables", "IMPORTABLE"], ["priceChanged", "Precio de compra distinto", "PURCHASE_CHANGED"],
    ["errors", "Errores", "ERRORS"]
  ] as const)("hides empty attributes in %s while exporting all attributes and barcode 2 last", async (panel, title, view) => {
    const base = previewExistingFixture();
    const fixture = { ...base, rows: [{ ...base.rows[0], existence: panel === "missing" ? "MISSING" : "EXISTING",
      classification: panel === "missing" ? "MISSING" : panel === "errors" ? "ERROR" : "EXISTING",
      excelData: { code: "A-1", name: "Producto", salePrice: "0", taxesIncluded: false, description: "  ", barcode2: "0001234567890" },
      databaseData: panel === "missing" ? null : { ...base.rows[0].databaseData, description: null },
      purchasePriceChanged: panel === "priceChanged",
      errors: panel === "errors" ? [{ code: "NUMBER_INVALID", row: 2, attribute: "purchasePrice", reason: "Precio inválido" }] : [] }] };
    const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => url.endsWith("/read") ? jsonResponse(readApiFixture())
      : url.endsWith("/preview") ? jsonResponse(fixture)
        : { ok: true, status: 200, blob: async () => new Blob(["xlsx"]), headers: { get: (): string | null => null } });
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("URL", { createObjectURL: vi.fn(() => "blob:export"), revokeObjectURL: vi.fn() });
    const { container } = render(<SharedExcelImportDialog open locale="es" token="token" context="STOCK"
      products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(["xlsx"], "productos.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    fireEvent.click(screen.getByLabelText("Generar documento resumen"));
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: title + " (1)" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: title + " (1)" }));
    const keys = Array.from(container.querySelectorAll(".shared-excel-review th[data-column-key]"), el => el.getAttribute("data-column-key"));
    expect(keys).not.toContain("excel.description");
    expect(keys).not.toContain("current.description");
    expect(keys).toContain("excel.salePrice");
    expect(keys).toContain("excel.taxesIncluded");
    expect(keys.at(-2)).toBe("excel.barcode2");
    expect(keys).toContain("excel.code");
    expect(keys).not.toContain("current.code");
    expect(container.querySelector('th[data-column-key="excel.code"]')).toHaveTextContent(/^Código$/);
    fireEvent.click(screen.getByRole("button", { name: "Exportar XLSX" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url.endsWith("/summary.xlsx"))).toBe(true));
    const [, init] = fetchMock.mock.calls.find(([url]) => url.endsWith("/summary.xlsx"))!;
    const exported = JSON.parse(await ((init!.body as FormData).get("config") as Blob).text());
    expect(exported.view).toBe(view);
    expect(exported.columns).toContain("excel.description");
    expect(exported.columns).toContain("excel.code");
    expect(exported.columns).not.toContain("current.code");
    expect(exported.columns).not.toContain("current.barcode");
    expect(exported.columns.at(-2)).toBe("excel.barcode2");
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith("/apply"))).toBe(false);
  });

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
    cleanup();
    vi.unstubAllGlobals();
  });

  it.each([
    ["es", "Corrige manualmente", "Abrir Excel"],
    ["en", "Manually correct", "Open Excel"],
    ["zh", "手动修正", "打开 Excel"]
  ] as const)("shows the exact error cell and manual retry instructions in %s while opening the grid", async (locale, fix, openLabel) => {
    const rows = Array.from({ length: 70 }, (_, index) => Array.from({ length: 11 }, (_, column) => ({
      value: index === 0 ? (column === 0 ? "Codigo" : "") : column === 0 ? `PRODUCT-${index}` : "",
      formula: null as string | null, errorCode: null as string | null
    })));
    rows[68][10] = { value: "#VALUE!", formula: "I69*2", errorCode: "FORMULA_RESULT_ERROR" };
    const fetchMock = vi.fn(async (url: string) => url.endsWith("/read")
      ? jsonResponse({ ...readApiFixture(), rows, columns: 11, nonEmptyRows: 70,
        formulas: [{ cell: "K69", formula: "I69*2", calculatedValue: "#VALUE!" }] })
      : jsonResponse([]));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(<SharedExcelImportDialog open locale={locale} context="STOCK" products={[]} token="token"
      onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    const input = container.querySelector('input[type="file"]')!;
    fireEvent.change(input, { target: { files: [new File(["xlsx"], "productos.xlsx")] } });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("K69");
    expect(alert).toHaveTextContent("#VALUE!");
    expect(alert).toHaveTextContent(fix);
    expect(container.querySelector('.shared-excel-preview table')).toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => url.includes("product-excel-imports")).map(([url]) => url))
      .toEqual(["/api/v1/product-excel-imports/read"]);

    // Reopening a corrected file with the same name must reread it and clear the old diagnostic.
    rows[68][10] = { value: "6.38", formula: "I69*2", errorCode: null };
    fireEvent.click(screen.getByRole("button", { name: openLabel }));
    fireEvent.change(input, { target: { files: [new File(["corrected"], "productos.xlsx")] } });
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => url.endsWith("/read"))).toHaveLength(2));
    await waitFor(() => expect(screen.queryByRole("alert")).not.toBeInTheDocument());
    expect(container.querySelector('.shared-excel-preview table')).toBeInTheDocument();
  });

  it("also displays cell coordinates prominently for a fatal read error from an older backend", async () => {
    vi.stubGlobal("fetch", vi.fn(async (url: string) => url.endsWith("/read")
      ? { ok: false, status: 400, headers: { get: (): string | null => null },
        json: async () => ({ code: "FORMULA_RESULT_ERROR", row: 69, column: 11, receivedValue: "#VALUE!", message: "Fórmula errónea" }) }
      : jsonResponse([])));
    const { container } = render(<SharedExcelImportDialog open locale="es" context="STOCK" products={[]} token="token"
      onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(["xlsx"], "productos.xlsx")] } });
    const alert = await screen.findByRole("alert");
    expect(alert).toHaveTextContent("K69");
    expect(alert).toHaveTextContent("#VALUE!");
    expect(alert).toHaveTextContent("Corrige manualmente");
  });

  it("exports visible error cells even when the workbook could not be read, without applying or previewing", async () => {
    const exports: Array<{ view: string; columns: string[]; errorRows: Array<Record<string, string>> }> = [];
    const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
      if (url.endsWith("/taxes/selectable")) return jsonResponse([]);
      if (url.endsWith("/read")) return { ok: false, status: 400, headers: { get: (): string | null => null },
        json: async () => ({ code: "FILE_SIGNATURE_INVALID", message: "Firma no compatible" }) };
      if (url.endsWith("/summary.xlsx")) {
        exports.push(JSON.parse(await ((init!.body as FormData).get("config") as Blob).text()));
        return { ok: true, status: 200, headers: { get: (): string | null => null }, blob: async () => new Blob(["xlsx"]) };
      }
      throw new Error("Unexpected call " + url);
    });
    vi.stubGlobal("fetch", fetchMock);
    vi.stubGlobal("URL", { createObjectURL: () => "blob:errors", revokeObjectURL: vi.fn() });
    const { container } = render(<SharedExcelImportDialog open locale="es" context="STOCK" products={[]} token="token"
      onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(["bad"], "bad.xlsx")] } });
    await waitFor(() => expect(container.querySelector('.shared-excel-review table')).toBeInTheDocument());
    const errorCell = container.querySelector('td[data-column-key="errors"]')?.textContent;
    fireEvent.click(screen.getByRole("button", { name: "Exportar XLSX" }));
    await waitFor(() => expect(exports).toHaveLength(1));
    expect(exports[0].view).toBe("ERRORS");
    expect(exports[0].errorRows[0].errors).toBe(errorCell);
    expect(fetchMock.mock.calls.map(([url]) => url).filter(url => url.includes("product-excel-imports")))
      .toEqual(["/api/v1/product-excel-imports/read", "/api/v1/product-excel-imports/summary.xlsx"]);
  });

  it("counts original review rows and does not count row errors twice in the tabs", async () => {
    const sourceRows = [2, 3].map(rowNumber => ({ ...previewExistingFixture().rows[0], rowNumber,
      rowNumbers: [rowNumber], existence: "EXISTING", classification: "ERROR", purchasePriceChanged: true,
      errors: [{ code: "DATE_INVALID", row: rowNumber, column: 3, attribute: "offerFrom", reason: "Fecha inválida" }] }));
    vi.stubGlobal("fetch", vi.fn(async (url: string) => {
      if (url.endsWith("/read")) return jsonResponse({ ...readApiFixture(),
        rows: [...readApiFixture().rows, readApiFixture().rows[1]] });
      return jsonResponse({ ...previewExistingFixture(), detectedRows: 2, sourceRows,
        rows: [{ ...sourceRows[0], rowNumbers: [2, 3] }], errors: sourceRows.flatMap(row => row.errors) });
    }));
    const { container } = render(<SharedExcelImportDialog open locale="es" context="STOCK" token="token"
      products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(["xls"], "rows.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await screen.findByRole("button", { name: "Productos importables (2)" });
    expect(screen.getByRole("button", { name: "Precio de compra distinto (2)" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Errores (2)" }));
    expect(container.querySelectorAll('.shared-excel-review tbody tr[aria-rowindex]')).toHaveLength(2);
  });

  it.each([false, true])("counts compatible duplicate source rows as accepted even with purchase differences (%s)", async (purchasePriceChanged) => {
    const sourceRows = [2, 3].map(rowNumber => ({ ...previewExistingFixture().rows[0], rowNumber,
      rowNumbers: [rowNumber], changes: {}, purchasePriceChanged }));
    vi.stubGlobal("fetch", vi.fn(async (url: string) => url.endsWith("/read")
      ? jsonResponse({ ...readApiFixture(), rows: [...readApiFixture().rows, readApiFixture().rows[1]] })
      : jsonResponse({ ...previewExistingFixture(), detectedRows: 2, sourceRows,
          rows: [{ ...sourceRows[0], rowNumbers: [2, 3] }] })));
    const { container } = render(<SharedExcelImportDialog open locale="es" context="STOCK" token="token"
      products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]')!, { target: { files: [new File(["xls"], "rows.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Aplicado: 2 aceptadas"));
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    expect(screen.getByText("2 aceptadas")).toBeInTheDocument();
  });

  it.each(["STOCK", "WAREHOUSE_OUTPUT"] as const)(
    "allows the summary-only view for %s without disabling the catalog flow",
    (context) => {
      render(<SharedExcelImportDialog
        open
        locale="es"
        context={context}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />);
      expect(screen.getByLabelText("Mostrar solo valores nuevos (Excel)")).toBeEnabled();
      expect(screen.queryByText("Esta opción solo está disponible al importar a un documento de almacén.")).not.toBeInTheDocument();
    }
  );

  it.each(["STOCK", "WAREHOUSE_INPUT", "WAREHOUSE_OUTPUT"] as const)(
    "projects only the summary and its export without changing product resolution in %s",
    async (context) => {
      const fixture = previewExistingFixture();
      fixture.rows[0].databaseData.name = "BD_ACTUAL_SENTINEL";
      const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => {
        if (url.endsWith("/read")) return jsonResponse(readApiFixture());
        if (url.endsWith("/preview")) return jsonResponse(fixture);
        return { ok: true, status: 200, blob: async () => new Blob(["xlsx"]),
          headers: { get: (): string | null => null } };
      });
      vi.stubGlobal("fetch", fetchMock);
      vi.stubGlobal("URL", { createObjectURL: vi.fn(() => "blob:summary"), revokeObjectURL: vi.fn() });
      const onImportAccepted = vi.fn();
      const { container } = render(<SharedExcelImportDialog open locale="es" token="token" context={context}
        products={[]} onClose={vi.fn()} onImportAccepted={onImportAccepted} />);
      fireEvent.change(container.querySelector('input[type="file"]') as HTMLInputElement,
        { target: { files: [new File(["xls"], "productos.xlsx")] } });
      await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
      for (const onlyNew of [true, false]) {
        if (onlyNew) fireEvent.click(screen.getByLabelText("Mostrar solo valores nuevos (Excel)"));
        else {
          fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
          fireEvent.click(screen.getByLabelText("Mostrar solo valores nuevos (Excel)"));
        }
        if (!(screen.getByLabelText("Generar documento resumen") as HTMLInputElement).checked) fireEvent.click(screen.getByLabelText("Generar documento resumen"));
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
        await waitFor(() => expect(screen.getByText("Documento resumen (1)")).toBeInTheDocument());
        fireEvent.click(screen.getByText("Documento resumen (1)"));
        const summaryTable = within(container.querySelector(".shared-excel-review-viewport table") as HTMLElement);
        expect(summaryTable.getByRole("columnheader", { name: /^Nombre · Valores nuevos \(Excel\)/ })).toBeInTheDocument();
        expect(Boolean(summaryTable.queryByRole("columnheader", { name: /^Nombre · Valores actuales \(BD\)/ }))).toBe(!onlyNew);
        expect(container.querySelector(".shared-excel-review")?.textContent?.includes("BD_ACTUAL_SENTINEL")).toBe(!onlyNew);
        fireEvent.click(screen.getByRole("button", { name: "Exportar XLSX" }));
        await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => url.endsWith("/summary.xlsx"))).toHaveLength(onlyNew ? 1 : 2));
        const [, init] = fetchMock.mock.calls.filter(([url]) => url.endsWith("/summary.xlsx")).at(-1)!;
        const exported = JSON.parse(await ((init!.body as FormData).get("config") as Blob).text());
        expect(exported.preview.options.showOnlyImported).toBe(onlyNew);
        expect(exported.expectedPreviewFingerprint).toBe(fixture.previewFingerprint);
        expect(exported.view).toBe("SUMMARY");
        const visibleColumns = Array.from(container.querySelectorAll(".shared-excel-review th[data-column-key]"), (header) => header.getAttribute("data-column-key"));
        expect(exported.columns).toEqual(expect.arrayContaining(visibleColumns));
        expect(exported.columns).toContain("excel.barcode2");
        expect(visibleColumns).not.toContain("excel.barcode2");
        expect(exported.columns.some((key: string) => key.startsWith("current."))).toBe(!onlyNew);
        await waitFor(() => expect(screen.getByRole("button", { name: "Exportar XLSX" })).toBeEnabled());
      }
      for (const [, init] of fetchMock.mock.calls.filter(([url]) => url.endsWith("/preview"))) {
        const config = JSON.parse(await ((init!.body as FormData).get("config") as Blob).text());
        expect(config.options.showOnlyImported).toBe(false);
      }
      expect(onImportAccepted).not.toHaveBeenCalled();
      expect(fetchMock.mock.calls.some(([url]) => url.endsWith("/apply"))).toBe(false);
    }
  );

  it("keeps every backend import error code localized in ES, EN and ZH", () => {
    for (const code of PRODUCT_EXCEL_IMPORT_ERROR_CODES) {
      const entry = localizedImportErrorCatalog[code];
      expect(entry, code).toBeDefined();
      for (const locale of ["es", "en", "zh"] as const) {
        expect(entry[locale].reason, `${code}:${locale}:reason`).toBeTruthy();
        expect(entry[locale].accepted, `${code}:${locale}:accepted`).toBeTruthy();
        expect(entry[locale].fix, `${code}:${locale}:fix`).toBeTruthy();
        expect(entry[locale].reason, `${code}:${locale}:specific reason`)
          .not.toBe("Import validation error");
        expect(entry[locale].accepted, `${code}:${locale}:specific accepted`)
          .not.toBe("Values accepted by the importer");
        expect(entry[locale].fix, `${code}:${locale}:specific fix`)
          .not.toBe("Correct the value and generate the preview again");
        expect(entry[locale].reason, `${code}:${locale}:no generic fallback`)
          .not.toContain("Could not validate import code");
        expect(entry[locale].reason).not.toContain("无法验证导入代码");
      }
    }
  });

  it.each([
    "ERROR_LIMIT",
    "TRANSPORT_REQUEST_TOO_LARGE",
    "NUMBER_FORMAT_UNSUPPORTED",
    "IDENTIFIER_NUMERIC_PRECISION",
    "CELL_ERROR_VALUE",
    "FORMULA_RESULT_ERROR"
  ] as const)("has semantic ES/EN/ZH copy for %s", (code) => {
    const entry = localizedImportErrorCatalog[code];
    for (const locale of ["es", "en", "zh"] as const) {
      expect(entry[locale].reason, `${code}:${locale}:reason`).not.toBe(entry[locale].accepted);
      expect(entry[locale].accepted, `${code}:${locale}:accepted`).not.toContain("Values accepted");
      expect(entry[locale].fix, `${code}:${locale}:fix`).not.toContain("Correct the value and generate the preview again");
    }
  });

  it("documents the same 250,000 materialized-cell limit enforced by the backend", () => {
    expect(localizedImportErrorCatalog.GRID_CELL_LIMIT.es.accepted).toContain("250.000");
    expect(localizedImportErrorCatalog.GRID_CELL_LIMIT.en.accepted).toContain("250,000");
    expect(localizedImportErrorCatalog.GRID_CELL_LIMIT.zh.accepted).toContain("250,000");
  });

  it("shows a short non-writing workflow before loading a file", () => {
    const html = renderToStaticMarkup(<SharedExcelImportDialog open locale="es" products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    expect(html).toContain("Abrir Excel → asignar columnas → Aplicar para revisar");
    expect(html).not.toContain("Oferta activa utiliza 1");
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
    expect(html).toContain("Precio de miembro");
    expect(html).not.toContain('value="A"');
    expect(html).not.toContain('value="F"');
    expect(html).toContain("Productos no importables (0)");
    expect(html).toContain("Productos importables (0)");
    expect(html).toContain("Errores (0)");
    expect(html).toContain("Configuración del archivo");
    expect(html).toContain("NOPE");
    expect(html).toContain("table-layout-column-resizer");
    expect(html).toContain("Orden: actualizar → columna/valor → atributo");
    expect(html).toContain("Formato: DD-MM-AA o DD-MM-AAAA");
    expect(html).not.toContain(".csv");
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
    expect(html).toContain('aria-label="Fila Excel 21"');
  });

  it("detects column mappings and update checks from the first row", () => {
    expect(detectExcelHeaderMapping([
      ["Código", "EAN", "Descripción", "Cantidad", "Precio", "Descuento", "Precio de venta", "Precio de miembro"],
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

  it("keeps mapping update controls independent from their field name", () => {
    render(
      <SharedExcelImportDialog
        open
        locale="es"
        sheet={[["Código", "Nombre"], ["A001", "Producto"]]}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );
    const checkbox = screen.getByRole("checkbox", { name: "Actualizar Descripción" }) as HTMLInputElement;
    expect(checkbox.checked).toBe(false);
    fireEvent.click(screen.getByRole("group", { name: "Descripción" }));
    expect(checkbox.checked).toBe(false);
    fireEvent.click(checkbox);
    expect(checkbox.checked).toBe(true);
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

  it("preserves formulas without executing them and keeps the cached value", () => {
    const original = [
      ["Precio de compra", "Precio de venta"],
      [4.1, { kind: "formula", formula: "A2*2.5", value: 10.25 }]
    ];

    const updated = updateExcelSheetCell(original, 1, 0, "5");

    expect(updated[1][1]).toEqual(expect.objectContaining({
      kind: "formula",
      formula: "A2*2.5",
      value: 10.25
    }));
    expect(updateExcelSheetCell(original, 1, 1, "=A2*3")[1][1]).toBe("=A2*3");
  });

  it("windows 5000 rows across 256 available columns while preserving original row numbers", async () => {
    const sheet = [
      Array.from({ length: 256 }, (_, index) => `C${index + 1}`),
      // Respect the backend's 250,000 materialized-cell limit while exercising IV and AA+ headers.
      ...Array.from({ length: 5_000 }, (_, rowIndex) => Array.from({ length: 48 }, (_, columnIndex) => (
        columnIndex === 0 ? `ROW-${rowIndex + 2}` : `${rowIndex}-${columnIndex}`
      )))
    ];
    const { container } = render(
      <SharedExcelImportDialog open locale="es" sheet={sheet} products={[]}
        onClose={vi.fn()} onImportAccepted={vi.fn()} />
    );
    const preview = container.querySelector(".shared-excel-preview") as HTMLDivElement;
    expect(preview.querySelectorAll("tbody tr").length).toBeLessThanOrEqual(62);
    expect(screen.getByText("ROW-2")).toBeInTheDocument();
    expect(screen.queryByText("ROW-1000")).not.toBeInTheDocument();
    fireEvent.scroll(preview, { target: { scrollTop: 28 * 998 } });
    await waitFor(() => expect(screen.getByText("ROW-1000")).toBeInTheDocument());
    expect(screen.getByText("1000")).toBeInTheDocument();
  }, 15_000);

  it("sends only edited cells back to the authoritative preview", () => {
    expect(changedExcelCells({
      fileName: "productos.xlsx",
      sha256: "hash",
      sheetName: "Hoja1",
      rows: [
        [{ value: "CODIGO" }, { value: "CANTIDAD" }],
        [{ value: "A-1" }, { value: "2", formula: "1+1" }]
      ],
      formulas: [],
      nonEmptyRows: 2,
      columns: 2,
      nonEmptyCells: 4
    }, [
      ["CODIGO", "CANTIDAD"],
      ["A-1", "3"]
    ])).toEqual([{ row: 2, column: "B", value: "3" }]);
  });

  it("uses normalized backend data and aggregated quantity in accepted rows", () => {
    const result = previewRowToClassifiedRow({
      rowNumber: 2,
      rowNumbers: [2, 4],
      classification: "EXISTING",
      excelData: { code: "A-1", name: "Producto", quantity: "5", purchasePrice: "1.2" },
      databaseData: { id: "product-1", code: "A-1", purchasePrice: "1" },
      version: 7,
      changes: { purchasePrice: { before: "1", after: "1.2" } },
      errors: [],
      purchasePriceChanged: true
    }, [
      ["CODIGO", "CANTIDAD", "PRECIO"],
      ["A-1", "2", "1,20"]
    ], { code: "A", purchasePrice: "C" }, "B");

    expect(result.rowNumbers).toEqual([2, 4]);
    expect(result.source[1]).toBe("5");
    expect(result.draft.purchasePrice).toBe("1.2");
    expect(result.product?.id).toBe("product-1");
    expect(result.status).toBe("purchasePriceChanged");
  });

  it("keeps purchase-price status after database sanitization", () => {
    const result = previewRowToClassifiedRow({
      rowNumber: 2,
      rowNumbers: [2],
      classification: "EXISTING",
      excelData: { code: "A-1", purchasePrice: "1.2" },
      databaseData: null,
      version: null,
      changes: {},
      errors: [],
      purchasePriceChanged: true
    }, [["CODIGO", "PRECIO"], ["A-1", "1,20"]], { code: "A", purchasePrice: "B" }, "", [
      { id: "product-1", code: "A-1", barcode: null }
    ]);
    expect(result.status).toBe("purchasePriceChanged");
  });

  it("resolves an existing product locally when the server hides database data", () => {
    const result = previewRowToClassifiedRow({
      rowNumber: 2,
      rowNumbers: [2],
      classification: "EXISTING",
      excelData: { code: " a-1 ", name: "Producto" },
      databaseData: null,
      version: null,
      changes: {},
      errors: [],
      purchasePriceChanged: false
    }, [["CODIGO", "NOMBRE"], ["a-1", "Producto"]], { code: "A", name: "B" }, "", [
      { id: "product-1", code: "A-1", barcode: "843" }
    ]);

    expect(result.product).toEqual({ id: "product-1", code: "A-1", barcode: "843" });
    expect(result.status).toBe("accepted");
  });

  it("does not turn an authoritative missing row into a local existing product", () => {
    const result = previewRowToClassifiedRow({
      rowNumber: 2,
      rowNumbers: [2],
      classification: "MISSING",
      excelData: { code: "A-1", name: "Nuevo" },
      databaseData: null,
      version: null,
      changes: {},
      errors: [],
      purchasePriceChanged: false
    }, [["CODIGO", "NOMBRE"], ["A-1", "Nuevo"]], { code: "A", name: "B" }, "", [
      { id: "product-1", code: "A-1", barcode: "843" }
    ]);

    expect(result.product).toBeUndefined();
    expect(result.status).toBe("missing");
  });

  it("blocks a hidden existing row when the local catalog cannot resolve it uniquely", () => {
    const result = previewRowToClassifiedRow({
      rowNumber: 2,
      rowNumbers: [2],
      classification: "EXISTING",
      excelData: { code: "UNKNOWN" },
      databaseData: null,
      version: null,
      changes: {},
      errors: [],
      purchasePriceChanged: false
    }, [["CODIGO"], ["UNKNOWN"]], { code: "A" }, "", []);

    expect(result.status).toBe("error");
    expect(result.errors).toContain("Producto existente no resuelto en el catálogo local");
    expect(result.structuredErrors?.[0].code).toBe("PRODUCT_LOCAL_RESOLUTION_FAILED");
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
        context="WAREHOUSE_INPUT"
        supplier={{ id: "supplier-1", code: "P-001", legalName: "Proveedor SL", documentType: "NIF", documentNumber: "B12345678" }}
        terminalContext={{ terminalCode: "01", terminalId: "terminal-options" }}
        sheet={[["Código"], ["A001"]]}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toMatch(/type="checkbox"\/> Añadir automáticamente/);
    expect(html).toMatch(/type="checkbox" checked=""\/> Generar documento resumen/);
    expect(html).toMatch(/type="checkbox" checked=""\/> Mostrar solo valores nuevos \(Excel\)/);
    expect(html).toMatch(/type="checkbox"\/> No actualizar cuando el precio nuevo sea 0/);
    expect(html).toMatch(/type="checkbox" checked=""\/> Actualizar el proveedor del producto/);
    expect(html).toContain('aria-label="Usar precio en documento"');
    expect(html).toContain("Precio de miembro");
    expect(html).toContain("P-001 · Proveedor SL · NIF B12345678");
  });

  it("disables supplier updates in incoming documents until a supplier is selected", () => {
    const html = renderToStaticMarkup(
      <SharedExcelImportDialog
        open
        locale="es"
        context="WAREHOUSE_INPUT"
        sheet={[["Código"], ["A001"]]}
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );

    expect(html).toMatch(/type="checkbox" disabled=""\/> Actualizar el proveedor del producto/);
    expect(html).toContain("Selecciona un proveedor activo");
  });

  it("reads and previews a selected workbook through the backend contracts", async () => {
    const fetchMock = vi.fn(async (url: string) => ({
      ok: true,
      status: 200,
      headers: { get: (): string | null => null },
      text: async () => JSON.stringify(url.endsWith("/product-excel-imports/read") ? {
        fileName: "productos.xls",
        sha256: "sha-1",
        sheetName: "Hoja1",
        rows: [
          [{ value: "CODIGO" }, { value: "NOMBRE" }],
          [{ value: "A-1" }, { value: "Producto" }]
        ],
        formulas: [],
        nonEmptyRows: 2,
        columns: 2,
        nonEmptyCells: 4
      } : {
        fileName: "productos.xls",
        sha256: "sha-1",
        sheetName: "Hoja1",
        rows: [{
          rowNumber: 2,
          rowNumbers: [2],
          classification: "MISSING",
          excelData: { code: "A-1", name: "Producto" },
          databaseData: null,
          version: null,
          changes: {},
          errors: [],
          purchasePriceChanged: false
        }],
        detectedRows: 1,
        existingRows: 0,
        missingRows: 1,
        errors: []
      })
    }));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(
      <SharedExcelImportDialog
        open
        locale="es"
        token="token"
        context="STOCK"
        products={[]}
        onClose={vi.fn()}
        onImportAccepted={vi.fn()}
      />
    );
    const fileInput = container.querySelector('input[type="file"]') as HTMLInputElement;

    fireEvent.change(fileInput, { target: { files: [new File(["xls"], "productos.xls")] } });

    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    fireEvent.click(screen.getByLabelText("Generar documento resumen"));
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    await waitFor(() => expect(screen.getByText("Productos no existentes para añadir automáticamente")).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /^Documento resumen/ }));
    const summaryTable = within(container.querySelector(".shared-excel-review-viewport table") as HTMLElement);
    expect(summaryTable.getByRole("columnheader", { name: /^Nombre · Valores nuevos \(Excel\)/ })).toBeInTheDocument();
    expect(summaryTable.getByRole("columnheader", { name: /^Nombre · Valores actuales \(BD\)/ })).toBeInTheDocument();
    expect(fetchMock.mock.calls.map(([url]) => String(url))).toEqual(expect.arrayContaining([
      expect.stringMatching(/\/product-excel-imports\/read$/),
      expect.stringMatching(/\/product-excel-imports\/preview$/)
    ]));
  });

  it("exports the optional summary explicitly and never writes when export fails", async () => {
    const fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith("/read")) return jsonResponse(readApiFixture());
      if (url.endsWith("/preview")) return jsonResponse(previewMissingFixture());
      return { ok: false, status: 400, headers: { get: (): string | null => null }, json: async () => ({ code: "SUMMARY_PREVIEW_INVALID", reason: "summary failed" }) };
    });
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(<SharedExcelImportDialog open locale="es" token="token" context="WAREHOUSE_INPUT" products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    await openAndApply(container, true);
    fireEvent.click(screen.getByRole("button", { name: /^Documento resumen/ }));
    fireEvent.click(screen.getByRole("button", { name: "Exportar XLSX" }));
    await waitFor(() => expect(fetchMock.mock.calls.some(([url]) => url.endsWith("/summary.xlsx"))).toBe(true));
    expect(fetchMock.mock.calls.some(([url]) => url.endsWith("/apply"))).toBe(false);
  });

  it("creates missing Warehouse products only on Add and refreshes the classification without importing", async () => {
    const flow = await operationFlow("WAREHOUSE_INPUT");
    expect(flow.operations).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Añadir productos" }));
    expect(screen.getByRole("alertdialog")).toHaveTextContent("1 productos");
    fireEvent.click(screen.getAllByRole("button", { name: "Añadir productos" }).at(-1)!);
    await waitFor(() => expect(screen.getByRole("button", { name: "Productos importables (2)" })).toBeInTheDocument());
    expect(flow.operations).toEqual([expect.objectContaining({ operation: "CREATE_MISSING", autoAddMissing: true })]);
    expect(flow.onImportAccepted).not.toHaveBeenCalled();
    expect(flow.onClose).not.toHaveBeenCalled();
    expect(flow.fetchMock.mock.calls.some(([url]) => url.endsWith("/summary.xlsx"))).toBe(false);
  });

  it("blocks close, file and duplicate apply while Warehouse apply is in flight", async () => {
    let resolveApply!: (value: unknown) => void;
    const applyResponse = new Promise((resolve) => { resolveApply = resolve; });
    const fetchMock = vi.fn(async (url: string) => {
      if (url.endsWith("/read")) return jsonResponse(readApiFixture());
      if (url.endsWith("/preview")) return jsonResponse(previewExistingFixture());
      await applyResponse;
      return jsonResponse({ fileName: "productos.xlsx", sha256: "sha-1", appliedCount: 1,
        rows: [{ rowNumber: 2, rowNumbers: [2], classification: "EXISTING", productId: "product-1", errors: [] }], errors: [],
        warehouseMetadata: {
          fileName: "productos.xlsx", formulas: [], sha256: "a".repeat(64), sheetName: "Hoja1",
          updateSupplier: false, skipZeroPriceUpdate: false, lines: []
        },
        warehouseProvenanceToken: "WXP1.A." + "A".repeat(512) });
    });
    vi.stubGlobal("fetch", fetchMock);
    const onClose = vi.fn();
    const onImportAccepted = vi.fn();
    const { container, rerender } = render(<SharedExcelImportDialog open locale="es" token="token"
      context="WAREHOUSE_INPUT" products={[]} onClose={onClose} onImportAccepted={onImportAccepted} />);
    fireEvent.change(container.querySelector('input[type="file"]') as HTMLInputElement,
      { target: { files: [new File(["xls"], "productos.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Importar Excel al documento" })).toBeInTheDocument());
    const importButton = screen.getByRole("button", { name: "Importar Excel al documento" });
    fireEvent.click(importButton);
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent("Importar Excel al documento"));
    expect((screen.getByRole("button", { name: "Abrir Excel" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Limpiar fichero" }) as HTMLButtonElement).disabled).toBe(true);
    expect((screen.getByRole("button", { name: "Volver [Esc]" }) as HTMLButtonElement).disabled).toBe(true);
    rerender(<SharedExcelImportDialog open locale="es" token="token"
      context="WAREHOUSE_INPUT"
      products={[{ id: "catalog-2", code: "B-2", barcode: "222" }]}
      onClose={onClose} onImportAccepted={onImportAccepted} />);
    fireEvent.keyDown(window, { key: "Escape" });
    fireEvent.click(importButton);
    expect(onClose).not.toHaveBeenCalled();
    expect(screen.queryByRole("alertdialog", { name: "¿Cerrar la importación de Excel?" })).not.toBeInTheDocument();
    expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/apply"))).toHaveLength(1);
    resolveApply(undefined);
    await waitFor(() => expect(onClose).toHaveBeenCalledOnce());
    expect(onImportAccepted).toHaveBeenCalledOnce();
  });

  it("allows a tax column but no fixed choice when no taxes are available", async () => {
    render(<SharedExcelImportDialog open locale="es" sheet={[["Impuestos"], ["21"]]} products={[]}
      taxOptions={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    const column = await screen.findByRole("combobox", { name: "Impuestos Columna o valor" });
    expect(column).toBeEnabled();
    fireEvent.change(column, { target: { value: "aa" } });
    expect(column).toHaveValue("AA");
    fireEvent.click(screen.getByRole("button", { name: "Impuestos" }));
    expect(screen.queryByRole("option")).not.toBeInTheDocument();
    expect(screen.getByText("No hay impuestos activos disponibles")).toBeInTheDocument();
  });

  it("keeps manual mapping after editing a cell instead of re-running autodetection", async () => {
    const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => url.endsWith("/read")
      ? jsonResponse(readApiFixture())
      : jsonResponse(previewMissingFixture()));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(<SharedExcelImportDialog open locale="es" token="token"
      context="STOCK" products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]') as HTMLInputElement,
      { target: { files: [new File(["xls"], "productos.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    fireEvent.change(screen.getByLabelText("Código Columna Excel"), { target: { value: "B" } });
    fireEvent.click(screen.getByRole("button", { name: "Editar tabla" }));
    fireEvent.change(screen.getByLabelText("A2"), { target: { value: "EDITADO" } });
    expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("B");
  });

  it("invalidates a current preview when the catalog changes after manual review", async () => {
    const fetchMock = vi.fn(async (url: string) => url.endsWith("/read")
      ? jsonResponse(readApiFixture())
      : jsonResponse(url.endsWith("/apply") ? { errors: [], rows: [{ rowNumber: 2, rowNumbers: [2], productId: "product-1" }] } : previewExistingFixture()));
    vi.stubGlobal("fetch", fetchMock);
    const props = {
      open: true,
      locale: "es" as const,
      token: "token",
      context: "STOCK" as const,
      products: [] as Array<{ id: string; code: string; barcode: string | null }>,
      onClose: vi.fn(),
      onImportAccepted: vi.fn()
    };
    const { container, rerender } = render(<SharedExcelImportDialog {...props} />);
    fireEvent.change(container.querySelector('input[type="file"]') as HTMLInputElement,
      { target: { files: [new File(["xls"], "productos.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    if (!(screen.getByLabelText("Generar documento resumen") as HTMLInputElement).checked) fireEvent.click(screen.getByLabelText("Generar documento resumen"));
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: /Documento resumen \(1\)/ })).toBeInTheDocument());
    rerender(<SharedExcelImportDialog {...props} products={[{ id: "product-1", code: "A-1", barcode: null }]} />);
    await waitFor(() => expect(screen.getByRole("status")).toHaveTextContent(
      "Genera una vista previa nueva después de cambiar la configuración"
    ));
    fireEvent.click(screen.getByRole("button", { name: /Documento resumen/ }));
    expect((screen.getByRole("button", { name: "Exportar XLSX" }) as HTMLButtonElement).disabled).toBe(true);
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: /Productos importables \(1\)/ })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: /Productos importables \(1\)/ }));
    fireEvent.click(screen.getByRole("button", { name: "Importar a edición masiva" }));
    await waitFor(() => expect(props.onImportAccepted).toHaveBeenCalledOnce());
    expect(props.onImportAccepted.mock.calls[0]?.[0]?.[0]?.product?.id).toBe("product-1");
  });

  it("offers the same explicit creation action in Warehouse output", async () => {
    const flow = await operationFlow("WAREHOUSE_OUTPUT");
    expect(screen.getByRole("button", { name: "Añadir productos" })).toBeEnabled();
    expect(flow.operations).toHaveLength(0);
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    expect(screen.getByLabelText("Añadir automáticamente los productos no existentes")).toBeChecked();
    expect(screen.queryByRole("button", { name: "Usar precio en documento" })).not.toBeInTheDocument();
  });

  it.each(["STOCK", "WAREHOUSE_OUTPUT"] as const)("imports existing rows in %s while missing products remain", async (context) => {
    const flow = await operationFlow(context);
    fireEvent.click(screen.getByRole("button", { name: "Productos importables (1)" }));
    fireEvent.click(screen.getByRole("button", { name: context === "STOCK" ? "Importar a edición masiva" : "Importar Excel al documento" }));
    await waitFor(() => expect(flow.onImportAccepted).toHaveBeenCalledOnce());
    expect(flow.operations).toEqual([expect.objectContaining({ operation: "PREPARE_DESTINATION", autoAddMissing: false, confirmMasterChanges: false })]);
    const rows = flow.onImportAccepted.mock.calls[0][0];
    expect(rows).toHaveLength(1);
    expect(rows[0].product.id).toBe("product-1");
    if (context === "STOCK") expect(rows[0]).not.toHaveProperty("quantity");
  });

  it("does not create missing products or transfer quantities during Stock Apply", async () => {
    const flow = await operationFlow("STOCK");
    expect(flow.operations).toHaveLength(0);
    expect(flow.onImportAccepted).not.toHaveBeenCalled();
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    expect(screen.queryByLabelText("Cantidad Columna Excel")).not.toBeInTheDocument();
    expect(screen.getByLabelText("Cantidad por paquete Columna Excel")).toBeInTheDocument();
    const previewCall = flow.fetchMock.mock.calls.find(([url]) => url.endsWith("/preview"))!;
    const config = JSON.parse(await ((previewCall[1]!.body as FormData).get("config") as Blob).text());
    expect(config.mapping).not.toHaveProperty("quantity");
    expect(config).not.toHaveProperty("quantityColumn");
    expect(config.options.requireQuantity).toBe(false);
  });

  it("retains backend accumulated Warehouse quantity even without a quantity column", async () => {
    const flow = await operationFlow("WAREHOUSE_OUTPUT", false, { quantity: "5" });
    fireEvent.click(screen.getByRole("button", { name: "Productos importables (1)" }));
    fireEvent.click(screen.getByRole("button", { name: "Importar Excel al documento" }));
    await waitFor(() => expect(flow.onImportAccepted).toHaveBeenCalledOnce());
    expect(flow.onImportAccepted.mock.calls[0][0][0].quantity).toBe(5);
  });

  it("keeps missing Stock products pending until the user explicitly starts creation", async () => {
    const flow = await operationFlow("STOCK");
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    fireEvent.click(screen.getByLabelText("Añadir automáticamente los productos no existentes"));
    fireEvent.click(screen.getByRole("button", { name: "Productos no importables (1)" }));
    expect(screen.getByRole("button", { name: "Añadir productos" })).toBeEnabled();
    expect(flow.operations).toHaveLength(0);
    expect(flow.onImportAccepted).not.toHaveBeenCalled();
  });

  it.each(["STOCK", "WAREHOUSE_INPUT"] as const)("sends a purchase-only operation from the difference tab in %s", async (context) => {
    const flow = await operationFlow(context, true);
    fireEvent.click(screen.getByRole("button", { name: "Precio de compra distinto (1)" }));
    fireEvent.click(screen.getByRole("button", { name: "Actualizar precio de compra" }));
    fireEvent.click(screen.getAllByRole("button", { name: "Actualizar precio de compra" }).at(-1)!);
    await waitFor(() => expect(flow.operations).toHaveLength(1));
    expect(flow.operations[0]).toMatchObject({ operation: "UPDATE_PURCHASE_PRICE", confirmMasterChanges: true, autoAddMissing: false });
    expect(flow.onImportAccepted).not.toHaveBeenCalled();
  });

  it("updates selected attributes separately from importing to Stock", async () => {
    const flow = await operationFlow("STOCK");
    fireEvent.click(screen.getByRole("button", { name: "Productos importables (1)" }));
    fireEvent.click(screen.getByRole("button", { name: "Actualizar atributos marcados" }));
    fireEvent.click(screen.getAllByRole("button", { name: "Actualizar atributos marcados" }).at(-1)!);
    await waitFor(() => expect(flow.operations).toHaveLength(1));
    expect(flow.operations[0]).toMatchObject({ operation: "UPDATE_SELECTED_FIELDS", confirmMasterChanges: true });
    expect(flow.onImportAccepted).not.toHaveBeenCalled();
  });

  it.each(["STOCK", "WAREHOUSE_OUTPUT"] as const)(
    "blocks partial import in %s when a row/global error is present",
    async (context) => {
      const fetchMock = vi.fn(async (url: string) => url.endsWith("/read")
        ? jsonResponse(readApiFixture())
        : jsonResponse({
            ...previewMissingFixture(),
            missingRows: 0,
            rows: [{
              rowNumber: 2, rowNumbers: [2], classification: "ERROR",
              excelData: { code: "A-1" }, databaseData: null, version: null,
              changes: {}, errors: [{ code: "ROW_INVALID", row: 2, column: 1,
                attribute: "code", receivedValue: "A-1", reason: "Fila inválida" }],
              purchasePriceChanged: false, masterDataChanged: false, concurrencyToken: null
            }],
            errors: []
          }));
      vi.stubGlobal("fetch", fetchMock);
      const onImportAccepted = vi.fn();
      const { container } = render(<SharedExcelImportDialog open locale="es" token="token" context={context} products={[]}
        onClose={vi.fn()} onImportAccepted={onImportAccepted} />);
      fireEvent.change(container.querySelector('input[type="file"]') as HTMLInputElement, {
        target: { files: [new File(["xls"], "productos.xlsx")] }
      });
      await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
      fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
      await waitFor(() => expect(screen.getByText("Errores (1)")).toBeInTheDocument());
      expect(onImportAccepted).not.toHaveBeenCalled();
    }
  );

  it("invalidates server preview when a data option changes and disables stale export", async () => {
    const fetchMock = vi.fn(async (url: string) => url.endsWith("/read")
      ? jsonResponse(readApiFixture())
      : jsonResponse(previewMissingFixture()));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(<SharedExcelImportDialog open locale="es" token="token"
      context="STOCK" products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]') as HTMLInputElement,
      { target: { files: [new File(["xls"], "productos.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    if (!(screen.getByLabelText("Generar documento resumen") as HTMLInputElement).checked) fireEvent.click(screen.getByLabelText("Generar documento resumen"));
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Documento resumen (1)" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    fireEvent.click(screen.getByLabelText("No actualizar cuando el precio nuevo sea 0"));
    fireEvent.click(screen.getByRole("button", { name: /^Documento resumen/ }));
    expect(screen.getByRole("button", { name: "Exportar XLSX" })).toBeDisabled();
  });

  it("keeps a current preview for action-only options but invalidates it when the document tariff changes", async () => {
    const fetchMock = vi.fn(async (url: string, _init?: RequestInit) => url.endsWith("/read")
      ? jsonResponse(readApiFixture())
      : jsonResponse(previewMissingFixture()));
    vi.stubGlobal("fetch", fetchMock);
    render(<SharedExcelImportDialog open locale="es" token="token"
      context="WAREHOUSE_INPUT"
      supplier={{ id: "supplier-1", code: "P-1", legalName: "Proveedor", active: true }}
      showDocumentPriceSource
      products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(document.querySelector('input[type="file"]') as HTMLInputElement,
      { target: { files: [new File(["xls"], "productos.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
    await waitFor(() => expect(screen.getByRole("button", { name: "Productos no importables (1)" })).toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    fireEvent.click(screen.getByLabelText("Añadir automáticamente los productos no existentes"));
    fireEvent.click(screen.getByLabelText("Generar documento resumen"));
    fireEvent.click(screen.getByLabelText("Actualizar el proveedor del producto"));
    fireEvent.click(screen.getByRole("button", { name: /^Documento resumen/ }));
    expect(screen.getByRole("button", { name: "Exportar XLSX" })).toBeEnabled();
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    fireEvent.click(screen.getByRole("button", { name: "Usar precio en documento" }));
    fireEvent.click(screen.getByRole("option", { name: "Precio de venta" }));
    fireEvent.click(screen.getByRole("button", { name: /^Documento resumen/ }));
    expect(screen.getByRole("button", { name: "Exportar XLSX" })).toBeDisabled();
    fireEvent.click(screen.getByRole("button", { name: "Configuración del archivo" }));
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
    await waitFor(() => expect(fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/preview"))).toHaveLength(2));
    const previewCall = fetchMock.mock.calls.filter(([url]) => String(url).endsWith("/preview")).at(-1);
    const previewForm = previewCall?.[1]?.body as FormData;
    const previewConfig = JSON.parse(await (previewForm.get("config") as Blob).text());
    expect(previewConfig.options.documentPriceSource).toBe("salePrice");
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

  it("detects a name-only row and marks the missing identifier", async () => {
    render(
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

    await waitFor(() => expect(screen.getByRole("button", { name: /^Aplicar$/ })).toBeEnabled());
    fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
    await waitFor(() => expect(screen.getByText("Errores (1)")).toBeInTheDocument());
  });

  it.each([
    ["en", "Attribute", "Comments", "The row contains an import error"],
    ["zh", "属性", "备注", "该行包含导入错误"]
  ] as const)("localizes structured row errors in %s", async (locale, attributeLabel, commentsLabel, genericReason) => {
    const fetchMock = vi.fn(async (url: string) => url.endsWith("/read")
      ? jsonResponse(readApiFixture())
      : jsonResponse({
          ...previewMissingFixture(),
          rows: [{
            rowNumber: 2, rowNumbers: [2], classification: "ERROR",
            excelData: { code: "A-1", comments: "nota" }, databaseData: null,
            version: null, changes: {}, purchasePriceChanged: false,
            errors: [{ code: "ROW_INVALID", row: 2, column: 3, attribute: "comments",
              receivedValue: "nota", reason: "Motivo en español", acceptedValues: "texto",
              recommendedFix: "Corrige" }]
          }], errors: []
        }));
    vi.stubGlobal("fetch", fetchMock);
    const { container } = render(<SharedExcelImportDialog open locale={locale} token="token"
      context="STOCK" products={[]} onClose={vi.fn()} onImportAccepted={vi.fn()} />);
    fireEvent.change(container.querySelector('input[type="file"]') as HTMLInputElement,
      { target: { files: [new File(["xls"], "productos.xlsx")] } });
    await waitFor(() => expect(screen.getByLabelText(locale === "en" ? "Code Excel column" : "编码 Excel 列")).toHaveValue("A"));
    fireEvent.click(screen.getByRole("button", { name: locale === "en" ? "Apply" : "应用" }));
    const reviewLabel = (locale === "en" ? "Review row " : "查看行 ") + "2";
    const reviewButton = await screen.findByRole("button", { name: reviewLabel });
    fireEvent.click(reviewButton);
    expect(reviewButton).toHaveAttribute("aria-expanded", "true");
    const detail = screen.getByRole("region", { name: reviewLabel });
    expect(within(detail).getByText(genericReason)).toBeInTheDocument();
    expect(within(detail).getAllByText(new RegExp(`${attributeLabel}: ${commentsLabel}`)).length).toBeGreaterThan(0);
    expect(screen.queryByText("Motivo en español")).not.toBeInTheDocument();
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

  it("sanitizes interactive Excel column entries to uppercase letters only", async () => {
    render(
      <SharedExcelImportDialog
        open locale="es"
        sheet={[["Código", "Usar precio"], ["A001", "1"]]}
        products={[{ id: "p1", code: "A001" }]}
        onClose={vi.fn()} onImportAccepted={vi.fn()}
      />
    );
    const input = await screen.findByLabelText("Usar precio Columna o valor");
    fireEvent.change(input, { target: { value: "a1$ b" } });
    expect(input).toHaveValue("AB");
    expect(sanitizeExcelColumnLetter("IV-2")).toBe("IV");
  });

  it("uses the shared resizer keyboard contract", async () => {
    render(
      <SharedExcelImportDialog
        open locale="es" sheet={[["Código"], ["A001"]]} products={[]}
        onClose={vi.fn()} onImportAccepted={vi.fn()}
      />
    );
    const resizer = await screen.findByRole("button", { name: "Redimensionar columna A" });
    const column = resizer.closest("th");
    const widthBefore = column?.closest("table")?.querySelectorAll("col")[1]?.getAttribute("style");
    fireEvent.keyDown(resizer, { key: "ArrowRight" });
    expect(column).toBeInTheDocument();
    const widthAfter = column?.closest("table")?.querySelectorAll("col")[1]?.getAttribute("style");
    expect(widthAfter).not.toBe(widthBefore);
  });

  it("validates the effective global values before apply", () => {
    const row = applyPreviewOptions([{
      rowNumber: 2, source: [], product: { id: "p1", code: "A001" }, status: "accepted",
      errors: [], draft: {
        code: "A001", barcode: "", name: "", description: "", comments: "", familyId: "", subfamilyId: "", taxId: "",
        productType: "", priceUseMode: "OFFER_PRICE", discountType: "1", purchasePrice: "0", purchaseDiscountPercent: "0",
        taxesIncluded: "0", salePrice: "0", memberPrice: "0", wholesalePrice: "0", offerPrice: "0", offerDiscountPercent: "0",
        offerActive: "0", offerFrom: "05-05-2026", offerUntil: "30-04-2026", barcode2: "", packageQuantity: "0", stockMin: "0", stockMax: "0"
      }
    }], {
      priceUseMode: { source: "excel", value: "NORMAL" }, discountType: { source: "excel", value: "1" },
      offerActive: { source: "excel", value: "" },
      taxId: { source: "excel", value: "" }, taxesIncluded: { source: "excel", value: "0" }, productType: { source: "excel", value: "UNIT" }
    });
    expect(row[0]?.status).toBe("error");
    expect(row[0]?.errors).toEqual(expect.arrayContaining(["discountProhibitedPriceMode", "offerDateRange"]));
  });

  it.each(["memberPrice", "offerPrice", "offerDiscountPercent"] as const)(
    "blocks prohibited discounts when imported state includes %s",
    (field) => {
      const draft = {
        code: "A001", barcode: "", name: "Producto", description: "", comments: "", familyId: "", subfamilyId: "", taxId: "",
        productType: "", priceUseMode: "NORMAL", discountType: "1", purchasePrice: "10", purchaseDiscountPercent: "",
        taxesIncluded: "0", salePrice: "12", memberPrice: "", wholesalePrice: "", offerPrice: "", offerDiscountPercent: "",
        offerActive: "0", offerFrom: "", offerUntil: "", barcode2: "", packageQuantity: "", stockMin: "", stockMax: ""
      };
      draft[field] = "10";
      const rows = applyPreviewOptions([{
        rowNumber: 2,
        source: [],
        product: { id: "p1", code: "A001" },
        status: "accepted",
        errors: [],
        draft
      }], {
        priceUseMode: { source: "excel", value: "NORMAL" },
        offerActive: { source: "excel", value: "" },
        discountType: { source: "excel", value: "1" },
        taxId: { source: "excel", value: "" },
        taxesIncluded: { source: "excel", value: "0" }, productType: { source: "excel", value: "UNIT" }
      });

      expect(rows[0]?.status).toBe("error");
      expect(rows[0]?.errors).toContain("discountProhibitedPriceMode");
    }
  );
});

function jsonResponse(value: unknown): { ok: boolean; status: number; headers: { get: () => string | null }; text: () => Promise<string> } {
  return {
    ok: true,
    status: 200,
    headers: { get: (): string | null => null },
    text: async () => JSON.stringify(value)
  };
}

async function openAndApply(container: HTMLElement, summary = false) {
  fireEvent.change(container.querySelector('input[type="file"]') as HTMLInputElement,
    { target: { files: [new File(["xls"], "productos.xlsx")] } });
  await waitFor(() => expect(screen.getByLabelText("Código Columna Excel")).toHaveValue("A"));
  if (summary && !(screen.getByLabelText("Generar documento resumen") as HTMLInputElement).checked)
    fireEvent.click(screen.getByLabelText("Generar documento resumen"));
  fireEvent.click(screen.getByRole("button", { name: /^Aplicar$/ }));
  await waitFor(() => expect(screen.getByRole("button", { name: "Añadir productos" })).toBeInTheDocument());
}

async function operationFlow(context: "STOCK" | "WAREHOUSE_INPUT" | "WAREHOUSE_OUTPUT", differentPurchase = false, excelValues: Record<string, string> = {}) {
  let created = false;
  const operations: Array<Record<string, unknown>> = [];
  const snapshot = () => ({ ...previewExistingFixture(), detectedRows: 2, existingRows: created ? 2 : 1, missingRows: created ? 0 : 1,
    rows: [ { ...previewExistingFixture().rows[0], excelData: { ...previewExistingFixture().rows[0].excelData, ...excelValues }, purchasePriceChanged: differentPurchase }, { ...previewMissingFixture().rows[0], rowNumber: 3, rowNumbers: [3],
      classification: created ? "EXISTING" : "MISSING", excelData: { code: "NEW-1", name: "Nuevo" },
      databaseData: created ? { id: "created-1", code: "NEW-1", name: "Nuevo" } : null }] });
  const fetchMock = vi.fn(async (url: string, init?: RequestInit) => {
    if (url.endsWith("/read")) {
      const read = readApiFixture();
      read.rows[0].push({ value: "Precio de compra" });
      read.rows[1].push({ value: "12" });
      return jsonResponse({ ...read, columns: 3 });
    }
    if (url.endsWith("/preview")) return jsonResponse(snapshot());
    if (url.endsWith("/apply")) {
      const request = JSON.parse(await ((init!.body as FormData).get("config") as Blob).text());
      operations.push(request);
      const create = request.operation === "CREATE_MISSING";
      if (create) created = true;
      return jsonResponse({ fileName: "productos.xlsx", sha256: "sha-1", appliedCount: create ? 1 : 0, errors: [],
        rows: [{ rowNumber: create ? 3 : 2, rowNumbers: [create ? 3 : 2], classification: "EXISTING", productId: create ? "created-1" : "product-1", errors: [] }],
        warehouseMetadata: { formulas: [], updateSupplier: false, skipZeroPriceUpdate: true, lines: [] }, warehouseProvenanceToken: "signed" });
    }
    return jsonResponse([]);
  });
  vi.stubGlobal("fetch", fetchMock);
  const onImportAccepted = vi.fn();
  const onClose = vi.fn();
  const { container } = render(<SharedExcelImportDialog open locale="es" token="token" context={context}
    products={[]} onClose={onClose} onImportAccepted={onImportAccepted} />);
  await openAndApply(container);
  return { fetchMock, operations, onImportAccepted, onClose };
}

function readApiFixture() {
  return {
    fileName: "productos.xlsx", sha256: "sha-1", sheetName: "Hoja1",
    rows: [[{ value: "Codigo" }, { value: "Nombre" }], [{ value: "A-1" }, { value: "Producto" }]],
    formulas: [], nonEmptyRows: 2, columns: 2, nonEmptyCells: 4
  };
}

function previewMissingFixture() {
  return {
    fileName: "productos.xlsx", sha256: "sha-1", sheetName: "Hoja1", detectedRows: 1, existingRows: 0, missingRows: 1,
    rows: [{ rowNumber: 2, rowNumbers: [2], classification: "MISSING", excelData: { code: "A-1", name: "Producto" },
      databaseData: null, version: null, changes: {}, errors: [], purchasePriceChanged: false, masterDataChanged: false, concurrencyToken: "token" }],
    errors: [], previewFingerprint: "f".repeat(64)
  };
}

function previewExistingFixture() {
  return {
    fileName: "productos.xlsx", sha256: "sha-1", sheetName: "Hoja1", detectedRows: 1, existingRows: 1, missingRows: 0,
    rows: [{ rowNumber: 2, rowNumbers: [2], classification: "EXISTING", excelData: { code: "A-1", name: "Producto" },
      databaseData: { id: "product-1", code: "A-1", name: "Producto" }, version: 1, changes: {}, errors: [],
      purchasePriceChanged: false, masterDataChanged: false, concurrencyToken: "token" }], errors: [],
    previewFingerprint: "f".repeat(64)
  };
}
