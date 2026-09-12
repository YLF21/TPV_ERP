// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SaleSerialNumberDialog } from "./SaleSerialNumberDialog";

describe("SaleSerialNumberDialog", () => {
  afterEach(cleanup);

  it("edits the focused serial in touch mode while keeping scanner input and keyboard activation separate", () => {
    const onConfirm = vi.fn();
    const props = { locale: "es" as const, productName: "Portátil", quantity: 2, initialSerialNumbers: ["A1", "B2"], onCancel: vi.fn(), onConfirm };
    const { rerender } = render(<SaleSerialNumberDialog {...props} />);
    expect(screen.queryByRole("group", { name: "Teclado alfanumérico" })).not.toBeInTheDocument();
    rerender(<SaleSerialNumberDialog {...props} interfaceMode="TOUCH" />);
    const inputs = screen.getAllByRole<HTMLInputElement>("textbox");
    inputs[1].focus();
    inputs[1].setSelectionRange(0, 1);
    fireEvent.click(screen.getByRole("button", { name: "C" }));
    expect(inputs[0]).toHaveValue("A1");
    expect(inputs[1]).toHaveValue("C2");
    expect(inputs[1]).toHaveFocus();
    fireEvent.keyDown(screen.getByRole("button", { name: "C" }), { key: "Enter" });
    expect(onConfirm).not.toHaveBeenCalled();
    fireEvent.change(inputs[1], { target: { value: "SCANNED-02" } });
    fireEvent.keyDown(inputs[1], { key: "Enter" });
    expect(onConfirm).toHaveBeenCalledWith(["A1", "SCANNED-02"]);
  });

  it("requires one distinct serial number per unit", () => {
    const onConfirm = vi.fn();
    render(
      <SaleSerialNumberDialog
        locale="es"
        productName="Portátil"
        quantity={2}
        initialSerialNumbers={[]}
        onCancel={vi.fn()}
        onConfirm={onConfirm}
      />,
    );

    const inputs = screen.getAllByRole("textbox");
    fireEvent.change(inputs[0], { target: { value: "SN-001" } });
    fireEvent.change(inputs[1], { target: { value: "sn-001" } });

    expect(screen.getByRole("alert")).toHaveTextContent("no pueden repetirse");
    expect(screen.getByRole("button", { name: "Aceptar" })).toBeDisabled();

    fireEvent.change(inputs[1], { target: { value: "SN-002" } });
    fireEvent.click(screen.getByRole("button", { name: "Aceptar" }));

    expect(onConfirm).toHaveBeenCalledWith(["SN-001", "SN-002"]);
  });
});
