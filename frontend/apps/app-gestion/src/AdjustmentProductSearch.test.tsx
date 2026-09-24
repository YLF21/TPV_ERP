// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, expect, it, vi } from "vitest";
import { AdjustmentProductSearch } from "./AdjustmentProductSearch";
import { searchWarehouseProducts } from "./warehouseOperationsApi";
vi.mock("./warehouseOperationsApi", () => ({ searchWarehouseProducts: vi.fn() }));
afterEach(() => { cleanup(); vi.resetAllMocks(); });
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
