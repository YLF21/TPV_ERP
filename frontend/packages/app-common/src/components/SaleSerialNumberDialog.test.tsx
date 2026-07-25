// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { SaleSerialNumberDialog } from "./SaleSerialNumberDialog";

describe("SaleSerialNumberDialog", () => {
  afterEach(cleanup);

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
