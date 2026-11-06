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
  type SupplierView
} from "./PartyDirectoryPanel";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));

const session: UserSession = { username: "field-filters", displayName: "Field filters", accessToken: "test", permissions: ["ADMIN"] };
const customer: CustomerView = {
  id: "target", clientId: "C-012", fiscalName: "María Norte", documentType: "NIF", documentNumber: "NIF-3412",
  phone: "+34 922 012345", email: "ventas@isla.test", address: { city: "Arrecife", province: "Las Palmas", country: "ES" },
  active: true, isMember: false
};
const customers: CustomerView[] = [
  { ...customer, id: "ana", clientId: "C-10", fiscalName: "Ana local", phone: "911111111" },
  { ...customer, id: "bea", clientId: "C-2", fiscalName: "Bea", phone: "912222222" },
  { ...customer, id: "old", clientId: "C-30", fiscalName: "Ana antigua", phone: "913333333", active: false },
  { ...customer, id: "other", clientId: "C-4", fiscalName: "Ana peninsular", phone: "914444444", address: { city: "Toledo", province: "Toledo", country: "PT" } },
  { ...customer, id: "mobile", clientId: "C-5", fiscalName: "Ana móvil", phone: "655555555", address: { city: "Arrecife Norte", province: "Las Palmas", country: "FR" } }
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
    if (path === "/member-categories") return [{ name: "Oro" }, { name: "Plata" }, { name: "Bronce" }];
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

function selectOption(field: string, option: string) {
  const trigger = screen.getByRole("button", { name: field });
  if (trigger.getAttribute("aria-expanded") === "false") fireEvent.click(trigger);
  fireEvent.click(screen.getByRole("option", { name: option }));
}

describe("Party directory field filters", () => {
  it("matches exact locations and countries across several addresses and combines fields", () => {
    const rows = [
      ...customers,
      { ...customer, id: "secondary", address: null, addresses: [{
        id: "contact", address: { city: "Toledo", province: "Toledo", country: "PT" },
      }] },
    ];
    expect(filterPartyDirectoryEntries(rows, "customers", "", "all", "es", {
      location: ["  Arrecife, Las Palmas  ", "toledo, toledo"], country: ["es", "pt"],
    }).map(row => row.id)).toEqual(["ana", "bea", "old", "other", "secondary"]);
    expect(filterPartyDirectoryEntries(rows, "customers", "", "all", "es", {
      location: "Arrecife", country: "es",
    })).toEqual([]);
  });

  it.each([
    ["venta", "customers"], ["venta", "members"], ["venta", "suppliers"],
    ["gestion", "customers"], ["gestion", "members"], ["gestion", "suppliers"],
  ] as const)("uses only dropdown fields in %s %s and retains choices when collapsed", async (app, kind) => {
    render(<PartyDirectoryPanel app={app} kind={kind} locale="es" session={session} />);
    const table = within(screen.getByRole("table"));
    await table.findByText("Bea");
    const fields = openFieldFilters();
    expect(fields.queryAllByRole("textbox")).toHaveLength(0);
    for (const label of ["Estado", "Población / Provincia", "País"]) {
      expect(fields.getByRole("button", { name: label })).toBeVisible();
    }
    expect(Boolean(fields.queryByRole("button", { name: "Categoría" }))).toBe(kind === "members");
    expect(screen.getByRole("searchbox")).toBeVisible();

    selectOption("Población / Provincia", "Toledo, Toledo");
    expect(table.getByText("Ana peninsular")).toBeVisible();
    expect(table.queryByText("Ana local")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
    expect(screen.queryByRole("group", { name: "Filtros por campo" })).not.toBeInTheDocument();
    expect(openFieldFilters().getByRole("button", { name: "Población / Provincia" })).toHaveTextContent("Toledo, Toledo");
  });

  it("retains all choices while combining several locations with country, status and quick search", async () => {
    render(<PartyDirectoryPanel app="venta" kind="customers" locale="es" session={session} />);
    const table = within(screen.getByRole("table"));
    await table.findByText("Bea");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Ana" } });
    openFieldFilters();
    selectOption("Estado", "Activos");
    selectOption("Población / Provincia", "Arrecife, Las Palmas");
    selectOption("Población / Provincia", "Toledo, Toledo");
    expect(screen.getByRole("option", { name: "Arrecife, Las Palmas" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("option", { name: "Arrecife Norte, Las Palmas" })).toBeVisible();
    expect(table.getByText("Ana local")).toBeVisible();
    expect(table.getByText("Ana peninsular")).toBeVisible();
    expect(table.queryByText("Ana móvil")).not.toBeInTheDocument();
    selectOption("País", "España");
    expect(table.queryByText("Ana peninsular")).not.toBeInTheDocument();
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro País" }));
    expect(table.getByText("Ana peninsular")).toBeVisible();
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    for (const row of customers) expect(table.getByText(row.fiscalName)).toBeVisible();
  });

  it("lists unassigned member categories and restores a multiple choice after reopening", async () => {
    const props = { app: "venta" as const, kind: "members" as const, locale: "es" as const, session };
    const view = render(<PartyDirectoryPanel {...props} />);
    await within(screen.getByRole("table")).findByText("Bea");
    openFieldFilters();
    selectOption("Categoría", "Oro");
    fireEvent.click(await screen.findByRole("option", { name: "Bronce" }));
    expect(within(screen.getByRole("table")).getByText("Ana local")).toBeVisible();
    expect(screen.queryByText("Bea")).not.toBeInTheDocument();
    const key = partyDirectoryPreferenceStorageKey("venta", session.username, "members");
    await waitFor(() => expect(JSON.parse(localStorage.getItem(key)!)).toMatchObject({ fieldFilters: { category: ["oro", "bronce"] } }));
    view.unmount();
    render(<PartyDirectoryPanel {...props} />);
    await within(screen.getByRole("table")).findByText("Ana local");
    const fields = openFieldFilters();
    fireEvent.click(fields.getByRole("button", { name: "Categoría" }));
    expect(await screen.findByRole("option", { name: "Bronce" })).toHaveAttribute("aria-selected", "true");
    expect(screen.getByRole("option", { name: "Oro" })).toHaveAttribute("aria-selected", "true");
  });

  it("discards removed text criteria from older preferences and keeps the quick search", async () => {
    const key = partyDirectoryPreferenceStorageKey("venta", session.username, "customers");
    localStorage.setItem(key, JSON.stringify({ query: "Ana", fieldFilters: { name: "missing", phone: "123", location: "partial" } }));
    render(<PartyDirectoryPanel app="venta" kind="customers" locale="es" session={session} />);
    await within(screen.getByRole("table")).findByText("Ana local");
    expect(screen.getByRole("searchbox")).toHaveValue("Ana");
    expect(openFieldFilters().getByRole("button", { name: "Población / Provincia" })).toHaveTextContent("Sin filtro");
    expect(JSON.parse(localStorage.getItem(key)!).fieldFilters).toEqual({});
  });
});
