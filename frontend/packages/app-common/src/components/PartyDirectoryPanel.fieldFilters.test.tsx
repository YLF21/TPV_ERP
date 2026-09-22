// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import {
  PartyDirectoryPanel,
  filterPartyDirectoryEntries,
  partyDirectoryPreferenceStorageKey,
  type CustomerView,
  type MemberDirectoryView,
  type PartyDirectoryFieldFilters,
  type SupplierView
} from "./PartyDirectoryPanel";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));

const session: UserSession = { username: "field-filters", displayName: "Field filters", accessToken: "test", permissions: ["ADMIN"] };
const emptyFields: PartyDirectoryFieldFilters = { code: "", name: "", document: "", phone: "", email: "", location: "", category: "" };
const customer: CustomerView = {
  id: "target", clientId: "C-012", fiscalName: "María Norte", documentType: "NIF", documentNumber: "NIF-3412",
  phone: "+34 922 012345", email: "ventas@isla.test", address: { city: "Arrecife", province: "Las Palmas" },
  active: true, isMember: false
};
const customers: CustomerView[] = [
  { ...customer, id: "ana", clientId: "C-10", fiscalName: "Ana local", phone: "911111111" },
  { ...customer, id: "bea", clientId: "C-2", fiscalName: "Bea", phone: "912222222" },
  { ...customer, id: "old", clientId: "C-30", fiscalName: "Ana antigua", phone: "913333333", active: false },
  { ...customer, id: "other", clientId: "C-4", fiscalName: "Ana peninsular", phone: "914444444", address: { city: "Toledo", province: "Toledo" } },
  { ...customer, id: "mobile", clientId: "C-5", fiscalName: "Ana móvil", phone: "655555555" }
];
const suppliers: SupplierView[] = customers.map(({ clientId, fiscalName, isMember: _isMember, ...row }) => ({ ...row, supplierId: clientId.replace("C-", "P-"), legalName: fiscalName }));
const members: MemberDirectoryView[] = customers.map((row) => ({
  ...row, customerId: row.id, memberId: row.clientId.replace("C-", "M-"), memberSince: "2026-01-01",
  customerActive: true, balance: 0, points: 0, categoryName: row.id === "ana" ? "Oro" : "Plata"
}));

function openFieldFilters() {
  fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
  return within(screen.getByRole("group", { name: "Filtros por campo" }));
}

beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => {
    if (path === "/customers") return customers;
    if (path === "/suppliers") return suppliers;
    if (path === "/members") return members;
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Party directory field matching", () => {
  it.each<[keyof PartyDirectoryFieldFilters, string, Partial<CustomerView>]>([
    ["code", "  c-012  ", { clientId: "C-999", fiscalName: "C-012" }],
    ["name", "  NORTE  ", { fiscalName: "María Sur", documentNumber: "Norte" }],
    ["document", "  3412  ", { documentNumber: "OTHER", phone: "3412" }],
    ["phone", "  922  ", { phone: null, documentNumber: "922" }],
    ["email", "  ISLA  ", { email: null, fiscalName: "Isla" }],
    ["location", "  PALMAS  ", { address: null, fiscalName: "Las Palmas" }],
    ["location", "  ARRE  ", { address: { city: "Madrid" }, fiscalName: "Arrecife" }]
  ])("matches %s only against its own field, ignoring case and surrounding spaces", (field, value, overrides) => {
    const decoy: CustomerView = { ...customer, ...overrides, id: "decoy" };
    expect(filterPartyDirectoryEntries([customer, decoy], "customers", "", "all", "es", { ...emptyFields, [field]: value })
      .map((entry) => entry.id)).toEqual(["target"]);
  });

  it("matches supplier legal and trade names without searching the email field", () => {
    const rows: SupplierView[] = [
      { ...suppliers[0], id: "legal", legalName: "Norte SL", tradeName: null },
      { ...suppliers[1], id: "trade", legalName: "Empresa SL", tradeName: "Norte" },
      { ...suppliers[2], id: "email", legalName: "Sur SL", email: "norte@example.test" }
    ];
    expect(filterPartyDirectoryEntries(rows, "suppliers", "", "all", "es", { ...emptyFields, name: "norte" })
      .map((entry) => entry.id)).toEqual(["legal", "trade"]);
    expect(filterPartyDirectoryEntries(rows, "suppliers", "", "all", "es", { ...emptyFields, code: "p-2" })
      .map((entry) => entry.id)).toEqual(["trade"]);
  });

  it("matches the displayed member code and category without treating hidden identifiers or names as those fields", () => {
    const rows: MemberDirectoryView[] = [
      { ...members[0], id: "custom", memberId: "M-100", numMember: "VIP-7", categoryName: "Oro" },
      { ...members[1], id: "fallback", memberId: "VIP-8", numMember: null, categoryName: "Oro" },
      { ...members[2], id: "hidden", memberId: "VIP-9", numMember: "EXT-9", categoryName: "Plata", fiscalName: "Oro" }
    ];
    expect(filterPartyDirectoryEntries(rows, "members", "", "all", "es", { ...emptyFields, code: "vip" })
      .map((entry) => entry.id)).toEqual(["custom", "fallback"]);
    expect(filterPartyDirectoryEntries(rows, "members", "", "all", "es", { ...emptyFields, category: "ORO" })
      .map((entry) => entry.id)).toEqual(["custom", "fallback"]);
  });
});

