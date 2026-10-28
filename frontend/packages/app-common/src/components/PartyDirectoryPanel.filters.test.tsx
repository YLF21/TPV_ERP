// @vitest-environment jsdom
import "@testing-library/jest-dom/vitest";
import { act, cleanup, fireEvent, render, screen, waitFor, within } from "@testing-library/react";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { apiRequest } from "../api/client";
import type { UserSession } from "../types";
import { PartyDirectoryPanel } from "./PartyDirectoryPanel";

vi.mock("../api/client", async (original) => ({ ...await original<typeof import("../api/client")>(), apiRequest: vi.fn() }));
const session: UserSession = { username: "filters", displayName: "Filters", accessToken: "test", permissions: ["ADMIN"] };
const entries = [
  { id: "ana-active", fiscalName: "Ana activa", active: true },
  { id: "ana-inactive", fiscalName: "Ana inactiva", active: false },
  { id: "zoe", fiscalName: "Zoe", active: true }
].map((row, index) => ({ ...row, clientId: `C-${index}`, supplierId: `P-${index}`, memberId: `M-${index}`,
  legalName: row.fiscalName, customerActive: row.active, documentType: "NIF", documentNumber: row.id,
  isMember: false, balance: 0, points: 0 }));

beforeEach(() => {
  localStorage.clear();
  vi.mocked(apiRequest).mockReset().mockImplementation(async (path) => ["/customers", "/suppliers", "/members"].includes(path) ? entries : []);
});
afterEach(() => { cleanup(); vi.useRealTimers(); localStorage.clear(); });

