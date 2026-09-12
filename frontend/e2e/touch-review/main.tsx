import React from "react";
import { createRoot } from "react-dom/client";
import { SaleScreen } from "../../packages/app-common/src/components/SaleScreen";
import "../../packages/app-common/src/styles/tpv.css";

const products = [
  { id: "demo-1", code: "000101", barcode: "8410000000011", name: "PAPEL DE REGALO", salePrice: 1.5, taxId: "demo-tax", taxesIncluded: true, taxRegime: "IVA", taxPercentage: 21, productType: "UNIT" },
  { id: "demo-2", code: "000102", barcode: "8410000000028", name: "BOLSA DE REGALO", salePrice: 1, taxId: "demo-tax", taxesIncluded: true, taxRegime: "IVA", taxPercentage: 21, productType: "UNIT" },
  { id: "demo-weight", code: "000103", barcode: "8410000000035", name: "TOMATE A GRANEL", salePrice: 2.5, taxId: "demo-tax", taxesIncluded: true, taxRegime: "IVA", taxPercentage: 21, productType: "WEIGHT" },
];
const operationCodes = ["OPEN_CASH_DRAWER", "EDIT_CATALOG_PRODUCT", "CLOSE_CASH_SESSION", "CASH_MOVEMENT", "RETURN_TICKET", "CANCEL_TICKET", "CONVERT_TICKET_TO_INVOICE", "DELETE_PARKED_SALE", "MANUAL_RETURN_WITHOUT_TICKET", "TEMPORARY_NAME", "TEMPORARY_PRICE_CHANGE", "OPEN_PRICE_PRODUCT", "APPLY_SALE_DISCOUNT", "APPLY_CHECKOUT_DISCOUNT", "CREATE_PENDING_RECEIVABLE", "CREDIT_OVERRIDE"];

// Deliberate test double. ALL fetches are intercepted; writes/unknown routes fail
// locally. No real token, terminal credential, backend or database is contacted.
window.fetch = async (input, init) => {
  const path = new URL(typeof input === "string" ? input : input instanceof URL ? input.href : input.url, location.href).pathname;
  let result: unknown;
  if (path.endsWith("/products/sale")) result = products;
  else if (path.endsWith("/cash/sessions/prepare-sales")) result = { cashSessionRequired: false, open: true, session: null };
  else if (path.endsWith("/sales/operation-security")) result = { storeId: "demo-store", version: 1, operations: operationCodes.map(code => ({ code, category: "PRODUCT", permissions: ["ADMIN"], shortcuts: [], requirePermission: false, requirePassword: code === "TEMPORARY_PRICE_CHANGE", defaultRequirePermission: false, defaultRequirePassword: false, customized: false })) };
  else if (path.endsWith("/terminal-configuration/payment")) result = { rules: { cashEnabled: true, cardManualEnabled: false, integratedCardEnabled: false }, providerDescriptors: [], configuration: { enabled: false } };
  else if (path.endsWith("/pos/payment-sessions/active")) result = null;
  else if (path.endsWith("/customers/sale-options") || path.endsWith("/customers/sale-options/search")) result = [{ id: "demo-customer", clientId: "C-DEMO", fiscalName: "CLIENTE DE EJEMPLO CON NOMBRE COMERCIAL LARGO", documentNumber: "DOCUMENTO DEMO", activeMember: false, outstandingDebt: 25, overdueDebt: 10 }];
  else if (path.endsWith("/pos/sales/quote")) {
    const body = JSON.parse(String(init?.body ?? "{}"));
    const lineBreakdown = (body.lines ?? []).map((line: { productId: string; quantity: number; discount: number; openUnitPrice?: number }, index: number) => {
      const p = products.find(p => p.id === line.productId)!;
      const price = line.openUnitPrice ?? p.salePrice;
      const base = price * line.quantity;
      const promotionDiscount = location.search.includes("promotions") && index === 0
        ? Math.round(base * 0.2 * 100) / 100 : 0;
      const total = Math.round((base * (1 - line.discount / 100) - promotionDiscount) * 100) / 100;
      return { lineId: `product:${p.id}:${index}`, position: index + 1, productId: p.id, code: p.code, name: p.name, quantity: line.quantity,
        normalUnitPrice: p.salePrice, memberUnitPrice: null, baseUnitPrice: price, priceSource: "SALE", memberPriceSaving: 0, memberDiscountPercent: 0, memberDiscount: 0,
        manualDiscountPercent: line.discount, manualDiscount: base * line.discount / 100, promotionDiscount, couponDiscount: 0, taxIncluded: true, taxRegime: "IVA", taxPercent: 21,
        taxBase: total / 1.21, tax: total - total / 1.21, baseSubtotal: base, commercialSubtotal: total, roundingAdjustment: 0, finalSubtotal: total };
    });
    const total = lineBreakdown.reduce((sum: number, line: { finalSubtotal: number }) => sum + line.finalSubtotal, 0);
    result = { total, productTotal: total, pricingVersion: 2, quoteFingerprint: "visual-fixture", lineBreakdown,
      promotionPreview: { appliedPromotions: location.search.includes("promotions") && lineBreakdown.length ? [{ name: "Promoción de ejemplo · 20 %", discountAmount: lineBreakdown[0].promotionDiscount }] : [] } };
  } else return Response.json({ message: "Operación no disponible: escenario visual aislado" }, { status: 400 });
  return Response.json(result);
};

createRoot(document.getElementById("root")!).render(<SaleScreen app="venta" locale="es"
  session={{ username: "DEMO", displayName: "REVISIÓN AISLADA", permissions: ["ADMIN"], accessToken: "visual-fixture-not-a-token" }}
  terminalContext={{ storeName: "DATOS FICTICIOS · SIN CONEXIÓN A BD", terminalCode: "DEMO", terminalId: "demo-terminal" }}
  interfaceMode={location.search.includes("keyboard") ? "KEYBOARD" : "TOUCH"}
  onBack={() => location.reload()} onLocaleChange={() => {}}
  onOpenSalesDocumentWindow={() => window.alert("Acceso a Factura / albarán: simulado en esta revisión aislada.")} />);
