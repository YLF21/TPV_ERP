// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SaleCustomerAddressDialog } from "./SaleCustomerAddressDialog";
import type { CustomerContactAddress } from "./customerContactAddresses";

const addresses: CustomerContactAddress[] = [
  { id: "first", address: { address: "Calle Mayor 1", postalCode: "28001", city: "Madrid" }, phone: "600111222" },
  { id: "second", address: { address: "Calle Sol 2", postalCode: "35001", city: "Las Palmas" }, phone: "600333444" },
  { id: "third", address: { address: "Calle Luna 3", postalCode: "38001", city: "Santa Cruz" } },
];
const customer = { id: "customer", clientId: "C-001", fiscalName: "Cliente de prueba" };

afterEach(cleanup);

describe("SaleCustomerAddressDialog", () => {
  it("highlights the default address without confirming it, then selects the highlighted row with Insert", () => {
    const onSelect = vi.fn();
    const { container } = render(<SaleCustomerAddressDialog locale="es" customer={customer} addresses={addresses}
      onSelect={onSelect} onCancel={vi.fn()} />);
    const rows = container.querySelectorAll<HTMLTableRowElement>("tbody tr");
    expect(rows[0]).toHaveFocus();
    expect(rows[0]).toHaveAttribute("aria-selected", "true");
    expect(rows[0]).toHaveTextContent("Predeterminada");
    expect(onSelect).not.toHaveBeenCalled();
    fireEvent.keyDown(rows[0], { key: "ArrowDown" });
    expect(rows[1]).toHaveFocus();
    expect(rows[1]).toHaveAttribute("aria-selected", "true");
    expect(rows[0]).toHaveAttribute("aria-selected", "false");
    fireEvent.keyDown(rows[1], { key: "Insert" });
    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith(addresses[1]);
  });

  it("supports Home, End, Enter and double click without changing the default address", () => {
    const onSelect = vi.fn();
    const { container } = render(<SaleCustomerAddressDialog locale="es" customer={customer} addresses={addresses}
      onSelect={onSelect} onCancel={vi.fn()} />);
    const rows = container.querySelectorAll<HTMLTableRowElement>("tbody tr");
    fireEvent.keyDown(rows[0], { key: "End" });
    expect(rows[2]).toHaveFocus();
    fireEvent.keyDown(rows[2], { key: "Home" });
    expect(rows[0]).toHaveFocus();
    fireEvent.keyDown(rows[0], { key: "Enter" });
    expect(onSelect).toHaveBeenLastCalledWith(addresses[0]);
    fireEvent.doubleClick(rows[2]);
    expect(onSelect).toHaveBeenLastCalledWith(addresses[2]);
  });

  it("traps Tab, cancels with Escape and disables confirmation for an empty list", () => {
    const onCancel = vi.fn();
    const onSelect = vi.fn();
    const { rerender } = render(<SaleCustomerAddressDialog locale="es" customer={customer} addresses={addresses}
      onSelect={onSelect} onCancel={onCancel} />);
    const confirm = screen.getByRole("button", { name: "Seleccionar dirección" });
    confirm.focus();
    fireEvent.keyDown(confirm, { key: "Tab" });
    expect(screen.getAllByRole("button", { name: "Cerrar" })[0]).toHaveFocus();
    fireEvent.keyDown(document.activeElement!, { key: "Escape" });
    expect(onCancel).toHaveBeenCalledTimes(1);
    expect(onSelect).not.toHaveBeenCalled();
    rerender(<SaleCustomerAddressDialog locale="es" customer={customer} addresses={[]}
      onSelect={onSelect} onCancel={onCancel} />);
    expect(confirm).toBeDisabled();
  });
});
