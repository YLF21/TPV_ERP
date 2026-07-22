// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { ParkedSalesDialog } from "./ParkedSalesDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));

const request = vi.mocked(apiRequest);
const summary = { id: "parked-1", createdAt: "2026-07-21T10:00:00Z", comment: "Mesa 1", total: "12.10" };
const opened = { document: { lineas: [{ productoId: "product-1", cantidad: 1, descuento: 0 }] }, comment: "Mesa 1" };
const recovery = { recoveryId: "recovery-1", parkedSaleId: "parked-1", status: "CLAIMED", sale: opened } as const;
const storageKey = "tpverp:parked-sale-recovery:parked-1";

afterEach(cleanup);
beforeEach(() => {
  request.mockReset();
  localStorage.clear();
  localStorage.setItem(storageKey, "recovery-1");
});

function show(overrides: Partial<Parameters<typeof ParkedSalesDialog>[0]> = {}) {
  return render(<ParkedSalesDialog
    token="token"
    locale="es"
    currentSale={{}}
    canPark
    onClose={vi.fn()}
    onParked={vi.fn()}
    onRecovered={vi.fn()}
    {...overrides}
  />);
}

describe("ParkedSalesDialog", () => {
  it("acknowledges the claim only after the sale was restored locally", async () => {
    request
      .mockResolvedValueOnce([summary])
      .mockResolvedValueOnce(recovery)
      .mockResolvedValueOnce({ ...recovery, status: "ACKNOWLEDGED" });
    const recovered = vi.fn().mockResolvedValue(undefined);
    show({ onRecovered: recovered });

    fireEvent.click(await screen.findByRole("button", { name: "Recuperar Mesa 1" }));

    await waitFor(() => expect(recovered).toHaveBeenCalledWith(opened));
    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/parked-sales/parked-1/recoveries/recovery-1/acknowledge",
      { token: "token", method: "POST" }
    ));
    expect(request).toHaveBeenCalledWith(
      "/parked-sales/parked-1/recoveries",
      { token: "token", method: "POST", body: { recoveryId: "recovery-1" } }
    );
    expect(request.mock.invocationCallOrder[1]).toBeLessThan(recovered.mock.invocationCallOrder[0]);
    expect(recovered.mock.invocationCallOrder[0]).toBeLessThan(request.mock.invocationCallOrder[2]);
    expect(localStorage.getItem(storageKey)).toBeNull();
  });

  it("keeps the recovery claim and parked sale visible when local restoration fails", async () => {
    request
      .mockResolvedValueOnce([summary])
      .mockResolvedValueOnce(recovery);
    const recovered = vi.fn().mockRejectedValue(new Error("No se pudo reconstruir"));
    show({ onRecovered: recovered });

    fireEvent.click(await screen.findByRole("button", { name: "Recuperar Mesa 1" }));

    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo reconstruir");
    expect(request).toHaveBeenCalledTimes(2);
    expect(screen.getByText("Mesa 1")).toBeInTheDocument();
    expect(localStorage.getItem(storageKey)).toBe("recovery-1");
  });

  it("replays an acknowledged recovery without rebuilding the cart twice", async () => {
    request
      .mockResolvedValueOnce([summary])
      .mockResolvedValueOnce({ ...recovery, status: "ACKNOWLEDGED" });
    const recovered = vi.fn();
    show({ onRecovered: recovered });

    fireEvent.click(await screen.findByRole("button", { name: "Recuperar Mesa 1" }));

    await waitFor(() => expect(screen.queryByText("Mesa 1")).not.toBeInTheDocument());
    expect(recovered).not.toHaveBeenCalled();
    expect(request).toHaveBeenCalledTimes(2);
    expect(localStorage.getItem(storageKey)).toBeNull();
  });

  it("traps focus, closes with Escape and restores the previous focus", async () => {
    request.mockResolvedValueOnce([]);
    const opener = document.createElement("button");
    opener.textContent = "Abrir ventas aparcadas";
    document.body.append(opener);
    opener.focus();
    const close = vi.fn();
    const view = show({ onClose: close });

    await waitFor(() => expect(screen.getByPlaceholderText("Mesa, cliente o referencia")).toHaveFocus());
    const headerClose = screen.getByRole("button", { name: "Cerrar Ventas aparcadas" });
    const footerClose = screen.getByRole("button", { name: "Cerrar" });
    headerClose.focus();
    fireEvent.keyDown(document, { key: "Tab", shiftKey: true });
    expect(footerClose).toHaveFocus();
    fireEvent.keyDown(document, { key: "Tab" });
    expect(headerClose).toHaveFocus();

    fireEvent.keyDown(document, { key: "Escape" });
    expect(close).toHaveBeenCalledOnce();
    view.unmount();
    expect(opener).toHaveFocus();
    opener.remove();
  });

  it("explains the empty state and allows reloading it", async () => {
    request.mockResolvedValueOnce([]).mockResolvedValueOnce([]);
    show({ canPark: false });

    expect(await screen.findByText("No hay ventas aparcadas.")).toBeInTheDocument();
    expect(screen.getByText(/Aparca un ticket en curso/)).toBeInTheDocument();
    expect(screen.getByText(/Añade al menos un producto/)).toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Volver a cargar" }));
    await waitFor(() => expect(request).toHaveBeenCalledTimes(2));
  });
});
