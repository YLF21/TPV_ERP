// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SaleProduct } from "./SaleScreen";
import { SaleProductConsultationDialog } from "./SaleProductConsultationDialog";

const product: SaleProduct = {
  id: "product-1",
  code: "2004461",
  barcode: "8435606744034",
  name: "NA0446 BL CARGADOR DE RED RÁPIDA JORWIN, 1 TYPE-C PD, CON CABLE TYPE-C",
  salePrice: 8.2,
  packageQuantity: 1,
  taxId: "tax-1",
  taxesIncluded: true,
  taxRegime: "IVA",
  taxPercentage: 21,
};

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("SaleProductConsultationDialog", () => {
  it("presents product data with a visual stock status", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({
      items: [{ product: { id: product.id }, stock: [{ quantity: -53 }] }],
    }), { status: 200, headers: { "Content-Type": "application/json" } })));

    const { container } = render(
      <SaleProductConsultationDialog products={[product]} initialProduct={product} onClose={vi.fn()} />,
    );

    expect(screen.getByText(product.name ?? "")).toBeTruthy();
    expect(screen.getByText("8,20")).toBeTruthy();
    expect(screen.getByLabelText("Producto sin imagen")).toBeTruthy();
    await waitFor(() => expect(screen.getByText("-53,00")).toBeTruthy());
    expect(container.querySelector(".sale-consultation-metrics .negative")).toBeTruthy();
  });

  it("keeps product search and selection functional", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    render(<SaleProductConsultationDialog products={[product]} onClose={vi.fn()} />);

    const input = screen.getByRole("textbox", { name: "Código, código de barras o nombre" });
    fireEvent.change(input, { target: { value: "2004461" } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(await screen.findByText(product.name ?? "")).toBeTruthy();
    expect(screen.getByText("Cantidad por paquete")).toBeTruthy();
  });
});
