import { renderToStaticMarkup } from "react-dom/server";
import { describe, expect, it, vi } from "vitest";
import {
  defaultStockSalesHistoryRange,
  filterStockSalesHistoryRows,
  stockSalesDocumentLabel,
  stockSalesHistoryPath,
  StockSalesHistoryPanel,
  type StockSalesHistoryRow
} from "./StockSalesHistoryPanel";

const row: StockSalesHistoryRow = {
  documentId: "document-1",
  documentType: "TICKET",
  documentNumber: "T-0001",
  status: "CONFIRMADO",
  occurredAt: "2026-07-10T12:30:00Z",
  customerName: "Cliente Uno",
  quantity: 2,
  unitPrice: 4.5,
  discountPercent: 10,
  lineTotal: 8.1,
  userName: "ADMIN",
  storeName: "Principal",
  warehouseId: "warehouse-1",
  warehouseName: "GENERAL"
};

describe("StockSalesHistoryPanel", () => {
  it("builds the real product history endpoint with a report date range", () => {
    expect(stockSalesHistoryPath("product/1", "2026-07-01", "2026-07-10"))
      .toBe("/stock/products/product%2F1/sales-history?from=2026-07-01&to=2026-07-10");
    expect(defaultStockSalesHistoryRange(new Date(2026, 6, 10))).toEqual({
      from: "2026-06-11",
      to: "2026-07-10"
    });
  });

  it("filters returned rows by backend status and identifies the document", () => {
    expect(filterStockSalesHistoryRows([row, { ...row, documentId: "document-2", status: "ANULADO" }], "CONFIRMADO"))
      .toEqual([row]);
    expect(stockSalesDocumentLabel(row)).toBe("TICKET T-0001");
  });

  it("renders the detailed report table and date controls", () => {
    const html = renderToStaticMarkup(
      <StockSalesHistoryPanel
        productId="product-1"
        productName="Cafe molido"
        locale="es"
        token="token"
        onClose={vi.fn()}
      />
    );

    expect(html).toContain("Historial de ventas");
    expect(html).toContain('type="date"');
    expect(html).toContain('class="erp-select');
    expect(html).toContain('aria-haspopup="listbox"');
    expect(html).toContain('aria-label="Estado"');
    expect(html).not.toContain("<select");
    expect(html).toContain("Documento");
    expect(html).toContain("Precio unitario");
    expect(html).toContain("Almacén");
  });
});
