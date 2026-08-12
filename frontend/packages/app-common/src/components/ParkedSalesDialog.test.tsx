// @vitest-environment jsdom

import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { ParkedSalesDialog } from "./ParkedSalesDialog";

vi.mock("../api/client", () => ({ apiRequest: vi.fn() }));

const request = vi.mocked(apiRequest);
const summary = { id: "parked-1", createdAt: "2026-07-21T10:00:00Z", comment: "Mesa 1", total: "12.10" };
const secondSummary = { id: "parked-2", createdAt: "2026-07-21T10:05:00Z", comment: "Mesa 2", total: "8.20" };
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
    currentUsername="cashier"
    canManageSales={false}
    onClose={vi.fn()}
    onRecovered={vi.fn()}
    {...overrides}
  />);
}

describe("ParkedSalesDialog", () => {
  it("recovers the selected sale with Enter and acknowledges only after local restoration", async () => {
    request
      .mockResolvedValueOnce([summary])
      .mockResolvedValueOnce(recovery)
      .mockResolvedValueOnce({ ...recovery, status: "ACKNOWLEDGED" });
    const recovered = vi.fn().mockResolvedValue(undefined);
    show({ onRecovered: recovered });

    expect(await screen.findByRole("dialog", { name: "Ventas aparcadas" }))
      .toHaveClass("sale-business-dialog", "parked-sales-dialog");
    const list = await screen.findByRole("listbox", { name: "Ventas aparcadas" });
    await waitFor(() => expect(list).toHaveFocus());
    expect(screen.getByRole("button", { name: "Eliminar Mesa 1" }))
      .toHaveClass("parked-sales-delete-button");
    expect(screen.getByRole("button", { name: "Eliminar todo" }))
      .toHaveClass("parked-sales-delete-all-button");
    expect(screen.getByRole("button", { name: "Cerrar" }))
      .toHaveClass("parked-sales-close-button");
    fireEvent.keyDown(list, { key: "Enter" });

    await waitFor(() => expect(recovered).toHaveBeenCalledWith(opened));
    expect(request).toHaveBeenCalledWith(
      "/parked-sales/parked-1/recoveries",
      { token: "token", method: "POST", body: { recoveryId: "recovery-1" } },
    );
    expect(request).toHaveBeenCalledWith(
      "/parked-sales/parked-1/recoveries/recovery-1/acknowledge",
      { token: "token", method: "POST" },
    );
    expect(request.mock.invocationCallOrder[1]).toBeLessThan(recovered.mock.invocationCallOrder[0]);
    expect(recovered.mock.invocationCallOrder[0]).toBeLessThan(request.mock.invocationCallOrder[2]);
    expect(localStorage.getItem(storageKey)).toBeNull();
  });

  it("navigates with arrows and opens the highlighted sale", async () => {
    const secondOpened = { ...opened, comment: "Mesa 2" };
    request
      .mockResolvedValueOnce([summary, secondSummary])
      .mockResolvedValueOnce({ ...recovery, parkedSaleId: "parked-2", sale: secondOpened })
      .mockResolvedValueOnce({});
    const recovered = vi.fn().mockResolvedValue(undefined);
    show({ onRecovered: recovered });

    const list = await screen.findByRole("listbox");
    await screen.findByRole("option", { name: /Mesa 2/ });
    fireEvent.keyDown(list, { key: "ArrowDown" });
    expect(screen.getByRole("option", { name: /Mesa 2/ })).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(list, { key: "Enter" });

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/parked-sales/parked-2/recoveries",
      expect.objectContaining({ method: "POST" }),
    ));
    expect(recovered).toHaveBeenCalledWith(secondOpened);
  });

  it("keeps the claim and parked sale visible when local restoration fails", async () => {
    request.mockResolvedValueOnce([summary]).mockResolvedValueOnce(recovery);
    const recovered = vi.fn().mockRejectedValue(new Error("No se pudo reconstruir"));
    show({ onRecovered: recovered });

    const list = await screen.findByRole("listbox");
    fireEvent.keyDown(list, { key: "Enter" });

    expect(await screen.findByRole("alert")).toHaveTextContent("No se pudo reconstruir");
    expect(request).toHaveBeenCalledTimes(2);
    expect(screen.getByText("Mesa 1")).toBeInTheDocument();
    expect(localStorage.getItem(storageKey)).toBe("recovery-1");
  });

  it("deletes one sale after a Ctrl+F4-style warning without credentials", async () => {
    request.mockResolvedValueOnce([summary]).mockResolvedValueOnce(undefined);
    show();

    const parkedDialog = await screen.findByRole("dialog", { name: "Ventas aparcadas" });
    fireEvent.click(await screen.findByRole("button", { name: "Eliminar Mesa 1" }));
    const confirmation = screen.getByRole("dialog", { name: "Eliminar venta guardada" });
    expect(confirmation).toHaveClass("sale-business-dialog", "sale-clear-sale-dialog");
    expect(confirmation.parentElement).toHaveClass("sale-action-suboverlay");
    expect(parkedDialog).toHaveAttribute("aria-hidden", "true");
    const cancel = within(confirmation).getByRole("button", { name: "Cancelar" });
    const remove = within(confirmation).getByRole("button", { name: "Eliminar" });
    expect(cancel).toHaveFocus();
    fireEvent.keyDown(confirmation, { key: "ArrowRight" });
    expect(remove).toHaveFocus();
    fireEvent.click(remove);

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/parked-sales/parked-1/deletions",
      { token: "token", method: "POST" },
    ));
    await waitFor(() => expect(screen.queryByText("Mesa 1")).not.toBeInTheDocument());
  });

  it("requires only the current manager password to delete all", async () => {
    request.mockResolvedValueOnce([summary]).mockResolvedValueOnce({ deletedCount: 1 });
    show({ canManageSales: true, currentUsername: "manager" });

    const deleteAll = await screen.findByRole("button", { name: "Eliminar todo" });
    await waitFor(() => expect(deleteAll).toBeEnabled());
    fireEvent.click(deleteAll);
    const dialog = screen.getByRole("dialog", { name: "Eliminar todas las ventas guardadas" });
    expect(dialog).toHaveClass("sale-business-dialog", "parked-sales-delete-all-dialog");
    expect(dialog.parentElement).toHaveClass("sale-action-suboverlay");
    expect(within(dialog).queryByRole("textbox", { name: /Usuario autorizador/ })).not.toBeInTheDocument();
    fireEvent.change(within(dialog).getByLabelText(/contraseña/i), { target: { value: "secret" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Eliminar todo" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/parked-sales/deletions",
      { token: "token", method: "POST", body: { authorizerPassword: "secret" } },
    ));
  });

  it("allows delegated management authorization for delete all", async () => {
    request.mockResolvedValueOnce([summary]).mockResolvedValueOnce({ deletedCount: 1 });
    show({ canManageSales: false });

    const deleteAll = await screen.findByRole("button", { name: "Eliminar todo" });
    await waitFor(() => expect(deleteAll).toBeEnabled());
    fireEvent.click(deleteAll);
    const dialog = screen.getByRole("dialog", { name: "Eliminar todas las ventas guardadas" });
    fireEvent.change(within(dialog).getByRole("textbox", { name: /Usuario autorizador/ }), { target: { value: "manager" } });
    fireEvent.change(within(dialog).getByLabelText(/contraseña/i), { target: { value: "secret" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Eliminar todo" }));

    await waitFor(() => expect(request).toHaveBeenCalledWith(
      "/parked-sales/deletions",
      {
        token: "token",
        method: "POST",
        body: { authorizerUsername: "manager", authorizerPassword: "secret" },
      },
    ));
  });
});
