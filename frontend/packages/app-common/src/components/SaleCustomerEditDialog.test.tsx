// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { createTranslator } from "../i18n/LocalizedMessages";
import type { UserSession } from "../types";
import type { CustomerView } from "./PartyDirectoryPanel";
import { SaleCustomerEditDialog } from "./SaleCustomerEditDialog";

vi.mock("../api/client", async original => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const session: UserSession = { username: "venta", displayName: "Venta", accessToken: "token", permissions: ["VENTA"] };
const first = {
  id: "00000000-0000-4000-8000-000000000001",
  address: { address: "CALLE PRIMERA", postalCode: "35120", city: "Mogán", province: "Las Palmas", country: "ES" },
  phone: "600111222", email: null, notes: "Principal",
};
const second = {
  id: "00000000-0000-4000-8000-000000000002",
  address: { ...first.address, address: "CALLE SEGUNDA" },
  phone: "600333444", email: null, notes: "Secundaria",
};
const customer: CustomerView = {
  id: "c1", clientId: "C-001", fiscalName: "Cliente original", documentType: "NIF", documentNumber: "12345678Z",
  active: true, isMember: true, version: 4, creditEnabled: true, paymentTermDays: 30,
  address: first.address, phone: first.phone, notes: first.notes, addresses: [first, second],
  addressBookRevision: 2, addressSyncStatus: "SYNCED",
};
let refreshed: CustomerView;

beforeEach(() => {
  localStorage.clear();
  refreshed = { ...customer, version: 7, addressBookRevision: 3 };
  request.mockReset().mockImplementation(async (path, options) => {
    if (path === "/commercial-contact-channels") return [];
    if (path === "/customers/c1" && options?.method === "PUT") return { ...refreshed, ...(options.body as object) };
    if (path === "/customers/c1") return customer;
    if (path === "/customers/c1/addresses/refresh" && options?.method === "POST") return refreshed;
    if (path === "/customers/c1/addresses/use-central" && options?.method === "POST")
      return { ...refreshed, version: 8, addressSyncStatus: "SYNCED", addresses: [second, first], address: second.address, phone: second.phone };
    throw new Error(`Unexpected request: ${path}`);
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

async function openEditor(onCreated = vi.fn()) {
  render(<SaleCustomerEditDialog locale="es" session={session} customerId="c1" onCancel={vi.fn()} onCreated={onCreated} />);
  const dialog = screen.getByRole("dialog", { name: "Modificar cliente" });
  await waitFor(() => expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeEnabled());
  return dialog;
}

function chooseSecondary(dialog: HTMLElement) {
  fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
  fireEvent.click(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }));
}

function putCalls() {
  return request.mock.calls.filter(([path, options]) => path === "/customers/c1" && options?.method === "PUT");
}

describe("SaleCustomerEditDialog", () => {
  it("loads the customer and refreshes its shared address book before enabling the grouped editor", async () => {
    const dialog = await openEditor();
    expect(request).toHaveBeenCalledWith("/customers/c1", expect.objectContaining({ token: "token" }));
    expect(request).toHaveBeenCalledWith("/customers/c1/addresses/refresh", expect.objectContaining({ method: "POST", token: "token" }));
    expect(within(dialog).getByLabelText("Nombre o razón social")).toHaveValue("Cliente original");
    expect(within(dialog).getByRole("heading", { name: createTranslator("es")("party.gestion.section.identity") })).toBeVisible();
    expect(within(dialog).getByRole("heading", { name: createTranslator("es")("party.gestion.section.personal") })).toBeVisible();
    expect(within(dialog).getByRole("button", { name: "Actualizar" })).toBeVisible();
    expect(within(dialog).queryByRole("checkbox", { name: "Crear miembro" })).not.toBeInTheDocument();
  });

  it("sends all addresses and the refreshed version on save, preserving existing membership", async () => {
    const onCreated = vi.fn();
    const dialog = await openEditor(onCreated);
    fireEvent.change(within(dialog).getByLabelText("Nombre o razón social"), { target: { value: "Cliente actualizado" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(putCalls()).toHaveLength(1));
    expect(putCalls()[0][1]).toEqual(expect.objectContaining({ token: "token", body: expect.objectContaining({
      fiscalName: "Cliente actualizado", expectedVersion: 7, isMember: true,
      addresses: [expect.objectContaining({ id: first.id }), expect.objectContaining({ id: second.id })],
    }) }));
    await waitFor(() => expect(onCreated).toHaveBeenCalledWith(expect.objectContaining({ id: "c1", fiscalName: "Cliente actualizado" })));
  });

  it("edits a secondary contact without replacing the default legacy contact", async () => {
    const dialog = await openEditor();
    chooseSecondary(dialog);
    fireEvent.change(within(dialog).getByLabelText("Teléfono"), { target: { value: "600999888" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(putCalls()).toHaveLength(1));
    const body = putCalls()[0][1]?.body as Record<string, any>;
    expect(body.phone).toBe(first.phone);
    expect(body.address.address).toBe(first.address.address);
    expect(body.addresses).toEqual([
      expect.objectContaining({ id: first.id, phone: first.phone }),
      expect.objectContaining({ id: second.id, phone: "600999888" }),
    ]);
  });

  it("adds and removes secondary contacts locally while protecting the default contact", async () => {
    const dialog = await openEditor();
    expect(within(dialog).queryByRole("button", { name: "Eliminar dirección" })).not.toBeInTheDocument();
    chooseSecondary(dialog);
    fireEvent.click(within(dialog).getByRole("button", { name: "Eliminar dirección" }));
    expect(within(dialog).getByLabelText("Dirección")).toHaveValue(first.address.address);
    expect(putCalls()).toHaveLength(0);
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: "Añadir nueva dirección" }));
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE TERCERA" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(putCalls()).toHaveLength(1));
    const body = putCalls()[0][1]?.body as Record<string, any>;
    expect(body.addresses).toHaveLength(2);
    expect(body.addresses[0]).toEqual(expect.objectContaining({ id: first.id }));
    expect(body.addresses[1].address.address).toBe("CALLE TERCERA");
    expect(body.address).toEqual(expect.objectContaining({ address: first.address.address }));
  });

  it.each(["PENDING", "CONFLICT"] as const)("preserves the exact %s address payload for an identity-only edit and blocks address changes", async status => {
    refreshed = { ...refreshed, addressSyncStatus: status };
    const dialog = await openEditor();
    fireEvent.change(within(dialog).getByLabelText("Nombre o razón social"), { target: { value: "Solo identidad" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(putCalls()).toHaveLength(1));
    const body = putCalls()[0][1]?.body as Record<string, any>;
    expect(body).toEqual(expect.objectContaining({ fiscalName: "Solo identidad", expectedVersion: 7,
      addresses: [first, second], address: first.address, phone: first.phone, notes: first.notes }));

    cleanup(); request.mockClear();
    const blocked = await openEditor();
    fireEvent.change(within(blocked).getByLabelText("Dirección"), { target: { value: "CAMBIO NO SINCRONIZADO" } });
    fireEvent.click(within(blocked).getByRole("button", { name: "Guardar" }));
    expect(within(blocked).getByLabelText("Dirección")).toHaveValue("CAMBIO NO SINCRONIZADO");
    expect(putCalls()).toHaveLength(0);
  });

  it("requires confirmation before using the central book after a conflict", async () => {
    refreshed = { ...refreshed, addressSyncStatus: "CONFLICT" };
    const dialog = await openEditor();
    fireEvent.click(within(dialog).getByRole("button", { name: "Actualizar" }));
    const confirmation = screen.getByRole("alertdialog", { name: "Actualizar direcciones" });
    expect(request.mock.calls.some(([path]) => path.endsWith("/use-central"))).toBe(false);
    fireEvent.click(within(confirmation).getByRole("button", { name: "Aceptar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/c1/addresses/use-central", expect.objectContaining({
      method: "POST", body: { expectedVersion: 7 },
    })));
    await waitFor(() => expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CALLE SEGUNDA"));
  });

  it("keeps the editor open when Escape dismisses a dropdown, then confirms discarding changed data", async () => {
    const onCancel = vi.fn();
    render(<SaleCustomerEditDialog locale="es" session={session} customerId="c1" onCancel={onCancel} onCreated={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "Modificar cliente" });
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeEnabled());
    fireEvent.change(within(dialog).getByLabelText("Nombre o razón social"), { target: { value: "Sin guardar" } });
    const picker = within(dialog).getByRole("button", { name: "Contacto y dirección" });
    fireEvent.click(picker);
    fireEvent.keyDown(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }), { key: "Escape" });
    expect(onCancel).not.toHaveBeenCalled();
    expect(screen.getByRole("dialog", { name: "Modificar cliente" })).toBeInTheDocument();
    fireEvent.keyDown(dialog, { key: "Escape" });
    const confirmation = screen.getByRole("alertdialog");
    expect(onCancel).not.toHaveBeenCalled();
    fireEvent.click(within(confirmation).getByRole("button", { name: "Confirmar" }));
    expect(onCancel).toHaveBeenCalledTimes(1);
  });

  it("disables saving when the initial customer load fails", async () => {
    request.mockImplementation(async path => {
      if (path === "/commercial-contact-channels") return [];
      if (path === "/customers/c1") throw new Error("customer unavailable");
      throw new Error(`Unexpected request: ${path}`);
    });
    render(<SaleCustomerEditDialog locale="es" session={session} customerId="c1" onCancel={vi.fn()} onCreated={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "Modificar cliente" });
    await screen.findByText("customer unavailable");
    expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeDisabled();
    expect(putCalls()).toHaveLength(0);
  });

  it("rejects a response for a different customer instead of editing it", async () => {
    const respond = request.getMockImplementation()!;
    request.mockImplementation((path, options) => path === "/customers/c1"
      ? Promise.resolve({ ...customer, id: "another-customer" })
      : respond(path, options));
    render(<SaleCustomerEditDialog locale="es" session={session} customerId="c1" onCancel={vi.fn()} onCreated={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "Modificar cliente" });
    await within(dialog).findByText(createTranslator("es")("party.loadError"));
    expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeDisabled();
    expect(request.mock.calls.some(([path]) => path.endsWith("/addresses/refresh"))).toBe(false);
    expect(putCalls()).toHaveLength(0);
  });

  it("keeps entered data and keyboard focus when a concurrent address update rejects saving", async () => {
    const respond = request.getMockImplementation()!;
    request.mockImplementation((path, options) => options?.method === "PUT"
      ? Promise.reject(new ApiError("opaque conflict", 409, { code: "CUSTOMER_ADDRESSES_VERSION_CONFLICT" }))
      : respond(path, options));
    const onCreated = vi.fn();
    const dialog = await openEditor(onCreated);
    const phone = within(dialog).getByLabelText("Teléfono");
    fireEvent.change(phone, { target: { value: "600999888" } });
    phone.focus();
    fireEvent.submit(dialog.querySelector("form")!);
    await within(dialog).findByText(createTranslator("es")("party.customerEditor.address.stale"));
    await waitFor(() => expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeEnabled());
    expect(phone).toHaveValue("600999888");
    expect(phone).toHaveFocus();
    expect(onCreated).not.toHaveBeenCalled();
  });

  it("allows cancelling a pending initial read and aborts it on unmount", () => {
    let loadSignal: AbortSignal | undefined;
    request.mockImplementation((path, options) => {
      if (path === "/commercial-contact-channels") return Promise.resolve([]);
      loadSignal = options?.signal;
      return new Promise(() => {});
    });
    const onCancel = vi.fn();
    const view = render(<SaleCustomerEditDialog locale="es" session={session} customerId="c1" onCancel={onCancel} onCreated={vi.fn()} />);
    const dialog = screen.getByRole("dialog", { name: "Modificar cliente" });
    expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeDisabled();
    fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
    expect(onCancel).toHaveBeenCalledOnce();
    view.unmount();
    expect(loadSignal?.aborted).toBe(true);
  });
});
