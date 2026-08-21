// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, describe, expect, it, vi } from "vitest";
import {
  buildDocumentReports,
  buildReportColumnDefinitions,
  buildTicketReportCounters,
  canCancelSelectedTicket,
  canConfirmSalesInvoiceRectification,
  canConvertSelectedTicketToInvoice,
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
import { defaultHardwareConfig } from "../hardware/hardware";

const session: UserSession = {
  username: "admin",
  displayName: "ADMIN",
  permissions: ["GESTION_VENTAS", "GESTION_CUENTAS"]
};

const terminalContext: TerminalContext = {
  storeName: "Tienda Principal",
  terminalCode: "01"
};

const noSavedVisualizationPreferences = vi.fn().mockResolvedValue([]);

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
  localStorage.clear();
});

describe("SalesReportScreen", () => {
  it("only enables cancellation for a selected confirmed ticket and an authorized role", () => {
    const confirmedTicket = {
      __documentId: "ticket-1",
      __documentStatus: "CONFIRMADO",
      ticket: "T-001",
      status: "salesReport.status.confirmed",
      invoiced: "",
      total: "10.00"
    };
    expect(canCancelSelectedTicket(
      { permissions: ["GESTION_CUENTAS"] },
      "salesReport.tickets",
      confirmedTicket
    )).toBe(true);
    expect(canCancelSelectedTicket(
      { permissions: ["VENTA"] },
      "salesReport.tickets",
      { ...confirmedTicket, __documentStatus: "ANULADO" }
    )).toBe(false);
    expect(canCancelSelectedTicket(
      { permissions: ["GESTION_PRODUCTO"] },
      "salesReport.tickets",
      confirmedTicket
    )).toBe(false);
  });

  it("only enables ticket conversion for a selected confirmed ticket and an authorized role", () => {
    const confirmedTicket = {
      __documentId: "ticket-1",
      __documentStatus: "CONFIRMADO",
      ticket: "T-001",
      status: "salesReport.status.confirmed",
      invoiced: "",
      total: "10.00"
    };
    expect(canConvertSelectedTicketToInvoice(
      { permissions: ["GESTION_VENTAS"] },
      "salesReport.tickets",
      confirmedTicket
    )).toBe(true);
    expect(canConvertSelectedTicketToInvoice(
      { permissions: ["GESTION_VENTAS"] },
      "salesReport.tickets",
      { ...confirmedTicket, __documentStatus: "BORRADOR" }
    )).toBe(false);
    expect(canConvertSelectedTicketToInvoice(
      { permissions: ["GESTION_CUENTAS"] },
      "salesReport.tickets",
      confirmedTicket
    )).toBe(false);
    expect(canConvertSelectedTicketToInvoice(
      { permissions: ["GESTION_VENTAS"] },
      "salesReport.invoices",
      confirmedTicket
    )).toBe(false);
  });

  it("enables the conversion button after selecting a confirmed ticket in the report", async () => {
    const now = new Date();
    const today = [
      now.getFullYear(),
      String(now.getMonth() + 1).padStart(2, "0"),
      String(now.getDate()).padStart(2, "0")
    ].join("-");
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/sales/operation-security") {
        return Promise.resolve({
          storeId: "store-1",
          version: 1,
          operations: [{
            code: "CONVERT_TICKET_TO_INVOICE",
            category: "TICKET",
            shortcuts: ["F12"],
            permissions: ["GESTION_VENTAS"],
            defaultRequirePermission: true,
            defaultRequirePassword: false,
            requirePermission: true,
            requirePassword: false,
            customized: false
          }, {
            code: "CANCEL_TICKET",
            category: "TICKET",
            shortcuts: ["F11"],
            permissions: ["GESTION_VENTAS"],
            defaultRequirePermission: true,
            defaultRequirePassword: true,
            requirePermission: true,
            requirePassword: true,
            customized: false
          }]
        });
      }
      if (path.startsWith("/document-reports/tickets")) {
        return Promise.resolve({ items: [{
          id: "ticket-1",
          tipo: "TICKET",
          estado: "CONFIRMADO",
          numero: "T-001",
          numTicket: "T-001",
          fecha: today,
          total: "12.10",
          effectiveTotal: "12.10",
          customerCode: "C-0005",
          customerName: "Cliente Cinco",
          lifecycleStatus: "CONFIRMED"
        }, {
          id: "ticket-returned",
          tipo: "TICKET",
          estado: "CONFIRMADO",
          numero: "T-RETURNED",
          fecha: today,
          total: "1000000.00",
          effectiveTotal: "0.00",
          invoiceNumber: "FV-RETURNED",
          lifecycleStatus: "RETURNED"
        }, {
          id: "ticket-invoiced",
          tipo: "TICKET",
          estado: "CONFIRMADO",
          numero: "T-INVOICED",
          fecha: today,
          total: "20.00",
          effectiveTotal: "20.00",
          invoiceNumber: "FV-ACTIVE",
          lifecycleStatus: "INVOICED"
        }, {
          id: "ticket-cancelled",
          tipo: "TICKET",
          estado: "ANULADO",
          numero: "T-CANCELLED",
          fecha: today,
          total: "50.00",
          effectiveTotal: "0.00",
          lifecycleStatus: "CANCELLED"
        }], nextCursor: null, hasMore: false });
      }
      if (path === "/warehouses") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    const { container } = render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={terminalContext}
        initialReport="salesReport.tickets"
        request={request}
        loadVisualizationPreferences={noSavedVisualizationPreferences}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    const cancelButton = screen.getByRole("button", { name: "Anular ticket" });
    fireEvent.click(screen.getByRole("button", { name: "Más acciones" }));
    const convertButton = screen.getByRole("menuitem", { name: "Convertir ticket a factura" });
    expect(convertButton).toBeDisabled();
    expect(cancelButton).toBeDisabled();
    await waitFor(() => expect(container.querySelector("tbody tr")).not.toBeNull());
    expect(screen.getByText("Tickets facturados: 1")).toBeVisible();
    expect(screen.getByText("Tickets anulados: 1")).toBeVisible();
    expect(screen.getByText(/Total:.*32,10/)).toBeVisible();
    expect(screen.getByText("C-0005")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Mostrar nombre del cliente" }));
    expect(screen.getByText("Cliente Cinco")).toBeVisible();
    expect(screen.getByRole("button", { name: "Mostrar código del cliente" })).toBeVisible();
    expect(localStorage.getItem("tpv-erp:venta:user:admin:ticket-customer-display")).toBe("name");
    expect(container.querySelector('th[data-column-key="total"]')).toHaveClass("report-column-numeric");
    expect(container.querySelector('td[data-column-key="total"]')).toHaveClass("report-column-numeric");
    fireEvent.click(container.querySelector("tbody tr")!);
    await waitFor(() => expect(convertButton).toBeEnabled());
    expect(cancelButton).toBeEnabled();
  });

  it("counts active invoiced and cancelled tickets without treating full returns as invoiced", () => {
    expect(buildTicketReportCounters([
      { status: "salesReport.status.invoiced" },
      { status: "salesReport.status.partiallyReturned" },
      { status: "salesReport.status.returned" },
      { status: "salesReport.status.ticketCancelled" }
    ])).toEqual({ invoiced: 2, cancelled: 1 });
  });

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

  it("maps ticket customer, invoice lifecycle and real refund methods without product count", () => {
    const reports = buildDocumentReports(
      [{
        id: "ticket-sale",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "001-260810-00005",
        fecha: "2026-08-10",
        total: "100.00",
        customerCode: "C-0005",
        customerName: "Cliente Cinco",
        invoiceNumber: "FV-2026-00042",
        lifecycleStatus: "PARTIALLY_RETURNED",
        paymentMethods: ["EFECTIVO", "COMPENSACION_DEVOLUCION", "TARJETA", "OTRO"]
      }, {
        id: "ticket-return",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "001-260811-00001",
        fecha: "2026-08-11",
        total: "-25.00",
        lifecycleStatus: "RETURNED",
        refundMethods: ["CASH", "EXCHANGE", "TRANSFER"]
      }, {
        id: "ticket-pending",
        tipo: "TICKET",
        estado: "PARCIAL",
        numero: "001-260811-00002",
        fecha: "2026-08-11",
        total: "50.00",
        paymentMethods: ["EFECTIVO"]
      }, {
        id: "ticket-discount",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "001-260811-00003",
        fecha: "2026-08-11",
        total: "10.00",
        paymentMethods: ["DESCUENTO"]
      }, {
        id: "ticket-exchange-only",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "001-260811-00004",
        fecha: "2026-08-11",
        total: "-10.00",
        lifecycleStatus: "RETURNED",
        refundMethods: ["EXCHANGE"]
      }],
      [], [], [], [], [], session, terminalContext
    );

    const report = reports["salesReport.tickets"];
    expect(report?.availableAttributes).not.toContain("productCount");
    expect(report?.defaultVisibleAttributes).not.toContain("productCount");
    expect(report?.rows[0]).toEqual(expect.objectContaining({
      customer: "C-0005",
      customerName: "Cliente Cinco",
      invoiced: "FV-2026-00042",
      status: "salesReport.status.partiallyReturned",
      payment: "EFECTIVO + TARJETA"
    }));
    expect(report?.rows[1]).toEqual(expect.objectContaining({
      status: "salesReport.status.returned",
      payment: "EFECTIVO + TRANSFERENCIA"
    }));
    expect(report?.rows[2]).toEqual(expect.objectContaining({
      payment: "EFECTIVO + salesReport.payment.pending"
    }));
    expect(report?.rows[3]).toEqual(expect.objectContaining({ payment: "DESCUENTO" }));
    expect(report?.rows[4]).toEqual(expect.objectContaining({ payment: "—" }));
  });

  it("distinguishes member balance from return credit in ticket reports", () => {
    const reports = buildDocumentReports(
      [{
        id: "ticket-member-balance",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "T-SALDO-1",
        fecha: "2026-08-21",
        total: "45.00",
        saldoSocio: "5.00",
        paymentMethods: ["SALDO_MIEMBRO"]
      }, {
        id: "ticket-member-balance-internal",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "T-SALDO-2",
        fecha: "2026-08-21",
        total: "30.00",
        paymentMethods: ["MEMBER_BALANCE"]
      }, {
        id: "ticket-return-credit",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "T-ABONO-1",
        fecha: "2026-08-21",
        total: "-20.00",
        refundMethods: ["CREDITO_DEVOLUCION"]
      }, {
        id: "ticket-return-credit-internal",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "T-ABONO-2",
        fecha: "2026-08-21",
        total: "-10.00",
        refundMethods: ["MEMBER_CREDIT"]
      }],
      [],
      [],
      [],
      [],
      [],
      session,
      terminalContext
    );

    const rows = reports["salesReport.tickets"]?.rows ?? [];
    expect(rows).toHaveLength(4);
    expect(rows[0]).toEqual(expect.objectContaining({
      memberBalance: "5.00",
      payment: "salesReport.payment.memberBalance"
    }));
    expect(rows[1]).toEqual(expect.objectContaining({
      payment: "salesReport.payment.memberBalance"
    }));
    expect(rows[2]).toEqual(expect.objectContaining({
      payment: "salesReport.payment.returnCredit"
    }));
    expect(rows[3]).toEqual(expect.objectContaining({
      payment: "salesReport.payment.returnCredit"
    }));
  });

  it("shows Descuento only for the F11 checkout method", () => {
    const reports = buildDocumentReports(
      [{
        id: "ticket-product-discount",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "T-DISCOUNT-PRODUCT",
        fecha: "2026-08-11",
        total: "90.00",
        descuentoGlobal: "10.00",
        paymentMethods: ["EFECTIVO"]
      }, {
        id: "ticket-f11-discount",
        tipo: "TICKET",
        estado: "CONFIRMADO",
        numero: "T-DISCOUNT-F11",
        fecha: "2026-08-11",
        total: "10.00",
        paymentMethods: ["DESCUENTO"]
      }],
      [], [], [], [], [], session, terminalContext
    );

    expect(reports["salesReport.tickets"]?.rows[0]?.payment).toBe("EFECTIVO");
    expect(reports["salesReport.tickets"]?.rows[1]?.payment).toBe("DESCUENTO");
  });

  it("does not offer invoice conversion or cancellation for invoiced and returned tickets", () => {
    const invoiced = {
      __documentId: "ticket-invoiced",
      __documentStatus: "CONFIRMADO",
      ticket: "T-1",
      status: "salesReport.status.invoiced",
      invoiced: "FV-1",
      total: "100.00"
    };
    const returned = {
      __documentId: "ticket-returned",
      __documentStatus: "CONFIRMADO",
      ticket: "T-2",
      status: "salesReport.status.returned",
      invoiced: "",
      total: "-25.00"
    };

    expect(canConvertSelectedTicketToInvoice(session, "salesReport.tickets", invoiced)).toBe(false);
    expect(canCancelSelectedTicket(session, "salesReport.tickets", invoiced)).toBe(false);
    expect(canConvertSelectedTicketToInvoice(session, "salesReport.tickets", returned)).toBe(false);
    expect(canCancelSelectedTicket(session, "salesReport.tickets", returned)).toBe(false);
  });

  it("uses historical document attribution instead of the active session", () => {
    const reports = buildDocumentReports(
      [{
        id: "ticket-1",
      tipo: "TICKET",
      numero: "T-1",
      fecha: "2026-07-18",
      estado: "ANULADO",
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
      __documentStatus: "ANULADO",
      ticket: "T-1",
      status: "salesReport.status.ticketCancelled",
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

    expect(html).toContain('class="report-nav"');
    expect(html).toContain('class="module-nav-back-icon"');
    expect(html).toContain('class="module-nav-item-icon"');
    expect(html).toContain('class="report-menu-icon"');
    expect(html).toContain('class="report-brand-back"');
    expect(html).toContain("APP VENTA");
    expect(html).toContain("Salidas");
    expect(html).toContain("Entradas");
    expect(html).toContain('class="sales-activity-toolbar"');
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

  it.each([
    "salesReport.deliveryNotes",
    "salesReport.invoices",
    "salesReport.warehouseOutputs",
    "salesReport.inputInvoices",
    "salesReport.inputDeliveryNotes",
    "salesReport.inputWarehouse"
  ])("uses the full-height shared table layout for %s", (reportKey) => {
    const html = renderToStaticMarkup(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ ...session, permissions: ["ADMIN"] }}
        terminalContext={terminalContext}
        initialReport={reportKey}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(html).toContain('class="report-data"');
    expect(html).toContain('class="report-table-region"');
    expect(html).toContain('class="report-total-row"');
    expect(html).toContain(`class="report-table" data-report-key="${reportKey}"`);
    expect(html).toContain('style="width:100%;min-width:');
    expect(html).toContain("Más acciones");
    expect(html).toContain('aria-keyshortcuts="F5"');
    expect(html).toContain('aria-keyshortcuts="F6"');
    expect(html).toContain('aria-keyshortcuts="F7"');
  });

  it("loads the next report page automatically without a load-more button", async () => {
    const today = new Date();
    const todayIso = [
      today.getFullYear(),
      String(today.getMonth() + 1).padStart(2, "0"),
      String(today.getDate()).padStart(2, "0")
    ].join("-");
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.startsWith("/document-reports/invoices")) {
        if (path.includes("cursor=invoice-page-2")) {
          return Promise.resolve({
            items: [{
              id: "invoice-2",
              tipo: "FACTURA_VENTA",
              estado: "CONFIRMADO",
              numero: "FV-AUTO-2",
              fecha: todayIso,
              total: "24.20"
            }],
            nextCursor: null,
            hasMore: false
          });
        }
        return Promise.resolve({ items: [], nextCursor: "invoice-page-2", hasMore: true });
      }
      if (path === "/tickets" || path === "/warehouses") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={terminalContext}
        request={request}
        loadVisualizationPreferences={noSavedVisualizationPreferences}
        initialReport="salesReport.invoices"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    expect(await screen.findByText("FV-AUTO-2")).toBeVisible();
    expect(request).toHaveBeenCalledWith(
      expect.stringContaining("cursor=invoice-page-2"),
      { token: "token" }
    );
    expect(screen.queryByRole("button", { name: "Cargar más" })).not.toBeInTheDocument();
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

  it("renders the reconciled daily sales summary from the new sales activity API", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.startsWith("/sales-activity/daily")) {
        return Promise.resolve({
          storeId: "store-1",
          companyName: "EMPRESA PRUEBA",
          storeCode: "001",
          date: "2026-07-16",
          netSalesTotal: "125.00",
          paymentMethods: [
            { method: "EFECTIVO", operationCount: 2, amount: "95.00" },
            { method: "PENDIENTE", operationCount: 1, amount: "30.00" }
          ],
          counts: { sales: 3, returns: 2, cancelled: 4, pending: 5 },
          users: [{
            userId: "user-1", userName: "ANA", netSalesTotal: "125.00",
            paymentMethods: [
              { method: "EFECTIVO", operationCount: 2, amount: "95.00" },
              { method: "PENDIENTE", operationCount: 1, amount: "30.00" }
            ],
            counts: { sales: 3, returns: 2, cancelled: 4, pending: 5 }
          }]
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
        loadVisualizationPreferences={noSavedVisualizationPreferences}
        onBack={vi.fn()}
        onLogout={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      expect.stringMatching(/^\/sales-activity\/daily\?date=\d{4}-\d{2}-\d{2}$/),
      { token: "token" }
    ));
    expect((await screen.findAllByText(/125,00/)).length).toBeGreaterThanOrEqual(2);
    expect(screen.getAllByText("Efectivo: (2)").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Pendiente de cobro: (1)").length).toBeGreaterThan(0);
    expect(screen.queryByText("Tarjeta")).not.toBeInTheDocument();
    expect(screen.queryByText("T-001 → FV-001")).not.toBeInTheDocument();
    expect(screen.getAllByText("Ventas").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Devoluciones").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Anulados").length).toBeGreaterThan(0);
    expect(screen.getAllByText("Pendientes").length).toBeGreaterThan(0);
    expect(screen.getByText("ANA")).toBeVisible();
  });

  it("uses one query date and reloads the daily report when it changes", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.startsWith("/sales-activity/daily")) {
        return Promise.resolve({
          storeId: "store-1", companyName: "EMPRESA", storeCode: "001",
          date: path.split("date=")[1], netSalesTotal: "0.00",
          paymentMethods: [], counts: { sales: 0, returns: 0, cancelled: 0, pending: 0 }, users: []
        });
      }
      if (path === "/tickets") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={terminalContext}
        request={request}
        loadVisualizationPreferences={noSavedVisualizationPreferences}
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    const dateInput = await screen.findByLabelText("Fecha de consulta");
    expect(screen.getAllByLabelText("Fecha de consulta")).toHaveLength(1);
    fireEvent.change(dateInput, { target: { value: "2026-07-05" } });
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/sales-activity/daily?date=2026-07-05", { token: "token" }
    ));
  });

  it("shows the new daily report error and retries without calculating local totals", async () => {
    let dailyAttempts = 0;
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.startsWith("/sales-activity/daily")) {
        dailyAttempts += 1;
        return dailyAttempts === 1
          ? Promise.reject(new Error("sin red"))
          : Promise.resolve({
            storeId: "store-1", companyName: "EMPRESA", storeCode: "001",
            date: "2026-07-16", netSalesTotal: "1.00",
            paymentMethods: [{ method: "PENDIENTE", operationCount: 1, amount: "1.00" }],
            counts: { sales: 1, returns: 0, cancelled: 0, pending: 1 }, users: []
          });
      }
      if (path === "/tickets") {
        return Promise.resolve([]);
      }
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });
    render(<SalesReportScreen app="venta" locale="es" session={{ ...session, accessToken: "token" }} terminalContext={terminalContext} request={request} loadVisualizationPreferences={noSavedVisualizationPreferences} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
    expect(await screen.findByRole("alert")).toHaveTextContent("sin red");
    expect(screen.queryByText("Total de ventas netas")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Reintentar" }));
    expect((await screen.findAllByText(/1,00/)).length).toBeGreaterThanOrEqual(2);
    expect(request.mock.calls.filter(([path]) => String(path).startsWith("/sales-activity/daily"))).toHaveLength(2);
  });

  it("renders the sales document ledger with bottom period filters and Excel-style totals", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/sales-activity/filter-options") {
        return Promise.resolve({ earliestDate: "2024-01-01", currentDate: "2026-08-16" });
      }
      if (path.startsWith("/sales-activity/documents")) {
        return Promise.resolve({
          items: [{
            id: "document-1", date: "2026-08-16", occurredAt: "2026-08-16T10:15:00Z",
            ticketNumber: "T-001", invoiceNumber: "FV-001", userName: "ANA",
            paymentMethods: ["EFECTIVO"], kind: "SALE", status: "CONFIRMADO", total: "25.00"
          }, {
            id: "document-2", date: "2026-08-16", occurredAt: "2026-08-16T11:00:00Z",
            ticketNumber: "", invoiceNumber: "FV-002", userName: "BRUNO",
            paymentMethods: ["TARJETA"], kind: "SALE", status: "PAGADO", total: "75.00"
          }],
          nextCursor: null, hasMore: false, ticketCount: 1, invoiceCount: 2,
          total: "100.00", dateFrom: "2026-08-16", dateTo: "2026-08-16"
        });
      }
      if (path === "/tickets") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    render(<SalesReportScreen app="venta" locale="es" session={{ ...session, accessToken: "token" }} terminalContext={terminalContext} request={request} loadVisualizationPreferences={noSavedVisualizationPreferences} initialReport="salesReport.salesDocuments" onBack={vi.fn()} onLocaleChange={vi.fn()} />);

    expect(await screen.findByRole("columnheader", { name: "Número de ticket" })).toBeVisible();
    expect(screen.getByRole("columnheader", { name: "Número de factura" })).toBeVisible();
    expect(screen.getByText("T-001")).toBeVisible();
    expect(screen.getByText("FV-002")).toBeVisible();
    expect(screen.getByText("Tickets: 1")).toBeVisible();
    expect(screen.getByText("Facturas: 2")).toBeVisible();
    expect(screen.getByText(/100,00/)).toBeVisible();
    const dock = screen.getByLabelText("Periodo seleccionado");
    expect(within(dock).getByRole("button", { name: "Hoy" })).toBeVisible();
    expect(within(dock).getByRole("button", { name: "Ayer" })).toBeVisible();
    expect(within(dock).getByRole("button", { name: "Semana actual" })).toBeVisible();
    expect(within(dock).getByRole("button", { name: "Periodo personalizado" })).toBeVisible();
  });

  it("shows direct output shortcuts and document visualization buttons without a print dialog", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/sales-activity/filter-options") {
        return Promise.resolve({ earliestDate: "2026-01-01", currentDate: "2026-08-16" });
      }
      if (path.startsWith("/sales-activity/documents")) {
        return Promise.resolve({
          items: [], nextCursor: null, hasMore: false, ticketCount: 0, invoiceCount: 0,
          total: "0.00", dateFrom: "2026-08-16", dateTo: "2026-08-16"
        });
      }
      if (path === "/tickets") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    render(<SalesReportScreen app="venta" locale="es" session={{ ...session, accessToken: "token" }} terminalContext={terminalContext} request={request} loadVisualizationPreferences={noSavedVisualizationPreferences} initialReport="salesReport.salesDocuments" onBack={vi.fn()} onLocaleChange={vi.fn()} />);

    expect(await screen.findByRole("button", { name: "Por día" })).toHaveAttribute("aria-pressed", "true");
    expect(screen.getByRole("button", { name: "Por documento" })).toHaveAttribute("aria-pressed", "false");
    expect(screen.getByRole("button", { name: "Imprimir" })).toHaveAttribute("aria-keyshortcuts", "F5");
    expect(screen.getByRole("button", { name: "Excel" })).toHaveAttribute("aria-keyshortcuts", "F6");
    expect(screen.getByRole("button", { name: "PDF" })).toHaveAttribute("aria-keyshortcuts", "F7");
    expect(screen.queryByText("Opciones de impresión")).not.toBeInTheDocument();
  });

  it("exports Excel with F6 and PDF with F7", async () => {
    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    const previousDesktop = window.tpvDesktop;
    Object.defineProperty(window, "tpvDesktop", {
      configurable: true,
      writable: true,
      value: { reports: { saveFile } }
    });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(new Uint8Array([1, 2, 3]), { status: 200 })
    );
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.includes("/sales-activity/daily/render")) {
        return Promise.resolve({
          renderedPdf: { contentType: "application/pdf", base64: window.btoa("pdf") },
          renderedImage: null
        });
      }
      if (path.startsWith("/sales-activity/daily")) {
        return Promise.resolve({
          storeId: "store-1", companyName: "EMPRESA", storeCode: "001", date: "2026-08-16",
          netSalesTotal: "0.00", paymentMethods: [],
          counts: { sales: 0, returns: 0, cancelled: 0, pending: 0 }, users: []
        });
      }
      if (path === "/tickets") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    try {
      render(<SalesReportScreen app="venta" locale="es" session={{ ...session, accessToken: "token" }} terminalContext={terminalContext} request={request} loadVisualizationPreferences={noSavedVisualizationPreferences} onBack={vi.fn()} onLocaleChange={vi.fn()} />);
      await screen.findByText("EMPRESA");

      fireEvent.keyDown(window, { key: "F6" });
      await waitFor(() => expect(fetchSpy).toHaveBeenCalledWith(
        expect.stringContaining("/sales-activity/daily/excel?date="),
        { headers: { Authorization: "Bearer token" } }
      ));
      await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(1));

      fireEvent.keyDown(window, { key: "F7" });
      await waitFor(() => expect(request).toHaveBeenCalledWith(
        expect.stringMatching(/^\/sales-activity\/daily\/render\?date=.*&format=A4$/),
        { token: "token" }
      ));
      await waitFor(() => expect(saveFile).toHaveBeenCalledTimes(2));
      expect(saveFile.mock.calls[1]?.[0]).toEqual(expect.objectContaining({
        defaultFileName: expect.stringMatching(/^resumen-ventas-.*\.pdf$/)
      }));
    } finally {
      fetchSpy.mockRestore();
      Object.defineProperty(window, "tpvDesktop", { configurable: true, writable: true, value: previousDesktop });
    }
  });

  it("prints directly on the ticket printer with F5 using the selected document visualization", async () => {
    const printTicket = vi.fn().mockResolvedValue({ ok: true });
    const previousDesktop = window.tpvDesktop;
    Object.defineProperty(window, "tpvDesktop", {
      configurable: true,
      writable: true,
      value: {
        hardware: {
          getHardwareConfig: vi.fn().mockResolvedValue(defaultHardwareConfig),
          printTicket
        }
      }
    });
    const request = vi.fn().mockImplementation((path: string) => {
      if (path.includes("/sales-activity/documents/render")) {
        return Promise.resolve({
          renderedPdf: { contentType: "application/pdf", base64: window.btoa("pdf") },
          renderedImage: { contentType: "image/png", base64: window.btoa("png") }
        });
      }
      if (path === "/sales-activity/filter-options") {
        return Promise.resolve({ earliestDate: "2026-01-01", currentDate: "2026-08-16" });
      }
      if (path.startsWith("/sales-activity/documents")) {
        return Promise.resolve({
          items: [], nextCursor: null, hasMore: false, ticketCount: 0, invoiceCount: 0,
          total: "15.50", dateFrom: "2026-08-16", dateTo: "2026-08-16"
        });
      }
      if (path === "/tickets") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    try {
      render(<SalesReportScreen app="venta" locale="es" session={{ ...session, accessToken: "token" }} terminalContext={terminalContext} request={request} loadVisualizationPreferences={noSavedVisualizationPreferences} initialReport="salesReport.salesDocuments" onBack={vi.fn()} onLocaleChange={vi.fn()} />);
      fireEvent.click(await screen.findByRole("button", { name: "Por documento" }));
      fireEvent.keyDown(window, { key: "F5" });

      await waitFor(() => expect(request).toHaveBeenCalledWith(
        expect.stringMatching(/\/sales-activity\/documents\/render\?.*grouping=DOCUMENT.*format=TICKET_80/),
        { token: "token" }
      ));
      await waitFor(() => expect(printTicket).toHaveBeenCalledTimes(1));
      expect(printTicket).toHaveBeenCalledWith(
        expect.objectContaining({ requireRenderedDocument: true, total: 15.5 }),
        expect.objectContaining({
          documentPrintRoutes: expect.arrayContaining([
            expect.objectContaining({ documentType: "TICKET", printerTarget: "TICKET_PRINTER", paperSize: "TICKET_80" })
          ])
        })
      );
    } finally {
      Object.defineProperty(window, "tpvDesktop", { configurable: true, writable: true, value: previousDesktop });
    }
  });

  it("sorts sales documents from the header arrows without exposing a Columns button", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/sales-activity/filter-options") {
        return Promise.resolve({ earliestDate: "2026-01-01", currentDate: "2026-08-16" });
      }
      if (path.startsWith("/sales-activity/documents")) {
        return Promise.resolve({
          items: [{
            id: "document-1", date: "2026-08-16", occurredAt: "2026-08-16T10:15:00Z",
            ticketNumber: "T-001", invoiceNumber: "", userName: "ANA",
            paymentMethods: ["EFECTIVO"], kind: "SALE", status: "CONFIRMADO", total: "25.00"
          }, {
            id: "document-2", date: "2026-08-16", occurredAt: "2026-08-16T11:15:00Z",
            ticketNumber: "T-002", invoiceNumber: "", userName: "BRUNO",
            paymentMethods: ["TARJETA"], kind: "SALE", status: "CONFIRMADO", total: "75.00"
          }],
          nextCursor: null, hasMore: false, ticketCount: 2, invoiceCount: 0,
          total: "100.00", dateFrom: "2026-08-16", dateTo: "2026-08-16"
        });
      }
      if (path === "/tickets") return Promise.resolve([]);
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });

    const { container } = render(<SalesReportScreen app="venta" locale="es" session={{ ...session, accessToken: "token" }} terminalContext={terminalContext} request={request} loadVisualizationPreferences={noSavedVisualizationPreferences} initialReport="salesReport.salesDocuments" onBack={vi.fn()} onLocaleChange={vi.fn()} />);

    expect(await screen.findByRole("button", { name: "Modificar ancho Número de ticket" })).toBeVisible();
    expect(screen.queryByRole("button", { name: "Columnas" })).not.toBeInTheDocument();
    const totalSort = screen.getByRole("button", { name: "Total Ordenar" });
    expect(totalSort).toHaveAttribute("data-sort-direction", "none");

    fireEvent.click(totalSort);
    expect(totalSort).toHaveAttribute("data-sort-direction", "asc");
    expect(container.querySelector(".sales-documents-table tbody tr:first-child")).toHaveTextContent("25,00");

    fireEvent.click(totalSort);
    expect(totalSort).toHaveAttribute("data-sort-direction", "desc");
    expect(container.querySelector(".sales-documents-table tbody tr:first-child")).toHaveTextContent("75,00");
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
        loadVisualizationPreferences={noSavedVisualizationPreferences}
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

  it("exposes direct F5/F6/F7 actions and disables F5 without a selected document", async () => {
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/tickets") return Promise.resolve([]);
      if (path.startsWith("/warehouse-outputs")) return Promise.resolve([]);
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
        loadVisualizationPreferences={noSavedVisualizationPreferences}
        initialReport="salesReport.warehouseOutputs"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    await screen.findByRole("heading", { name: "Salida almacén" });
    expect(screen.getByRole("button", { name: "Imprimir" })).toBeDisabled();
    expect(screen.getByRole("button", { name: "Excel" })).toHaveAttribute("aria-keyshortcuts", "F6");
    expect(screen.getByRole("button", { name: "PDF" })).toHaveAttribute("aria-keyshortcuts", "F7");
    expect(screen.queryByText("F5: selecciona una fila · F6/F7: líneas visibles")).not.toBeInTheDocument();
  });

  it("does not select the first warehouse row automatically and opens its lines on double click", async () => {
    const now = new Date();
    const today = [
      now.getFullYear(),
      String(now.getMonth() + 1).padStart(2, "0"),
      String(now.getDate()).padStart(2, "0")
    ].join("-");
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/warehouses") {
        return Promise.resolve([{ id: "warehouse-1", name: "GENERAL" }]);
      }
      if (path.startsWith("/warehouse-inputs")) {
        return Promise.resolve({
          items: [{
            id: "input-1",
            number: "ENT-NO-SELECT",
            date: today,
            warehouseId: "warehouse-1",
            origin: "PRUEBA",
            lines: [{
              productId: "P-INPUT",
              productCode: "CAF-001",
              productName: "Café de prueba",
              quantity: 1,
              purchaseUnitPrice: 4.2,
              purchaseTotal: 4.2
            }]
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
    fireEvent.doubleClick(row!);
    expect(await screen.findByRole("heading", { name: "ENT-NO-SELECT" })).toBeVisible();
    expect(screen.getByText("CAF-001")).toBeVisible();
    expect(screen.getByText("Café de prueba")).toBeVisible();
    expect(screen.queryByText("P-INPUT")).not.toBeInTheDocument();
    expect(screen.queryByText("input-1")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Exportar Excel" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Imprimir copia" })).toBeVisible();
    expect(request).not.toHaveBeenCalledWith(expect.stringMatching(/^\/documents\//), expect.anything());
  });

  it("shows readable product data and copy actions for warehouse outputs", async () => {
    const now = new Date();
    const today = [
      now.getFullYear(),
      String(now.getMonth() + 1).padStart(2, "0"),
      String(now.getDate()).padStart(2, "0")
    ].join("-");
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/warehouses") {
        return Promise.resolve([{ id: "warehouse-1", name: "GENERAL" }]);
      }
      if (path.startsWith("/warehouse-outputs")) {
        return Promise.resolve({
          items: [{
            id: "output-1",
            number: "SAL-READABLE",
            date: today,
            warehouseId: "warehouse-1",
            destination: "CONSUMO INTERNO",
            lines: [{
              productId: "P-OUTPUT",
              productCode: "AGUA-001",
              productName: "Agua mineral",
              quantity: 2,
              saleUnitPrice: 5,
              saleTotal: 10
            }]
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
        initialReport="salesReport.warehouseOutputs"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    const row = (await screen.findByText("SAL-READABLE")).closest("tr");
    fireEvent.doubleClick(row!);

    expect(await screen.findByRole("heading", { name: "SAL-READABLE" })).toBeVisible();
    expect(screen.getByText("AGUA-001")).toBeVisible();
    expect(screen.getByText("Agua mineral")).toBeVisible();
    expect(screen.queryByText("P-OUTPUT")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Exportar Excel" })).toBeVisible();
    expect(screen.getByRole("button", { name: "Imprimir copia" })).toBeVisible();
  });

  it("opens the document contents by double-clicking a delivery-note row", async () => {
    const today = new Date();
    const todayIso = [
      today.getFullYear(),
      String(today.getMonth() + 1).padStart(2, "0"),
      String(today.getDate()).padStart(2, "0")
    ].join("-");
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/tickets" || path === "/warehouses") return Promise.resolve([]);
      if (path.startsWith("/document-reports/delivery-notes")) {
        return Promise.resolve({
          items: [{
            id: "delivery-1",
            tipo: "ALBARAN_VENTA",
            estado: "CONFIRMADO",
            numero: "AV-001",
            fecha: todayIso,
            clienteCodigo: "C-001",
            total: "60.50"
          }],
          nextCursor: null,
          hasMore: false
        });
      }
      if (path === "/documents/delivery-1/detail") {
        return Promise.resolve({
          id: "delivery-1",
          type: "ALBARAN_VENTA",
          status: "CONFIRMADO",
          number: "AV-001",
          date: todayIso,
          base: "50.00",
          tax: "10.50",
          discount: "0.00",
          total: "60.50",
          lines: [{
            id: "line-1",
            position: 1,
            code: "P-001",
            name: "Producto del albarán",
            quantity: "2.000",
            unitPrice: "25.00",
            discount: "0.00",
            taxRegime: "IVA",
            taxPercentage: "21.00",
            total: "60.50"
          }]
        });
      }
      if (path === "/documents/delivery-1/print-copy") {
        return Promise.resolve({
          documentType: "ALBARAN_VENTA",
          documentNumber: "AV-001",
          issueDate: todayIso,
          lines: [{ name: "Producto del albarán", quantity: 2, unitPrice: 25, total: 60.5 }],
          baseTotal: 50,
          taxTotal: 10.5,
          total: 60.5,
          renderedPdf: { contentType: "application/pdf", base64: window.btoa("jasper-pdf") }
        });
      }
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });
    const previewWindow = {
      opener: window,
      location: { replace: vi.fn() },
      document: { open: vi.fn(), write: vi.fn(), close: vi.fn() },
      setTimeout: vi.fn((callback: () => void) => callback()),
      focus: vi.fn(),
      print: vi.fn(),
      close: vi.fn()
    };
    vi.spyOn(window, "open").mockReturnValue(previewWindow as unknown as Window);
    const createObjectUrl = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:jasper-copy");

    const { container } = render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={terminalContext}
        request={request}
        loadVisualizationPreferences={noSavedVisualizationPreferences}
        initialReport="salesReport.deliveryNotes"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    const row = (await screen.findByText("AV-001")).closest("tr");
    expect(container.querySelector('th[data-column-key="total"]')).toHaveClass("report-column-numeric");
    expect(container.querySelector('td[data-column-key="total"]')).toHaveClass("report-column-numeric");
    fireEvent.doubleClick(row!);

    expect(await screen.findByRole("heading", { name: "AV-001" })).toBeVisible();
    expect(await screen.findByText("Producto del albarán")).toBeVisible();
    expect(request).toHaveBeenCalledWith("/documents/delivery-1/detail", { token: "token" });
    fireEvent.click(screen.getByRole("button", { name: "Imprimir copia" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/documents/delivery-1/print-copy", { token: "token" }));
    expect(createObjectUrl).toHaveBeenCalledWith(expect.objectContaining({ type: "application/pdf" }));
    expect(previewWindow.location.replace).toHaveBeenCalledWith("blob:jasper-copy");
    expect(previewWindow.document.write).not.toHaveBeenCalled();
    expect(previewWindow.print).not.toHaveBeenCalled();

    const saveFile = vi.fn().mockResolvedValue({ ok: true });
    Object.defineProperty(window, "tpvDesktop", {
      configurable: true,
      value: { reports: { saveFile } }
    });
    const fetchSpy = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(new Uint8Array([1, 2, 3]), { status: 200 })
    );
    fireEvent.click(screen.getByRole("button", { name: "Exportar Excel" }));
    await waitFor(() => expect(fetchSpy).toHaveBeenCalledWith(
      expect.stringContaining("/excel/documents/delivery-1/export"),
      { headers: { Authorization: "Bearer token" } }
    ));
    await waitFor(() => expect(saveFile).toHaveBeenCalledWith(expect.objectContaining({
      defaultFileName: "AV-001.xlsx"
    })));
    fetchSpy.mockRestore();
    createObjectUrl.mockRestore();
    Reflect.deleteProperty(window, "tpvDesktop");
  });

  it("prints a cancelled ticket using its Jasper cancellation receipt", async () => {
    const today = new Date();
    const todayIso = [
      today.getFullYear(),
      String(today.getMonth() + 1).padStart(2, "0"),
      String(today.getDate()).padStart(2, "0")
    ].join("-");
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/warehouses") return Promise.resolve([]);
      if (path.startsWith("/document-reports/tickets")) {
        return Promise.resolve({ items: [{
          id: "cancelled-ticket-1",
          tipo: "TICKET",
          estado: "ANULADO",
          numero: "T-CANCELLED",
          numTicket: "T-CANCELLED",
          fecha: todayIso,
          confirmadoEn: `${todayIso}T10:00:00Z`,
          total: "6.05",
          effectiveTotal: "0.00",
          lifecycleStatus: "CANCELLED",
          payments: [{ method: "EFECTIVO", amount: "6.05" }]
        }], nextCursor: null, hasMore: false });
      }
      if (path === "/documents/cancelled-ticket-1/detail") {
        return Promise.resolve({
          id: "cancelled-ticket-1",
          type: "TICKET",
          status: "ANULADO",
          number: "T-CANCELLED",
          date: todayIso,
          base: "5.00",
          tax: "1.05",
          discount: "0.00",
          total: "6.05",
          lines: [{
            id: "line-1",
            position: 1,
            code: "P-001",
            name: "Producto anulado",
            quantity: "1.000",
            unitPrice: "5.00",
            discount: "0.00",
            taxRegime: "IVA",
            taxPercentage: "21.00",
            total: "6.05"
          }]
        });
      }
      if (path === "/tickets/cancelled-ticket-1/cancellation-receipt") {
        return Promise.resolve({
          operationId: "operation-1",
          originalTicketNumber: "T-CANCELLED",
          originalIssuedAt: `${todayIso}T10:00:00Z`,
          cancelledAt: `${todayIso}T10:05:00Z`,
          total: "6.05",
          reason: "Error de cobro",
          operatorUsername: "ADMIN",
          authorizerUsername: "ADMIN",
          delegated: false,
          payments: [{ method: "EFECTIVO", amount: "6.05" }],
          renderedPdf: { contentType: "application/pdf", base64: window.btoa("cancellation-pdf") },
          ticketRenderedImage: { contentType: "image/png", base64: window.btoa("cancellation-png") }
        });
      }
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });
    const previewWindow = {
      opener: window,
      location: { replace: vi.fn() },
      document: { open: vi.fn(), write: vi.fn(), close: vi.fn() },
      setTimeout: vi.fn((callback: () => void) => callback()),
      focus: vi.fn(),
      print: vi.fn(),
      close: vi.fn()
    };
    vi.spyOn(window, "open").mockReturnValue(previewWindow as unknown as Window);
    const createObjectUrl = vi.spyOn(URL, "createObjectURL")
      .mockReturnValue("blob:cancellation-receipt");

    const { container } = render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={terminalContext}
        request={request}
        loadVisualizationPreferences={noSavedVisualizationPreferences}
        initialReport="salesReport.tickets"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/document-reports/tickets?limit=500",
      { token: "token" }
    ));
    await waitFor(() => expect(screen.getAllByText("Líneas visibles: 1")).not.toHaveLength(0));
    const cancelledTicketRow = container.querySelector<HTMLTableRowElement>(
      'table[data-report-key="salesReport.tickets"] tbody tr'
    );
    expect(cancelledTicketRow).not.toBeNull();
    fireEvent.click(cancelledTicketRow!);
    const printSelectedButton = screen.getByRole("button", { name: "Imprimir" });
    expect(printSelectedButton).toBeEnabled();
    fireEvent.click(printSelectedButton);

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/cancelled-ticket-1/cancellation-receipt",
      { token: "token" }
    ));
    expect(createObjectUrl).toHaveBeenCalledWith(expect.objectContaining({ type: "application/pdf" }));
    expect(previewWindow.location.replace).toHaveBeenCalledWith("blob:cancellation-receipt");
    expect(request).not.toHaveBeenCalledWith("/tickets/cancelled-ticket-1/print", expect.anything());
    createObjectUrl.mockRestore();
  });

  it("opens the original ticket from an invoice converted from that ticket", async () => {
    const today = new Date();
    const todayIso = [
      today.getFullYear(),
      String(today.getMonth() + 1).padStart(2, "0"),
      String(today.getDate()).padStart(2, "0")
    ].join("-");
    const request = vi.fn().mockImplementation((path: string) => {
      if (path === "/tickets" || path === "/warehouses") return Promise.resolve([]);
      if (path.startsWith("/document-reports/invoices")) {
        return Promise.resolve({
          items: [{
            id: "invoice-1",
            tipo: "FACTURA_VENTA",
            estado: "PAGADO",
            numero: "FV-001",
            fecha: todayIso,
            clienteCodigo: "C-001",
            total: "60.50",
            pendiente: "0.00"
          }],
          nextCursor: null,
          hasMore: false
        });
      }
      if (path === "/documents/invoice-1/detail") {
        return Promise.resolve({
          id: "invoice-1",
          type: "FACTURA_VENTA",
          status: "PAGADO",
          number: "FV-001",
          date: todayIso,
          base: "50.00",
          tax: "10.50",
          discount: "0.00",
          total: "60.50",
          originTicket: { id: "ticket-1", number: "T-001" },
          lines: []
        });
      }
      if (path === "/documents/ticket-1/detail") {
        return Promise.resolve({
          id: "ticket-1",
          type: "TICKET",
          status: "PAGADO",
          number: "T-001",
          date: todayIso,
          base: "50.00",
          tax: "10.50",
          discount: "0.00",
          total: "60.50",
          originTicket: null,
          lines: [{
            id: "ticket-line-1",
            position: 1,
            code: "P-001",
            name: "Producto del ticket original",
            quantity: "1.000",
            unitPrice: "50.00",
            discount: "0.00",
            taxRegime: "IVA",
            taxPercentage: "21.00",
            total: "60.50"
          }]
        });
      }
      if (path === "/tickets/ticket-1/print") {
        return Promise.resolve({
          documentId: "ticket-1",
          documentNumber: "T-001",
          issuedAt: `${todayIso}T12:00:00Z`,
          lines: [{ name: "Producto del ticket original", quantity: 1, price: 50, total: 60.5 }],
          payments: [{ method: "EFECTIVO", amount: 60.5 }],
          baseTotal: 50,
          taxTotal: 10.5,
          total: 60.5,
          ticketRenderedPdf: {
            contentType: "application/pdf",
            base64: "JVBERi0xLjc="
          }
        });
      }
      return Promise.resolve({ items: [], nextCursor: null, hasMore: false });
    });
    const previewWindow = {
      opener: window,
      location: { replace: vi.fn() },
      document: { open: vi.fn(), write: vi.fn(), close: vi.fn() },
      setTimeout: vi.fn((callback: () => void) => callback()),
      focus: vi.fn(),
      print: vi.fn(),
      close: vi.fn()
    };
    vi.spyOn(window, "open").mockReturnValue(previewWindow as unknown as Window);
    const createObjectUrl = vi.spyOn(URL, "createObjectURL").mockReturnValue("blob:ticket-copy");

    render(
      <SalesReportScreen
        app="venta"
        locale="es"
        session={{ ...session, accessToken: "token" }}
        terminalContext={terminalContext}
        request={request}
        loadVisualizationPreferences={noSavedVisualizationPreferences}
        initialReport="salesReport.invoices"
        onBack={vi.fn()}
        onLocaleChange={vi.fn()}
      />
    );

    fireEvent.doubleClick((await screen.findByText("FV-001")).closest("tr")!);
    fireEvent.click(await screen.findByRole("button", { name: "Ver ticket original" }));

    expect(await screen.findByRole("heading", { name: "T-001" })).toBeVisible();
    expect(await screen.findByText("Producto del ticket original")).toBeVisible();
    expect(request).toHaveBeenCalledWith("/documents/ticket-1/detail", { token: "token" });
    expect(screen.queryByRole("button", { name: "Ver ticket original" })).not.toBeInTheDocument();

    fireEvent.click(screen.getByRole("button", { name: "Imprimir copia" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/ticket-1/print",
      { token: "token" }
    ));
    expect(createObjectUrl).toHaveBeenCalledWith(expect.objectContaining({ type: "application/pdf" }));
    expect(previewWindow.location.replace).toHaveBeenCalledWith("blob:ticket-copy");
    expect(previewWindow.document.write).not.toHaveBeenCalled();
    expect(previewWindow.print).not.toHaveBeenCalled();
    createObjectUrl.mockRestore();
  });

  it("builds V67-compatible report table definitions with sensible defaults", () => {
    expect(reportTableKey("salesReport.tickets")).toBe("reports.salesReport.tickets");
    expect(buildReportColumnDefinitions({
      availableAttributes: ["date", "customerName", "unknown", "total"],
      defaultVisibleAttributes: ["date", "total"],
      rows: [],
      totals: {}
    })).toEqual([
      { key: "date", defaultWidth: 112, minWidth: 120, defaultVisible: true },
      { key: "customerName", defaultWidth: 200, minWidth: 176, defaultVisible: false },
      { key: "unknown", defaultWidth: 144, minWidth: undefined, defaultVisible: false },
      { key: "total", defaultWidth: 112, minWidth: 120, defaultVisible: true }
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
