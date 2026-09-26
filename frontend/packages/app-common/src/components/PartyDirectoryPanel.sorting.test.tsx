// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { PartyDirectoryPanel, partyDirectoryPreferenceStorageKey } from "./PartyDirectoryPanel";

vi.mock("../api/client", async (importOriginal) => ({ ...await importOriginal<typeof import("../api/client")>(), apiRequest: vi.fn() }));

const session: UserSession = { username: "sorting", displayName: "Sorting", accessToken: "test", permissions: ["ADMIN"] };
const kinds = ["customers", "members", "suppliers"] as const;
const rows = [
  { id: "ten", clientId: "C-10", supplierId: "P-10", memberId: "M-10", fiscalName: "Ana", legalName: "Ana" },
  { id: "two", clientId: "C-2", supplierId: "P-2", memberId: "M-2", fiscalName: "Zoe", legalName: "Zoe" }
].map((row) => ({ ...row, documentType: "NIF", documentNumber: row.id, active: true, customerActive: true, isMember: true, balance: 0, points: 0 }));

function namesInOrder() {
  return screen.getAllByRole("row").slice(1).map((row) => within(row).getByText(/^(Ana|Zoe)$/).textContent);
}

beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => {
    if (["/customers", "/members", "/suppliers"].includes(path)) return rows;
    return [];
  });
});
afterEach(() => { cleanup(); localStorage.clear(); });

describe("Party directory default sorting", () => {
  it.each(kinds)("defaults venta %s to natural ascending code and preserves a later choice", async (kind) => {
    const props = { app: "venta" as const, kind, locale: "es" as const, session };
    const view = render(<PartyDirectoryPanel {...props} />);
    await screen.findByText("Zoe");
    expect(namesInOrder()).toEqual(["Zoe", "Ana"]);
    expect(screen.getByRole("columnheader", { name: "Código" })).toHaveAttribute("aria-sort", "ascending");

    fireEvent.click(screen.getByRole("button", { name: /Ordenar por Nombre/ }));
    expect(namesInOrder()).toEqual(["Ana", "Zoe"]);
    await waitFor(() => expect(JSON.parse(localStorage.getItem(partyDirectoryPreferenceStorageKey("venta", session.username, kind))!))
      .toMatchObject({ sort: { column: "name", direction: "asc" } }));
    view.unmount();
    render(<PartyDirectoryPanel {...props} />);
    await screen.findByText("Zoe");
    expect(namesInOrder()).toEqual(["Ana", "Zoe"]);
  });

  it.each(kinds)("keeps gestion %s sorted by name by default", async (kind) => {
    render(<PartyDirectoryPanel app="gestion" kind={kind} locale="es" session={session} />);
    await screen.findByText("Zoe");
    expect(namesInOrder()).toEqual(["Ana", "Zoe"]);
    expect(screen.getByRole("columnheader", { name: /Nombre/ })).toHaveAttribute("aria-sort", "ascending");
  });

  it("uses the venta code default when a saved sort column is invalid", async () => {
    localStorage.setItem(partyDirectoryPreferenceStorageKey("venta", session.username, "customers"), JSON.stringify({ sort: { column: "removed", direction: "desc" } }));
    render(<PartyDirectoryPanel kind="customers" locale="es" session={session} />);
    await screen.findByText("Zoe");
    expect(namesInOrder()).toEqual(["Zoe", "Ana"]);
  });
});
