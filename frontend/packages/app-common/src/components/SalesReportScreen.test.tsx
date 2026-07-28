// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildDocumentReports,
  buildReportColumnDefinitions,
  canConfirmSalesInvoiceRectification,
  canManageSalesInvoiceRectification,
  canOpenOperationalTimeline,
  formatReportDisplayValue,
  isPurchaseDocumentReport,
  isWarehouseDocumentReport,
  moveReportColumnBeforeTotal,
  moveVisibleReportColumn,
  normalizeRequiredTotal,
  reportAttributeLabelKey,
  reportTableKey,
  quickDateRange,
  salesReportResponseError,
  salesReportAccess,
  sortReportRows,
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
    replaceLayout(nextLayout) {
      layout = nextLayout;
    },
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

afterEach(() => {
  cleanup();
});

describe("SalesReportScreen", () => {
  it("ordena importes y alterna periodos rápidos de forma determinista", () => {
    const rows = [{ total: "10,50" }, { total: "2,25" }, { total: "100,00" }];
    expect(sortReportRows(rows, { attribute: "total", direction: "asc" }, "es")
      .map((row) => row.total)).toEqual(["2,25", "10,50", "100,00"]);
    expect(quickDateRange("week", new Date(2026, 6, 29))).toEqual({
      dateFrom: "2026-07-27",
      dateTo: "2026-07-29"
    });
    expect(quickDateRange("month", new Date(2026, 6, 29))).toEqual({
      dateFrom: "2026-07-01",
      dateTo: "2026-07-29"
    });
  });
  it("formats every report amount as euros and preserves non-monetary values", () => {
    expect(formatReportDisplayValue("pending", "-12.10", "es")).toBe(
      new Intl.NumberFormat("es-ES", {
        style: "currency",
        currency: "EUR",
        minimumFractionDigits: 2,
        maximumFractionDigits: 2
      }).format(-12.1)
    );
    expect(formatReportDisplayValue("total", "60.50", "en")).toContain("€");
    expect(formatReportDisplayValue("total", "1,018.96", "es")).toContain("1.018,96");
    expect(formatReportDisplayValue("productCount", "60.50", "es")).toBe("60.50");
  });

  it("uses explicit purchase and sale labels for warehouse totals", () => {
    expect(reportAttributeLabelKey("salesReport.warehouseOutputs", "total"))
      .toBe("salesReport.column.saleTotal");
    expect(reportAttributeLabelKey("salesReport.inputWarehouse", "total"))
      .toBe("salesReport.column.purchaseTotal");
    expect(reportAttributeLabelKey("salesReport.invoices", "total"))
      .toBe("salesReport.column.total");
  });

  it("extracts actionable backend details from failed exports", async () => {
    const response = new Response(JSON.stringify({
      detail: "La configuración de columnas no se puede exportar"
    }), {
      status: 400,
      headers: { "Content-Type": "application/problem+json" }
    });
    expect(await salesReportResponseError(response))
      .toBe("La configuración de columnas no se puede exportar");
    expect(await salesReportResponseError(new Response("", { status: 503 }))).toBe("HTTP 503");
  });

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
          lines: [{
            productId: "product-1",
            quantity: 3,
            saleUnitPrice: 10.25,
            saleTotal: 30.75
          }]
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
          lines: [{
            productId: "product-1",
            quantity: 6,
            purchaseUnitPrice: 4.2,
            purchaseTotal: 25.2
          }]
        }
      ],
      session,
      terminalContext,
      [{ id: "warehouse-1", name: "GENERAL" }]
    );

    expect(reports["salesReport.warehouseOutputs"]?.rows).toEqual([
      expect.objectContaining({
        date: "05/07/2026",
        output: "SAL-0001",
        warehouse: "GENERAL",
        productCount: "3",
        comment: "Salida por rotura",
        reason: "Rotura",
        total: "30.75"
      })
    ]);
    expect(reports["salesReport.inputWarehouse"]?.rows).toEqual([
      expect.objectContaining({
        date: "06/07/2026",
        time: "",
        input: "ENT-0001",
        warehouse: "GENERAL",
        productCount: "6",
        comment: "Entrada por compra",
        origin: "Proveedor General",
        total: "25.20"
      })
    ]);
  });

  it("sums every historical line in warehouse input and output reports", () => {
    const reports = buildDocumentReports(
      [],
      [],
      [],
      [{
        id: "output",
        number: "SAL-2",
        date: "2026-07-07",
        warehouseId: "warehouse-1",
        lines: [
          { quantity: 2, saleUnitPrice: 10.25, saleTotal: 20.5 },
          { quantity: 3, saleUnitPrice: 5, saleTotal: 15 }
        ]
      }],
      [],
      [{
        id: "input",
        number: "ENT-2",
        date: "2026-07-07",
        warehouseId: "warehouse-1",
        lines: [
          { quantity: 2, purchaseUnitPrice: 4.2, purchaseTotal: 8.4 },
          { quantity: 3, purchaseUnitPrice: 2, purchaseTotal: 6 }
        ]
      }],
      session,
      terminalContext,
      [{ id: "warehouse-1", nombre: "ALMACÉN CENTRAL" }]
    );

    expect(reports["salesReport.warehouseOutputs"]?.rows[0]).toEqual(expect.objectContaining({
      warehouse: "ALMACÉN CENTRAL",
      productCount: "5",
      total: "35.50"
    }));
    expect(reports["salesReport.inputWarehouse"]?.rows[0]).toEqual(expect.objectContaining({
      warehouse: "ALMACÉN CENTRAL",
      productCount: "5",
      total: "14.40"
    }));
  });

  it("calculates warehouse totals from unit prices when the API omits derived line totals", () => {
    const reports = buildDocumentReports(
      [],
      [],
      [],
      [{
        id: "output",
        warehouseId: "warehouse-1",
        lines: [{ quantity: 3, saleUnitPrice: 10.25 }]
      }],
      [],
      [{
        id: "input",
        warehouseId: "warehouse-1",
        lines: [{ quantity: 4, purchaseUnitPrice: 4.2 }]
      }],
      session,
      terminalContext
    );

    expect(reports["salesReport.warehouseOutputs"]?.rows[0]?.total).toBe("30.75");
    expect(reports["salesReport.inputWarehouse"]?.rows[0]?.total).toBe("16.80");
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

  it("uses historical document attribution instead of the active session", () => {
    const reports = buildDocumentReports(
      [{
        id: "ticket-1",
        tipo: "TICKET",
        numero: "T-1",
        fecha: "2026-07-18",
        total: "10.00",
        usuarioNombre: "CAJERO HISTORICO",
        terminalOrigenNombre: "CAJA 02",
        ocurridoEn: "2026-07-18T10:35:00Z"
      }],
      [],
      [],
      [],
      [],
      [],
      session,
      terminalContext
    );

    expect(reports["salesReport.tickets"]?.rows[0]).toEqual(expect.objectContaining({
      __documentId: "ticket-1",
      ticket: "T-1",
      user: "CAJERO HISTORICO",
      terminal: "CAJA 02",
      time: expect.stringMatching(/^\d{2}:\d{2}$/)
    }));
  });

  it("only exposes document activity in management with the matching document permission", () => {
    const row = { __documentId: "document-1" };
    expect(canOpenOperationalTimeline("gestion", { permissions: ["APP_GESTION_ACCESS", "GESTION_VENTAS"] }, "salesReport.tickets", row)).toBe(true);
    expect(canOpenOperationalTimeline("venta", { permissions: ["APP_GESTION_ACCESS", "GESTION_VENTAS"] }, "salesReport.tickets", row)).toBe(false);
    expect(canOpenOperationalTimeline("gestion", { permissions: ["APP_GESTION_ACCESS", "VENTA"] }, "salesReport.tickets", row)).toBe(false);
    expect(canOpenOperationalTimeline("gestion", { permissions: ["APP_GESTION_ACCESS", "GESTION_CUENTAS"] }, "salesReport.inputInvoices", row)).toBe(true);
    expect(canOpenOperationalTimeline("gestion", { permissions: ["APP_GESTION_ACCESS", "GESTION_PRODUCTO"] }, "salesReport.tickets", row)).toBe(false);
    expect(canOpenOperationalTimeline("gestion", { permissions: ["GESTION_VENTAS"] }, "salesReport.tickets", row)).toBe(false);
  });

  it("only manages sales rectifications in APP GESTION with the required state and permission", () => {
    const invoice = {
      __documentId: "invoice-1",
      __documentType: "FACTURA_VENTA",
      __documentStatus: "PAGADO"
    };
    const draft = {
      __documentId: "rectification-1",
      __documentType: "RECTIFICATIVA_VENTA",
      __documentStatus: "BORRADOR"
    };
    const management = { permissions: ["APP_GESTION_ACCESS", "INVOICES_WRITE"] as UserSession["permissions"] };

    expect(canManageSalesInvoiceRectification("gestion", management, "salesReport.invoices", invoice)).toBe(true);
    expect(canManageSalesInvoiceRectification("gestion", management, "salesReport.invoices", draft)).toBe(true);
    expect(canManageSalesInvoiceRectification("venta", management, "salesReport.invoices", invoice)).toBe(false);
    expect(canManageSalesInvoiceRectification("gestion", { permissions: ["INVOICES_WRITE"] }, "salesReport.invoices", invoice)).toBe(false);
    expect(canManageSalesInvoiceRectification("gestion", management, "salesReport.invoices", {
      ...invoice,
      __documentStatus: "BORRADOR"
    })).toBe(false);
    expect(canManageSalesInvoiceRectification("gestion", management, "salesReport.invoices", {
      ...draft,
      __documentStatus: "CONFIRMADO"
    })).toBe(false);
    expect(canManageSalesInvoiceRectification("gestion", management, "salesReport.inputInvoices", invoice)).toBe(false);
    expect(canConfirmSalesInvoiceRectification({ permissions: ["APP_GESTION_ACCESS", "INVOICES_WRITE"] })).toBe(false);
    expect(canConfirmSalesInvoiceRectification({ permissions: ["APP_GESTION_ACCESS", "INVOICES_CONFIRM"] })).toBe(true);
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

  it("renders a selected report as embedded APP GESTION content without duplicate navigation", () => {
    const html = renderToStaticMarkup(
      <SalesReportScreen
        app="gestion"
        locale="es"
        session={session}
        terminalContext={terminalContext}
        onBack={vi.fn()}
        onLogout={vi.fn()}
        onLocaleChange={vi.fn()}
        embedded
        initialReport="salesReport.tickets"
      />
    );

    expect(html).toContain('class="report-screen gestion-embedded-module report-density-comfortable"');
    expect(html).toContain("Tickets");
    expect(html).not.toContain('class="report-nav"');
    expect(html).not.toContain('class="report-brand-back"');
    expect(html).not.toContain('class="report-user-button"');
  });

  it("renders ticket sales with the daily accounting buckets from the authoritative backend report", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.startsWith("/commercial-reports/daily")) {
        return Promise.resolve({
          storeId: "store-1",
          date: "2026-07-16",
          invoiced: "100.00",
          ticketSales: "40.00",
          collectedCurrent: "70.00",
          newPending: "70.00",
          priorDebtCollected: "20.00",
          cashInflow: "90.00"
        });
      }
      if (path === "/tickets") {
        return Promise.resolve([]);
      }
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
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
    expect(screen.getByText("40.00€")).toBeVisible();
    expect(screen.getAllByText("70.00€")).toHaveLength(2);
    expect(screen.getByText("20.00€")).toBeVisible();
    expect(screen.getByText("90.00€")).toBeVisible();
    expect(screen.getByText("Ventas de tickets")).toBeVisible();
  });

  it("shows translated authoritative loading/error and retries without local totals", async () => {
    let dailyAttempts = 0;
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.startsWith("/commercial-reports/daily")) {
        dailyAttempts += 1;
        return dailyAttempts === 1
          ? Promise.reject(new Error("sin red"))
          : Promise.resolve({
            storeId: "store-1", date: "2026-07-16", invoiced: "1.00", ticketSales: "0.00", collectedCurrent: "0.00",
            newPending: "1.00", priorDebtCollected: "0.00", cashInflow: "0.00"
          });
      }
      if (path === "/tickets") {
        return Promise.resolve([]);
      }
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });
    render(<SalesReportScreen app="venta" locale="es" session={{ ...session, accessToken: "token" }} terminalContext={terminalContext} request={request} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("sin red");
    expect(screen.queryByText("Total facturado")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar informe diario" }));
    expect((await screen.findAllByText("1.00€")).length).toBeGreaterThanOrEqual(2);
    expect(request.mock.calls.filter(([path]) => String(path).startsWith("/commercial-reports/daily"))).toHaveLength(2);
  });

  it("keeps reports read-only and leaves document creation to operational modules", () => {
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
    expect(html).not.toContain("Crear documento de compra");
    expect(html).not.toContain("Crear documento");
    expect(html).not.toContain("Entrada almacén");
  });

  it("shows warehouse report load failures and retries instead of silently rendering an empty report", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/tickets") {
        return Promise.resolve([]);
      }
      if (path.startsWith("/warehouse-outputs")) {
        return Promise.reject(new Error("almacén no disponible"));
      }
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ username: "warehouse", displayName: "ALMACÉN", permissions: ["GESTION_ALMACEN"], accessToken: "token" }}
        terminalContext={terminalContext}
        request={request}
        initialReport="salesReport.warehouseOutputs"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudieron cargar los datos del informe.");
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    await waitFor(() => {
      expect(request.mock.calls.filter(([path]) => String(path).startsWith("/warehouse-outputs"))).toHaveLength(2);
    });
  });

  it("closes the print actions before opening the browser print dialog", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/tickets") return Promise.resolve([]);
      if (path.startsWith("/warehouse-outputs")) return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });
    const previousDesktop = window.tpvDesktop;
    window.tpvDesktop = undefined;
    localStorage.clear();
    const printSpy = vi.spyOn(window, "print").mockImplementation(() => {
      expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    });

    try {
      render(
        <SalesReportScreen
          app="venta"
          locale="es"
          session={{
            username: "warehouse",
            displayName: "ALMACÉN",
            permissions: ["GESTION_ALMACEN"],
            accessToken: "token"
          }}
          terminalContext={terminalContext}
          request={request}
          initialReport="salesReport.warehouseOutputs"
          onBack={vi.fn()}
          onLocaleChange={vi.fn()}
        />
      );

      await screen.findByRole("heading", { name: "Salida almacén" });
      fireEvent.click(screen.getByRole("button", { name: "Imprimir" }));
      expect(screen.getByRole("menu")).toBeInTheDocument();
      fireEvent.click(screen.getByRole("menuitem", { name: "Imprimir PDF" }));

      expect(printSpy).toHaveBeenCalledTimes(1);
      expect(screen.queryByRole("menu")).not.toBeInTheDocument();
    } finally {
      printSpy.mockRestore();
      window.tpvDesktop = previousDesktop;
      localStorage.clear();
    }
  });

  it("does not select the first report row until the user interacts with it", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/warehouses") {
        return Promise.resolve([{ id: "warehouse-1", name: "GENERAL" }]);
      }
      if (path.startsWith("/warehouse-inputs")) {
        return Promise.resolve({
          items: [{
            id: "input-1",
            number: "ENT-NO-SELECT",
            date: "2026-07-28",
            warehouseId: "warehouse-1",
            origin: "PRUEBA",
            lines: [{ quantity: 1, purchaseUnitPrice: 4.2, purchaseTotal: 4.2 }]
          }],
          nextCursor: null,
          hasMore: false
        });
      }
      if (path === "/tickets") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{
          username: "warehouse",
          displayName: "ALMACÉN",
          permissions: ["GESTION_ALMACEN"],
          accessToken: "token"
        }}
        terminalContext={terminalContext}
        request={request}
        initialReport="salesReport.inputWarehouse"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    const row = (await screen.findByText("ENT-NO-SELECT")).closest("tr");
    expect(row).not.toHaveClass("selected");
    expect(row).toHaveAttribute("aria-selected", "false");
    fireEvent.click(row!);
    expect(row).toHaveClass("selected");
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
