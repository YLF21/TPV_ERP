// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { getHardwareBridge } from "../hardware/hardware";
import { SaleTicketCancellationDialog } from "./SaleTicketCancellationDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
vi.mock("../hardware/hardware", () => ({ getHardwareBridge: vi.fn() }));

const request = vi.mocked(apiRequest);
const hardware = vi.mocked(getHardwareBridge);

const preview = {
  ticket: {
    id: "ticket-1",
    numero: "T-001",
    fecha: "2026-07-30",
    total: "25.00",
  },
  manualReferences: [{
    paymentId: "payment-1",
    paymentMethod: "TRANSFERENCIA",
    amount: "25.00",
  }],
  integratedCardPayments: [],
  cashAmount: "0.00",
  openCashDrawer: false,
  consumedVoucherCodes: [],
  generatedVoucherCodes: [],
};

describe("SaleTicketCancellationDialog", () => {
  beforeEach(() => {
    request.mockReset();
    hardware.mockReset();
    localStorage.clear();
    hardware.mockReturnValue({
      openCashDrawer: vi.fn().mockResolvedValue({ ok: true }),
      printTicket: vi.fn().mockResolvedValue({ ok: true }),
    } as never);
  });

  afterEach(() => {
    cleanup();
    localStorage.clear();
  });

  it("loads the latest ticket and requires the privileged user's password", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/tickets/cancellation-preview/last") return preview as never;
      if (path === "/tickets/ticket-1/cancel") {
        return {
          ticket: preview.ticket,
          restoredVouchers: [],
          invalidatedVoucherCodes: [],
          openCashDrawer: false,
        } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    const onFiscalMutation = vi.fn();
    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["GESTION_VENTAS"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="LAST"
        onClose={vi.fn()}
        onFiscalMutation={onFiscalMutation}
      />,
    );

    expect(await screen.findByText("T-001")).toBeInTheDocument();
    expect(screen.queryByLabelText("Usuario autorizador")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Motivo"), {
      target: { value: "Error de cobro" },
    });
    fireEvent.change(screen.getByLabelText(/Referencia de devoluci/), {
      target: { value: "DEV-123" },
    });
    fireEvent.change(screen.getByLabelText(/contrase/i), {
      target: { value: "secret" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Anular y compensar" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/ticket-1/cancel",
      expect.objectContaining({
        token: "token",
        body: expect.objectContaining({
          reason: "Error de cobro",
          authorizerPassword: "secret",
          manualCompensations: { "payment-1": "DEV-123" },
        }),
      }),
    ));
    expect(onFiscalMutation).toHaveBeenCalledOnce();
  });

  it("requests delegated credentials from a seller", async () => {
    request.mockResolvedValue(preview as never);
    render(
      <SaleTicketCancellationDialog
        token="token"
        locale="es"
        permissions={["VENTA"]}
        terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
        mode="LAST"
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findByLabelText("Usuario autorizador")).toBeInTheDocument();
  });
});
