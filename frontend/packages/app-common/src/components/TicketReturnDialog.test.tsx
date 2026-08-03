// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { TicketReturnDialog } from "./TicketReturnDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);

describe("TicketReturnDialog", () => {
  beforeEach(() => {
    request.mockReset();
    request.mockImplementation(async (path) => path === "/tickets/return-valuation"
      ? {
          selectedGross: "10.00",
          lostBenefits: "0.00",
          refundableAmount: "10.00",
          eligibleRefundableAmount: "10.00",
          cumulativeEligibleRefundableAmount: "10.00",
          cumulativeRefundableAmount: "10.00",
          previouslyRefundedAmount: "0.00",
          remainingBasketValue: "10.00",
        } as never
      : {
          sourceType: "TICKET",
          sourceCode: "T-001",
          ticketId: "ticket-1",
          ticketNumber: "T-001",
          date: "2026-07-24",
          total: "20.00",
          lines: [{
            lineId: "line-1",
            giftReceiptLineId: null,
            productId: "product-1",
            code: "PORTATIL",
            name: "Portátil",
            lineType: "PRODUCT",
            refundableQuantity: 1,
            unitPrice: "10.00",
            refundableTotal: "10.00",
            refundableSerialNumbers: ["SN-002"],
            discount: "0.00",
            taxesIncluded: true,
            taxRegime: "IVA",
            taxPercentage: "21.00",
          }],
        } as never);
  });

  afterEach(cleanup);

  it("selects all remaining lines and only adds them to the cart", async () => {
    const onAddToCart = vi.fn();
    const onClose = vi.fn();
    render(<TicketReturnDialog token="token" locale="es" onClose={onClose} onAddToCart={onAddToCart} />);

    fireEvent.change(screen.getByLabelText(/ticket o ticket regalo/i), {
      target: { value: "T-001" },
    });
    fireEvent.click(screen.getByRole("button", { name: /Buscar ticket/i }));

    expect(await screen.findByText("Portátil")).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Seleccionar todo el ticket/i }));
    expect(screen.getByText("S/N: SN-002")).toBeInTheDocument();
    const addButton = screen.getByRole("button", { name: /carrito en negativo/i });
    await waitFor(() => expect(addButton).toBeEnabled());
    fireEvent.click(addButton);

    await waitFor(() => expect(onAddToCart).toHaveBeenCalledWith([
      expect.objectContaining({
        sourceType: "TICKET",
        sourceTicketId: "ticket-1",
        lineId: "line-1",
        returnQuantity: 1,
        selectedSerialNumbers: ["SN-002"],
      }),
    ]));
    expect(onClose).toHaveBeenCalledOnce();
    expect(request).toHaveBeenCalledTimes(2);
  });
});
