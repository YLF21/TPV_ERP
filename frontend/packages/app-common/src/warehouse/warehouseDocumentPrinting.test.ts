import { describe, expect, it, vi } from "vitest";
import type { HardwareBridge } from "../hardware/hardware";
import {
  buildWarehouseA4Document,
  printWarehouseA4Document,
  renderWarehouseA4PreviewHtml
} from "./warehouseDocumentPrinting";

const labels = {
  terminal: "Terminal",
  description: "Descripción",
  quantity: "Cantidad",
  unitPrice: "Precio unitario",
  base: "Base",
  tax: "Impuesto",
  taxIncluded: "Impuesto incluido",
  yes: "Sí",
  no: "No",
  mixed: "Mixto",
  total: "Total",
  documentNumber: "Documento",
  warehouse: "Almacén",
  partner: "Proveedor",
  discount: "Descuento",
  print: "Imprimir",
  close: "Cerrar"
};

describe("warehouse document printing", () => {
  it("builds a complete A4 document independently from the visible table", () => {
    const request = buildWarehouseA4Document({
      title: "Entrada de almacén",
      documentNumber: "ENT-2026-000001",
      storeName: "TIENDA DEMO",
      terminalCode: "SERVIDOR",
      issuedAt: "2026-07-26",
      warehouse: "GENERAL",
      partnerLabel: "Proveedor",
      partner: "PROVEEDOR PRUEBAS SL",
      discountPercent: 5,
      notes: ["Entrega completa"],
      lines: [
        { code: "P-1", name: "Producto uno", quantity: 12, unitPrice: 2.5, total: 30 }
      ],
      subtotal: 30,
      total: 28.5,
      labels
    });

    expect(request.documentType).toBe("REPORT");
    expect(request.lines).toEqual([
      expect.objectContaining({ name: "P-1 - Producto uno", quantity: 12, price: 2.5, total: 30 })
    ]);
    expect(request.metadata).toEqual(expect.arrayContaining([
      { label: "Documento", value: "ENT-2026-000001" },
      { label: "Almacén", value: "GENERAL" },
      { label: "Proveedor", value: "PROVEEDOR PRUEBAS SL" },
      { label: "Descuento", value: "5%" }
    ]));
    expect(request.notes).toEqual(["Entrega completa"]);
    expect(request.total).toBe(28.5);
  });

  it("renders an isolated, escaped A4 preview with print controls", () => {
    const request = buildWarehouseA4Document({
      title: "<script>alert(1)</script>",
      locale: "en",
      storeName: "Tienda & Demo",
      terminalCode: "SERVIDOR",
      issuedAt: "2026-07-26",
      lines: [{ name: "Producto <uno>", quantity: 1, unitPrice: 2, total: 2 }],
      subtotal: 2,
      total: 2,
      labels
    });

    const html = renderWarehouseA4PreviewHtml(request);

    expect(html).toContain("@page");
    expect(html).toContain('<html lang="en">');
    expect(html).toContain("window.print()");
    expect(html).toContain("&lt;script&gt;alert(1)&lt;/script&gt;");
    expect(html).toContain("Tienda &amp; Demo");
    expect(html).not.toContain("<script>alert(1)</script>");
  });

  it("prints through the configured desktop hardware bridge", async () => {
    const config = { a4PrinterName: "A4 oficina" };
    const getHardwareConfig = vi.fn().mockResolvedValue(config);
    const printA4Document = vi.fn().mockResolvedValue({ ok: true });
    const bridge = { getHardwareConfig, printA4Document } as unknown as HardwareBridge;
    const request = buildWarehouseA4Document({
      title: "Salida",
      storeName: "Tienda",
      terminalCode: "Caja 1",
      issuedAt: "2026-07-26",
      lines: [],
      subtotal: 0,
      total: 0,
      labels
    });

    await expect(printWarehouseA4Document(request, bridge)).resolves.toEqual({ ok: true });
    expect(getHardwareConfig).toHaveBeenCalledOnce();
    expect(printA4Document).toHaveBeenCalledWith(request, config);
  });
});
