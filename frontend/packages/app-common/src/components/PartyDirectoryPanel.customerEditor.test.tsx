// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, apiRequest } from "../api/client";
import { PartyDirectoryPanel, type CustomerView } from "./PartyDirectoryPanel";
import type { UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";

vi.mock("../api/client", async original => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const session: UserSession = { username: "customer-editor", displayName: "Editor", accessToken: "test", permissions: ["ADMIN"] };
const addressA = { id: "00000000-0000-4000-8000-000000000001", address: { address: "CALLE PRIMERA", postalCode: "35120", city: "Mogán", province: "Las Palmas", country: "ES" }, phone: "600111222", email: null, notes: "Primera" };
const addressB = { id: "00000000-0000-4000-8000-000000000002", address: { ...addressA.address, address: "CALLE SEGUNDA" }, phone: "600333444", email: null, notes: "Segunda" };
const customer: CustomerView = { id: "c1", clientId: "C-001", fiscalName: "Cliente existente", documentType: "NIF", documentNumber: "12345678Z", active: true, isMember: false, version: 7, creditEnabled: true, address: addressA.address, phone: addressA.phone, notes: addressA.notes, addresses: [addressA, addressB], addressBookRevision: 3, addressSyncStatus: "SYNCED" };
let refreshed: CustomerView;

beforeEach(() => {
  localStorage.clear(); refreshed = customer;
  request.mockReset().mockImplementation(async (path, options) => {
    if (path === "/customers" && options?.method === "POST") return { ...customer, ...(options.body as object) };
    if (path === "/customers/c1" && options?.method === "PUT") return { ...refreshed, ...(options.body as object) };
    if (path === "/customers/c1/addresses/refresh") return refreshed;
    if (path === "/customers/c1/addresses/use-central") return { ...customer, version: 8, addresses: [addressB, addressA], address: addressB.address, phone: addressB.phone };
    if (path === "/customers/central-lookup") throw new ApiError("not found", 404, { code: "CUSTOMER_CENTRAL_NOT_FOUND" });
    if (path === "/customers") return [customer];
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

async function directory() {
  render(<PartyDirectoryPanel app="venta" kind="customers" locale="es" session={session} />);
  await screen.findByText(customer.fiscalName);
}
async function newCustomer() {
  await directory();
  fireEvent.click(screen.getByRole("button", { name: "Nuevo cliente F8" }));
  return screen.getByRole("dialog", { name: "Nuevo cliente" });
}
async function editCustomer() {
  await directory();
  fireEvent.click(screen.getByText(customer.fiscalName).closest("[role=row]")!, { detail: 1 });
  fireEvent.click(screen.getByRole("button", { name: "Modificar cliente F7" }));
  const dialog = screen.getByRole("dialog");
  await waitFor(() => expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeEnabled());
  return dialog;
}
function fillIdentity(dialog: HTMLElement) {
  fireEvent.change(within(dialog).getByLabelText("Número de documento"), { target: { value: "87654321X" } });
  fireEvent.change(within(dialog).getByLabelText("Nombre o razón social"), { target: { value: "Nuevo cliente" } });
}
function selectNewAddress(dialog: HTMLElement) {
  fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
  const options = screen.getAllByRole("option");
  expect(options.at(-1)).toHaveTextContent("Añadir nueva dirección");
  fireEvent.click(screen.getByRole("option", { name: "Añadir nueva dirección" }));
}

describe("Venta customer editor", () => {
  it("starts with document fields, credit disabled and no legacy reuse help", async () => {
    const dialog = await newCustomer();
    const number = within(dialog).getByLabelText("Número de documento");
    const name = within(dialog).getByLabelText("Nombre o razón social");
    expect(number).toHaveFocus();
    expect(number.compareDocumentPosition(name) & Node.DOCUMENT_POSITION_FOLLOWING).toBeTruthy();
    expect(within(dialog).getByRole("checkbox", { name: "Permitir ventas a crédito" })).not.toBeChecked();
    expect(within(dialog).getByRole("checkbox", { name: "Crear miembro" })).not.toBeChecked();
    expect(within(dialog).queryByText(/¿Ya existe en otra tienda/)).not.toBeInTheDocument();
    expect(within(dialog).getByRole("button", { name: "Validar" })).toBeDisabled();
  });

  it("shows the existing SaaS identity and Accept closes the complete new-customer window", async () => {
    const respond = request.getMockImplementation()!;
    request.mockImplementation(async (path, options) => path === "/customers/central-lookup" ? { centralCode: "C-009999", documentNumber: "87654321X" } : respond(path, options));
    const dialog = await newCustomer(); fillIdentity(dialog);
    fireEvent.click(within(dialog).getByRole("button", { name: "Validar" }));
    const notice = await screen.findByRole("alertdialog", { name: "Cliente existente" });
    expect(notice).toHaveTextContent("El cliente con documento 87654321X ya existe Código cliente: C-009999");
    fireEvent.click(within(notice).getByRole("button", { name: "Aceptar" }));
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(screen.queryByRole("alertdialog")).not.toBeInTheDocument();
    expect(request.mock.calls.some(([path, options]) => path === "/customers/adopt-central" || path === "/customers" && options?.method === "POST")).toBe(false);
  });

  it("saves every contact while only the chosen default supplies legacy fields", async () => {
    const dialog = await newCustomer(); fillIdentity(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE NUEVA A" } });
    fireEvent.change(within(dialog).getByLabelText("Teléfono"), { target: { value: "600111222" } });
    selectNewAddress(dialog);
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CALLE NUEVA B" } });
    fireEvent.change(within(dialog).getByLabelText("Teléfono"), { target: { value: "600333444" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers", expect.objectContaining({ method: "POST", body: expect.objectContaining({
      isMember: false, creditEnabled: false, address: expect.objectContaining({ address: "CALLE NUEVA A" }), phone: "600111222",
      addresses: [expect.objectContaining({ address: expect.objectContaining({ address: "CALLE NUEVA A" }), phone: "600111222" }), expect.objectContaining({ address: expect.objectContaining({ address: "CALLE NUEVA B" }), phone: "600333444" })],
    }) })));
  });

  it("requests direct membership only when checked and resets the option for the next customer", async () => {
    const dialog = await newCustomer(); fillIdentity(dialog);
    const createMember = within(dialog).getByRole("checkbox", { name: "Crear miembro" });
    expect(createMember.closest(".party-form-section--identity")).toBeInTheDocument();
    fireEvent.click(createMember);
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers", expect.objectContaining({ method: "POST", body: expect.objectContaining({
      isMember: true, numMember: null, creditEnabled: false, addresses: expect.any(Array),
    }) })));
    expect(request.mock.calls.some(([path]) => path.endsWith("/member/activate"))).toBe(false);
    await waitFor(() => expect(screen.queryByRole("dialog")).not.toBeInTheDocument());
    fireEvent.click(screen.getByRole("button", { name: "Nuevo cliente F8" }));
    expect(within(screen.getByRole("dialog")).getByRole("checkbox", { name: "Crear miembro" })).not.toBeChecked();
  });

  it("does not create a member after the option is unchecked", async () => {
    const dialog = await newCustomer(); fillIdentity(dialog);
    const createMember = within(dialog).getByRole("checkbox", { name: "Crear miembro" });
    fireEvent.click(createMember); fireEvent.click(createMember);
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers", expect.objectContaining({ method: "POST", body: expect.objectContaining({ isMember: false }) })));
  });

  it("treats the membership checkbox as an unsaved change and hides it when editing", async () => {
    const dialog = await newCustomer();
    fireEvent.click(within(dialog).getByRole("checkbox", { name: "Crear miembro" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
    const confirm = screen.getByRole("alertdialog");
    fireEvent.click(within(confirm).getByRole("button", { name: "Confirmar" }));
    fireEvent.click(screen.getByText(customer.fiscalName).closest("[role=row]")!, { detail: 1 });
    fireEvent.click(screen.getByRole("button", { name: "Modificar cliente F7" }));
    expect(within(screen.getByRole("dialog")).queryByRole("checkbox", { name: "Crear miembro" })).not.toBeInTheDocument();
  });

  it("moves a secondary to the first position without deleting the previous default", async () => {
    const dialog = await editCustomer();
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    const options = screen.getAllByRole("option");
    expect(options[0]).toHaveTextContent("Predeterminada · CALLE PRIMERA · 600111222");
    fireEvent.click(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }));
    expect(within(dialog).getByLabelText("Notas")).toHaveValue("Segunda");
    fireEvent.click(within(dialog).getByRole("button", { name: "Convertir a predeterminado" }));
    expect(within(dialog).queryByRole("button", { name: "Convertir a predeterminado" })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/c1", expect.objectContaining({ method: "PUT", body: expect.objectContaining({
      expectedVersion: 7, creditEnabled: true, address: addressB.address, phone: addressB.phone, addresses: [addressB, addressA],
    }) })));
  });

  it("preserves null countries and permits unrelated changes while the shared book is pending", async () => {
    const nullCountry = { ...addressA, address: { ...addressA.address, country: null } };
    refreshed = { ...customer, addressSyncStatus: "PENDING", address: nullCountry.address, addresses: [nullCountry, addressB] };
    const dialog = await editCustomer();
    fireEvent.change(within(dialog).getByLabelText("Nombre o razón social"), { target: { value: "Otro nombre" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/c1", expect.objectContaining({ body: expect.objectContaining({ fiscalName: "Otro nombre", addresses: [nullCountry, addressB] }) })));
  });

  it("deletes only a secondary address on save and keeps the default contact intact", async () => {
    const dialog = await editCustomer();
    expect(within(dialog).queryByRole("button", { name: "Eliminar dirección" })).not.toBeInTheDocument();
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Eliminar dirección" }));
    expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CALLE PRIMERA");
    expect(within(dialog).getByLabelText("Teléfono")).toHaveValue("600111222");
    expect(within(dialog).queryByRole("button", { name: "Eliminar dirección" })).not.toBeInTheDocument();
    expect(request.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    expect(screen.queryByRole("option", { name: "CALLE SEGUNDA · 600333444" })).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: "Predeterminada · CALLE PRIMERA · 600111222" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    await waitFor(() => expect(request).toHaveBeenCalledWith("/customers/c1", expect.objectContaining({ method: "PUT", body: expect.objectContaining({
      expectedVersion: 7, addresses: [addressA], address: addressA.address, phone: addressA.phone,
    }) })));
  });

  it("treats a deleted address as an unsaved change and blocks pending shared replacements", async () => {
    refreshed = { ...customer, addressSyncStatus: "PENDING" };
    const dialog = await editCustomer();
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Eliminar dirección" }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(request.mock.calls.some(([, options]) => options?.method === "PUT")).toBe(false);
    fireEvent.click(within(dialog).getByRole("button", { name: "Cerrar" }));
    expect(screen.getByRole("alertdialog")).toBeInTheDocument();
  });

  it("keeps address edits in the form when pending synchronization blocks a replacement", async () => {
    refreshed = { ...customer, addressSyncStatus: "PENDING" };
    const dialog = await editCustomer();
    fireEvent.change(within(dialog).getByLabelText("Dirección"), { target: { value: "CAMBIO PENDIENTE" } });
    fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
    expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CAMBIO PENDIENTE");
    expect(request.mock.calls.some(([path, options]) => path === "/customers/c1" && options?.method === "PUT")).toBe(false);
  });

  it("requires confirmation before replacing a conflict with the SaaS book", async () => {
    refreshed = { ...customer, addressSyncStatus: "CONFLICT" };
    const dialog = await editCustomer();
    const refresh = within(dialog).getByRole("button", { name: "Actualizar" });
    expect(refresh.closest("header")).toHaveClass("filter-header");
    fireEvent.click(refresh);
    const confirmation = screen.getByRole("alertdialog", { name: "Actualizar direcciones" });
    expect(request.mock.calls.some(([path]) => path.endsWith("/use-central"))).toBe(false);
    fireEvent.click(within(confirmation).getByRole("button", { name: "Aceptar" }));
    await waitFor(() => expect(within(dialog).getByLabelText("Dirección")).toHaveValue("CALLE SEGUNDA"));
    expect(request).toHaveBeenCalledWith("/customers/c1/addresses/use-central", expect.objectContaining({ body: { expectedVersion: 7 } }));
    expect(within(dialog).getByLabelText("Nombre o razón social")).toHaveValue(customer.fiscalName);
  });

  it("does not overwrite a newer new-customer draft with a late existing-customer refresh", async () => {
    let finish!: (value: CustomerView) => void;
    const pending = new Promise<CustomerView>(resolve => { finish = resolve; });
    const respond = request.getMockImplementation()!;
    request.mockImplementation((path, options) => path.endsWith("/addresses/refresh") ? pending : respond(path, options));
    await directory();
    fireEvent.click(screen.getByText(customer.fiscalName).closest("[role=row]")!, { detail: 1 });
    fireEvent.click(screen.getByRole("button", { name: "Modificar cliente F7" }));
    fireEvent.click(within(screen.getByRole("dialog")).getByRole("button", { name: "Cerrar" }));
    fireEvent.click(screen.getByRole("button", { name: "Nuevo cliente F8" }));
    const dialog = screen.getByRole("dialog"); fillIdentity(dialog);
    await act(async () => { finish({ ...customer, fiscalName: "RESPUESTA ANTIGUA" }); await pending; });
    expect(within(dialog).getByLabelText("Nombre o razón social")).toHaveValue("Nuevo cliente");
  });

  it("provides every new editor caption in ES, EN and ZH", () => {
    for (const locale of ["es", "en", "zh"] as const) {
      const t = createTranslator(locale);
      for (const key of ["validate", "createMember", "existsTitle", "exists", "address.default", "address.add", "address.makeDefault", "address.remove", "address.pending", "address.conflict", "address.reload"])
        expect(t(`party.customerEditor.${key}`)).not.toBe(`party.customerEditor.${key}`);
    }
  });
});
