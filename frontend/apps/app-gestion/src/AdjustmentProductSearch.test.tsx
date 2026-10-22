// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { AdjustmentProductSearch } from "./AdjustmentProductSearch";
import { searchWarehouseProducts } from "./warehouseOperationsApi";
vi.mock("./warehouseOperationsApi", () => ({ searchWarehouseProducts: vi.fn() }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });
it("advances through suggestions while held and stops on keyup without choosing", async () => {
  const products = Array.from({ length: 5 }, (_, index) => ({ id: `p${index}`, code: `P${index}`, name: `Producto ${index}`, barcode: `${index}` }));
  vi.mocked(searchWarehouseProducts).mockResolvedValue({ items: products.map((product) => ({ product })) });
  const select = vi.fn();
  render(<AdjustmentProductSearch token="token" value="" selected={false} onChange={vi.fn()} onSelect={select} t={(key) => key} />);
  fireEvent.click(screen.getByRole("combobox"));
  expect(await screen.findAllByRole("option")).toHaveLength(5);
  const input = screen.getByRole("combobox");
  input.focus();
  vi.useFakeTimers();
  try {
    fireEvent.keyDown(input, { key: "ArrowDown" });
    expect(input).toHaveAttribute("aria-activedescendant", expect.stringContaining("option-1"));
    act(() => { vi.advanceTimersByTime(250); });
    expect(input).toHaveAttribute("aria-activedescendant", expect.stringContaining("option-3"));
    fireEvent.keyUp(input, { key: "ArrowDown" });
    act(() => { vi.advanceTimersByTime(500); });
    expect(input).toHaveAttribute("aria-activedescendant", expect.stringContaining("option-3"));
    expect(select).not.toHaveBeenCalled();
  } finally { vi.useRealTimers(); }
});
it("opens products without typing and selects with the keyboard without submitting", async () => {
  const product = { id: "p1", code: "P1", name: "Producto", barcode: "123" };
  vi.mocked(searchWarehouseProducts).mockResolvedValue({ items: [{ product }, { product: { ...product, id: "service", productType: "SERVICE" } }] });
  const select = vi.fn(); const submit = vi.fn((event) => event.preventDefault());
  render(<form onSubmit={submit}><AdjustmentProductSearch token="token" value="" selected={false} onChange={vi.fn()} onSelect={select} t={(key) => key} /></form>);
  fireEvent.click(screen.getByRole("button", { name: "warehouse.adjustment.chooseProduct" }));
  expect(await screen.findByRole("option")).toHaveTextContent("P1Producto123");
  expect(searchWarehouseProducts).toHaveBeenCalledWith("", "token");
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Enter" });
  expect(select).toHaveBeenCalledWith(product);
  expect(submit).not.toHaveBeenCalled();
  expect(screen.queryByRole("listbox")).not.toBeInTheDocument();
});
it("shows a search error and allows Escape to dismiss the dropdown", async () => {
  vi.mocked(searchWarehouseProducts).mockRejectedValue(new Error("offline"));
  render(<AdjustmentProductSearch token="token" value="" selected={false} onChange={vi.fn()} onSelect={vi.fn()} t={(key) => key} />);
  fireEvent.click(screen.getByRole("combobox"));
  expect(await screen.findByRole("alert")).toHaveTextContent("warehouse.adjustment.searchError");
  fireEvent.keyDown(screen.getByRole("combobox"), { key: "Escape" });
  expect(screen.getByRole("combobox")).toHaveAttribute("aria-expanded", "false");
});
