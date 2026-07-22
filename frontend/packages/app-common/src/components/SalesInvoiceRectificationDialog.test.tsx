// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { fireEvent, render, screen, waitFor } from "@testing-library/react";
import { beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import { SalesInvoiceRectificationDialog } from "./SalesInvoiceRectificationDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));

const request = vi.mocked(apiRequest);
const source = {
  id: "invoice-1",
  type: "FACTURA_VENTA",
  status: "PAGADO",
  number: "FV-001-26-000001",
  issueDate: "2026-07-21",
  customerId: "customer-1",
  warehouseId: "warehouse-1",
  globalDiscount: "0.00",
  base: "100.00",
  tax: "21.00",
  total: "121.00",
  lines: [{
    id: "line-1",
    type: "PRODUCT",
    code: "P-1",
    name: "Producto fiscal",
    originalQuantity: "2.000",
    availableStockQuantity: "2.000",
    unitPrice: "60.50",
    discount: "0.00",
    taxesIncluded: true,
    taxRegime: "IVA",
    taxPercentage: "21.00",
    base: "100.00",
    tax: "21.00",
    total: "121.00"
  }]
};

function rectification(status: "BORRADOR" | "CONFIRMADO") {
  return {
    document: {
      id: "rectification-1",
      estado: status,
      numero: status === "CONFIRMADO" ? "RV-001-26-000001" : null,
      base: "-50.00",
      impuesto: "-10.50",
      total: "-60.50"
    },
    original: source,
    fiscalType: "R1",
    method: "I",
    reason: "GOODS_RETURN",
    detail: "Devolución justificada del producto",
    affectsStock: true,
    lines: [{
      id: "rectification-line-1",
      originalLineId: "line-1",
      type: "PRODUCT",
      code: "P-1",
      name: "Producto fiscal",
      quantity: "-1.000",
      unitPrice: "60.50",
      base: "-50.00",
      tax: "-10.50",
      total: "-60.50"
    }]
  };
}

describe("SalesInvoiceRectificationDialog", () => {
  beforeEach(() => {
    request.mockReset();
  });

  it("previews, persists and separately confirms a linked corrective draft", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/invoices/invoice-1/rectification-source") return source as never;
      if (path === "/invoices/invoice-1/rectifications/preview") return rectification("BORRADOR") as never;
      if (path === "/invoices/invoice-1/rectifications") return rectification("BORRADOR") as never;
      if (path === "/invoices/rectifications/rectification-1/confirm") return rectification("CONFIRMADO") as never;
      throw new Error(`Unexpected path ${path}`);
    });
    const changed = vi.fn();

    render(<SalesInvoiceRectificationDialog
      token="token"
      locale="es"
      documentId="invoice-1"
      continueDraft={false}
      canConfirm
      t={createTranslator("es")}
      onClose={vi.fn()}
      onChanged={changed}
    />);

    await screen.findByText("FV-001-26-000001");
    fireEvent.change(screen.getByPlaceholderText("Explica el hecho que obliga a rectificar la factura…"), {
      target: { value: "Devolución justificada del producto" }
    });
    fireEvent.change(screen.getByLabelText("Cantidad diferencia Producto fiscal"), {
      target: { value: "-1" }
    });
    fireEvent.click(screen.getByRole("button", { name: "Revisar borrador" }));

    await screen.findByText("Revisión fiscal previa");
    expect(request).toHaveBeenCalledWith("/invoices/invoice-1/rectifications/preview", {
      token: "token",
      method: "POST",
      body: {
        reason: "GOODS_RETURN",
        detail: "Devolución justificada del producto",
        lines: [{ originalLineId: "line-1", quantity: "-1", unitPrice: "60.50" }]
      }
    });

    fireEvent.click(screen.getByRole("button", { name: "Crear borrador vinculado" }));
    await screen.findByText("Borrador vinculado");
    expect(changed).toHaveBeenCalledTimes(1);

    fireEvent.click(screen.getByRole("button", { name: "Confirmar y emitir" }));
    await screen.findByText("Factura rectificativa emitida correctamente.");
    expect(screen.getByText("RV-001-26-000001")).toBeVisible();
    expect(changed).toHaveBeenCalledTimes(2);
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/invoices/rectifications/rectification-1/confirm",
      { token: "token", method: "POST" }
    ));
  });

  it("keeps confirmation unavailable without the confirmation permission", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/invoices/rectifications/rectification-1"
        || path === "/invoices/rectifications/rectification-1/preview") {
        return rectification("BORRADOR") as never;
      }
      if (path === "/invoices/invoice-1/rectifications/preview") return rectification("BORRADOR") as never;
      throw new Error(`Unexpected path ${path}`);
    });
    render(<SalesInvoiceRectificationDialog
      token="token"
      locale="es"
      documentId="rectification-1"
      continueDraft
      canConfirm={false}
      t={createTranslator("es")}
      onClose={vi.fn()}
      onChanged={vi.fn()}
    />);

    await screen.findByText("FV-001-26-000001");
    fireEvent.click(screen.getByRole("button", { name: "Revisar borrador" }));
    await screen.findByText("Revisión fiscal previa");
    fireEvent.click(screen.getByRole("button", { name: "Guardar cambios del borrador" }));
    await screen.findByText("Borrador vinculado");
    expect(screen.getByRole("button", { name: "Confirmar y emitir" })).toBeDisabled();
  });
});
