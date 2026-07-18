// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  buildDocumentReports,
  buildReportColumnDefinitions,
  isPurchaseDocumentReport,
  isWarehouseDocumentReport,
  moveReportColumnBeforeTotal,
  moveVisibleReportColumn,
  normalizeRequiredTotal,
  reportTableKey,
  salesReportAccess,
  visibleSalesReports,
  SalesReportScreen
} from "./SalesReportScreen";
import {
  moveTableColumnByKeyboard,
  reorderTableColumns,
  resizeTableColumn,
  toggleTableColumnVisibility,
  visibleTableColumns
} from "./tableLayoutPreferences";
import type { TableLayout } from "./tableLayoutPreferences";
import type { UseTableLayoutPreferenceResult } from "./useTableLayoutPreference";
import type { TerminalContext, UserSession } from "../types";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["GESTION_VENTAS", "GESTION_CUENTAS"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

function createTableLayoutController(
  initialLayout: TableLayout<string>
): UseTableLayoutPreferenceResult<string> {
  let layout = initialLayout;
  return {
    get layout() {
      return layout;
    },
    ready: true,
    reorderColumns(draggedKey, targetKey) {
      layout = reorderTableColumns(layout, draggedKey, targetKey);
    },
    moveColumn(columnKey, direction) {
      layout = moveTableColumnByKeyboard(layout, columnKey, direction);
    },
    resizeColumn(columnKey, width) {
      layout = resizeTableColumn(layout, columnKey, width);
    },
    toggleColumnVisibility(columnKey) {
      layout = toggleTableColumnVisibility(layout, columnKey);
    }
  };
}

describe("SalesReportScreen", () => {
  it("maps existing warehouse endpoints into output and input warehouse reports", () => {
    const reports = buildDocumentReports(
      [],
      [],
      [],
      [
        {
          id: "warehouse-output-1",
          number: "SAL-0001",
          date: "2026-07-05",
          warehouseId: "warehouse-1",
          destination: "Rotura",
          concept: "Salida por rotura",
          status: "CONFIRMADA",
          lines: [{ productId: "product-1", quantity: 3 }]
        }
      ],
      [
        {
          id: "movement-1",
          productId: "product-1",
          warehouseId: "warehouse-1",
          userId: "user-1",
          type: "ALBARAN_COMPRA",
          quantity: "8.000",
          reason: "Entrada proveedor",
          createdAt: "2026-07-05T10:30:00Z"
        }
      ],
      [
        {
          id: "warehouse-input-1",
          number: "ENT-0001",
          date: "2026-07-06",
          warehouseId: "warehouse-1",
          supplierId: "supplier-1",
          origin: "Proveedor General",
          concept: "Entrada por compra",
          status: "CONFIRMADA",
          lines: [{ productId: "product-1", quantity: 6 }]
        }
      ],
      session,
      terminalContext
    );

    expect(reports["salesReport.warehouseOutputs"]?.rows).toEqual([
      expect.objectContaining({
        date: "05/07/2026",
        output: "SAL-0001",
        warehouse: "warehouse-1",
        productCount: "3",
        comment: "Salida por rotura",
        reason: "Rotura",
        total: "0.00"
      })
    ]);
    expect(reports["salesReport.inputWarehouse"]?.rows).toEqual([
      expect.objectContaining({
        date: "06/07/2026",
        time: "",
        input: "ENT-0001",
        warehouse: "warehouse-1",
        productCount: "6",
        comment: "Entrada por compra",
        origin: "Proveedor General",
        total: "0.00"
      })
    ]);
  });

  it("separates warehouse and purchase document creation reports", () => {
    expect(isWarehouseDocumentReport("salesReport.warehouseOutputs")).toBe(true);
    expect(isWarehouseDocumentReport("salesReport.inputWarehouse")).toBe(true);
    expect(isWarehouseDocumentReport("salesReport.inputInvoices")).toBe(false);
    expect(isWarehouseDocumentReport("salesReport.inputDeliveryNotes")).toBe(false);
    expect(isWarehouseDocumentReport("salesReport.dailySales")).toBe(false);
    expect(isWarehouseDocumentReport("salesReport.invoices")).toBe(false);
    expect(isPurchaseDocumentReport("salesReport.inputInvoices")).toBe(true);
    expect(isPurchaseDocumentReport("salesReport.inputDeliveryNotes")).toBe(true);
    expect(isPurchaseDocumentReport("salesReport.inputWarehouse")).toBe(false);
  });

  it("keeps accounts read-only and grants purchase creation to product and warehouse", () => {
    expect(salesReportAccess({ permissions: ["GESTION_PRODUCTO"] }).purchaseWrite).toBe(true);
    expect(salesReportAccess({ permissions: ["GESTION_ALMACEN"] }).purchaseWrite).toBe(true);
    expect(salesReportAccess({ permissions: ["GESTION_CUENTAS"] }).purchaseWrite).toBe(false);
    expect(salesReportAccess({ permissions: ["GESTION_VENTAS"] }).purchases).toBe(false);
    expect(visibleSalesReports({ permissions: ["GESTION_PRODUCTO"] }).all).toEqual([
      "salesReport.inputInvoices",
      "salesReport.inputDeliveryNotes"
    ]);
  });

  it("maps enriched invoice and delivery-note report data by purchase and sale type", () => {
    const reports = buildDocumentReports(
      [],
      [
        {
          tipo: "FACTURA_VENTA",
          estado: "PENDIENTE",
          numero: "FV-1",
          numeroExterno: "Pedido web",
          fecha: "2026-07-10",
          total: "121.00",
          pendiente: "21.00",
          clienteCodigo: "C-1",
          clienteNombre: "Cliente Uno"
        },
        {
          tipo: "FACTURA_COMPRA",
          estado: "PARCIAL",
          numero: "FC-1",
          numeroExterno: "Proveedor ref",
          fecha: "2026-07-11",
          fechaVencimiento: "2026-08-11",
          total: "50.00",
          pendiente: "12.50",
          proveedorCodigo: "P-1",
          proveedorNombre: "Proveedor Uno",
          almacenNombre: "GENERAL"
        }
      ],
      [
        {
          tipo: "ALBARAN_VENTA",
          estado: "CONFIRMADO",
          numero: "AV-1",
          fecha: "2026-07-12",
          total: "10.00",
          clienteCodigo: "C-2",
          clienteNombre: "Cliente Dos"
        },
        {
          tipo: "ALBARAN_COMPRA",
          estado: "CONFIRMADO",
          numero: "AC-1",
          fecha: "2026-07-13",
          total: "20.00",
          proveedorCodigo: "P-2",
          proveedorNombre: "Proveedor Dos",
          almacenNombre: "GENERAL",
          lineas: 3
        }
      ],
      [],
      [],
      [],
      session,
      terminalContext
    );

    expect(reports["salesReport.invoices"]?.rows).toEqual([
      expect.objectContaining({
        invoice: "FV-1",
        customer: "C-1",
        customerName: "Cliente Uno",
        pending: "21.00",
        comment: "Pedido web"
      })
    ]);
    expect(reports["salesReport.inputInvoices"]?.rows).toEqual([
      expect.objectContaining({
        invoice: "FC-1",
        supplier: "P-1",
        supplierName: "Proveedor Uno",
        warehouse: "GENERAL",
        dueDate: "11/08/2026",
        pending: "12.50",
        comment: "Proveedor ref"
      })
    ]);
    expect(reports["salesReport.deliveryNotes"]?.rows).toEqual([
      expect.objectContaining({
        deliveryNote: "AV-1",
        customer: "C-2",
        customerName: "Cliente Dos"
      })
    ]);
    expect(reports["salesReport.inputDeliveryNotes"]?.rows).toEqual([
      expect.objectContaining({
        deliveryNote: "AC-1",
        supplier: "P-2",
        supplierName: "Proveedor Dos",
        warehouse: "GENERAL",
        productCount: "3"
      })
    ]);
  });

  it("renders the formal report layout chrome", () => {
    const html = renderToStaticMarkup(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLogout={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(html).toContain('class="report-brand-back"');
    expect(html).toContain("APP VENTA");
    expect(html).toContain("Salidas");
    expect(html).toContain("Entradas");
    expect(html).toContain('class="report-data-toolbar"');
    expect(html).toContain('class="report-footer-context"');
    expect(html).toContain('class="report-user-button"');
    expect(html).toContain('class="top-date-time"');
    expect(html).toContain("DB:");
    expect(html).toContain("Conexión");
    expect(html).not.toContain("Backend");
    expect(html).not.toContain("SaaS:");
    expect(html).not.toContain("Líneas visibles</span><strong>0");
    expect(html).not.toContain("Cafe molido");
    expect(html).not.toContain("Pan integral");
    expect(html).not.toContain("Aceite oliva");
  });

  it("renders the five daily accounting buckets from the authoritative backend report", async () => {
    const request = vi.fn().mockResolvedValue({
      storeId: "store-1",
      date: "2026-07-16",
      invoiced: "100.00",
      collectedCurrent: "30.00",
      newPending: "70.00",
      priorDebtCollected: "20.00",
      cashInflow: "50.00"
    });

    render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={terminalContext}
        request={request}
        onBack={vi.fn()}
        onLogout={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      expect.stringMatching(/^\/commercial-reports\/daily\?date=/),
      { token: "token" }
    ));
    expect(screen.getByText("100.00€")).toBeVisible();
    expect(screen.getByText("30.00€")).toBeVisible();
    expect(screen.getByText("70.00€")).toBeVisible();
    expect(screen.getByText("20.00€")).toBeVisible();
    expect(screen.getByText("50.00€")).toBeVisible();
  });

  it("shows translated authoritative loading/error and retries without local totals", async () => {
    const request = vi.fn().mockRejectedValueOnce(new Error("sin red")).mockResolvedValueOnce({
      storeId: "store-1", date: "2026-07-16", invoiced: "1.00", collectedCurrent: "0.00",
      newPending: "1.00", priorDebtCollected: "0.00", cashInflow: "0.00"
    });
    render(<SalesReportScreen app="venta" locale="es" session={{ ...session, accessToken: "token" }} terminalContext={terminalContext} request={request} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("sin red");
    expect(screen.queryByText("Total facturado")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar informe diario" }));
    expect((await screen.findAllByText("1.00€")).length).toBeGreaterThanOrEqual(2);
    expect(request).toHaveBeenCalledTimes(2);
  });

  it("shows purchase creation from reports to product management", () => {
    const html = renderToStaticMarkup(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ username: "product", displayName: "PRODUCTO", permissions: ["GESTION_PRODUCTO"] }}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(html).toContain("Entrada factura");
    expect(html).toContain("Crear documento de compra");
    expect(html).not.toContain("Entrada almacén");
  });

  it("builds V67-compatible report table definitions with sensible defaults", () => {
    expect(reportTableKey("salesReport.tickets")).toBe("reports.salesReport.tickets");
    expect(buildReportColumnDefinitions({
      availableAttributes: ["date", "customerName", "unknown", "total"],
      defaultVisibleAttributes: ["date", "total"],
      rows: [],
      totals: {}
    })).toEqual([
      { key: "date", defaultWidth: 112, defaultVisible: true },
      { key: "customerName", defaultWidth: 200, defaultVisible: false },
      { key: "unknown", defaultWidth: 144, defaultVisible: false },
      { key: "total", defaultWidth: 112, defaultVisible: true }
    ]);
  });

  it("keeps total required and applies Visualization ordering to the generic layout", () => {
    const tableLayout = createTableLayoutController([
      { key: "total", width: 112, visible: false },
      { key: "date", width: 112, visible: true },
      { key: "time", width: 80, visible: false },
      { key: "customerName", width: 200, visible: true }
    ]);

    normalizeRequiredTotal(tableLayout);
    expect(tableLayout.layout.map((column) => column.key))
      .toEqual(["date", "time", "customerName", "total"]);
    expect(visibleTableColumns(tableLayout.layout).map((column) => column.key))
      .toEqual(["date", "customerName", "total"]);

    moveVisibleReportColumn(tableLayout, "date", 1);
    expect(visibleTableColumns(tableLayout.layout).map((column) => column.key))
      .toEqual(["customerName", "date", "total"]);

    moveReportColumnBeforeTotal(tableLayout, "time");
    expect(visibleTableColumns(tableLayout.layout).map((column) => column.key))
      .toEqual(["customerName", "date", "time", "total"]);
  });
});
