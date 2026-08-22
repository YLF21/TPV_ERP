// @vitest-environment jsdom

import { act, cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import type { HardwareBridge } from "../hardware/hardware";
import { tableLayoutStorageKey, writeStoredTableLayout } from "./tableLayoutPreferences";
import {
  buildWarehouseDocumentCommand,
  canConfirmWarehouseDocument,
  createManualWarehouseDocumentLine,
  documentLineTotal,
  documentTotalAfterDiscount,
  warehouseDocumentRequestErrorMessage,
  warehouseDocumentPath,
  WarehouseDocumentDialog
} from "./WarehouseDocumentDialog";

vi.mock("../api/client", async (importOriginal) => {
  const actual = await importOriginal<typeof import("../api/client")>();
  return { ...actual, apiRequest: vi.fn(actual.apiRequest) };
});

const products = [{ id: "product-1", code: "A001", barcode: "841000000001", name: "Cafe molido", salePrice: 4.5 }];
const warehouses = [{ id: "warehouse-1", name: "GENERAL" }];
const customers = [{ id: "customer-1", fiscalName: "Cliente SL", documentNumber: "B00000000" }];
const suppliers = [{ id: "supplier-1", legalName: "Proveedor SL", documentNumber: "B12345678" }];
const lines = [
  {
    rowNumber: 2,
    productId: "product-1",
    productLabel: "A001 - Cafe molido",
    importedProduct: "A001",
    quantity: 3,
    valid: true,
    errorKey: ""
  }
];

const existingDocument = {
  id: "output-1",
  number: "SAL-2026-000001",
  warehouseId: "warehouse-1",
  date: "2026-07-26",
  destination: "Cliente SL",
  concept: "Rotura",
  status: "BORRADOR",
  lines: [{ productId: "product-1", quantity: 3 }]
};

function installHardwareBridge(overrides: Partial<HardwareBridge> = {}) {
  const hardware = {
    getHardwareConfig: vi.fn().mockResolvedValue({ a4PrinterName: "A4 oficina" }),
    printA4Document: vi.fn().mockResolvedValue({ ok: true }),
    ...overrides
  } as unknown as HardwareBridge;
  Object.defineProperty(window, "tpvDesktop", {
    configurable: true,
    value: { hardware }
  });
  return hardware;
}

describe("WarehouseDocumentDialog", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.mocked(apiRequest).mockReset();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
    vi.restoreAllMocks();
    Reflect.deleteProperty(window, "tpvDesktop");
  });

  it("renders output mode with document workspace and file actions", () => {
    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="output"
        open
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        canConfirm
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html).toContain("Salida almacén");
    expect(html).toContain("Archivo");
    expect(html).toContain("Guardar (F9)");
    expect(html).toContain("Salir (Esc)");
    expect(html).toContain("Cliente/destino");
    expect(html).toContain("Descuento total del documento %");
    expect(html).toContain("Importe total");
    expect(html).not.toContain("Acciones");
    expect(html).not.toContain("Eliminar</button>");
    expect(html).toContain("Confirmar");
    expect(html).toContain('class="warehouse-document-dialog warehouse-document-dialog-v2"');
    expect(html).toContain("erp-select__trigger");
    expect(html).not.toContain("<select");
  });

  it("renders input mode with supplier fields", () => {
    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="input"
        open
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html).toContain("Entrada almacén");
    expect(html).toContain("Proveedor");
    expect(html).not.toContain("Cliente</span>");
  });

  it("closes the document editor when Escape is pressed outside its controls", () => {
    const onClose = vi.fn();
    render(
      <WarehouseDocumentDialog
        mode="input"
        open
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        onClose={onClose}
        onConfirmed={vi.fn()}
      />
    );

    fireEvent.keyDown(window, { key: "Escape" });

    expect(onClose).toHaveBeenCalledOnce();
  });

  it("translates the warehouse document to English while retaining Spanish number formatting", () => {
    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html).toContain("File");
    expect(html).toContain("Customer/destination");
    expect(html).toContain("Total document discount %");
    expect(html).toContain("0,00");
    expect(html).not.toContain("Cliente/destino");
  });

  it("keeps draft saving available and hides confirmation without explicit permission", () => {
    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="input"
        open
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html).toContain("Guardar (F9)");
    expect(html).toContain("Confirmar");
    expect(html).toContain('disabled=""');
  });

  it("disables confirmation after the warehouse document has been confirmed", async () => {
    const { getByRole } = render(
      <WarehouseDocumentDialog
        mode="input"
        open
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        canConfirm
        document={{
          ...existingDocument,
          id: "input-1",
          number: "ENT-2026-000001",
          status: "CONFIRMADA"
        }}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    const confirmButton = await waitFor(() => {
      const button = getByRole("button", { name: "Confirmar" }) as HTMLButtonElement;
      expect(button.disabled).toBe(true);
      return button;
    });
    fireEvent.click(confirmButton);
    fireEvent.keyDown(getByRole("dialog"), { key: "F10" });

    expect(apiRequest).not.toHaveBeenCalled();
  });

  it("blocks confirmation with no valid lines", () => {
    expect(canConfirmWarehouseDocument({
      warehouseId: "warehouse-1",
      partnerId: "supplier-1",
      partnerText: "",
      lines: []
    })).toBe(false);
    expect(canConfirmWarehouseDocument({
      warehouseId: "warehouse-1",
      partnerId: "supplier-1",
      partnerText: "",
      lines: [{ ...lines[0], valid: false, errorKey: "warehouseDocument.error.invalidQuantity" }]
    })).toBe(false);
    expect(canConfirmWarehouseDocument({
      warehouseId: "warehouse-1",
      partnerId: "",
      partnerText: "",
      lines
    })).toBe(true);
  });

  it("builds output and input commands for backend endpoints", () => {
    expect(warehouseDocumentPath("input")).toBe("/warehouse-inputs");
    expect(warehouseDocumentPath("output")).toBe("/warehouse-outputs");
    expect(buildWarehouseDocumentCommand("output", {
      warehouseId: "warehouse-1",
      partnerId: "customer-1",
      partnerText: "Cliente SL",
      date: "2026-07-08",
      concept: "Rotura",
      lines
    })).toEqual({
      warehouseId: "warehouse-1",
      date: "2026-07-08",
      destination: "Cliente SL",
      concept: "Rotura",
      lines: [{ productId: "product-1", quantity: 3 }]
    });
    expect(buildWarehouseDocumentCommand("input", {
      warehouseId: "warehouse-1",
      partnerId: "supplier-1",
      partnerText: "Proveedor SL",
      date: "2026-07-08",
      concept: "Compra",
      lines
    })).toEqual({
      warehouseId: "warehouse-1",
      date: "2026-07-08",
      supplierId: "supplier-1",
      origin: "Proveedor SL",
      externalNumber: undefined,
      concept: "Compra",
      documentType: "ENTRADA_ALMACEN",
      priceSource: "PURCHASE",
      globalDiscount: 0,
      sourceDeliveryNoteIds: [],
      lines: [{
        productId: "product-1",
        productName: "A001 - Cafe molido",
        quantity: 3,
        unitPrice: 0,
        discount: 0,
        priceOverridden: false
      }]
    });
  });

  it("requires a supplier only for incoming invoices", () => {
    expect(canConfirmWarehouseDocument({
      warehouseId: "warehouse-1",
      partnerId: "",
      partnerText: "Origen libre",
      documentType: "ALBARAN_ENTRADA",
      lines
    })).toBe(true);
    expect(canConfirmWarehouseDocument({
      warehouseId: "warehouse-1",
      partnerId: "",
      partnerText: "Origen libre",
      documentType: "FACTURA_ENTRADA",
      lines
    })).toBe(false);
  });

  it("sends imported formulas as private document metadata", () => {
    expect(buildWarehouseDocumentCommand("input", {
      warehouseId: "warehouse-1",
      partnerId: "supplier-1",
      partnerText: "Proveedor SL",
      date: "2026-07-08",
      concept: "Compra",
      lines: [{
        rowNumber: 1,
        productId: "product-1",
        productLabel: "Producto",
        importedProduct: "A001",
        quantity: 3,
        discountPercent: "0",
        valid: true,
        errorKey: ""
      }],
      excelImport: {
        fileName: "productos.xlsx",
        formulas: [{ cell: "I2", formula: "E2*2.5", calculatedValue: "10.25" }]
      }
    })).toEqual(expect.objectContaining({
      excelImport: {
        fileName: "productos.xlsx",
        formulas: [{ cell: "I2", formula: "E2*2.5", calculatedValue: "10.25" }]
      }
    }));
  });

  it("creates a valid manual line from the product master", () => {
    expect(createManualWarehouseDocumentLine("product-1", 4, products, 1)).toEqual(expect.objectContaining({
      productId: "product-1",
      productLabel: "A001 - Cafe molido",
      quantity: 4,
      valid: true
    }));
    expect(createManualWarehouseDocumentLine("missing", 4, products, 2).valid).toBe(false);
  });

  it("applies line discount before document discount", () => {
    const lineTotal = documentLineTotal(100, 2, "20");
    expect(lineTotal).toBe(160);
    expect(documentTotalAfterDiscount(lineTotal, "5")).toBe(152);
  });

  it("uses the translated conflict message instead of exposing backend copy", () => {
    expect(warehouseDocumentRequestErrorMessage(
      new ApiError("La operacion entra en conflicto con los datos existentes", 409, { code: "DATA_INTEGRITY_CONFLICT" }),
      "No se pudo confirmar",
      {
        integrityConflict: "Revisa el documento",
        stateConflict: "Recarga el borrador"
      }
    )).toBe("Revisa el documento");
  });

  it("persists output line order and widths while keeping existing and new rows aligned", async () => {
    writeStoredTableLayout("gestion", "maria", "warehouse.outputs.lines", [
      { key: "total", width: 160, visible: true },
      { key: "code", width: 180, visible: true },
      { key: "barcode", width: 200, visible: true },
      { key: "name", width: 260, visible: true },
      { key: "discount", width: 150, visible: true },
      { key: "price", width: 120, visible: true },
      { key: "quantity", width: 170, visible: true }
    ], localStorage);

    const { container } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        app="gestion"
        username="maria"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        token="token"
        document={{
          id: "output-1",
          number: null,
          warehouseId: "warehouse-1",
          date: "2026-07-15",
          status: "BORRADOR",
          lines: [{ productId: "product-1", quantity: 3 }]
        }}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    await waitFor(() => expect(container.querySelectorAll("tbody tr").length).toBeGreaterThan(0));

    const headerKeys = () => Array.from(container.querySelectorAll<HTMLElement>("thead [data-column-key]"))
      .map((header) => header.dataset.columnKey);
    const firstRowCells = () => Array.from(container.querySelectorAll<HTMLTableCellElement>("tbody tr:first-child td"));

    expect(headerKeys().slice(0, 2)).toEqual(["total", "code"]);
    expect(firstRowCells()[0].textContent).toContain("13,50");
    expect(firstRowCells()[1].textContent).toContain("A001");
    expect(Array.from(container.querySelectorAll("colgroup col")).map((col) => (col as HTMLElement).style.width).slice(0, 2))
      .toEqual(["160px", "180px"]);
    expect(Array.from(container.querySelectorAll<HTMLElement>("thead [data-column-key]")).every((header) => header.draggable))
      .toBe(true);

    const values = new Map<string, string>();
    const dataTransfer = {
      effectAllowed: "move",
      dropEffect: "move",
      setData: (type: string, value: string) => values.set(type, value),
      getData: (type: string) => values.get(type) ?? ""
    };
    fireEvent.dragStart(container.querySelector('[data-column-key="name"]') as HTMLElement, { dataTransfer });
    fireEvent.dragOver(container.querySelector('[data-column-key="total"]') as HTMLElement, { dataTransfer });
    fireEvent.drop(container.querySelector('[data-column-key="total"]') as HTMLElement, { dataTransfer });
    expect(headerKeys()[0]).toBe("name");
    expect(firstRowCells()[0].textContent).toContain("Cafe molido");

    fireEvent.keyDown(container.querySelector('[data-column-key="name"]') as HTMLElement, {
      key: "ArrowRight",
      ctrlKey: true
    });
    expect(headerKeys().slice(0, 2)).toEqual(["total", "name"]);
    expect(firstRowCells()[0].textContent).toContain("13,50");

    const nameHeader = container.querySelector('[data-column-key="name"]') as HTMLElement;
    fireEvent.keyDown(nameHeader.querySelector("button") as HTMLButtonElement, { key: "ArrowRight" });
    const stored = JSON.parse(localStorage.getItem(
      tableLayoutStorageKey("gestion", "maria", "warehouse.outputs.lines")
    ) ?? "{}") as { columns: Array<{ key: string; width: number }> };
    expect(stored.columns.map((column) => column.key).slice(0, 2)).toEqual(["total", "name"]);
    expect(stored.columns.find((column) => column.key === "name")?.width).toBe(268);
  });

  it("uses the independent input line preference key", () => {
    writeStoredTableLayout("venta", "ana", "warehouse.inputs.lines", [
      { key: "quantity", width: 170, visible: true },
      { key: "code", width: 180, visible: true }
    ], localStorage);

    const html = renderToStaticMarkup(
      <WarehouseDocumentDialog
        mode="input"
        open
        app="venta"
        username="ana"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    expect(html.indexOf('data-column-key="quantity"')).toBeLessThan(html.indexOf('data-column-key="code"'));
  });

  it("prints a complete warehouse document through the desktop bridge", async () => {
    const hardware = installHardwareBridge();
    const { getByRole, getByText } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={existingDocument}
        terminalContext={{ terminalCode: "CAJA-1", storeName: "TIENDA DEMO" }}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    await waitFor(() => expect((getByRole("button", { name: "Print" }) as HTMLButtonElement).disabled).toBe(false));
    fireEvent.click(getByRole("button", { name: "Print" }));

    await waitFor(() => expect(hardware.printA4Document).toHaveBeenCalledOnce());
    expect(hardware.printA4Document).toHaveBeenCalledWith(
      expect.objectContaining({
        documentType: "REPORT",
        locale: "en",
        title: "Warehouse output",
        storeName: "TIENDA DEMO",
        terminalCode: "CAJA-1",
        lines: [expect.objectContaining({
          name: "A001 - Cafe molido",
          quantity: 3,
          price: 4.5,
          total: 13.5
        })]
      }),
      expect.objectContaining({ a4PrinterName: "A4 oficina" })
    );
    expect(getByText("Document sent to the printer")).toBeTruthy();
  });

  it("prevents duplicate desktop print requests while hardware is pending", async () => {
    let resolveConfig!: (config: { a4PrinterName: string }) => void;
    const configPending = new Promise<{ a4PrinterName: string }>((resolve) => {
      resolveConfig = resolve;
    });
    const hardware = installHardwareBridge({
      getHardwareConfig: vi.fn().mockReturnValue(configPending)
    });
    const { getByRole } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={existingDocument}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    const printButton = await waitFor(() => getByRole("button", { name: "Print" }));
    await act(async () => {
      printButton.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      printButton.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });

    expect(hardware.getHardwareConfig).toHaveBeenCalledOnce();
    expect((printButton as HTMLButtonElement).disabled).toBe(true);

    resolveConfig({ a4PrinterName: "A4 oficina" });
    await waitFor(() => expect(hardware.printA4Document).toHaveBeenCalledOnce());
    await waitFor(() => expect((printButton as HTMLButtonElement).disabled).toBe(false));
  });

  it("uses a localized print error instead of exposing the hardware message", async () => {
    const hardwareMessage = "No se pudo abrir la cola tecnica";
    installHardwareBridge({
      printA4Document: vi.fn().mockResolvedValue({
        ok: false,
        code: "PRINT_FAILED",
        message: hardwareMessage
      })
    });
    const { getByRole, getByText, queryByText } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={existingDocument}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    fireEvent.click(await waitFor(() => getByRole("button", { name: "Print" })));

    await waitFor(() => expect(getByText("The document could not be printed")).toBeTruthy());
    expect(queryByText(hardwareMessage)).toBeNull();
  });

  it("opens an isolated localized browser preview and reports popup blocking", async () => {
    const write = vi.fn();
    const print = vi.fn();
    const previewWindow = {
      opener: window,
      document: { open: vi.fn(), write, close: vi.fn() },
      setTimeout: (callback: () => void) => {
        callback();
        return 1;
      },
      focus: vi.fn(),
      print
    } as unknown as Window;
    const open = vi.spyOn(window, "open").mockReturnValue(previewWindow);
    const { getByRole, getByText } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={existingDocument}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    fireEvent.click(await waitFor(() => getByRole("button", { name: "Print" })));

    expect(open).toHaveBeenCalledWith("", "_blank", "popup=yes,width=1040,height=820");
    expect(write).toHaveBeenCalledWith(expect.stringContaining('<html lang="en">'));
    expect(print).toHaveBeenCalledOnce();
    expect(getByText("Print preview")).toBeTruthy();

    await waitFor(() => expect((getByRole("button", { name: "Print" }) as HTMLButtonElement).disabled).toBe(false));
    open.mockReturnValue(null);
    fireEvent.click(getByRole("button", { name: "Print" }));
    expect(getByText("The browser blocked the print preview")).toBeTruthy();
  });

  it("prevents duplicate browser auto-print windows during preview startup", async () => {
    const previewWindow = {
      opener: window,
      document: { open: vi.fn(), write: vi.fn(), close: vi.fn() },
      setTimeout: vi.fn(),
      focus: vi.fn(),
      print: vi.fn()
    } as unknown as Window;
    const open = vi.spyOn(window, "open").mockReturnValue(previewWindow);
    const { getByRole } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={existingDocument}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    const printButton = await waitFor(() => getByRole("button", { name: "Print" }));
    await act(async () => {
      printButton.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      printButton.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });

    expect(open).toHaveBeenCalledOnce();
    expect((printButton as HTMLButtonElement).disabled).toBe(true);
    await waitFor(() => expect((printButton as HTMLButtonElement).disabled).toBe(false));
  });

  it("prevents duplicate manual preview windows during preview startup", async () => {
    const previewWindow = {
      opener: window,
      document: { open: vi.fn(), write: vi.fn(), close: vi.fn() }
    } as unknown as Window;
    const open = vi.spyOn(window, "open").mockReturnValue(previewWindow);
    const { getAllByRole, getByRole } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={existingDocument}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    fireEvent.click(getByRole("button", { name: "File" }));
    const previewButton = getByRole("button", { name: "Preview Ctrl+P" });
    const printButton = getAllByRole("button", { name: "Print" }).at(-1) as HTMLButtonElement;
    await act(async () => {
      previewButton.dispatchEvent(new MouseEvent("click", { bubbles: true }));
      previewButton.dispatchEvent(new MouseEvent("click", { bubbles: true }));
    });

    expect(open).toHaveBeenCalledOnce();
    expect((printButton as HTMLButtonElement).disabled).toBe(true);
    await waitFor(() => expect((printButton as HTMLButtonElement).disabled).toBe(false));
  });

  it("prints the document number returned by saving even without onSaved", async () => {
    const hardware = installHardwareBridge();
    vi.mocked(apiRequest).mockResolvedValue({
      ...existingDocument,
      number: "SAL-2026-000099"
    });
    const { getByRole } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        token="token"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={{ ...existingDocument, number: null }}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    fireEvent.click(await waitFor(() => getByRole("button", { name: "Save (F9)" })));
    await waitFor(() => expect(apiRequest).toHaveBeenCalledOnce());
    fireEvent.click(getByRole("button", { name: "Print" }));

    await waitFor(() => expect(hardware.printA4Document).toHaveBeenCalledOnce());
    expect(hardware.printA4Document).toHaveBeenCalledWith(
      expect.objectContaining({
        metadata: expect.arrayContaining([
          { label: "Document number", value: "SAL-2026-000099" }
        ])
      }),
      expect.anything()
    );
  });

  it("blocks printing when a document line cannot resolve its product", async () => {
    const hardware = installHardwareBridge();
    const { getByRole, getByText } = render(
      <WarehouseDocumentDialog
        mode="output"
        open
        locale="en"
        products={products}
        warehouses={warehouses}
        customers={customers}
        suppliers={suppliers}
        document={{
          ...existingDocument,
          lines: [{ productId: "missing-product", quantity: 2 }]
        }}
        onClose={vi.fn()}
        onConfirmed={vi.fn()}
      />
    );

    fireEvent.click(await waitFor(() => getByRole("button", { name: "Print" })));

    expect(getByText("Product not found")).toBeTruthy();
    expect(hardware.getHardwareConfig).not.toHaveBeenCalled();
    expect(hardware.printA4Document).not.toHaveBeenCalled();
  });
});
