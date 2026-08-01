// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, expect, it, vi } from "vitest";
import { createTranslator } from "../i18n/LocalizedMessages";
import {
  filterPurchaseDocuments,
  purchaseDocumentsPath,
  PurchaseDocumentsPanel,
  type PurchaseDocumentQueryView
} from "./PurchaseDocumentsPanel";

const documents: PurchaseDocumentQueryView[] = [{
  id: "purchase-1",
  type: "ALBARAN_COMPRA",
  status: "CONFIRMADO",
  number: "AC-001-26-000001",
  externalNumber: "PROV-554",
  date: "2026-07-30",
  supplierId: "supplier-1",
  supplierName: "Proveedor Pruebas SL",
  warehouseId: "warehouse-1",
  warehouseName: "GENERAL",
  base: 100,
  tax: 21,
  total: 121,
  paid: 20,
  pending: 101,
  lines: [{
    id: "line-1",
    productId: "product-1",
    lineType: "PRODUCT",
    position: 0,
    code: "P-001",
    name: "Producto de prueba",
    quantity: 2,
    unitPrice: 50,
    discount: 0,
    taxRegime: "IVA",
    taxPercentage: 21,
    base: 100,
    tax: 21,
    total: 121
  }]
}];

describe("PurchaseDocumentsPanel", () => {
  it("builds independent endpoints and filters by supplier, number and status", () => {
    expect(purchaseDocumentsPath("deliveryNote"))
      .toBe("/purchase-documents?type=ALBARAN_COMPRA");
    expect(purchaseDocumentsPath("invoice"))
      .toBe("/purchase-documents?type=FACTURA_COMPRA");
    expect(filterPurchaseDocuments(documents, "proveedor pruebas", "CONFIRMADO")).toEqual(documents);
    expect(filterPurchaseDocuments(documents, "PROV-554", "")).toEqual(documents);
    expect(filterPurchaseDocuments(documents, "", "BORRADOR")).toEqual([]);
  });

  it("loads delivery notes and opens a read-only consultation", async () => {
    const request = vi.fn().mockResolvedValue(documents);
    await act(async () => {
      render(
        <PurchaseDocumentsPanel
          mode="deliveryNote"
          token="warehouse-token"
          locale="es"
          t={createTranslator("es")}
          request={request}
        />
      );
    });

    await waitFor(() => expect(screen.getByText("AC-001-26-000001")).toBeInTheDocument());
    expect(request).toHaveBeenCalledWith(
      "/purchase-documents?type=ALBARAN_COMPRA",
      { token: "warehouse-token" }
    );

    await userEvent.click(screen.getByText("AC-001-26-000001"));
    await userEvent.click(screen.getByRole("button", { name: "Consultar documento" }));

    expect(screen.getByRole("dialog", { name: "Consulta de documento de compra" })).toBeVisible();
    expect(screen.getByText("Producto de prueba")).toBeVisible();
    expect(screen.getAllByText(/121,00/).length).toBeGreaterThan(0);

    await userEvent.keyboard("{Escape}");

    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.getByText("AC-001-26-000001").closest("tr")).toHaveFocus();
  });
});
