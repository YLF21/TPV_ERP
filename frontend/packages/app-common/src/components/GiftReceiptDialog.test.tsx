// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { GiftReceiptDialog } from "./GiftReceiptDialog";

vi.mock("../api/client", async () => {
  const actual = await vi.importActual<typeof import("../api/client")>("../api/client");
  return { ...actual, apiRequest: vi.fn() };
});

const request = vi.mocked(apiRequest);

function ticketPreview(ticketNumber: string) {
  return {
    ticketId: `id-${ticketNumber}`,
    ticketNumber,
    issuedAt: "2026-08-03T10:00:00Z",
    lines: [{
      lineId: `line-${ticketNumber}`,
      code: `P-${ticketNumber}`,
      name: `Producto ${ticketNumber}`,
      productType: "UNIT",
      availableQuantity: "2.000",
      serialNumbers: [],
    }],
  };
}

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
    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith("/tickets/last-current-terminal", { token: "token" });
  });

  it("loads the selected ticket without looking up the last ticket or issuing a receipt", async () => {
    request.mockResolvedValue(ticketPreview("T-SELECTED") as never);

    render(<GiftReceiptDialog
      token="token"
      locale="es"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      initialTicketNumber=" T-SELECTED "
      onClose={vi.fn()}
    />);

    expect(await screen.findByText("P-T-SELECTED")).toBeInTheDocument();
    const input = screen.getByLabelText("N.º de ticket") as HTMLInputElement;
    expect(input).toHaveValue("T-SELECTED");
    await waitFor(() => expect(input).toHaveFocus());
    expect(input.selectionStart).toBe(0);
    expect(input.selectionEnd).toBe(input.value.length);
    expect(screen.getByRole("checkbox", { name: "Seleccionar producto: Producto T-SELECTED" })).not.toBeChecked();
    expect(screen.getByRole("button", { name: "Generar e imprimir" })).toBeDisabled();
    expect(request).toHaveBeenCalledTimes(1);
    expect(request).toHaveBeenCalledWith("/gift-receipts/preview?ticketNumber=T-SELECTED", { token: "token" });
  });

  it.each(["success", "error"])("ignores an obsolete preview %s when the selected ticket changes", async (outcome) => {
    let resolveOld!: (value: unknown) => void;
    let rejectOld!: (reason: Error) => void;
    let resolveCurrent!: (value: unknown) => void;
    request.mockImplementation((path) => {
      if (path === "/gift-receipts/preview?ticketNumber=T-OLD") {
        return new Promise((resolve, reject) => {
          resolveOld = resolve;
          rejectOld = reject;
        }) as never;
      }
      if (path === "/gift-receipts/preview?ticketNumber=T-CURRENT") {
        return new Promise((resolve) => { resolveCurrent = resolve; }) as never;
      }
      throw new Error(`Unexpected request: ${path}`);
    });
    const props = {
      token: "token",
      locale: "es" as const,
      terminalContext: { storeName: "Tienda", terminalCode: "01" },
      onClose: vi.fn(),
    };
    const { rerender } = render(<GiftReceiptDialog {...props} initialTicketNumber="T-OLD" />);
    rerender(<GiftReceiptDialog {...props} initialTicketNumber="T-CURRENT" />);

    await act(async () => {
      if (outcome === "success") resolveOld(ticketPreview("T-OLD"));
      else rejectOld(new Error("Obsolete request failed"));
    });
    expect(screen.getByRole("dialog")).toHaveAttribute("aria-busy", "true");
    expect(screen.getByLabelText("N.º de ticket")).toHaveValue("T-CURRENT");
    expect(screen.queryByText("P-T-OLD")).not.toBeInTheDocument();
    expect(screen.queryByRole("alert")).not.toBeInTheDocument();

    await act(async () => { resolveCurrent(ticketPreview("T-CURRENT")); });
    expect(screen.getByText("P-T-CURRENT")).toBeInTheDocument();
    expect(screen.getByRole("dialog")).toHaveAttribute("aria-busy", "false");
    expect(request).toHaveBeenCalledTimes(2);
  });

  it("ignores a preview that finishes after the dialog is unmounted", async () => {
    let resolvePreview!: (value: unknown) => void;
    request.mockImplementation(() => new Promise((resolve) => { resolvePreview = resolve; }) as never);
    const { unmount } = render(<GiftReceiptDialog
      token="token"
      locale="es"
      terminalContext={{ storeName: "Tienda", terminalCode: "01" }}
      initialTicketNumber="T-CLOSED"
      onClose={vi.fn()}
    />);
    unmount();
    render(<button type="button">Acción del informe</button>);
    const reportAction = screen.getByRole("button", { name: "Acción del informe" });
    reportAction.focus();

    await act(async () => {
      resolvePreview(ticketPreview("T-CLOSED"));
      await new Promise((resolve) => globalThis.setTimeout(resolve, 0));
    });

    expect(reportAction).toHaveFocus();
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(request).toHaveBeenCalledTimes(1);
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
