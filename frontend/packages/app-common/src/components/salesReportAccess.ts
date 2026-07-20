import type { UserSession } from "../types";

export const outputReports = [
  "salesReport.dailySales",
  "salesReport.tickets",
  "salesReport.deliveryNotes",
  "salesReport.invoices",
  "salesReport.warehouseOutputs"
];

export const inputReports = [
  "salesReport.inputInvoices",
  "salesReport.inputDeliveryNotes",
  "salesReport.inputWarehouse"
];

export const allReports = [...outputReports, ...inputReports];

export function salesReportAccess(session: Pick<UserSession, "permissions">) {
  const admin = session.permissions.includes("ADMIN");
  const sales = admin
    || session.permissions.includes("GESTION_VENTAS")
    || session.permissions.includes("GESTION_CUENTAS");
  const purchases = admin
    || session.permissions.includes("GESTION_PRODUCTO")
    || session.permissions.includes("GESTION_ALMACEN")
    || session.permissions.includes("GESTION_CUENTAS");
  const warehouse = admin || session.permissions.includes("GESTION_ALMACEN");
  const purchaseWrite = admin
    || session.permissions.includes("GESTION_PRODUCTO")
    || session.permissions.includes("GESTION_ALMACEN");
  return { sales, purchases, purchaseWrite, warehouse };
}

export function visibleSalesReports(session: Pick<UserSession, "permissions">) {
  const access = salesReportAccess(session);
  const visibleOutputReports = [
    ...(access.sales ? outputReports.filter((report) => report !== "salesReport.warehouseOutputs") : []),
    ...(access.warehouse ? ["salesReport.warehouseOutputs"] : [])
  ];
  const visibleInputReports = [
    ...(access.purchases ? ["salesReport.inputInvoices", "salesReport.inputDeliveryNotes"] : []),
    ...(access.warehouse ? ["salesReport.inputWarehouse"] : [])
  ];
  return { visibleOutputReports, visibleInputReports, all: [...visibleOutputReports, ...visibleInputReports] };
}

export function isPurchaseDocumentReport(report: string) {
  return report === "salesReport.inputInvoices" || report === "salesReport.inputDeliveryNotes";
}
