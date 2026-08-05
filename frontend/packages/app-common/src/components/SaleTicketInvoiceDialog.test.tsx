// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { SaleTicketInvoiceDialog } from "./SaleTicketInvoiceDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);

describe("SaleTicketInvoiceDialog", () => {
  beforeEach(() => {
    request.mockReset();
    request.mockImplementation(async (path) => {
      if (path === "/tickets/last-current-terminal") {
        return {
          id: "ticket-1",
          numero: "T-001",
          fecha: "2026-07-30",
          total: "15.00",
        } as never;
      }
      if (path === "/customers/sale-options") {
        return [{ id: "customer-1", clientId: "C1", fiscalName: "Cliente Uno" }] as never;
      }
      if (path === "/tickets/ticket-1/invoice") return {} as never;
      throw new Error(`Unexpected request: ${path}`);
    });
  });

  afterEach(cleanup);

  it("defaults to the latest terminal ticket and converts it for the selected customer", async () => {
    const onFiscalMutation = vi.fn();
    render(
      <SaleTicketInvoiceDialog
        token="token"
        locale="es"
        onClose={vi.fn()}
        onFiscalMutation={onFiscalMutation}
      />,
    );

    expect(await screen.findByText("T-001")).toBeInTheDocument();
    fireEvent.change(screen.getByLabelText("Cliente fiscal"), {
      target: { value: "customer-1" },
    });
    fireEvent.click(screen.getByRole("button", { name: "Crear factura" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/tickets/ticket-1/invoice",
      {
        token: "token",
        body: { customerId: "customer-1" },
      },
    ));
    expect(onFiscalMutation).toHaveBeenCalledOnce();
  });

  it("loads the selected ticket number when opened from a report", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/tickets/by-number?number=T-REPORT-7") {
        return {
          id: "ticket-report-7",
          numero: "T-REPORT-7",
          fecha: "2026-08-05",
          total: "31.50",
        } as never;
      }
      if (path === "/customers/sale-options") return [] as never;
      throw new Error(`Unexpected request: ${path}`);
    });

    render(
      <SaleTicketInvoiceDialog
        token="token"
        locale="es"
        initialTicketNumber="T-REPORT-7"
        onClose={vi.fn()}
      />,
    );

    expect(await screen.findByText("T-REPORT-7")).toBeInTheDocument();
    expect(request).toHaveBeenCalledWith(
      "/tickets/by-number?number=T-REPORT-7",
      { token: "token" },
    );
    expect(request).not.toHaveBeenCalledWith(
      "/tickets/last-current-terminal",
      expect.anything(),
    );
  });
});
