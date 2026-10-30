// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, waitFor } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { WarehouseCustomerDialog } from "./WarehouseCustomerDialog";

vi.mock("../api/client", async importOriginal => ({ ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn() }));
vi.mock("./SaleCustomerCreateDialog", async importOriginal => ({
  ...await importOriginal<typeof import("./SaleCustomerCreateDialog")>(),
  SaleCustomerCreateDialog: ({ customerId, onCancel, onCreated }: {
    customerId?: string; onCancel: () => void; onCreated: (customer: { id: string }) => void;
  }) => <div role="dialog" aria-label={customerId ? "Ficha compartida" : "Alta compartida"}>
    <p>{customerId}</p><button onClick={() => onCreated({ id: customerId ?? "new-customer" })}>Guardar ficha de prueba</button>
    <button onClick={onCancel}>Volver al listado</button>
  </div>
}));

const customer = { id: "customer-1", clientId: "C-001", fiscalName: "Cliente", active: true, outstandingDebt: 12 };
const session: UserSession = { username: "test", displayName: "Test", permissions: ["ADMIN"], accessToken: "token" };
const props = { session, locale: "es" as const, selectedCustomerId: customer.id,
  onSelect: vi.fn(), onChanged: vi.fn(), onClose: vi.fn() };

beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(apiRequest).mockImplementation(async path => path.includes("/search?") ? [customer] : customer);
});
afterEach(cleanup);

describe("WarehouseCustomerDialog", () => {
  it("has create/edit actions, no-customer row and Insert selection without debt collection", async () => {
    const view = render(<WarehouseCustomerDialog {...props} />);
    await view.findByRole("button", { name: "C-001 · Cliente" });
    expect(view.getByRole("button", { name: "Nuevo clienteF5" })).toBeEnabled();
    expect(view.getByRole("button", { name: "Modificar clienteCtrl+F7" })).toBeEnabled();
    expect(view.queryByRole("button", { name: /Cobrar deuda/ })).toBeNull();
    const dialog = view.getByRole("dialog", { name: "Seleccionar cliente" });
    fireEvent.keyDown(dialog, { key: "Insert", repeat: true });
    expect(props.onSelect).not.toHaveBeenCalled();
    fireEvent.keyDown(dialog, { key: "Insert" });
    expect(props.onSelect).toHaveBeenLastCalledWith(customer);
    fireEvent.doubleClick(view.getByRole("button", { name: "Sin cliente" }));
    expect(props.onSelect).toHaveBeenLastCalledWith(null);
  });

  it("keeps create/edit shortcuts and does not open debt collection on Enter", async () => {
    const view = render(<WarehouseCustomerDialog {...props} />);
    await view.findByRole("button", { name: "C-001 · Cliente" });
    fireEvent.keyDown(view.getByRole("dialog", { name: "Seleccionar cliente" }), { key: "F5" });
    expect(view.getByRole("dialog", { name: "Alta compartida" })).toBeVisible();
    fireEvent.click(view.getByRole("button", { name: "Volver al listado" }));
    await view.findByRole("button", { name: "C-001 · Cliente" });
    fireEvent.keyDown(view.getByRole("dialog", { name: "Seleccionar cliente" }), { key: "F7", ctrlKey: true });
    expect(view.getByRole("dialog", { name: "Ficha compartida" })).toHaveTextContent(customer.id);
    fireEvent.click(view.getByRole("button", { name: "Volver al listado" }));
    await view.findByRole("button", { name: "C-001 · Cliente" });
    const callsBeforeEnter = vi.mocked(apiRequest).mock.calls.length;
    fireEvent.keyDown(view.getByRole("dialog", { name: "Seleccionar cliente" }), { key: "Enter" });
    expect(view.getAllByRole("dialog")).toHaveLength(1);
    expect(view.getByRole("dialog", { name: "Seleccionar cliente" })).toBeVisible();
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(vi.mocked(apiRequest)).toHaveBeenCalledTimes(callsBeforeEnter);
    expect(vi.mocked(apiRequest).mock.calls.every(([, options]) => !options?.method || options.method === "GET")).toBe(true);
    fireEvent.click(view.getByRole("button", { name: "Sin cliente" }));
    expect(view.getByRole("button", { name: "Modificar clienteCtrl+F7" })).toBeDisabled();
    expect(view.queryByRole("button", { name: /Cobrar deuda/ })).toBeNull();
  });

  it("keeps create and edit unavailable without the same permissions as Sales", async () => {
    const view = render(<WarehouseCustomerDialog {...props} session={{ ...session, permissions: ["GESTION_ALMACEN"] }} />);
    await view.findByRole("button", { name: "C-001 · Cliente" });
    expect(view.queryByRole("button", { name: /Nuevo cliente|Modificar cliente|Cobrar deuda/ })).toBeNull();
    const dialog = view.getByRole("dialog", { name: "Seleccionar cliente" });
    for (const event of [{ key: "F5" }, { key: "F7", ctrlKey: true }, { key: "Enter" }]) fireEvent.keyDown(dialog, event);
    expect(view.queryByRole("dialog", { name: /compartid/ })).toBeNull();
    expect(props.onSelect).not.toHaveBeenCalled();
  });

  it("returns the created customer and refreshes the edited customer without selecting it", async () => {
    const saved = { ...customer, id: "new-customer", clientId: "C-002" };
    vi.mocked(apiRequest).mockImplementation(async path => path.includes("/search?") ? [customer] : saved);
    const view = render(<WarehouseCustomerDialog {...props} />);
    await view.findByRole("button", { name: "C-001 · Cliente" });
    fireEvent.click(view.getByRole("button", { name: "Nuevo clienteF5" }));
    fireEvent.click(view.getByRole("button", { name: "Guardar ficha de prueba" }));
    await waitFor(() => expect(props.onSelect).toHaveBeenCalledWith(saved));
    expect(props.onChanged).toHaveBeenCalledWith(saved);
    view.unmount();
    props.onSelect.mockClear();
    const editView = render(<WarehouseCustomerDialog {...props} />);
    await editView.findByRole("button", { name: "C-001 · Cliente" });
    fireEvent.click(editView.getByRole("button", { name: "Modificar clienteCtrl+F7" }));
    fireEvent.click(editView.getByRole("button", { name: "Guardar ficha de prueba" }));
    await editView.findByRole("button", { name: "C-001 · Cliente" });
    expect(props.onSelect).not.toHaveBeenCalled();
    expect(props.onChanged).toHaveBeenCalledWith(saved);
  });

  it("does not select stale results after a failed search", async () => {
    const view = render(<WarehouseCustomerDialog {...props} />);
    await view.findByRole("button", { name: "C-001 · Cliente" });
    vi.mocked(apiRequest).mockRejectedValue(new Error("offline"));
    fireEvent.change(view.getByRole("textbox", { name: "Buscar cliente" }), { target: { value: "otro" } });
    await waitFor(() => expect(view.queryByRole("button", { name: "C-001 · Cliente" })).toBeNull());
    fireEvent.keyDown(view.getByRole("dialog", { name: "Seleccionar cliente" }), { key: "Insert" });
    expect(view.getByRole("button", { name: "Seleccionar cliente" })).toBeDisabled();
    expect(props.onSelect).not.toHaveBeenCalled();
  });
});
