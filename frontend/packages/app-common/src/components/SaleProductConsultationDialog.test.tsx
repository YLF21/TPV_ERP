// @vitest-environment jsdom
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import type { SaleProduct } from "./SaleScreen";
import { SaleProductConsultationDialog } from "./SaleProductConsultationDialog";
import { SaleTouchKeyboardScope } from "./SaleTouchKeyboardScope";

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
const secondProduct = {
  ...product,
  id: "product-2",
  code: "2004462",
  barcode: "8435606744035",
  name: "SEGUNDO PRODUCTO",
} satisfies SaleProduct;

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

    const input = screen.getByRole("combobox", { name: "Código, código de barras o nombre" });
    fireEvent.change(input, { target: { value: "2004461" } });
    fireEvent.keyDown(input, { key: "Enter" });

    expect(await screen.findByText(product.name ?? "")).toBeTruthy();
    expect(screen.getByText("Cantidad por paquete")).toBeTruthy();
  });

  it("selects each Enter result so the next search replaces it, including no matches", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    const barcodeOnly = { ...product, id: "barcode-only", code: undefined, barcode: "8400000000012" };
    const user = userEvent.setup();
    render(<SaleProductConsultationDialog products={[product, barcodeOnly]} onClose={vi.fn()} />);

    const input = screen.getByRole("combobox", { name: "Código, código de barras o nombre" }) as HTMLInputElement;
    await user.type(input, "2004");
    await user.keyboard("{Enter}");

    expect(input.value).toBe(product.code);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(product.code?.length);

    await user.keyboard(barcodeOnly.barcode);
    expect(input.value).toBe(barcodeOnly.barcode);
    await user.keyboard("{Enter}");
    expect(input.value).toBe(barcodeOnly.barcode);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(barcodeOnly.barcode.length);

    await user.keyboard("sin-coincidencias");
    await user.keyboard("{Enter}");
    expect(screen.getByText("No se encontraron productos.")).toBeTruthy();
    expect(input.value).toBe("sin-coincidencias");
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe("sin-coincidencias".length);
  });

  it("selects the consultation result when Enter is pressed on the touch keyboard", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    const user = userEvent.setup();
    render(
      <SaleTouchKeyboardScope locale="es" interfaceMode="TOUCH">
        <SaleProductConsultationDialog products={[product]} onClose={vi.fn()} />
      </SaleTouchKeyboardScope>,
    );

    const input = screen.getByRole("combobox", { name: "Código, código de barras o nombre" }) as HTMLInputElement;
    await user.type(input, "2004");
    await user.click(await screen.findByRole("button", { name: "Enter" }));

    expect(input.value).toBe(product.code);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(product.code?.length);
  });

  it("starts the search with the selected product's barcode when its code is unavailable", () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    const barcodeOnly = { ...product, code: undefined };
    render(<SaleProductConsultationDialog products={[barcodeOnly]} initialProduct={barcodeOnly} onClose={vi.fn()} />);

    expect((screen.getByRole("combobox", { name: "Código, código de barras o nombre" }) as HTMLInputElement).value)
      .toBe(product.barcode);
  });

  it("starts with the first result active and keeps focus while arrows navigate within the result limits", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    const user = userEvent.setup();
    render(<SaleProductConsultationDialog products={[product, secondProduct]} onClose={vi.fn()} />);

    const input = screen.getByRole("combobox", { name: "Código, código de barras o nombre" });
    const first = screen.getByRole("option", { name: /2004461/ });
    const second = screen.getByRole("option", { name: /2004462/ });
    expect(document.activeElement).toBe(input);
    expect(first.getAttribute("aria-selected")).toBe("true");
    expect(second.getAttribute("aria-selected")).toBe("false");

    await user.keyboard("{ArrowDown}");
    expect(first.getAttribute("aria-selected")).toBe("false");
    expect(second.getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(input);

    await user.keyboard("{ArrowDown}");
    expect(second.getAttribute("aria-selected")).toBe("true");
    await user.keyboard("{ArrowUp}");
    expect(first.getAttribute("aria-selected")).toBe("true");
    expect(second.getAttribute("aria-selected")).toBe("false");
    await user.keyboard("{ArrowUp}");
    expect(first.getAttribute("aria-selected")).toBe("true");
    expect(document.activeElement).toBe(input);
  });

  it("uses left and right arrows for native caret movement without changing the active result", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    const user = userEvent.setup();
    render(<SaleProductConsultationDialog products={[product, secondProduct]} onClose={vi.fn()} />);

    const input = screen.getByRole("combobox", { name: "Código, código de barras o nombre" }) as HTMLInputElement;
    const first = screen.getByRole("option", { name: /2004461/ });
    const second = screen.getByRole("option", { name: /2004462/ });
    await user.type(input, "20");
    await user.keyboard("{ArrowDown}");
    expect(second.getAttribute("aria-selected")).toBe("true");

    await user.keyboard("{ArrowLeft}");
    expect(input.selectionStart).toBe(1);
    expect(input.selectionEnd).toBe(1);
    expect(second.getAttribute("aria-selected")).toBe("true");
    await user.keyboard("{ArrowRight}");
    expect(input.selectionStart).toBe(2);
    expect(input.selectionEnd).toBe(2);
    expect(first.getAttribute("aria-selected")).toBe("false");
    expect(second.getAttribute("aria-selected")).toBe("true");
  });

  it("opens the active result on Enter and selects its displayed code in the search", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    const user = userEvent.setup();
    render(<SaleProductConsultationDialog products={[product, secondProduct]} onClose={vi.fn()} />);

    const input = screen.getByRole("combobox", { name: "Código, código de barras o nombre" }) as HTMLInputElement;
    await user.keyboard("{ArrowDown}{Enter}");

    expect(screen.getByRole("heading", { name: secondProduct.name })).toBeTruthy();
    expect(input.value).toBe(secondProduct.code);
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(secondProduct.code?.length);
  });

  it("resets the active result to the first row when filtering changes", async () => {
    vi.stubGlobal("fetch", vi.fn(async () => new Response(JSON.stringify({ items: [] }), {
      status: 200,
      headers: { "Content-Type": "application/json" },
    })));
    const user = userEvent.setup();
    render(<SaleProductConsultationDialog products={[product, secondProduct]} onClose={vi.fn()} />);

    const input = screen.getByRole("combobox", { name: "Código, código de barras o nombre" });
    const first = screen.getByRole("option", { name: /2004461/ });
    const second = screen.getByRole("option", { name: /2004462/ });
    await user.keyboard("{ArrowDown}");
    expect(second.getAttribute("aria-selected")).toBe("true");

    await user.type(input, "2004");
    expect(first.getAttribute("aria-selected")).toBe("true");
    expect(second.getAttribute("aria-selected")).toBe("false");
    expect(document.activeElement).toBe(input);
  });
});
