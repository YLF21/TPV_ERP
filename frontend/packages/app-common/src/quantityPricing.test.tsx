// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { QuantityPricingEditor } from "./QuantityPricingEditor";
import { formatQuantityPrice, quantityPricingSummary, simulateQuantityPricing, validateQuantityPricing } from "./quantityPricing";
import { buildCreateProductRequest, createDefaultProductForm, createProductFormFromEditProduct, ProductCreateDialog } from "./components/ProductCreateDialog";

afterEach(cleanup);

describe("quantity pricing", () => {
  it("chooses the cheapest pack combination including loose units with exact decimal prices", () => {
    const pricing = { mode: "PACK" as const, tiers: [{ quantity: 3, price: "2.50" }, { quantity: 5, price: "4.00" }] };
    expect(simulateQuantityPricing(pricing, "1.00", 8)).toEqual({ total: "6.5", packs: [{ quantity: 3, count: 1 }, { quantity: 5, count: 1 }], looseUnits: 0 });
    expect(simulateQuantityPricing(pricing, "1.00", 7)).toEqual({ total: "6", packs: [{ quantity: 5, count: 1 }], looseUnits: 2 });
    expect(simulateQuantityPricing({ mode: "PACK", tiers: [{ quantity: 2, price: "0.30" }] }, "0.20", 3)?.total).toBe("0.5");
  });

  it("applies the cheapest qualifying threshold inclusively to every unit", () => {
    const pricing = { mode: "UNIT_THRESHOLD" as const, tiers: [{ quantity: 3, price: "0.80" }, { quantity: 5, price: "0.60" }] };
    expect(simulateQuantityPricing(pricing, "1.00", 2)?.total).toBe("2");
    expect(simulateQuantityPricing(pricing, "1.00", 3)?.total).toBe("2.4");
    expect(simulateQuantityPricing(pricing, "1.00", 5)?.total).toBe("3");
    expect(simulateQuantityPricing({ mode: "UNIT_THRESHOLD", tiers: [{ quantity: 2, price: "0.90" }, { quantity: 5, price: "0.95" }] }, "1.00", 5)?.unitPrice).toBe("0.9");
    expect(simulateQuantityPricing({ mode: "UNIT_THRESHOLD", tiers: [{ quantity: 2, price: "0.333" }] }, "1.00", 5)?.total).toBe("1.67");
    expect(quantityPricingSummary(pricing, "1.00", "es")).toBe("Umbrales: 2 tramos");
  });

  it("rejects missing, duplicate and invalid tiers and includes confirmed pricing in the request", () => {
    expect(validateQuantityPricing({ mode: "PACK", tiers: [] })).toContain("empty");
    expect(validateQuantityPricing({ mode: "PACK", tiers: [{ quantity: 0, price: "0" }, { quantity: 0, price: "1.1234" }] })).toEqual(["quantity", "price", "duplicate"]);
    const pricing = { mode: "PACK" as const, tiers: [{ quantity: 2, price: "1.5" }] };
    expect(buildCreateProductRequest({ ...createDefaultProductForm(), quantityPricing: pricing }).quantityPricing).toEqual(pricing);
  });

  it("keeps modal edits in a draft until confirmation and focuses Keep editing on discard warning", () => {
    const onConfirm = vi.fn();
    const onClose = vi.fn();
    render(<QuantityPricingEditor locale="es" value={null} basePrice="1.00" onConfirm={onConfirm} onClose={onClose} />);
    const dialog = screen.getByRole("dialog", { name: "Precios por cantidad" });
    fireEvent.click(within(dialog).getByLabelText("Precio por lotes"));
    const price = within(dialog).getByLabelText("Precio del lote");
    fireEvent.change(price, { target: { value: "1.50" } });
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
    const warning = screen.getByRole("alertdialog");
    expect(within(warning).getByRole("button", { name: "Seguir editando" })).toHaveFocus();
    fireEvent.click(within(warning).getByRole("button", { name: "Seguir editando" }));
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(onConfirm).toHaveBeenCalledWith({ enabled: false, mode: "PACK", tiers: [{ quantity: 2, price: "1.50" }] });
  });

  it("keeps F9 inside the editor and shows confirmed draft on the product form", () => {
    const updateProduct = vi.fn();
    const view = render(<ProductCreateDialog open locale="es" token="token" editProduct={{ id: "p1", form: { salePrice: "1.00", name: "Café" } }} onClose={vi.fn()} updateProduct={updateProduct} />);
    fireEvent.click(view.getByRole("button", { name: "Configurar precios por cantidad" }));
    const editor = screen.getByRole("dialog", { name: "Precios por cantidad" });
    fireEvent.keyDown(editor, { key: "F9" });
    expect(updateProduct).not.toHaveBeenCalled();
    fireEvent.click(within(editor).getByLabelText("Precio por lotes"));
    fireEvent.change(within(editor).getByLabelText("Precio del lote"), { target: { value: "1.50" } });
    fireEvent.click(within(editor).getByRole("button", { name: "Aplicar" }));
    expect(screen.queryByRole("dialog", { name: "Precios por cantidad" })).toBeNull();
    expect(view.getByText("Lotes: 1 tramo")).toBeInTheDocument();
    expect(view.getByText("Aplicado")).toBeInTheDocument();
    expect(updateProduct).not.toHaveBeenCalled();
  });

  it("simulates the visible maximum without losing an exact pack total", () => {
    const simulation = simulateQuantityPricing({ mode: "PACK", tiers: [{ quantity: 7, price: "1.001" }] }, "0.20", 10000);
    expect(simulation).not.toBeNull();
    expect(simulation?.total).toBe("1430.23");
    expect(simulateQuantityPricing({ mode: "PACK", tiers: [{ quantity: 7, price: "1.001" }] }, "0.20", 10001)).toBeNull();
    const manyTiers = { mode: "PACK" as const, tiers: Array.from({ length: 100 }, (_, index) => ({ quantity: index + 2, price: String(index + 1) })) };
    expect(simulateQuantityPricing(manyTiers, "2.00", 10000)?.total).toBe("5000");
  });

  it.each([
    ["es", "Introduce una cantidad entre 1 y 10.000 unidades.", "Introduce un PVP válido para simular."],
    ["en", "Enter a quantity between 1 and 10,000 units.", "Enter a valid sale price to simulate."],
    ["zh", "请输入 1 至 10,000 件的数量。", "请输入有效售价以模拟。"],
  ] as const)("explains an out-of-range simulation quantity in %s", (locale, quantityError, priceError) => {
    const rule = { mode: "PACK" as const, tiers: [{ quantity: 2, price: "1.50" }] };
    render(<QuantityPricingEditor locale={locale} value={rule} basePrice="1.00" onConfirm={vi.fn()} onClose={vi.fn()} />);
    const input = document.querySelector<HTMLInputElement>(".quantity-pricing-simulator input[type=number]");
    expect(input).not.toBeNull();
    fireEvent.change(input!, { target: { value: "10001" } });
    expect(screen.getByText(quantityError)).toBeInTheDocument();
    expect(screen.queryByText(priceError)).not.toBeInTheDocument();
  });

  it("does not overwrite a rule when an older edit source omitted the field", () => {
    const unknown = createProductFormFromEditProduct({ id: "p1", form: { name: "Café" } });
    expect("quantityPricing" in buildCreateProductRequest(unknown)).toBe(false);
    const knownAbsent = createProductFormFromEditProduct({ id: "p1", form: { quantityPricing: null } });
    expect(buildCreateProductRequest(knownAbsent).quantityPricing).toBeNull();
  });

  it("keeps 17 whole digits and three decimals exact in the editor, request and preview", () => {
    const amount = "99999999999999999.999";
    expect(formatQuantityPrice(amount, "es")).toBe("99.999.999.999.999.999,999 €");
    expect(formatQuantityPrice(amount, "en")).toBe("99,999,999,999,999,999.999 €");
    const pricing = { mode: "PACK" as const, tiers: [{ quantity: 2, price: amount }] };
    expect(validateQuantityPricing(pricing)).toEqual([]);
    expect(simulateQuantityPricing(pricing, amount, 2)?.total).toBe("100000000000000000");
    expect(buildCreateProductRequest({ ...createDefaultProductForm(), quantityPricing: pricing }).quantityPricing).toEqual(pricing);
    const onConfirm = vi.fn();
    render(<QuantityPricingEditor locale="es" value={pricing} basePrice={amount} onConfirm={onConfirm} onClose={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "Precios por cantidad" });
    expect(within(dialog).getByLabelText("Precio del lote")).toHaveValue(amount);
    expect(within(dialog).getByText(/100\.000\.000\.000\.000\.000,00 €/)).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(onConfirm).toHaveBeenCalledWith({ ...pricing, enabled: true });
  });

  it("keeps inactive tiers through a product save and reactivates them at the same price", () => {
    const rule = { mode: "UNIT_THRESHOLD" as const, tiers: [{ quantity: 12, price: "0.80" }] };
    const onConfirm = vi.fn();
    const editor = (value: typeof rule & { enabled?: boolean }) =>
      <QuantityPricingEditor locale="es" value={value} basePrice="1.00" onConfirm={onConfirm} onClose={vi.fn()} />;
    const view = render(editor(rule));
    fireEvent.click(screen.getByRole("button", { name: "No aplicar" }));
    const inactive = { ...rule, enabled: false };
    expect(onConfirm).toHaveBeenLastCalledWith(inactive);
    expect(buildCreateProductRequest({ ...createDefaultProductForm(), quantityPricing: inactive }).quantityPricing).toEqual(inactive);
    expect(simulateQuantityPricing(inactive, "1.00", 20)?.total).toBe("20");
    view.rerender(editor(inactive));
    fireEvent.click(screen.getByRole("button", { name: "Guardar" }));
    expect(onConfirm).toHaveBeenLastCalledWith(inactive);
    fireEvent.click(screen.getByRole("button", { name: "Aplicar" }));
    expect(onConfirm).toHaveBeenLastCalledWith({ ...rule, enabled: true });
    expect(simulateQuantityPricing(rule, "1.00", 20)?.total).toBe("16");
  });
});
