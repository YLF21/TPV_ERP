// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import { PartyDirectoryPanel, type CustomerView, type MemberDirectoryView, type SupplierView } from "./PartyDirectoryPanel";
import type { UserSession } from "../types";
import { createTranslator } from "../i18n/LocalizedMessages";

vi.mock("../api/client", async original => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const request = vi.mocked(apiRequest);
const t = createTranslator("es");
const session: UserSession = { username: "address-editor", displayName: "Editor", accessToken: "token", permissions: ["ADMIN"] };
const addressA = {
  id: "00000000-0000-4000-8000-000000000001",
  address: { address: "CALLE PRIMERA", postalCode: "35120", city: "Mogán", province: "Las Palmas", country: "ES" },
  phone: "600111222", email: "first@example.es", notes: "Primera",
};
const addressB = {
  id: "00000000-0000-4000-8000-000000000002",
  address: { address: "CALLE SEGUNDA", postalCode: "35001", city: "Las Palmas", province: "Las Palmas", country: "ES" },
  phone: "600333444", email: "second@example.es", notes: "Segunda",
};
const uppercaseA = { ...addressA, address: { ...addressA.address, city: "MOGÁN", province: "LAS PALMAS" } };
const uppercaseB = { ...addressB, address: { ...addressB.address, city: "LAS PALMAS", province: "LAS PALMAS" } };
const resolvedAddress = { address: "Calle Mayor 1", postalCode: "28013", city: "Madrid", province: "mAdRid", country: "eS" };
const resolvedUppercase = { address: "Calle Mayor 1", postalCode: "28013", city: "MADRID", province: "MADRID", country: "ES" };
const customerBase: CustomerView = {
  id: "customer-1", clientId: "C-001", fiscalName: "Cliente de prueba", documentType: "NIF", documentNumber: "12345678Z",
  active: true, isMember: false, version: 7, creditEnabled: true,
  address: addressA.address, phone: addressA.phone, email: addressA.email, notes: addressA.notes,
  addresses: [addressA, addressB], addressBookRevision: 3, addressSyncStatus: "SYNCED",
};
const member: MemberDirectoryView = {
  id: "member-1", customerId: customerBase.id, clientId: customerBase.clientId, memberId: "M-001", numMember: "M-001",
  fiscalName: customerBase.fiscalName, documentType: "NIF", documentNumber: "12345678Z",
  memberSince: "2026-01-01", active: true, customerActive: true, balance: 25, points: 7,
};
const supplier: SupplierView = {
  id: "supplier-1", supplierId: "P-001", legalName: "Proveedor de prueba", tradeName: "Marca existente",
  documentType: "CIF", documentNumber: "B12345674", active: true, version: 4,
  address: addressB.address, phone: "928111222", email: "supplier@example.es", notes: "Entrega por la mañana",
};
type Kind = "customers" | "members" | "suppliers";
let currentCustomer: CustomerView;
let availability: "available" | "unavailable" | "pending";

beforeEach(() => {
  localStorage.clear(); currentCustomer = customerBase; availability = "available";
  request.mockReset().mockImplementation(async (path, options) => {
    if (path.startsWith("/party-addresses/suggestions?")) {
      if (availability === "pending") return new Promise(() => {});
      return { available: availability === "available", items: availability === "available" ? [{ id: "portal-1", type: "portal", label: "Calle Mayor, 1, Madrid" }] : [] };
    }
    if (path.startsWith("/party-addresses/resolve?")) return { available: true, address: resolvedAddress };
    if (path === "/customers/customer-1" && options?.method === "PUT") return { ...currentCustomer, ...(options.body as object) };
    if (path === "/suppliers/supplier-1" && options?.method === "PUT") return { ...supplier, ...(options.body as object) };
    if (path === "/customers/customer-1/addresses/refresh") return currentCustomer;
    if (path === "/customers") return [currentCustomer];
    if (path === "/members") return [member];
    if (path === "/members/member-1") return { ...member, autoCategoryLocked: false };
    if (path === "/suppliers") return [supplier];
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

async function edit(kind: Kind) {
  currentCustomer = kind === "members" ? { ...customerBase, isMember: true, memberUuid: "member-1", numMember: "M-001" } : customerBase;
  render(<PartyDirectoryPanel app="venta" kind={kind} locale="es" session={session} />);
  const name = kind === "suppliers" ? supplier.legalName : currentCustomer.fiscalName;
  await screen.findByText(name);
  fireEvent.click(screen.getByText(name).closest("[role=row]")!, { detail: 1 });
  if (kind === "customers") fireEvent.click(screen.getByRole("button", { name: "Modificar cliente F7" }));
  const dialog = screen.getByRole("dialog");
  await waitFor(() => expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeEnabled());
  if (kind !== "suppliers") {
    fireEvent.click(within(dialog).getByRole("button", { name: "Contacto y dirección" }));
    fireEvent.click(screen.getByRole("option", { name: "CALLE SEGUNDA · 600333444" }));
  }
  return dialog;
}
function puts() { return request.mock.calls.filter(([, options]) => options?.method === "PUT"); }
async function save(dialog: HTMLElement, kind: Kind) {
  expect(puts()).toHaveLength(0);
  fireEvent.click(within(dialog).getByRole("button", { name: "Guardar" }));
  await waitFor(() => expect(puts()).toHaveLength(1));
  const [path, options] = puts()[0];
  expect(path).toBe(kind === "suppliers" ? "/suppliers/supplier-1" : "/customers/customer-1");
  return options?.body as Record<string, unknown>;
}
function assertPreserved(body: Record<string, unknown>, kind: Kind, address: typeof resolvedAddress) {
  if (kind === "suppliers") {
    expect(body).toMatchObject({ legalName: supplier.legalName, tradeName: supplier.tradeName, documentType: supplier.documentType,
      documentNumber: supplier.documentNumber, address, phone: supplier.phone, email: supplier.email, notes: supplier.notes });
    expect(body).not.toHaveProperty("addresses");
    expect(body).not.toHaveProperty("isMember");
  } else {
    // The immutable customer identity is selected by the PUT route; the address
    // update must not try to reassign its central code or linked member UUID.
    expect(body).not.toHaveProperty("clientId");
    expect(body).not.toHaveProperty("memberUuid");
    expect(body).toMatchObject({ fiscalName: currentCustomer.fiscalName, documentNumber: currentCustomer.documentNumber,
      isMember: currentCustomer.isMember, numMember: kind === "members" ? "M-001" : null, creditEnabled: true,
      expectedVersion: 7, address: uppercaseA.address, phone: addressA.phone, email: addressA.email, notes: addressA.notes });
    expect(body.addresses).toEqual([uppercaseA, { ...addressB, address }]);
    expect(request.mock.calls.some(([path]) => path.includes("/customers/member-1") || path.includes("/member/activate"))).toBe(false);
  }
}

describe.each<Kind>(["customers", "members", "suppliers"])("Address autocomplete in %s editor", kind => {
  it("applies resolved fields only to the selected address and persists only on Save", async () => {
    const dialog = await edit(kind);
    const input = within(dialog).getByRole("combobox", { name: "Dirección" });
    fireEvent.change(input, { target: { value: "Mayor" } });
    fireEvent.click(await screen.findByRole("option", { name: "Calle Mayor, 1, Madrid" }));
    await waitFor(() => expect(input).toHaveValue("Calle Mayor 1"));
    expect(within(dialog).getByLabelText("Código postal")).toHaveValue("28013");
    expect(within(dialog).getByLabelText(t("party.field.city"))).toHaveValue("MADRID");
    expect(within(dialog).getByRole("combobox", { name: t("party.field.province") })).toHaveValue("MADRID");
    expect(within(dialog).getByRole("combobox", { name: t("party.field.country") })).toHaveValue("ESPAÑA (ES)");
    const suggestionPath = request.mock.calls.find(([path]) => path.startsWith("/party-addresses/suggestions?"))![0];
    expect(suggestionPath).toBe("/party-addresses/suggestions?q=Mayor");
    expect(puts()).toHaveLength(0);
    const body = await save(dialog, kind);
    assertPreserved(body, kind, resolvedUppercase);
  });

  it.each(["unavailable", "pending"] as const)("keeps manual text saveable when suggestions are %s", async state => {
    availability = state;
    const dialog = await edit(kind);
    const input = within(dialog).getByRole("combobox", { name: "Dirección" });
    fireEvent.change(input, { target: { value: "DIRECCIÓN MANUAL 23" } });
    await waitFor(() => expect(request.mock.calls.some(([path]) => path.startsWith("/party-addresses/suggestions?"))).toBe(true));
    if (state === "unavailable") await screen.findByText(t("party.addressSearch.unavailable"));
    expect(within(dialog).getByRole("button", { name: "Guardar" })).toBeEnabled();
    const body = await save(dialog, kind);
    assertPreserved(body, kind, { ...uppercaseB.address, address: "DIRECCIÓN MANUAL 23" });
    expect(request.mock.calls.some(([path]) => path.startsWith("/party-addresses/resolve?"))).toBe(false);
  });

  it("shows existing locations in uppercase, accepts manual locations and persists uppercase without changing street/contact data", async () => {
    const dialog = await edit(kind);
    const city = within(dialog).getByLabelText(t("party.field.city"));
    const province = within(dialog).getByRole("combobox", { name: t("party.field.province") });
    const country = within(dialog).getByRole("combobox", { name: t("party.field.country") });
    expect(city).toHaveValue("LAS PALMAS");
    expect(province).toHaveValue("LAS PALMAS");
    expect(country).toHaveValue("ESPAÑA (ES)");
    expect(within(dialog).getByRole("combobox", { name: "Dirección" })).toHaveValue("CALLE SEGUNDA");
    fireEvent.change(city, { target: { value: "San Cristóbal de La Laguna" } });
    expect(city).toHaveValue("SAN CRISTÓBAL DE LA LAGUNA");
    fireEvent.focus(province);
    fireEvent.change(province, { target: { value: "Provincia mixta" } });
    expect(province).toHaveValue("PROVINCIA MIXTA");
    fireEvent.click(screen.getByRole("option", { name: "Usar “PROVINCIA MIXTA”" }));
    expect(province).toHaveValue("PROVINCIA MIXTA");
    fireEvent.focus(country);
    expect(screen.getByRole("option", { name: "FRANCIA (FR)" })).toBeInTheDocument();
    fireEvent.click(screen.getByRole("option", { name: "FRANCIA (FR)" }));
    expect(country).toHaveValue("FRANCIA (FR)");
    fireEvent.focus(country);
    fireEvent.click(screen.getByRole("option", { name: "ESPAÑA (ES)" }));
    expect(country).toHaveValue("ESPAÑA (ES)");
    const body = await save(dialog, kind);
    assertPreserved(body, kind, { ...uppercaseB.address, city: "SAN CRISTÓBAL DE LA LAGUNA", province: "PROVINCIA MIXTA" });
    expect(request.mock.calls.some(([path]) => path.startsWith("/party-addresses/"))).toBe(false);
  });
});
