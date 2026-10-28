// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { PartyDirectoryPanel, type CustomerView, type MemberDirectoryView } from "./PartyDirectoryPanel";
import type { Permission, UserSession } from "../types";

vi.mock("../api/client", async original => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const addressA = { id: "00000000-0000-4000-8000-000000000001", address: { address: "CALLE PRIMERA", postalCode: "35120", city: "Mogán", province: "Las Palmas", country: "ES" }, phone: "600111222", email: null, notes: "Primera" };
const addressB = { ...addressA, id: "00000000-0000-4000-8000-000000000002", address: { ...addressA.address, address: "CALLE SEGUNDA" }, phone: "600333444", notes: "Segunda" };
const uppercaseA = { ...addressA, address: { ...addressA.address, city: "MOGÁN", province: "LAS PALMAS" } };
const uppercaseB = { ...addressB, address: { ...addressB.address, city: "MOGÁN", province: "LAS PALMAS" } };
const customer: CustomerView = {
  id: "customer-1", clientId: "C-001", fiscalName: "Cliente miembro", documentType: "NIF", documentNumber: "12345678Z",
  active: true, isMember: true, memberUuid: "member-1", numMember: "M-001", creditEnabled: true, version: 7,
  address: addressA.address, phone: addressA.phone, notes: addressA.notes, addresses: [addressA, addressB], addressBookRevision: 3, addressSyncStatus: "SYNCED",
};
const member: MemberDirectoryView = {
  id: "member-1", customerId: customer.id, clientId: customer.clientId, memberId: "M-001", numMember: "M-001",
  fiscalName: customer.fiscalName, documentType: customer.documentType, documentNumber: customer.documentNumber,
  memberSince: "2026-01-01", active: true, customerActive: true, balance: 25, points: 7,
};
let refreshed: CustomerView;
beforeEach(() => {
  localStorage.clear(); refreshed = customer;
  request.mockReset().mockImplementation(async (path, options) => {
    if (path === "/customers/customer-1" && options?.method === "PUT") return { ...refreshed, ...(options.body as object) };
    if (path === "/customers/customer-1/addresses/refresh") return refreshed;
    if (path === "/customers") return [customer];
    if (path === "/members") return [member];
    if (path === "/members/member-1") return { ...member, autoCategoryLocked: false };
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

async function editMember(permissions: Permission[] = ["ADMIN"]) {
  const session: UserSession = { username: "member-editor", displayName: "Editor", accessToken: "test", permissions };
  render(<PartyDirectoryPanel app="venta" kind="members" locale="es" session={session} />);
  await screen.findByText(customer.fiscalName);
  fireEvent.click(screen.getByText(customer.fiscalName).closest("[role=row]")!, { detail: 1 });
  const dialog = screen.getByRole("dialog", { name: "Ficha de miembro" });
  if (permissions.includes("ADMIN")) {
    await waitFor(() => expect(within(dialog).getByLabelText("Nombre o razón social")).not.toBeDisabled());
  }
  return dialog;
}

describe("Venta member editor", () => {
  it("uses the customer layout and refreshes the associated customer's shared address book", async () => {
    const dialog = await editMember();
    expect(dialog.querySelector(".party-customer-editor--venta")).toBeInTheDocument();
    const number = within(dialog).getByLabelText("Número de documento");
    const name = within(dialog).getByLabelText("Nombre o razón social");
    expect(number.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(name).toHaveFocus();
    const personal = dialog.querySelector(".party-form-section--personal")!;
    const communications = dialog.querySelector(".party-form-section--communications")!;
    const credit = dialog.querySelector(".party-credit-settings")!;
    expect(personal.nextElementSibling).toBe(communications);
    expect(communications.nextElementSibling).toBe(credit);
    const update = within(dialog).getByRole("button", { name: "Actualizar" });
    expect(update.closest("header")).toHaveClass("filter-header");
    refreshed = { ...customer, version: 8, addresses: [addressB, addressA], address: addressB.address, phone: addressB.phone };
    fireEvent.click(update);
    await waitFor(() => expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CALLE SEGUNDA"));
    expect(request).toHaveBeenCalledWith("/customers/customer-1/addresses/refresh", expect.objectContaining({ method: "POST" }));
    expect(request.mock.calls.some(([path]) => path.includes("/customers/member-1"))).toBe(false);
  });

  it("preserves customer edits across loyalty tabs and saves all addresses without changing membership or credit", async () => {
    const dialog = await editMember();
    fireEvent.change(within(dialog).getByLabelText("Nombre o razón social"), { target: { value: "Nombre editado" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }));
    fireEvent.change(within(dialog).getByLabelText("Notas"), { target: { value: "Nota editada" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Convertir a predeterminado" }));
    const tabs = within(dialog).getAllByRole("tab");
    fireEvent.keyDown(tabs[0], { key: "ArrowRight" });
    expect(tabs[1]).toHaveFocus();
    expect(tabs[1]).toHaveAttribute("aria-selected", "true");
    fireEvent.keyDown(tabs[1], { key: "Home" });
    expect(tabs[0]).toHaveFocus();
    expect(within(dialog).getByLabelText("Nombre o razón social")).toHaveValue("Nombre editado");
    expect(within(dialog).getByLabelText("Notas")).toHaveValue("Nota editada");
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/customer-1", expect.objectContaining({ method: "PUT", body: expect.objectContaining({
      fiscalName: "Nombre editado", isMember: true, numMember: "M-001", creditEnabled: true, expectedVersion: 7,
      address: uppercaseB.address, phone: addressB.phone, addresses: [{ ...uppercaseB, notes: "Nota editada" }, uppercaseA],
    }) })));
  });

  it("requires confirmation before discarding an edited secondary address", async () => {
    const dialog = await editMember();
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }));
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CAMBIO SIN GUARDAR" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
    expect(dialog).toBeInTheDocument();
    expect(request.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
  });

  it.each(["PENDING", "CONFLICT"] as const)("keeps mixed-case shared contacts unchanged during an identity-only save with a %s book", async status => {
    refreshed = { ...customer, addressSyncStatus: status };
    const dialog = await editMember();
    expect(within(dialog).getByLabelText("Población")).toHaveValue("MOGÁN");
    expect(within(dialog).getByRole("combobox", { name: "Provincia" })).toHaveValue("LAS PALMAS");
    fireEvent.change(within(dialog).getByLabelText("Nombre o razón social"), { target: { value: "Solo identidad miembro" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/customer-1", expect.objectContaining({ method: "PUT", body: expect.objectContaining({
      fiscalName: "Solo identidad miembro", isMember: true, numMember: "M-001", creditEnabled: true, expectedVersion: 7,
      address: addressA.address, phone: addressA.phone, notes: addressA.notes, addresses: [addressA, addressB],
    }) })));
  });

  it("saves a removed secondary address against the member's customer and preserves membership", async () => {
    const dialog = await editMember();
    expect(within(dialog).queryByRole("button", { name: "Eliminar dirección" })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Eliminar dirección" }));
    expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CALLE PRIMERA");
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/customer-1", expect.objectContaining({ method: "PUT", body: expect.objectContaining({
      isMember: true, numMember: "M-001", creditEnabled: true, expectedVersion: 7, addresses: [uppercaseA], address: uppercaseA.address,
    }) })));
  });

  it("keeps the member's customer fields and refresh action read-only without write permission", async () => {
    const dialog = await editMember(["CUSTOMERS_READ"]);
    expect(within(dialog).getByLabelText("Nombre o razón social")).toBeDisabled();
    expect(within(dialog).getByRole("button", { name: "Actualizar" })).toBeDisabled();
    expect(within(dialog).queryByRole("button", { name: "Guardar" })).not.toBeInTheDocument();
    expect(within(dialog).queryByRole("button", { name: "Desactivar" })).not.toBeInTheDocument();
  });
});