describe("Party directory field filter controls", () => {
  it.each(["customers", "members", "suppliers"] as const)("opens the applicable fields for venta %s and retains criteria when collapsed", async (kind) => {
    render(<PartyDirectoryPanel app="venta" kind={kind} locale="es" session={session} />);
    await within(screen.getByRole("table")).findByText("Bea");
    const toggle = screen.getByRole("button", { name: "Filtrar" });
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(screen.queryByRole("group", { name: "Filtros por campo" })).not.toBeInTheDocument();
    const fields = openFieldFilters();
    expect(toggle).toHaveAttribute("aria-expanded", "true");
    for (const label of ["Código", "Nombre / Razón social", "Documento", "Teléfono", "Email"]) {
      expect(fields.getByRole("textbox", { name: label })).toHaveAttribute("type", "text");
    }
    const applicable = kind === "members" ? "Categoría" : "Población / Provincia";
    const absent = kind === "members" ? "Población / Provincia" : "Categoría";
    expect(fields.getByRole("textbox", { name: applicable })).toBeVisible();
    expect(fields.queryByRole("textbox", { name: absent })).not.toBeInTheDocument();
    fireEvent.change(fields.getByRole("textbox", { name: "Nombre / Razón social" }), { target: { value: "Bea" } });
    expect(screen.queryByText("Ana local")).not.toBeInTheDocument();
    fireEvent.click(toggle);
    expect(toggle).toHaveAttribute("aria-expanded", "false");
    expect(within(screen.getByRole("table")).getByText("Bea")).toBeVisible();
    expect(screen.queryByText("Ana local")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Quitar filtro Nombre / Razón social" })).toBeVisible();
    expect(openFieldFilters().getByRole("textbox", { name: "Nombre / Razón social" })).toHaveValue("Bea");
  });

  it("combines quick search, status and field filters with AND, removes one chip, and clears all without resetting sorting", async () => {
    render(<PartyDirectoryPanel app="venta" kind="customers" locale="es" session={session} />);
    await within(screen.getByRole("table")).findByText("Bea");
    fireEvent.click(screen.getByRole("button", { name: /Ordenar por Nombre/ }));
    fireEvent.click(screen.getByRole("button", { name: /Ordenar por Nombre/ }));
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Ana" } });
    fireEvent.click(screen.getByRole("button", { name: "Estado" }));
    fireEvent.click(screen.getByRole("option", { name: "Activos" }));
    const fields = openFieldFilters();
    fireEvent.change(fields.getByRole("textbox", { name: "Teléfono" }), { target: { value: "91" } });
    fireEvent.change(fields.getByRole("textbox", { name: "Población / Provincia" }), { target: { value: "Palmas" } });
    expect(screen.getByText("Ana local")).toBeVisible();
    for (const name of ["Bea", "Ana antigua", "Ana peninsular", "Ana móvil"]) expect(screen.queryByText(name)).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Población / Provincia" }));
    expect(screen.getByText("Ana peninsular")).toBeVisible();
    expect(screen.queryByText("Ana antigua")).not.toBeInTheDocument();
    expect(screen.queryByText("Ana móvil")).not.toBeInTheDocument();
    expect(screen.queryByText("Bea")).not.toBeInTheDocument();
    expect(fields.getByRole("textbox", { name: "Población / Provincia" })).toHaveValue("");
    expect(fields.getByRole("textbox", { name: "Teléfono" })).toHaveValue("91");
    expect(screen.getByRole("searchbox")).toHaveValue("Ana");
    expect(screen.getByRole("button", { name: "Estado" })).toHaveTextContent("Activos");
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    for (const row of customers) expect(screen.getByText(row.fiscalName)).toBeVisible();
    expect(screen.getByRole("searchbox")).toHaveValue("");
    expect(fields.getByRole("textbox", { name: "Teléfono" })).toHaveValue("");
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    expect(screen.getByRole("columnheader", { name: /Nombre/ })).toHaveAttribute("aria-sort", "descending");
  });

  it("restores saved field criteria after reopening the directory", async () => {
    const props = { app: "venta" as const, kind: "members" as const, locale: "es" as const, session };
    const view = render(<PartyDirectoryPanel {...props} />);
    await within(screen.getByRole("table")).findByText("Bea");
    fireEvent.change(openFieldFilters().getByRole("textbox", { name: "Categoría" }), { target: { value: "Oro" } });
    const key = partyDirectoryPreferenceStorageKey("venta", session.username, "members");
    await waitFor(() => expect(JSON.parse(localStorage.getItem(key)!)).toMatchObject({ fieldFilters: { category: "Oro" } }));
    view.unmount();
    render(<PartyDirectoryPanel {...props} />);
    await within(screen.getByRole("table")).findByText("Ana local");
    expect(screen.queryByText("Bea")).not.toBeInTheDocument();
    expect(screen.getByRole("button", { name: "Quitar filtro Categoría" })).toBeVisible();
    const toggle = screen.getByRole("button", { name: "Filtrar" });
    if (toggle.getAttribute("aria-expanded") === "false") fireEvent.click(toggle);
    expect(within(screen.getByRole("group", { name: "Filtros por campo" })).getByRole("textbox", { name: "Categoría" })).toHaveValue("Oro");
  });

  it.each([
    { query: "Bea", statusFilter: "all", sort: { column: "name", direction: "desc" } },
    { fieldFilters: { code: 12, name: "Bea", document: null, phone: [], email: {}, location: false, category: "foreign" } }
  ])("accepts legacy preferences and sanitizes malformed saved fields", async (saved) => {
    localStorage.setItem(partyDirectoryPreferenceStorageKey("venta", session.username, "customers"), JSON.stringify(saved));
    render(<PartyDirectoryPanel app="venta" kind="customers" locale="es" session={session} />);
    await within(screen.getByRole("table")).findByText("Bea");
    expect(screen.queryByText("Ana local")).not.toBeInTheDocument();
    const toggle = screen.getByRole("button", { name: "Filtrar" });
    if (toggle.getAttribute("aria-expanded") === "false") fireEvent.click(toggle);
    const fields = within(screen.getByRole("group", { name: "Filtros por campo" }));
    for (const label of ["Código", "Documento", "Teléfono", "Email", "Población / Provincia"]) {
      expect(fields.getByRole("textbox", { name: label })).toHaveValue("");
    }
    expect(screen.queryByRole("button", { name: "Quitar filtro Categoría" })).not.toBeInTheDocument();
  });

  it.each(["customers", "members", "suppliers"] as const)("supports field filters for complete gestion %s lists with independent preferences", async (kind) => {
    localStorage.setItem(partyDirectoryPreferenceStorageKey("venta", session.username, kind), JSON.stringify({ fieldFilters: { name: "missing" } }));
    render(<PartyDirectoryPanel app="gestion" kind={kind} locale="es" session={session} />);
    await within(screen.getByRole("table")).findByText("Bea");
    expect(screen.getByText("Ana local")).toBeVisible();
    const fields = openFieldFilters();
    fireEvent.change(fields.getByRole("textbox", { name: "Nombre / Razón social" }), { target: { value: "Bea" } });
    expect(screen.queryByText("Ana local")).not.toBeInTheDocument();
    expect(within(screen.getByRole("table")).getByText("Bea")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Nombre / Razón social" }));
    expect(screen.getByText("Ana local")).toBeVisible();
    expect(fields.getByRole("textbox", { name: "Nombre / Razón social" })).toHaveValue("");
  });
});
