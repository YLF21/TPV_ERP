// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { GiftReceiptDialog } from "./GiftReceiptDialog";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});

const request = vi.mocked(apiRequest);

describe("GiftReceiptDialog", () => {
  afterEach(() => {
    cleanup();
    request.mockReset();
  });

  it("uses whole quantity inputs for unit products", async () => {
    request.mockResolvedValue({
      ticketId: "ticket-1",
      ticketNumber: "T-1",
      issuedAt: "2026-08-03T10:00:00Z",
      lines: [{
        lineId: "line-1",
        code: "P-1",
        name: "Producto",
        productType: "UNIT",
        availableQuantity: "5.000",
        serialNumbers: [],
      }],
    } as never);

    render(<GiftReceiptDialog
      token="token"
      locale="es"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      onClose={vi.fn()}
    />);
    fireEvent.change(screen.getByLabelText("N.º de ticket"), { target: { value: "T-1" } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar ticket" }));

    expect(await screen.findByText("P-1")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Seleccionar todo el ticket" }));
    const quantity = screen.getByRole("spinbutton");
    expect(quantity).toHaveAttribute("step", "1");
    expect(quantity).toHaveAttribute("min", "1");
    expect(quantity).toHaveValue(5);
    expect(screen.getByText("5")).toBeInTheDocument();
  });
});
