// @vitest-environment jsdom

import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { renderToStaticMarkup } from "react-dom/server";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { tableLayoutStorageKey, writeStoredTableLayout } from "./tableLayoutPreferences";
import {
  defaultStockSalesHistoryRange,
  filterStockSalesHistoryRows,
  stockSalesDocumentLabel,
  stockSalesHistoryPath,
  StockSalesHistoryPanel,
  type StockSalesHistoryRow
} from "./StockSalesHistoryPanel";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});

const apiRequestMock = vi.mocked(apiRequest);

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
  beforeEach(() => {
    apiRequestMock.mockReset();
    localStorage.clear();
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

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

  it("keeps product sales history headers, colgroup and body in the persisted interactive order", async () => {
    writeStoredTableLayout("venta", "ana", "stock.productSalesHistory", [
      { key: "warehouse", width: 160, visible: true },
      { key: "document", width: 180, visible: true },
      { key: "occurredAt", width: 160, visible: true },
      { key: "status", width: 130, visible: true },
      { key: "customer", width: 200, visible: true },
      { key: "quantity", width: 110, visible: true },
      { key: "unitPrice", width: 130, visible: true },
      { key: "discount", width: 110, visible: true },
      { key: "total", width: 130, visible: true },
      { key: "user", width: 150, visible: true },
      { key: "store", width: 160, visible: true }
    ], localStorage);
    apiRequestMock.mockResolvedValueOnce([row]);

    const { container } = render(
      <StockSalesHistoryPanel
        productId="product-1"
        productName="Cafe molido"
        locale="es"
        app="venta"
        username="ana"
        token="token"
        onClose={vi.fn()}
      />
    );

    await waitFor(() => expect(container.querySelectorAll("tbody tr").length).toBe(1));

    const headerKeys = () => Array.from(container.querySelectorAll<HTMLElement>("thead [data-column-key]"))
      .map((header) => header.dataset.columnKey);
    const rowValues = () => Array.from(container.querySelectorAll("tbody tr:first-child td"))
      .map((cell) => cell.textContent?.trim());

    expect(headerKeys().slice(0, 2)).toEqual(["warehouse", "document"]);
    expect(rowValues().slice(0, 2)).toEqual(["GENERAL", "TICKET T-0001"]);
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
    fireEvent.dragStart(container.querySelector('[data-column-key="user"]') as HTMLElement, { dataTransfer });
    fireEvent.dragOver(container.querySelector('[data-column-key="warehouse"]') as HTMLElement, { dataTransfer });
    fireEvent.drop(container.querySelector('[data-column-key="warehouse"]') as HTMLElement, { dataTransfer });
    expect(headerKeys()[0]).toBe("user");
    expect(rowValues()[0]).toBe("ADMIN");

    fireEvent.keyDown(container.querySelector('[data-column-key="user"]') as HTMLElement, {
      key: "ArrowRight",
      ctrlKey: true
    });
    expect(headerKeys().slice(0, 2)).toEqual(["warehouse", "user"]);
    expect(rowValues().slice(0, 2)).toEqual(["GENERAL", "ADMIN"]);

    const userHeader = container.querySelector('[data-column-key="user"]') as HTMLElement;
    fireEvent.keyDown(userHeader.querySelector("button") as HTMLButtonElement, { key: "ArrowRight" });
    const stored = JSON.parse(localStorage.getItem(
      tableLayoutStorageKey("venta", "ana", "stock.productSalesHistory")
    ) ?? "[]") as Array<{ key: string; width: number }>;
    expect(stored.map((column) => column.key).slice(0, 2)).toEqual(["warehouse", "user"]);
    expect(stored.find((column) => column.key === "user")?.width).toBe(158);
  });
});