describe("Party directory applied filter chips", () => {
  it.each(["customers", "members", "suppliers"] as const)("moves one %s row per held-arrow tick without opening or changing records", async (kind) => {
    vi.mocked(apiRequest).mockImplementation(async (path) => path.startsWith(`/${kind}/management/page`)
      ? { items: entries, hasMore: false } : path === `/${kind}` ? entries : []);
    const { container } = render(<PartyDirectoryPanel app="gestion" kind={kind} locale="es" session={session} />);
    await screen.findByText("Zoe");
    const rows = [...container.querySelectorAll<HTMLButtonElement>("button.party-directory-selectable-row")];
    expect(rows).toHaveLength(3);
    rows[0].focus();
    const requests = vi.mocked(apiRequest).mock.calls.length;
    vi.useFakeTimers();
    fireEvent.keyDown(rows[0], { key: "ArrowDown" });
    expect(document.activeElement).toBe(rows[1]);
    expect(rows[1]).toHaveClass("selected");
    fireEvent.keyDown(rows[1], { key: "ArrowDown", repeat: true });
    expect(document.activeElement).toBe(rows[1]);
    act(() => vi.advanceTimersByTime(180));
    expect(document.activeElement).toBe(rows[2]);
    fireEvent.keyUp(rows[2], { key: "ArrowDown" });
    act(() => vi.advanceTimersByTime(500));
    expect(document.activeElement).toBe(rows[2]);
    expect(screen.queryByRole("dialog")).not.toBeInTheDocument();
    expect(vi.mocked(apiRequest).mock.calls).toHaveLength(requests);
  });

  it.each(["customers", "members", "suppliers"] as const)("removes only one criterion and restores matching %s rows", async (kind) => {
    render(<PartyDirectoryPanel app="venta" kind={kind} locale="es" session={session} />);
    await screen.findByText("Zoe");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Ana" } });
    fireEvent.click(screen.getByRole("button", { name: "Estado" }));
    fireEvent.click(screen.getByRole("option", { name: "Activos" }));
    expect(screen.getByText("Ana activa")).toBeVisible();
    expect(screen.queryByText("Ana inactiva")).not.toBeInTheDocument();
    expect(screen.queryByText("Zoe")).not.toBeInTheDocument();
    const chips = screen.getByRole("group", { name: "Filtros aplicados" });
    expect(chips).toHaveTextContent("Búsqueda: Ana");
    expect(chips).toHaveTextContent("Estado: Activos");
    fireEvent.click(within(chips).getByRole("button", { name: "Quitar filtro Estado" }));
    expect(screen.getByText("Ana inactiva")).toBeVisible();
    expect(screen.queryByText("Zoe")).not.toBeInTheDocument();
    expect(screen.getByRole("searchbox")).toHaveValue("Ana");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Búsqueda" }));
    expect(screen.getByText("Zoe")).toBeVisible();
    expect(screen.queryByRole("group", { name: "Filtros aplicados" })).not.toBeInTheDocument();
    await waitFor(() => expect(screen.getByRole("searchbox")).toHaveFocus());
  });

  it("uses removable filter chips in the management app", async () => {
    render(<PartyDirectoryPanel app="gestion" kind="customers" locale="es" session={session} />);
    await screen.findByText("Zoe");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Ana" } });
    expect(screen.getByRole("group", { name: "Filtros aplicados" })).toHaveTextContent("Búsqueda: Ana");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Búsqueda" }));
    expect(screen.getByText("Zoe")).toBeVisible();
    expect(screen.getByRole("searchbox")).toHaveValue("");
  });

  it.each([
    ["Teléfono", "699888777"], ["Email", "secundaria@example.test"], ["Población / Provincia", "Sintra"],
    ["Código postal", "2710"], ["País", "PT"],
  ])("finds customers by secondary %s without replacing the primary data in the table", async (label, value) => {
    const customer = { ...entries[0], phone: "600111222", addresses: [
      { id: "primary", phone: "600111222", email: "principal@example.test", address: { city: "Madrid", province: "Madrid", postalCode: "28001", country: "ES" } },
      { id: "secondary", phone: "699888777", email: "secundaria@example.test", address: { city: "Sintra", province: "Lisboa", postalCode: "2710", country: "PT" } },
    ] };
    vi.mocked(apiRequest).mockImplementation(async path => path === "/customers" ? [customer, entries[2]] : []);
    render(<PartyDirectoryPanel app="venta" kind="customers" locale="es" session={session} />);
    await screen.findByText("Zoe");
    fireEvent.click(screen.getByRole("button", { name: "Filtrar" }));
    fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value } });
    expect(screen.getByText("Ana activa")).toBeVisible();
    expect(screen.getByText("600111222")).toBeVisible();
    expect(screen.queryByText("Zoe")).not.toBeInTheDocument();
    fireEvent.change(screen.getByLabelText(label, { exact: true }), { target: { value: "sin coincidencia" } });
    expect(screen.queryByText("Ana activa")).not.toBeInTheDocument();
  });

  it.each(["customers", "suppliers"] as const)("keeps management %s chips on server filters and resets pagination", async (kind) => {
    vi.mocked(apiRequest).mockImplementation(async path => {
      if (!path.startsWith(`/${kind}/management/page?`)) return [];
      const query = new URL(path, "http://test").searchParams;
      return { items: entries.filter(row => (!query.has("search") || row.fiscalName.includes(query.get("search")!))
        && (!query.has("active") || String(row.active) === query.get("active"))), hasMore: true, nextCursor: "next-page" };
    });
    render(<PartyDirectoryPanel app="gestion" kind={kind} locale="es" session={session} allowSafeRetirement />);
    await screen.findByText("Zoe");
    expect(screen.getByRole("button", { name: "Filtrar" })).toBeVisible();
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Ana" } });
    fireEvent.click(screen.getByRole("button", { name: "Estado" }));
    fireEvent.click(screen.getByRole("option", { name: "Activos" }));
    const queries = () => vi.mocked(apiRequest).mock.calls.map(([path]) => path).filter(path => path.startsWith(`/${kind}/management/page?`))
      .map(path => new URL(path, "http://test").searchParams);
    await waitFor(() => expect(queries().at(-1)?.get("active")).toBe("true"));
    expect(queries().at(-1)?.get("search")).toBe("Ana");
    fireEvent.click(screen.getByRole("button", { name: "Quitar filtro Estado" }));
    await waitFor(() => expect(queries().at(-1)?.has("active")).toBe(false));
    expect(queries().at(-1)?.get("search")).toBe("Ana");
    expect(queries().at(-1)?.has("cursor")).toBe(false);
    await screen.findByText("Ana inactiva");
    fireEvent.click(screen.getByRole("button", { name: "Limpiar todos" }));
    await waitFor(() => expect(queries().at(-1)?.has("search")).toBe(false));
    await screen.findByText("Zoe");
  });

  it("clears the member candidate search and selected candidate without changing the directory search", async () => {
    render(<PartyDirectoryPanel app="venta" kind="members" locale="es" session={session} />);
    await screen.findByText("Zoe");
    fireEvent.change(screen.getByRole("searchbox"), { target: { value: "Ana" } });
    fireEvent.click(screen.getByRole("button", { name: "Nuevo miembro F8" }));
    const dialog = screen.getByRole("dialog");
    fireEvent.change(within(dialog).getByRole("searchbox"), { target: { value: "Ana" } });
    fireEvent.click(within(dialog).getByRole("option", { name: /Ana activa/ }));
    fireEvent.click(within(dialog).getByRole("button", { name: "Quitar filtro Búsqueda" }));
    expect(within(dialog).getByRole("option", { name: /Zoe/ })).toBeVisible();
    expect(within(dialog).getByRole("option", { name: /Ana activa/ })).toHaveAttribute("aria-selected", "false");
    expect(screen.getAllByRole("searchbox")[0]).toHaveValue("Ana");
  });
});
