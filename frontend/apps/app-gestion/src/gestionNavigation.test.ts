import { describe, expect, it } from "vitest";
import { createTranslator, messages } from "@tpverp/app-common";
import {
  gestionNavigationDestinationCount,
  gestionNavigationGroupCount,
  gestionNavigationGroups,
} from "./gestionNavigation";

describe("APP GESTIÓN navigation registry", () => {
  it("keeps the approved 11 groups and 53 destinations in order", () => {
    expect(gestionNavigationGroupCount).toBe(11);
    expect(gestionNavigationDestinationCount).toBe(53);
    expect(gestionNavigationGroups.map((group) => group.key)).toEqual([
      "overview", "control-alerts", "cash", "customer-documents", "supplier-documents",
      "products", "warehouse", "third-parties", "fiscal", "security", "configuration",
    ]);
    expect(gestionNavigationGroups[8].destinations).toHaveLength(1);
    expect(gestionNavigationGroups[0].direct).toBe(true);
    expect(gestionNavigationGroups[1].direct).toBe(true);
    expect(gestionNavigationGroups[8].direct).toBe(true);
    expect(gestionNavigationGroups[8].destinations[0].lock).toBe("FISCAL");
    expect(gestionNavigationGroups[9].destinations).toHaveLength(4);
    expect(gestionNavigationGroups[4].destinations.map((item) => item.key)).toEqual([
      "stock.warehouse.purchaseDeliveryNotes", "stock.warehouse.purchaseInvoices",
      "salesReport.inputDeliveryNotes", "salesReport.inputInvoices", "stock.warehouse.goodsCheck",
    ]);
    expect(gestionNavigationGroups[6].destinations.slice(-2).map((item) => item.key)).toEqual([
      "salesReport.inputWarehouse", "salesReport.warehouseOutputs",
    ]);
    expect(gestionNavigationGroups[10].destinations.map((item) => item.key)).toEqual([
      "paymentMethods", "taxes", "memberLoyaltySettings", "internalEan", "documentPrintSettings",
      "documentTemplates", "voucherSettings", "licenses", "productManagement", "customerManagement",
      "supplierManagement",
    ]);
    expect(new Set(gestionNavigationGroups.flatMap((group) => group.destinations.map((item) => item.key))).size)
      .toBe(gestionNavigationDestinationCount);
    expect(gestionNavigationGroups.every((group) => group.icon)).toBe(true);
    expect(gestionNavigationGroups.flatMap((group) => group.destinations).every((item) => item.icon)).toBe(true);
    const labelKeys = gestionNavigationGroups.flatMap((group) => [
      group.labelKey,
      ...group.destinations.map((item) => item.labelKey),
    ]);
    expect((["es", "en", "zh"] as const).flatMap((locale) =>
      labelKeys.filter((key) => messages[locale][key] === undefined).map((key) => `${locale}:${key}`)
    )).toEqual([]);
  });

  it("uses the approved Spanish names without changing the underlying screen titles", () => {
    const t = createTranslator("es");
    expect(gestionNavigationGroups.map((group) => t(group.labelKey))).toEqual([
      "Resumen", "Alertas de control", "Caja", "Documentos cliente", "Documentos proveedor",
      "Productos", "Almacén", "Terceros", "Control fiscal", "Seguridad", "Configuración",
    ]);
    expect(gestionNavigationGroups[2].destinations.map((item) => t(item.labelKey))).toEqual([
      "Ventas diarias", "Efectivo en caja", "Cierres de caja",
    ]);
    expect(gestionNavigationGroups[4].destinations.map((item) => t(item.labelKey))).toEqual([
      "Gestión de albaranes de entrada",
      "Gestión de facturas de entrada",
      "Informe de albaranes de entrada",
      "Informe de facturas de entrada",
      "Comprobación de pedido",
    ]);
    expect(gestionNavigationGroups[6].destinations.map((item) => t(item.labelKey)).slice(-4)).toEqual([
      "Entrada de almacén — operativa",
      "Salida de almacén — operativa",
      "Informe de entradas de almacén",
      "Informe de salidas de almacén",
    ]);
  });
});
