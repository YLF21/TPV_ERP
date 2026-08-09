// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
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
    expect(screen.getByText("1 productos · 5 unidades")).toBeInTheDocument();
    expect(screen.queryByText(/Este justificante no muestra precios/)).not.toBeInTheDocument();
    expect(screen.queryByText(/Busca el ticket, selecciona/)).not.toBeInTheDocument();
  });

  it("prefills and fully selects the last ticket from the current terminal", async () => {
    request.mockImplementation(async (path) => {
      if (path === "/tickets/last-current-terminal") {
        return { numero: "001-260809-00008" } as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    render(<GiftReceiptDialog
      token="token"
      locale="es"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      onClose={vi.fn()}
    />);

    const input = screen.getByLabelText(/N.º de ticket/) as HTMLInputElement;
    await waitFor(() => expect(input).toHaveValue("001-260809-00008"));
    await waitFor(() => expect(document.activeElement).toBe(input));
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(input.value.length);
  });

  it("does not replace a ticket number typed before the default finishes loading", async () => {
    let resolveLastTicket!: (value: unknown) => void;
    request.mockImplementation((path) => {
      if (path === "/tickets/last-current-terminal") {
        return new Promise((resolve) => { resolveLastTicket = resolve; }) as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });

    render(<GiftReceiptDialog
      token="token"
      locale="es"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      onClose={vi.fn()}
    />);

    const input = screen.getByLabelText(/N.º de ticket/) as HTMLInputElement;
    fireEvent.change(input, { target: { value: "T-MANUAL" } });
    resolveLastTicket({ numero: "001-260809-00008" });

    await waitFor(() => expect(input).toHaveValue("T-MANUAL"));
  });

  it("shows a clear message without a technical reference when the ticket does not exist", async () => {
    request.mockRejectedValue(new ApiError(
      "La solicitud contiene datos no válidos (Ref: trace-123)",
      404,
      { code: "TICKET_NOT_FOUND", detail: "Ticket no encontrado" },
      "trace-123",
    ));

    render(<GiftReceiptDialog
      token="token"
      locale="es"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      onClose={vi.fn()}
    />);
    fireEvent.change(screen.getByLabelText("N.º de ticket"), { target: { value: "T-INEXISTENTE" } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar ticket" }));

    expect(await screen.findByText("Ticket no encontrado")).toBeInTheDocument();
    expect(screen.queryByText(/Ref:/)).not.toBeInTheDocument();
    expect(screen.queryByText(/datos no válidos/)).not.toBeInTheDocument();
  });

  it("recognizes the legacy validation response from a backend pending restart", async () => {
    request.mockRejectedValue(new ApiError(
      "La solicitud contiene datos no válidos",
      400,
      { code: "VALIDATION_ERROR", detail: "La solicitud contiene datos no válidos" },
    ));

    render(<GiftReceiptDialog
      token="token"
      locale="es"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      onClose={vi.fn()}
    />);
    fireEvent.change(screen.getByLabelText("N.º de ticket"), { target: { value: "T-INEXISTENTE" } });
    fireEvent.click(screen.getByRole("button", { name: "Buscar ticket" }));

    expect(await screen.findByText("Ticket no encontrado")).toBeInTheDocument();
    expect(screen.queryByText(/datos no válidos/)).not.toBeInTheDocument();
  });

  it("closes with Escape while no operation is running", () => {
    const onClose = vi.fn();
    request.mockResolvedValue({ numero: null } as never);
    render(<GiftReceiptDialog
      token="token"
      locale="es"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      onClose={onClose}
    />);

    fireEvent.keyDown(window, { key: "Escape" });

    expect(onClose).toHaveBeenCalledTimes(1);
  });
});
