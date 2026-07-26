// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { parseSaleOpenPrice, SaleOpenPriceDialog } from "./SaleOpenPriceDialog";

const labels = {
  title: "Introducir precio",
  product: "Producto",
  price: "Precio de venta",
  placeholder: "0,00",
  invalid: "Introduce un precio mayor que 0 con un máximo de 2 decimales",
  cancel: "Cancelar",
  accept: "Aceptar",
};

afterEach(cleanup);

describe("SaleOpenPriceDialog", () => {
  it("accepts positive prices with comma or dot and at most two decimals", () => {
    expect(parseSaleOpenPrice(" 12,50 ")).toBe(12.5);
    expect(parseSaleOpenPrice("0.01")).toBe(0.01);
    expect(parseSaleOpenPrice("0")).toBeNull();
    expect(parseSaleOpenPrice("-1")).toBeNull();
    expect(parseSaleOpenPrice("1.001")).toBeNull();
    expect(parseSaleOpenPrice("texto")).toBeNull();
  });

  it("submits a valid price and rejects invalid input", () => {
    const onAccept = vi.fn();
    render(<SaleOpenPriceDialog
      labels={labels}
      productName="Producto abierto"
      onCancel={vi.fn()}
      onAccept={onAccept}
    />);

    const input = screen.getByLabelText("Precio de venta");
    fireEvent.change(input, { target: { value: "1,001" } });
    fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));
    expect(screen.getByRole("alert")).toHaveTextContent("máximo de 2 decimales");
    expect(onAccept).not.toHaveBeenCalled();

    fireEvent.change(input, { target: { value: "7,25" } });
    fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));
    expect(onAccept).toHaveBeenCalledWith(7.25);
  });

  it("cancels with Escape", () => {
    const onCancel = vi.fn();
    render(<SaleOpenPriceDialog
      labels={labels}
      productName="Producto abierto"
      onCancel={onCancel}
      onAccept={vi.fn()}
    />);
    fireEvent.keyDown(screen.getByRole("dialog"), { key: "Escape" });
    expect(onCancel).toHaveBeenCalledOnce();
  });
});
