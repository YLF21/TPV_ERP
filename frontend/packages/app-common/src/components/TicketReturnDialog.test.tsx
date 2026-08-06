// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { calendarDaysElapsed, TicketReturnDialog } from "./TicketReturnDialog";

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
           paymentAvailability: [
             { paymentMethod: "EFECTIVO", kind: "CASH", originalAmount: "41.00", refundedAmount: "0.00", reservedAmount: "0.00", availableAmount: "41.00" },
             { paymentMethod: "TARJETA", kind: "MANUAL_CARD", originalAmount: "41.00", refundedAmount: "10.00", reservedAmount: "0.00", availableAmount: "31.00" },
           ],
           lines: [{
            lineId: "line-1",
            giftReceiptLineId: null,
            productId: "product-1",
            code: "PORTATIL",
            name: "Portátil",
            lineType: "PRODUCT",
            productType: "UNIT",
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
    expect(screen.getAllByText(/Pagado originalmente: 41,00/)).toHaveLength(2);
    expect(screen.getByText(/Devuelto anteriormente: 10,00/)).toBeInTheDocument();
    expect(screen.queryByText(/Puede cubrir todo el importe seleccionado/)).not.toBeInTheDocument();
    expect(screen.getByRole("table", { name: /productos disponibles para devolución/i })).toBeInTheDocument();
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

  it("does not allow decimal quantities for unit products", async () => {
    request.mockImplementation(async (path) => path === "/tickets/return-valuation"
      ? {
          selectedGross: "50.00", lostBenefits: "0.00", refundableAmount: "50.00",
          eligibleRefundableAmount: "50.00", cumulativeEligibleRefundableAmount: "50.00",
          cumulativeRefundableAmount: "50.00", previouslyRefundedAmount: "0.00",
          remainingBasketValue: "0.00",
        } as never
      : {
          sourceType: "TICKET", sourceCode: "T-001", ticketId: "ticket-1",
          ticketNumber: "T-001", date: "2026-07-24", total: "50.00",
          lines: [{
            lineId: "line-1", productId: "product-1", code: "P-1", name: "Producto",
            lineType: "PRODUCT", productType: "UNIT", refundableQuantity: "5.000",
            unitPrice: "10.00", refundableTotal: "50.00", refundableSerialNumbers: [],
            discount: "0.00", taxesIncluded: true, taxRegime: "IVA", taxPercentage: "21.00",
          }],
        } as never);

    render(<TicketReturnDialog token="token" locale="es" onClose={vi.fn()} onAddToCart={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/ticket o ticket regalo/i), { target: { value: "T-001" } });
    fireEvent.click(screen.getByRole("button", { name: /Buscar ticket/i }));
    expect(await screen.findByRole("checkbox", { name: "Producto" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: /Seleccionar todo el ticket/i }));

    const quantity = screen.getByRole("spinbutton");
    expect(quantity).toHaveAttribute("step", "1");
    expect(quantity).toHaveValue(5);
    fireEvent.change(quantity, { target: { value: "4.992" } });
    expect(screen.getByRole("button", { name: /carrito en negativo/i })).toBeDisabled();
  });

  it("reopens the current ticket, reserves cart quantities and selects one unit by barcode", async () => {
    request.mockImplementation(async (path) => path === "/tickets/return-valuation"
      ? {
          selectedGross: "10.00", lostBenefits: "0.00", refundableAmount: "10.00",
          eligibleRefundableAmount: "10.00", cumulativeEligibleRefundableAmount: "30.00",
          cumulativeRefundableAmount: "30.00", previouslyRefundedAmount: "0.00",
          remainingBasketValue: "20.00",
        } as never
      : {
          sourceType: "TICKET", sourceCode: "T-001", ticketId: "ticket-1",
          ticketNumber: "T-001", date: "2026-07-24", total: "50.00",
          lines: [{
            lineId: "line-1", productId: "product-1", code: "P-1",
            barcode: "8430000000010", barcode2: "20000001", name: "Producto",
            lineType: "PRODUCT", productType: "UNIT", refundableQuantity: "5.000",
            unitPrice: "10.00", refundableTotal: "50.00", refundableSerialNumbers: [],
            discount: "0.00", taxesIncluded: true, taxRegime: "IVA", taxPercentage: "21.00",
          }],
        } as never);

    render(<TicketReturnDialog
      token="token"
      locale="es"
      existingCartLines={[{
        sourceTicketId: "ticket-1", sourceCode: "T-001", lineId: "line-1",
        returnQuantity: 2, selectedSerialNumbers: [],
      }]}
      onClose={vi.fn()}
      onAddToCart={vi.fn()}
    />);

    const ticketInput = screen.getByLabelText(/ticket o ticket regalo/i);
    expect(ticketInput).toHaveValue("T-001");
    expect(ticketInput).toHaveAttribute("readonly");
    expect(await screen.findByRole("checkbox", { name: "Producto" })).toBeInTheDocument();
    const productSearch = screen.getByLabelText(/Buscar producto en este ticket/i);
    await waitFor(() => expect(productSearch).toHaveFocus());
    expect(screen.getByText(/En carrito: 2/)).toBeInTheDocument();

    fireEvent.change(productSearch, { target: { value: "8430000000010" } });
    fireEvent.keyDown(productSearch, { key: "Enter" });

    expect(screen.getByRole("checkbox", { name: "Producto" })).toBeChecked();
    expect(screen.getByRole("spinbutton")).toHaveValue(1);

    fireEvent.change(productSearch, { target: { value: "8430000000010" } });
    fireEvent.keyDown(productSearch, { key: "Enter" });
    expect(screen.getByRole("spinbutton")).toHaveValue(2);
  });

  it("calculates calendar days elapsed without depending on the time of day", () => {
    expect(calendarDaysElapsed("2026-08-01", new Date(2026, 7, 6, 23, 59))).toBe(5);
    expect(calendarDaysElapsed("2026-08-06", new Date(2026, 7, 6, 0, 1))).toBe(0);
  });

  it("selects one unit manually but keeps select-all as the full remaining quantity", async () => {
    request.mockImplementation(async (path) => path === "/tickets/return-valuation"
      ? {
          selectedGross: "10.00", lostBenefits: "0.00", refundableAmount: "10.00",
          eligibleRefundableAmount: "10.00", cumulativeEligibleRefundableAmount: "10.00",
          cumulativeRefundableAmount: "10.00", previouslyRefundedAmount: "0.00",
          remainingBasketValue: "40.00",
        } as never
      : {
          sourceType: "TICKET", sourceCode: "T-001", ticketId: "ticket-1",
          ticketNumber: "T-001", date: "2026-07-24", total: "50.00",
          lines: [{
            lineId: "line-1", productId: "product-1", code: "P-1", name: "Producto",
            lineType: "PRODUCT", productType: "UNIT", refundableQuantity: "5.000",
            unitPrice: "10.00", refundableTotal: "50.00", refundableSerialNumbers: [],
            discount: "0.00", taxesIncluded: true, taxRegime: "IVA", taxPercentage: "21.00",
          }],
        } as never);

    render(<TicketReturnDialog token="token" locale="es" onClose={vi.fn()} onAddToCart={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/ticket o ticket regalo/i), { target: { value: "T-001" } });
    fireEvent.click(screen.getByRole("button", { name: /Buscar ticket/i }));
    const checkbox = await screen.findByRole("checkbox", { name: "Producto" });

    fireEvent.click(checkbox);
    expect(screen.getByRole("spinbutton")).toHaveValue(1);
    fireEvent.click(screen.getByRole("button", { name: /Quitar selección/i }));
    fireEvent.click(screen.getByRole("button", { name: /Seleccionar todo el ticket/i }));
    expect(screen.getByRole("spinbutton")).toHaveValue(5);
  });

  it("does not choose between duplicate promotional lines and requires an exact serial", async () => {
    request.mockImplementation(async (path) => path === "/tickets/return-valuation"
      ? {
          selectedGross: "10.00", lostBenefits: "0.00", refundableAmount: "10.00",
          eligibleRefundableAmount: "10.00", cumulativeEligibleRefundableAmount: "10.00",
          cumulativeRefundableAmount: "10.00", previouslyRefundedAmount: "0.00",
          remainingBasketValue: "10.00",
        } as never
      : {
          sourceType: "TICKET", sourceCode: "T-001", ticketId: "ticket-1",
          ticketNumber: "T-001", date: "2026-07-24", total: "20.00",
          lines: [
            {
              lineId: "line-1", productId: "product-1", code: "P-1", barcode: "8430000000010",
              name: "Producto promoción", lineType: "PRODUCT", productType: "UNIT",
              refundableQuantity: "1.000", unitPrice: "10.00", refundableTotal: "10.00",
              refundableSerialNumbers: [], discount: "0.00", taxesIncluded: true,
              taxRegime: "IVA", taxPercentage: "21.00",
            },
            {
              lineId: "line-2", productId: "product-1", code: "P-1", barcode: "8430000000010",
              name: "Producto promoción", lineType: "PRODUCT", productType: "UNIT",
              refundableQuantity: "1.000", unitPrice: "5.00", refundableTotal: "5.00",
              refundableSerialNumbers: ["SN-1", "SN-2"], discount: "50.00", taxesIncluded: true,
              taxRegime: "IVA", taxPercentage: "21.00",
            },
          ],
        } as never);

    render(<TicketReturnDialog token="token" locale="es" onClose={vi.fn()} onAddToCart={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/ticket o ticket regalo/i), { target: { value: "T-001" } });
    fireEvent.click(screen.getByRole("button", { name: /Buscar ticket/i }));
    const productSearch = await screen.findByLabelText(/Buscar producto en este ticket/i);
    fireEvent.change(productSearch, { target: { value: "8430000000010" } });
    fireEvent.keyDown(productSearch, { key: "Enter" });

    expect(screen.getByText(/aparece en varias líneas/i)).toBeInTheDocument();
    expect(screen.getAllByRole("checkbox", { name: "Producto promoción" })).toHaveLength(2);
    expect(screen.getAllByRole("checkbox", { name: "Producto promoción" }).every((box) => !box.hasAttribute("checked"))).toBe(true);

    fireEvent.click(screen.getAllByRole("checkbox", { name: "Producto promoción" })[1]);
    expect(screen.getByText("S/N: SN-1")).toBeInTheDocument();
    expect(screen.getByText("S/N: SN-2")).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /carrito en negativo/i })).toBeDisabled();
    fireEvent.click(screen.getByLabelText("S/N: SN-2"));
    await waitFor(() => expect(screen.getByRole("button", { name: /carrito en negativo/i })).toBeEnabled());
  });

  it("increments the manually chosen duplicate line on repeated scans", async () => {
    request.mockImplementation(async (path) => path === "/tickets/return-valuation"
      ? {
          selectedGross: "20.00", lostBenefits: "0.00", refundableAmount: "20.00",
          eligibleRefundableAmount: "20.00", cumulativeEligibleRefundableAmount: "20.00",
          cumulativeRefundableAmount: "20.00", previouslyRefundedAmount: "0.00",
          remainingBasketValue: "10.00",
        } as never
      : {
          sourceType: "TICKET", sourceCode: "T-001", ticketId: "ticket-1",
          ticketNumber: "T-001", date: "2026-08-01", total: "35.00",
          lines: [
            {
              lineId: "line-1", productId: "product-1", code: "P-1", barcode: "8430000000010",
              name: "Producto A", lineType: "PRODUCT", productType: "UNIT",
              refundableQuantity: "3.000", unitPrice: "10.00", refundableTotal: "30.00",
              refundableSerialNumbers: [], discount: "0.00", taxesIncluded: true,
              taxRegime: "IVA", taxPercentage: "21.00",
            },
            {
              lineId: "line-2", productId: "product-1", code: "P-1", barcode: "8430000000010",
              name: "Producto A", lineType: "PRODUCT", productType: "UNIT",
              refundableQuantity: "1.000", unitPrice: "5.00", refundableTotal: "5.00",
              refundableSerialNumbers: [], discount: "50.00", taxesIncluded: true,
              taxRegime: "IVA", taxPercentage: "21.00",
            },
          ],
        } as never);

    render(<TicketReturnDialog token="token" locale="es" onClose={vi.fn()} onAddToCart={vi.fn()} />);
    fireEvent.change(screen.getByLabelText(/ticket o ticket regalo/i), { target: { value: "T-001" } });
    fireEvent.click(screen.getByRole("button", { name: /Buscar ticket/i }));
    const productSearch = await screen.findByLabelText(/Buscar producto en este ticket/i);
    fireEvent.change(productSearch, { target: { value: "8430000000010" } });
    fireEvent.keyDown(productSearch, { key: "Enter" });
    expect(screen.getByText(/aparece en varias/i)).toBeInTheDocument();

    fireEvent.click(screen.getAllByRole("checkbox", { name: "Producto A" })[0]);
    expect(screen.getByRole("spinbutton")).toHaveValue(1);
    fireEvent.change(productSearch, { target: { value: "8430000000010" } });
    fireEvent.keyDown(productSearch, { key: "Enter" });
    expect(screen.getByRole("spinbutton")).toHaveValue(2);
  });
});
