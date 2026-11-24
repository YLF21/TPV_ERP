// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, within } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import type { QuantityPricing } from "../quantityPricing";
import { StockBulkQuantityPricingDialog } from "./StockBulkQuantityPricingDialog";
import type { StockInventoryRow } from "./StockScreen";
import type { StockBulkEditRowData } from "./stockBulkEdit";

afterEach(cleanup);

const pack: QuantityPricing = { mode: "PACK", tiers: [{ quantity: 2, price: "0.80" }, { quantity: 5, price: "1.70" }] };
function row(id: string, value: QuantityPricing | undefined, selected = true): StockBulkEditRowData {
  return { id, selected, query: "", draft: {}, product: {
    code: id, name: id, salePrice: "0.50", ...(value !== undefined ? { quantityPricing: value } : {})
  } as StockInventoryRow };
}

it("opens the tier editor directly and drafts a full rule only for products without a rule", () => {
  const confirm = vi.fn();
  render(<StockBulkQuantityPricingDialog rows={[row("missing", null), row("configured", pack), row("unknown", undefined), row("not-selected", null, false)]}
    action="missing" locale="es" onClose={vi.fn()} onConfirm={confirm} />);
  const editor = screen.getByRole("dialog", { name: "Precios por cantidad" });
  expect(screen.getAllByRole("dialog")).toHaveLength(1);
  expect(within(editor).queryByRole("table")).not.toBeInTheDocument();
  expect(within(editor).getByText(/Seleccionados 3 · Aplicables 1/)).toBeInTheDocument();
  fireEvent.change(within(editor).getByLabelText("Precio del lote"), { target: { value: "0.80" } });
  fireEvent.click(within(editor).getByRole("button", { name: "Añadir tramo" }));
  const prices = within(editor).getAllByLabelText("Precio del lote");
  fireEvent.change(prices[1], { target: { value: "1.70" } });
  fireEvent.click(within(editor).getByRole("button", { name: "Aplicar" }));
  expect(confirm).toHaveBeenCalledWith(new Map([["missing", {
    mode: "PACK", enabled: true, tiers: [{ quantity: 2, price: "0.80" }, { quantity: 3, price: "1.70" }]
  }]]));
});

it("replaces selected known rules and keeps the editor's disabled state", () => {
  const confirm = vi.fn();
  render(<StockBulkQuantityPricingDialog rows={[row("configured", pack), row("missing", null), row("unknown", undefined)]}
    action="replace" locale="en" onClose={vi.fn()} onConfirm={confirm} />);
  const editor = screen.getByRole("dialog", { name: "Quantity pricing" });
  expect(within(editor).getByText(/Selected 3 · Applicable 2/)).toBeInTheDocument();
  fireEvent.change(within(editor).getByLabelText("Pack price"), { target: { value: "0.90" } });
  fireEvent.click(within(editor).getByRole("button", { name: "Do not apply" }));
  expect([...confirm.mock.calls[0][0].keys()]).toEqual(["configured", "missing"]);
  expect(confirm.mock.calls[0][0].get("configured")).toEqual({
    mode: "PACK", enabled: false, tiers: [{ quantity: 2, price: "0.90" }]
  });
});

it("keeps each product's enabled state on Save and sets it explicitly on Apply or Do not apply", () => {
  const rows = [row("active", pack), row("inactive", { ...pack, enabled: false }), row("without-rule", null)];
  for (const [button, expected] of [
    ["Save", [true, false, false]],
    ["Apply", [true, true, true]],
    ["Do not apply", [false, false, false]]
  ] as const) {
    const confirm = vi.fn();
    const { unmount } = render(<StockBulkQuantityPricingDialog rows={rows} action="replace" locale="en"
      onClose={vi.fn()} onConfirm={confirm} />);
    const editor = screen.getByRole("dialog", { name: "Quantity pricing" });
    fireEvent.change(within(editor).getByLabelText("Pack price"), { target: { value: "0.90" } });
    fireEvent.click(within(editor).getByRole("button", { name: button }));
    const changes = confirm.mock.calls[0][0] as Map<string, QuantityPricing>;
    expect([...changes.keys()]).toEqual(["active", "inactive", "without-rule"]);
    expect([...changes.values()].map(rule => rule?.enabled)).toEqual(expected);
    expect([...changes.values()].map(rule => rule?.tiers)).toEqual([
      [{ quantity: 2, price: "0.90" }],
      [{ quantity: 2, price: "0.90" }],
      [{ quantity: 2, price: "0.90" }]
    ]);
    unmount();
  }
});

it("confirms remove, enable and disable with explicit scope while preserving existing tiers", () => {
  const rows = [row("enabled", pack), row("disabled", { ...pack, enabled: false }), row("missing", null), row("unknown", undefined)];
  for (const [action, expectedIds] of [
    ["remove", ["enabled", "disabled"]],
    ["enable", ["disabled"]],
    ["disable", ["enabled"]]
  ] as const) {
    const confirm = vi.fn();
    const { unmount } = render(<StockBulkQuantityPricingDialog rows={rows} action={action} locale="zh"
      onClose={vi.fn()} onConfirm={confirm} />);
    const dialog = screen.getByRole("alertdialog");
    expect(within(dialog).getByText(new RegExp(`已选择 4 · 状态已知 3 · 将变更 ${expectedIds.length}`))).toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", {
      name: { remove: "移除规则", enable: "启用规则", disable: "停用规则" }[action]
    }));
    const changes = confirm.mock.calls[0][0] as Map<string, QuantityPricing>;
    expect([...changes.keys()]).toEqual(expectedIds);
    if (action === "remove") expect([...changes.values()]).toEqual([null, null]);
    else expect(changes.get(expectedIds[0])).toEqual({ ...pack, enabled: action === "enable" });
    unmount();
  }
});
